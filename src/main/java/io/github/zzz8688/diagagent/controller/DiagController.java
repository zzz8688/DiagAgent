package io.github.zzz8688.diagagent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.zzz8688.diagagent.agent.SreAgent;
import io.github.zzz8688.diagagent.dto.ChatMessageDTO;
import io.github.zzz8688.diagagent.entity.DiagnosisRecord;
import io.github.zzz8688.diagagent.entity.DiagnosisSession;
import io.github.zzz8688.diagagent.entity.SessionMessage;
import io.github.zzz8688.diagagent.service.DiagnosisRecordService;
import io.github.zzz8688.diagagent.service.DiagnosisSessionService;
import io.github.zzz8688.diagagent.service.SessionMessageService;
import io.github.zzz8688.diagagent.store.MongoChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class DiagController {

    private final SreAgent sreAgent;
    private final DiagnosisRecordService diagnosisRecordService;
    private final DiagnosisSessionService diagnosisSessionService;
    private final SessionMessageService sessionMessageService;
    private final MongoTemplate mongoTemplate;
    private final MongoChatMemoryStore mongoChatMemoryStore;

    @PostMapping(value = "/diagnose")
    public ResponseEntity<Flux<String>> diagnose(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            DiagnosisSession newSession = DiagnosisSession.builder()
                    .sessionId(sessionId)
                    .title(query.length() > 50 ? query.substring(0, 50) + "..." : query)
                    .engine("llm")
                    .build();
            diagnosisSessionService.createSession(newSession);
            log.info("Created new session: {}", sessionId);
        }

        final String finalSessionId = sessionId;
        
        log.info("Received user query: {}, sessionId: {}", query, finalSessionId);

        sessionMessageService.saveMessage(finalSessionId, "USER", query);

        StringBuilder fullResponse = new StringBuilder();

        Flux<String> flux = sreAgent.diagnoseStream(finalSessionId, query)
                .doOnNext(chunk -> fullResponse.append(chunk))
                .map(chunk -> "data: " + chunk + "\n\n")
                .doOnComplete(() -> {
                    log.info("Streaming diagnosis complete for session: {}", finalSessionId);
                    sessionMessageService.saveMessage(finalSessionId, "AI", fullResponse.toString());
                    DiagnosisSession session = diagnosisSessionService.getSessionById(finalSessionId);
                    if (session != null) {
                        session.setUpdatedAt(LocalDateTime.now());
                        diagnosisSessionService.updateSession(session);
                        log.info("Updated session: {}", finalSessionId);
                    }
                })
                .doOnError(error -> log.error("Streaming diagnosis error for session: {}", finalSessionId, error));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8")
                .body(flux);
    }

    @PostMapping("/diagnose/non-streaming")
    public ResponseEntity<String> diagnoseNonStreaming(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            DiagnosisSession newSession = DiagnosisSession.builder()
                    .sessionId(sessionId)
                    .title(query.length() > 50 ? query.substring(0, 50) + "..." : query)
                    .engine("llm")
                    .build();
            diagnosisSessionService.createSession(newSession);
            log.info("Created new session: {}", sessionId);
        }

        log.info("Received user query (non-streaming): {}, sessionId: {}", query, sessionId);

        sessionMessageService.saveMessage(sessionId, "USER", query);

        String response = sreAgent.diagnose(sessionId, query);

        sessionMessageService.saveMessage(sessionId, "AI", response);

        DiagnosisSession session = diagnosisSessionService.getSessionById(sessionId);
        if (session != null) {
            session.setUpdatedAt(LocalDateTime.now());
            diagnosisSessionService.updateSession(session);
            log.info("Updated session: {}", sessionId);
        }

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/debug/clear-all-chat-memories")
    public ResponseEntity<String> clearAllChatMemories() {
        try {
            log.info("Attempting to drop 'chat_messages' collection from MongoDB.");
            mongoTemplate.dropCollection("chat_messages");
            log.info("'chat_messages' collection dropped successfully.");
            return ResponseEntity.ok("Chat memory collection ('chat_messages') dropped successfully.");
        } catch (Exception e) {
            log.error("Failed to drop 'chat_messages' collection.", e);
            return ResponseEntity.status(500).body("Failed to drop chat memory collection: " + e.getMessage());
        }
    }

    @PostMapping("/save-diagnosis")
    public void saveDiagnosis(@RequestBody DiagnosisRecord record) {
        try {
            log.info("Saving diagnosis record: query={}", record.getQuery());
            diagnosisRecordService.saveDiagnosis(record);
        } catch (Exception e) {
            log.error("Failed to save diagnosis record", e);
            throw e;
        }
    }

    @GetMapping("/history")
    public List<DiagnosisRecord> getHistory() {
        log.info("Fetching diagnosis history");
        return diagnosisRecordService.findRecentRecords(20);
    }

    @GetMapping("/history/page")
    public Page<DiagnosisRecord> getHistoryPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        log.info("Fetching diagnosis history: page={}, size={}", pageNum, pageSize);
        return diagnosisRecordService.findByPage(pageNum, pageSize);
    }

    @DeleteMapping("/history/{id}")
    public void deleteHistory(@PathVariable Long id) {
        log.info("Deleting diagnosis record: {}", id);
        diagnosisRecordService.removeById(id);
    }

    @DeleteMapping("/history")
    public void deleteAllHistory() {
        log.info("Deleting all diagnosis records");
        diagnosisRecordService.remove(null);
    }

    @PostMapping("/session")
    public void createSession(@RequestBody DiagnosisSession session) {
        if (session.getSessionId() == null || session.getSessionId().isBlank()) {
            session.setSessionId(UUID.randomUUID().toString());
        }
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            session.setTitle("新会话");
        }
        diagnosisSessionService.createSession(session);
    }

    @GetMapping("/session")
    public List<DiagnosisSession> getAllSessions() {
        return diagnosisSessionService.getAllSessions();
    }

    @GetMapping("/session/{sessionId}")
    public DiagnosisSession getSession(@PathVariable String sessionId) {
        return diagnosisSessionService.getSessionById(sessionId);
    }

    @PutMapping("/session/{sessionId}")
    public void updateSession(@PathVariable String sessionId, @RequestBody DiagnosisSession session) {
        session.setSessionId(sessionId);
        diagnosisSessionService.updateSession(session);
    }

    @DeleteMapping("/session/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        sessionMessageService.deleteMessagesBySessionId(sessionId);
        mongoChatMemoryStore.deleteMessages(sessionId);
        diagnosisSessionService.deleteSession(sessionId);
    }

    @DeleteMapping("/session")
    public void deleteAllSessions() {
        List<DiagnosisSession> allSessions = diagnosisSessionService.getAllSessions();
        for (DiagnosisSession session : allSessions) {
            sessionMessageService.deleteMessagesBySessionId(session.getSessionId());
            mongoChatMemoryStore.deleteMessages(session.getSessionId());
        }
        diagnosisSessionService.deleteAllSessions();
    }

    @GetMapping("/session/{sessionId}/messages")
    public List<ChatMessageDTO> getSessionMessages(@PathVariable String sessionId) {
        log.info("获取会话聊天记录：{}", sessionId);
        List<SessionMessage> messages = sessionMessageService.getMessagesBySessionId(sessionId);
        log.info("获取到 {} 条聊天记录", messages.size());
        
        return messages.stream()
                .map(msg -> ChatMessageDTO.builder()
                        .type(msg.getRole())
                        .text(msg.getContent())
                        .build())
                .toList();
    }
}

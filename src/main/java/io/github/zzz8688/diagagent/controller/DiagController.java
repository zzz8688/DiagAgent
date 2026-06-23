package io.github.zzz8688.diagagent.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import io.github.zzz8688.diagagent.agent.runtime.MultiAgentReActRuntime;
import io.github.zzz8688.diagagent.config.ToolResultHolder;
import io.github.zzz8688.diagagent.dto.ChatMessageDTO;
import io.github.zzz8688.diagagent.entity.DiagnosisRecord;
import io.github.zzz8688.diagagent.entity.DiagnosisSession;
import io.github.zzz8688.diagagent.dto.PageResult;
import io.github.zzz8688.diagagent.service.DiagnosisRecordService;
import io.github.zzz8688.diagagent.service.DiagnosisSessionService;
import io.github.zzz8688.diagagent.service.MemorySummaryService;
import io.github.zzz8688.diagagent.service.ReactLoopGuardService;
import io.github.zzz8688.diagagent.service.ConcurrentSessionExecutionException;
import io.github.zzz8688.diagagent.service.RedisCacheService;
import io.github.zzz8688.diagagent.service.RedisRuntimeCoordinationService;
import io.github.zzz8688.diagagent.service.StopGenerateService;
import io.github.zzz8688.diagagent.util.JwtContextUtil;
import io.github.zzz8688.diagagent.store.MongoChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/diag")
@RequiredArgsConstructor
public class DiagController {

    private static final Logger log = LoggerFactory.getLogger(DiagController.class);

    private final DiagnosisRecordService diagnosisRecordService;
    private final DiagnosisSessionService diagnosisSessionService;
    private final MongoTemplate mongoTemplate;
    private final MongoChatMemoryStore mongoChatMemoryStore;
    private final StopGenerateService stopGenerateService;
    private final RedisCacheService redisCacheService;
    private final RedisRuntimeCoordinationService redisRuntimeCoordinationService;
    private final ReactLoopGuardService reactLoopGuardService;
    private final MemorySummaryService memorySummaryService;
    private final MultiAgentReActRuntime multiAgentReActRuntime;
    private final ChatMemoryProvider chatMemoryProvider;

    @Value("${chat.memory.enable-summary:false}")
    private boolean enableSummary;

    @PostMapping("/diagnose/graph")
    public ResponseEntity<String> diagnoseGraph(@RequestBody Map<String, String> request) {
        String query = requireQuery(request.get("query"));
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        enforceRateLimit(sessionId, "diag-graph", 8L);

        // 检查会话是否已存在
        DiagnosisSession existingSession = diagnosisSessionService.getSessionById(sessionId);
        if (existingSession == null) {
            DiagnosisSession newSession = DiagnosisSession.builder()
                    .sessionId(sessionId)
                    .title(query.length() > 50 ? query.substring(0, 50) + "..." : query)
                    .engine("current-runtime")
                    .build();
            diagnosisSessionService.createSession(newSession);
            log.info("Created new session (current runtime): {}", sessionId);
        }

        log.info("Received current architecture diagnosis request: query='{}', sessionId='{}'", query, sessionId);

        // 检查缓存 - 区分引擎
        String questionHash = redisCacheService.hashQuestion(query);
        RedisCacheService.AnswerCacheLookup cacheLookup = redisCacheService.lookupAnswerCache(questionHash, "multi-agent");
        if (cacheLookup.hit()) {
            if (cacheLookup.stale()) {
                scheduleCacheRebuild(query, questionHash, "multi-agent", "graph-endpoint");
            }
            log.info("Returning cached answer for current architecture query: {}", query);
            appendConversationTurn(sessionId, query, cacheLookup.answer());
            return ResponseEntity.ok(cacheLookup.answer());
        }

        try {
            reactLoopGuardService.startQuestion(sessionId, query);
            stopGenerateService.startGenerate(sessionId);
            appendTranscriptMessage(sessionId, UserMessage.from(query));
            String response = multiAgentReActRuntime.executeDiagnosis(query, sessionId, false).getFinalConclusion();
            
            // 检查是否被用户停止
            if (!stopGenerateService.shouldContinue(sessionId) || response == null || response.isEmpty()) {
                log.info("多智能体诊断已被用户停止");
                stopGenerateService.stopGenerate(sessionId);
                return ResponseEntity.ok(""); // 返回空字符串
            }

            appendTranscriptMessage(sessionId, AiMessage.from(response));

            DiagnosisSession session = diagnosisSessionService.getSessionById(sessionId);
            if (session != null) {
                session.setUpdatedAt(LocalDateTime.now());
                diagnosisSessionService.updateSession(session);
            }

            // 保存到缓存 - 区分引擎
            redisCacheService.cacheAnswer(questionHash, "multi-agent", response);
            
            stopGenerateService.stopGenerate(sessionId);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (e instanceof ConcurrentSessionExecutionException concurrent) {
                throw concurrent;
            }
            log.error("Multi-agent diagnosis failed", e);
            stopGenerateService.stopGenerate(sessionId);
            // 检查是否是被用户停止
            if (!stopGenerateService.shouldContinue(sessionId)) {
                log.info("多智能体诊断已被用户停止（异常路径）");
                return ResponseEntity.ok("");
            }
            String errorResponse = "### 诊断失败\n\n诊断过程中发生错误: " + e.getMessage();
            appendTranscriptMessage(sessionId, AiMessage.from(errorResponse));
            return ResponseEntity.ok(errorResponse);
        } finally {
            reactLoopGuardService.finishQuestion(sessionId);
        }
    }

    @PostMapping("/diagnose/non-streaming")
    public ResponseEntity<String> diagnoseNonStreaming(@RequestBody Map<String, String> request) {
        String query = requireQuery(request.get("query"));
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        enforceRateLimit(sessionId, "diag-non-streaming", 8L);

        // 检查会话是否已存在
        DiagnosisSession existingSession = diagnosisSessionService.getSessionById(sessionId);
        if (existingSession == null) {
            DiagnosisSession newSession = DiagnosisSession.builder()
                    .sessionId(sessionId)
                    .title(query.length() > 50 ? query.substring(0, 50) + "..." : query)
                    .engine("current-runtime")
                    .build();
            diagnosisSessionService.createSession(newSession);
            log.info("Created new session: {}", sessionId);
        }

        log.info("Received current architecture diagnosis request: query='{}', sessionId='{}'", query, sessionId);

        reactLoopGuardService.startQuestion(sessionId, query);
        String response;
        try {
            appendTranscriptMessage(sessionId, UserMessage.from(query));
            response = multiAgentReActRuntime.executeDiagnosis(query, sessionId, false).getFinalConclusion();
            if (response != null && !response.isBlank()) {
                appendTranscriptMessage(sessionId, AiMessage.from(response));
            }
        } finally {
            reactLoopGuardService.finishQuestion(sessionId);
        }

        DiagnosisSession session = diagnosisSessionService.getSessionById(sessionId);
        if (session != null) {
            session.setUpdatedAt(LocalDateTime.now());
            diagnosisSessionService.updateSession(session);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = {"/diagnose", "/diagnose/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> diagnose(@RequestBody Map<String, String> request) {
        String query = requireQuery(request.get("query"));
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        enforceRateLimit(sessionId, "diag-stream", 6L);

        // 检查会话是否已存在
        DiagnosisSession existingSession = diagnosisSessionService.getSessionById(sessionId);
        if (existingSession == null) {
            DiagnosisSession newSession = DiagnosisSession.builder()
                    .sessionId(sessionId)
                    .title(query.length() > 50 ? query.substring(0, 50) + "..." : query)
                    .engine("current-runtime")
                    .build();
            diagnosisSessionService.createSession(newSession);
            log.info("Created new session: {}", sessionId);
        }

        final String finalSessionId = sessionId;
        log.info("Received current architecture streaming diagnosis request: query='{}', sessionId='{}'", query, finalSessionId);

        String questionHash = redisCacheService.hashQuestion(query);
        RedisCacheService.AnswerCacheLookup cacheLookup = redisCacheService.lookupAnswerCache(questionHash, "multi-agent");
        if (cacheLookup.hit()) {
            if (cacheLookup.stale()) {
                scheduleCacheRebuild(query, questionHash, "multi-agent", "stream-endpoint");
            }
            log.info("Returning cached answer for query: {} with engine: multi-agent", query);
            appendConversationTurn(finalSessionId, query, cacheLookup.answer());
            return Flux.just(cacheLookup.answer());
        }

        reactLoopGuardService.startQuestion(finalSessionId, query);
        stopGenerateService.startGenerate(finalSessionId);

        StringBuilder fullResponse = new StringBuilder();
        boolean[] stoppedByUser = {false};

        return multiAgentReActRuntime.executeDiagnosisStream(query, finalSessionId, false)
                .onErrorMap(ConcurrentSessionExecutionException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()))
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    log.info("Streaming completed for session: {}", finalSessionId);
                    if (stopGenerateService.shouldContinue(finalSessionId) && !stoppedByUser[0]) {
                        String responseText = fullResponse.toString();
                        appendConversationTurn(finalSessionId, query, responseText);
                        redisCacheService.cacheAnswer(questionHash, "multi-agent", responseText);
                        DiagnosisSession session = diagnosisSessionService.getSessionById(finalSessionId);
                        if (session != null) {
                            session.setUpdatedAt(LocalDateTime.now());
                            diagnosisSessionService.updateSession(session);
                        }
                    }
                    stopGenerateService.stopGenerate(finalSessionId);
                })
                .doOnError(error -> {
                    log.error("Streaming error for session: {}", finalSessionId, error);
                    stopGenerateService.stopGenerate(finalSessionId);
                })
                .doOnCancel(() -> {
                    log.info("Streaming cancelled for session: {}", finalSessionId);
                    stoppedByUser[0] = true;
                    stopGenerateService.stopGenerate(finalSessionId);
                    // 停止时不保存响应
                })
                .takeWhile(token -> stopGenerateService.shouldContinue(finalSessionId))
                .concatWith(Flux.defer(() -> {
                    if (!stopGenerateService.shouldContinue(finalSessionId) || stoppedByUser[0]) {
                        log.info("Emitting STOPPED signal for session: {}", finalSessionId);
                        return Flux.just("[STOPPED]");
                    }
                    return Flux.empty();
                }))
                .doFinally(signalType -> {
                    log.info("Stream finished with signal: {} for session: {}", signalType, finalSessionId);
                    reactLoopGuardService.finishQuestion(finalSessionId);
                });
    }

    @PostMapping("/stop/{sessionId}")
    public ResponseEntity<String> stopGenerate(@PathVariable String sessionId) {
        log.info("Stop generation requested for session: {}", sessionId);

        if (stopGenerateService.isGenerating(sessionId)) {
            stopGenerateService.cancelTask(sessionId);
            return ResponseEntity.ok("Stop signal sent for session: " + sessionId);
        } else {
            return ResponseEntity.ok("No active generation for session: " + sessionId);
        }
    }

    @GetMapping("/status/{sessionId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String sessionId) {
        boolean isGenerating = stopGenerateService.isGenerating(sessionId);
        long remainingTime = stopGenerateService.getRemainingTime(sessionId);
        Map<String, Object> toolResult = ToolResultHolder.get(sessionId);

        return ResponseEntity.ok(Map.of(
                "isGenerating", isGenerating,
                "remainingTime", remainingTime,
                "sessionId", sessionId,
                "hasToolResult", toolResult != null
        ));
    }

    private void appendConversationTurn(String sessionId, String userText, String aiText) {
        appendTranscriptMessage(sessionId, UserMessage.from(userText));
        appendTranscriptMessage(sessionId, AiMessage.from(aiText));
    }

    private void appendTranscriptMessage(String sessionId, ChatMessage message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }
        if (enableSummary) {
            chatMemoryProvider.get(sessionId).add(message);
            return;
        }
        List<ChatMessage> messages = new ArrayList<>(mongoChatMemoryStore.getMessages(sessionId));
        messages.add(message);
        mongoChatMemoryStore.updateMessages(sessionId, messages);
    }

    private List<ChatMessageDTO> buildConversationDtos(String sessionId) {
        return mongoChatMemoryStore.getMessages(sessionId).stream()
                .filter(message -> message instanceof UserMessage || message instanceof AiMessage)
                .map(message -> ChatMessageDTO.builder()
                        .type(message instanceof UserMessage ? "USER" : "AI")
                        .text(message instanceof UserMessage userMessage ? userMessage.singleText() : ((AiMessage) message).text())
                        .build())
                .toList();
    }

    @DeleteMapping("/cache/answers")
    public ResponseEntity<Map<String, Object>> clearAnswerCache() {
        RedisCacheService.ClearAnswerCacheResult result = redisCacheService.clearAnswerCache();
        log.info("热点答案缓存已清空: deletedAnswerKeys={}, deletedRebuildLocks={}, deletedHotKeyIndex={}, fallbackOnly={}",
                result.deletedAnswerKeys(),
                result.deletedRebuildLocks(),
                result.deletedHotKeyIndex(),
                result.fallbackOnly());
        return ResponseEntity.ok(Map.of(
                "deletedAnswerKeys", result.deletedAnswerKeys(),
                "deletedRebuildLocks", result.deletedRebuildLocks(),
                "deletedHotKeyIndex", result.deletedHotKeyIndex(),
                "fallbackOnly", result.fallbackOnly(),
                "message", "热点答案缓存已清空"
        ));
    }

    @DeleteMapping("/debug/clear-all-chat-memories")
    public ResponseEntity<String> clearAllChatMemories() {
        try {
            log.info("Attempting to drop 'chat_messages' collection from MongoDB.");
            mongoTemplate.dropCollection("chat_messages");
            mongoTemplate.dropCollection("memory_summaries");
            log.info("'chat_messages' collection dropped successfully.");
            return ResponseEntity.ok("Chat memory collections ('chat_messages', 'memory_summaries') dropped successfully.");
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
    public ResponseEntity<Map<String, Object>> getHistoryPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        log.info("Fetching diagnosis history: page={}, size={}", pageNum, pageSize);
        PageResult<DiagnosisRecord> page = diagnosisRecordService.findByPage(pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/history/{id}")
    public void deleteHistory(@PathVariable Long id) {
        log.info("Deleting diagnosis record: {}", id);
        diagnosisRecordService.deleteById(id);
    }

    @DeleteMapping("/history")
    public void deleteAllHistory() {
        log.info("Deleting all diagnosis records");
        diagnosisRecordService.deleteAll();
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
        DiagnosisSession existing = diagnosisSessionService.getSessionById(sessionId);
        if (existing != null) {
            Long currentUserId = JwtContextUtil.getCurrentUserId();
            if (!existing.getUserId().equals(currentUserId)) {
                throw new RuntimeException("无权修改该会话");
            }
            existing.setEngine(session.getEngine());
            diagnosisSessionService.updateSession(existing);
        }
    }

    @DeleteMapping("/session/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        DiagnosisSession session = diagnosisSessionService.getSessionById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }
        Long currentUserId = JwtContextUtil.getCurrentUserId();
        if (!session.getUserId().equals(currentUserId)) {
            throw new RuntimeException("无权删除该会话");
        }
        
        mongoChatMemoryStore.deleteMessages(sessionId);
        memorySummaryService.deleteBySessionPrefix(sessionId);
        diagnosisSessionService.deleteSession(sessionId);
    }

    @DeleteMapping("/session")
    public void deleteAllSessions() {
        List<DiagnosisSession> allSessions = diagnosisSessionService.getAllSessions();
        for (DiagnosisSession session : allSessions) {
            mongoChatMemoryStore.deleteMessages(session.getSessionId());
            memorySummaryService.deleteBySessionPrefix(session.getSessionId());
        }
        diagnosisSessionService.deleteAllSessions();
    }

    @GetMapping("/session/{sessionId}/messages")
    public List<ChatMessageDTO> getSessionMessages(@PathVariable String sessionId) {
        log.info("获取会话聊天记录：{}", sessionId);
        
        // 验证该会话是否属于当前用户
        DiagnosisSession session = diagnosisSessionService.getSessionById(sessionId);
        Long currentUserId = JwtContextUtil.getCurrentUserId();
        
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }
        
        if (!session.getUserId().equals(currentUserId)) {
            throw new RuntimeException("无权访问该会话");
        }

        List<ChatMessageDTO> messages = buildConversationDtos(sessionId);
        log.info("从 Mongo transcript 获取到 {} 条聊天记录", messages.size());
        return messages;
    }

    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        return query.trim();
    }

    private void enforceRateLimit(String sessionId, String scope, long maxRequests) {
        RedisRuntimeCoordinationService.RateLimitDecision decision =
                redisRuntimeCoordinationService.checkSessionRateLimit(sessionId, scope, maxRequests);
        if (!decision.allowed()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "请求过于频繁，请稍后再试: " + scope
            );
        }
    }

    private void scheduleCacheRebuild(String query, String questionHash, String engine, String trigger) {
        redisCacheService.rebuildAnswerCacheAsync(
                questionHash,
                engine,
                trigger,
                () -> multiAgentReActRuntime.executeDiagnosis(
                        query,
                        buildCacheRebuildSessionId(engine, questionHash),
                        true
                ).getFinalConclusion()
        );
    }

    private String buildCacheRebuildSessionId(String engine, String questionHash) {
        String suffix = questionHash == null ? "unknown" : questionHash.substring(0, Math.min(12, questionHash.length()));
        return "cache-rebuild-" + engine + "-" + suffix + "-" + System.currentTimeMillis();
    }
}

package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.zzz8688.diagagent.agent.runtime.TranscriptCodec;
import io.github.zzz8688.diagagent.agent.runtime.compat.Langchain4jTranscriptMessageMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
@RequiredArgsConstructor
public class MongoChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MongoChatMemoryStore.class);

    private static final String COLLECTION = "chat_messages";

    private final MongoTemplate mongoTemplate;
    private final TranscriptCodec transcriptCodec;
    private final Langchain4jTranscriptMessageMapper transcriptMessageMapper;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        ChatMemoryDocument document = mongoTemplate.findById(memoryId.toString(), ChatMemoryDocument.class, COLLECTION);
        if (document == null) {
            return List.of();
        }
        try {
            if (document.transcriptJson != null && !document.transcriptJson.isBlank()) {
                return new ArrayList<>(transcriptMessageMapper.toFrameworkMessages(
                        transcriptCodec.decode(document.transcriptJson)
                ));
            }
            if (document.messagesJson != null && !document.messagesJson.isBlank()) {
                return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(document.messagesJson));
            }
            return List.of();
        } catch (Exception e) {
            log.warn("读取 transcript 失败，返回空消息列表: memoryId={}, error={}", memoryId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }
        List<ChatMessage> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        ChatMemoryDocument document = new ChatMemoryDocument();
        document.id = memoryId.toString();
        document.transcriptCodec = transcriptCodec.codecId();
        document.transcriptJson = transcriptCodec.encode(transcriptMessageMapper.toTranscriptMessages(safeMessages));
        document.updatedAt = Instant.now().toString();
        mongoTemplate.save(document, COLLECTION);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        mongoTemplate.remove(query(where("_id").is(memoryId.toString())), ChatMemoryDocument.class, COLLECTION);
    }

    @Document(collection = COLLECTION)
    private static class ChatMemoryDocument {
        @Id
        private String id;
        private String transcriptCodec;
        private String transcriptJson;
        private String messagesJson;
        private String updatedAt;
    }
}

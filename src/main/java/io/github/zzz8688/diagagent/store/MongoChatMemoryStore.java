package io.github.zzz8688.diagagent.store;

import com.google.gson.Gson;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class MongoChatMemoryStore implements ChatMemoryStore {

    private final MongoTemplate mongoTemplate;
    private final Gson gson = new Gson();

    public MongoChatMemoryStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        log.info("从 MongoDB 获取对话记忆：{}", memoryId);
        Query query = new Query(Criteria.where("memoryId").is(memoryId.toString()));
        ChatMemoryDoc doc = mongoTemplate.findOne(query, ChatMemoryDoc.class);

        if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
            log.info("未找到对话记忆，返回空列表");
            return new ArrayList<>();
        }

        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(doc.getContent());
            
            if (messages == null || messages.isEmpty()) {
                log.warn("获取到空消息列表，删除有问题的记忆并返回空列表");
                deleteMessages(memoryId);
                return new ArrayList<>();
            }
            
            log.info("获取到 {} 条消息", messages.size());
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                log.info("  消息 {}: {} - {}", i, msg.getClass().getSimpleName(), 
                    msg.toString().substring(0, Math.min(100, msg.toString().length())));
            }
            return messages;
        } catch (Exception e) {
            log.error("解析对话记忆失败，删除有问题的记忆并返回空列表", e);
            deleteMessages(memoryId);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        log.info("更新 MongoDB 对话记忆：{}, 原始消息数：{}", memoryId, messages.size());

        String messagesJson = ChatMessageSerializer.messagesToJson(messages);

        Query query = new Query(Criteria.where("memoryId").is(memoryId.toString()));
        Update update = new Update();
        update.set("memoryId", memoryId.toString());
        update.set("content", messagesJson);
        update.set("updatedAt", new java.util.Date());

        mongoTemplate.upsert(query, update, ChatMemoryDoc.class);
        log.info("对话记忆更新成功");
    }

    @Override
    public void deleteMessages(Object memoryId) {
        log.debug("删除 MongoDB 对话记忆：{}", memoryId);
        Query query = new Query(Criteria.where("memoryId").is(memoryId.toString()));
        mongoTemplate.remove(query, ChatMemoryDoc.class);
    }
}

package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.store.MemorySummaryDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MemorySummaryService {

    private static final Logger log = LoggerFactory.getLogger(MemorySummaryService.class);

    private final MongoTemplate mongoTemplate;

    public MemorySummaryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Optional<MemorySummaryDoc> getSummary(String memoryId) {
        Query query = new Query(Criteria.where("memoryId").is(memoryId));
        return Optional.ofNullable(mongoTemplate.findOne(query, MemorySummaryDoc.class));
    }

    public MemorySummaryDoc saveRollingSummary(String memoryId,
                                               String topic,
                                               String summaryType,
                                               String content,
                                               int baseVersion,
                                               int version,
                                               MemorySummaryDoc.SourceMessageRange sourceMessageRange,
                                               Map<String, Object> metadata,
                                               int lastSummarizedMessageCount,
                                               int summarizedUserQuestionCount) {
        Date now = new Date();
        Query query = new Query(Criteria.where("memoryId").is(memoryId));
        Update update = new Update()
                .set("memoryId", memoryId)
                .set("topic", topic)
                .set("summaryType", summaryType)
                .set("content", content)
                .set("baseVersion", baseVersion)
                .set("version", version)
                .set("sourceMessageRange", sourceMessageRange)
                .set("metadata", metadata)
                .set("lastSummarizedMessageCount", lastSummarizedMessageCount)
                .set("summarizedUserQuestionCount", summarizedUserQuestionCount)
                .set("updatedAt", now)
                .setOnInsert("createdAt", now);

        mongoTemplate.upsert(query, update, MemorySummaryDoc.class);
        log.info("长期记忆已更新，memoryId={}, summaryType={}, version={}, summarizedQuestions={}",
                memoryId, summaryType, version, summarizedUserQuestionCount);
        return getSummary(memoryId).orElseThrow();
    }

    public void deleteByMemoryId(String memoryId) {
        Query query = new Query(Criteria.where("memoryId").is(memoryId));
        mongoTemplate.remove(query, MemorySummaryDoc.class);
    }

    public void deleteBySessionPrefix(String sessionId) {
        Query query = new Query(Criteria.where("memoryId").regex("^" + java.util.regex.Pattern.quote(sessionId) + "($|-).*"));
        mongoTemplate.remove(query, MemorySummaryDoc.class);
    }

    public List<MemorySummaryDoc> listRecentSummaries(int limit) {
        Query query = new Query()
                .limit(limit <= 0 ? 20 : limit)
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"));
        return mongoTemplate.find(query, MemorySummaryDoc.class);
    }

    public long countSummaries() {
        return mongoTemplate.count(new Query(), MemorySummaryDoc.class);
    }
}

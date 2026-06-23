package io.github.zzz8688.diagagent.store;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.Map;

@Document(collection = "memory_summaries")
public class MemorySummaryDoc {

    @Id
    private String id;

    private String memoryId;

    private String topic;

    private String summaryType;

    private String content;

    private Integer version;

    private Integer baseVersion;

    private SourceMessageRange sourceMessageRange;

    private Map<String, Object> metadata;

    private Integer lastSummarizedMessageCount;

    private Integer summarizedUserQuestionCount;

    private Date createdAt;

    private Date updatedAt;

    public MemorySummaryDoc() {
    }

    public MemorySummaryDoc(String id, String memoryId, String topic, String summaryType, String content,
                            Integer version, Integer baseVersion, SourceMessageRange sourceMessageRange,
                            Map<String, Object> metadata, Integer lastSummarizedMessageCount,
                            Integer summarizedUserQuestionCount, Date createdAt, Date updatedAt) {
        this.id = id;
        this.memoryId = memoryId;
        this.topic = topic;
        this.summaryType = summaryType;
        this.content = content;
        this.version = version;
        this.baseVersion = baseVersion;
        this.sourceMessageRange = sourceMessageRange;
        this.metadata = metadata;
        this.lastSummarizedMessageCount = lastSummarizedMessageCount;
        this.summarizedUserQuestionCount = summarizedUserQuestionCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSummaryType() {
        return summaryType;
    }

    public void setSummaryType(String summaryType) {
        this.summaryType = summaryType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public SourceMessageRange getSourceMessageRange() {
        return sourceMessageRange;
    }

    public void setSourceMessageRange(SourceMessageRange sourceMessageRange) {
        this.sourceMessageRange = sourceMessageRange;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Integer getLastSummarizedMessageCount() {
        return lastSummarizedMessageCount;
    }

    public void setLastSummarizedMessageCount(Integer lastSummarizedMessageCount) {
        this.lastSummarizedMessageCount = lastSummarizedMessageCount;
    }

    public Integer getSummarizedUserQuestionCount() {
        return summarizedUserQuestionCount;
    }

    public void setSummarizedUserQuestionCount(Integer summarizedUserQuestionCount) {
        this.summarizedUserQuestionCount = summarizedUserQuestionCount;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public static class SourceMessageRange {
        private Integer startMessageIndex;
        private Integer endMessageIndex;
        private Integer startUserQuestionCount;
        private Integer endUserQuestionCount;

        public SourceMessageRange() {
        }

        public SourceMessageRange(Integer startMessageIndex, Integer endMessageIndex,
                                  Integer startUserQuestionCount, Integer endUserQuestionCount) {
            this.startMessageIndex = startMessageIndex;
            this.endMessageIndex = endMessageIndex;
            this.startUserQuestionCount = startUserQuestionCount;
            this.endUserQuestionCount = endUserQuestionCount;
        }

        public Integer getStartMessageIndex() {
            return startMessageIndex;
        }

        public void setStartMessageIndex(Integer startMessageIndex) {
            this.startMessageIndex = startMessageIndex;
        }

        public Integer getEndMessageIndex() {
            return endMessageIndex;
        }

        public void setEndMessageIndex(Integer endMessageIndex) {
            this.endMessageIndex = endMessageIndex;
        }

        public Integer getStartUserQuestionCount() {
            return startUserQuestionCount;
        }

        public void setStartUserQuestionCount(Integer startUserQuestionCount) {
            this.startUserQuestionCount = startUserQuestionCount;
        }

        public Integer getEndUserQuestionCount() {
            return endUserQuestionCount;
        }

        public void setEndUserQuestionCount(Integer endUserQuestionCount) {
            this.endUserQuestionCount = endUserQuestionCount;
        }
    }
}

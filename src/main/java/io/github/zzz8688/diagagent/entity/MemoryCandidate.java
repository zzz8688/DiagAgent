package io.github.zzz8688.diagagent.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "memory_candidates")
public class MemoryCandidate {

    @Id
    private String id;

    private String memoryId;

    private String sessionId;

    private String topic;

    private String summaryType;

    private Integer sourceSummaryVersion;

    private String summaryContent;

    private String candidateContent;

    private String dedupeKey;

    private String contentHash;

    private CandidateStatus status;

    private CandidateOperation proposedOperation;

    private String targetFilePath;

    private String maintenanceNotes;

    private Date createdAt;

    private Date updatedAt;

    private Date processedAt;

    private Map<String, Object> metadata;

    public enum CandidateStatus {
        PENDING,
        PROCESSING,
        PROMOTED,
        MERGED,
        CORRECTED,
        FORGOTTEN,
        REJECTED,
        FAILED
    }

    public enum CandidateOperation {
        CREATE,
        MERGE,
        CORRECT,
        FORGET,
        SKIP
    }
}

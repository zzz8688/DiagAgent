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
@Document(collection = "managed_memory_versions")
public class ManagedMemoryVersion {

    @Id
    private String id;

    private String memoryPath;

    private Integer versionNumber;

    private String trigger;

    private String operation;

    private String status;

    private String sourceCandidateId;

    private String memoryId;

    private String sessionId;

    private String dedupeKey;

    private String contentHash;

    private Date createdAt;

    private String snapshotContent;

    private Map<String, Object> metadata;
}

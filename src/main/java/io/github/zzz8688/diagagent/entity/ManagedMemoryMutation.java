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
@Document(collection = "managed_memory_mutations")
public class ManagedMemoryMutation {

    @Id
    private String id;

    private String memoryPath;

    private String mutationType;

    private String trigger;

    private String operation;

    private String status;

    private String sourceCandidateId;

    private String memoryId;

    private String sessionId;

    private String dedupeKey;

    private Integer fromVersion;

    private Integer toVersion;

    private String fromContentHash;

    private String toContentHash;

    private String summary;

    private Date createdAt;

    private Map<String, Object> metadata;
}

package io.github.zzz8688.diagagent.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "processed_memory_files")
public class ProcessedMemoryFile {

    @Id
    private String memoryPath;
    private String filePath;
    private long fileSize;
    private String fileHash;
    private LocalDateTime processedAt;
    private List<String> vectorIds = new ArrayList<>();

    public ProcessedMemoryFile() {
    }

    public ProcessedMemoryFile(String memoryPath,
                               String filePath,
                               long fileSize,
                               String fileHash,
                               LocalDateTime processedAt,
                               List<String> vectorIds) {
        this.memoryPath = memoryPath;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.processedAt = processedAt;
        this.vectorIds = vectorIds == null ? new ArrayList<>() : new ArrayList<>(vectorIds);
    }

    public String getMemoryPath() {
        return memoryPath;
    }

    public void setMemoryPath(String memoryPath) {
        this.memoryPath = memoryPath;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public List<String> getVectorIds() {
        return vectorIds;
    }

    public void setVectorIds(List<String> vectorIds) {
        this.vectorIds = vectorIds == null ? new ArrayList<>() : new ArrayList<>(vectorIds);
    }
}

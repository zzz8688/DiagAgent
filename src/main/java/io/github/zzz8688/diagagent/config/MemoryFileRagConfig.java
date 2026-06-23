package io.github.zzz8688.diagagent.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.zzz8688.diagagent.entity.ProcessedMemoryFile;
import io.github.zzz8688.diagagent.repository.ProcessedMemoryFileRepository;
import io.github.zzz8688.diagagent.store.KnowledgeDocumentSplitter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class MemoryFileRagConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileRagConfig.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ProcessedMemoryFileRepository processedMemoryFileRepository;
    private final KnowledgeDocumentSplitter knowledgeDocumentSplitter;

    @Value("${memory.files.path:./memory}")
    private String memoryFilesPath;

    @Value("${memory.files.rag.chunk.max-embedding-input-length:2000}")
    private int maxEmbeddingInputLength;

    public MemoryFileRagConfig(@Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> memoryEmbeddingStore,
                               EmbeddingModel embeddingModel,
                               ProcessedMemoryFileRepository processedMemoryFileRepository,
                               KnowledgeDocumentSplitter knowledgeDocumentSplitter) {
        this.embeddingStore = memoryEmbeddingStore;
        this.embeddingModel = embeddingModel;
        this.processedMemoryFileRepository = processedMemoryFileRepository;
        this.knowledgeDocumentSplitter = knowledgeDocumentSplitter;
    }

    @PostConstruct
    public void loadMemoryFiles() {
        try {
            loadMemoryFilesIncremental();
        } catch (Exception e) {
            log.error("加载 memory files 失败", e);
        }
    }

    public void loadMemoryFilesIncremental() {
        Path basePath = Paths.get(memoryFilesPath);
        if (!Files.exists(basePath)) {
            log.info("memory files 目录不存在，跳过初始化: {}", basePath.toAbsolutePath());
            return;
        }
        if (!Files.isDirectory(basePath)) {
            log.warn("memory files 路径不是目录，跳过初始化: {}", basePath.toAbsolutePath());
            return;
        }

        List<ProcessedMemoryFile> processedFiles = processedMemoryFileRepository.findAll();
        List<String> currentPaths = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(basePath)) {
            List<Path> memoryFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedMemoryFile)
                    .sorted()
                    .collect(Collectors.toList());

            for (Path path : memoryFiles) {
                String memoryPath = toMemoryPath(basePath, path);
                currentPaths.add(memoryPath);
                FileStatus status = checkFileStatus(memoryPath, path);
                if (status == FileStatus.UNCHANGED) {
                    continue;
                }

                Optional<ProcessedMemoryFile> existingProcessed =
                        processedMemoryFileRepository.findByMemoryPath(memoryPath);
                if (status == FileStatus.MODIFIED && existingProcessed.isPresent()) {
                    deleteMemoryFileVectors(existingProcessed.get());
                }

                ingestMemoryFile(basePath, path, memoryPath);
            }
        } catch (IOException e) {
            log.error("遍历 memory files 目录失败: {}", basePath.toAbsolutePath(), e);
            return;
        }

        handleDeletedFiles(processedFiles, currentPaths);
    }

    private void ingestMemoryFile(Path basePath, Path path, String memoryPath) {
        try {
            String content = Files.readString(path);
            String title = titleFromPath(path);
            List<TextSegment> segments = knowledgeDocumentSplitter.splitForDocument(
                    content,
                    memoryPath,
                    title,
                    "memory-file"
            );
            List<TextSegment> segmentsWithMetadata = segments.stream()
                    .map(segment -> TextSegment.from(segment.text(), enrichMetadata(segment, memoryPath, path, title)))
                    .toList();
            List<String> vectorIds = ingestSegments(segmentsWithMetadata, memoryPath);
            saveFileRecord(path, memoryPath, vectorIds);
            log.info("memory file 已加载: {}", memoryPath);
        } catch (Exception e) {
            log.error("加载 memory file 失败: {}", memoryPath, e);
        }
    }

    private dev.langchain4j.data.document.Metadata enrichMetadata(TextSegment segment,
                                                                  String memoryPath,
                                                                  Path path,
                                                                  String title) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        metadata.put("id", memoryPath);
        metadata.put("title", title);
        metadata.put("source", memoryPath);
        metadata.put("type", "memory-file");
        metadata.put("memoryPath", memoryPath);
        metadata.put("filename", path.getFileName().toString());
        return metadata;
    }

    private List<String> ingestSegments(List<TextSegment> segments, String memoryPath) {
        List<String> vectorIds = new ArrayList<>();
        int indexed = 0;
        int skipped = 0;
        for (TextSegment segment : segments) {
            try {
                String text = segment.text();
                if (text == null || text.isBlank()) {
                    skipped++;
                    continue;
                }
                if (text.length() > maxEmbeddingInputLength) {
                    skipped++;
                    continue;
                }
                Response<Embedding> response = embeddingModel.embed(segment);
                if (response == null || response.content() == null) {
                    skipped++;
                    continue;
                }
                String vectorId = embeddingStore.add(response.content(), segment);
                if (vectorId != null) {
                    vectorIds.add(vectorId);
                }
                indexed++;
            } catch (Exception e) {
                skipped++;
                log.warn("memory file segment 向量化失败: path={}", memoryPath, e);
            }
        }
        log.info("memory file {} 已索引 {} 个片段，跳过 {} 个", memoryPath, indexed, skipped);
        return vectorIds;
    }

    private void saveFileRecord(Path path, String memoryPath, List<String> vectorIds) {
        try {
            ProcessedMemoryFile record = new ProcessedMemoryFile(
                    memoryPath,
                    path.toAbsolutePath().toString(),
                    Files.size(path),
                    calculateFileHash(path),
                    LocalDateTime.now(),
                    vectorIds
            );
            processedMemoryFileRepository.save(record);
        } catch (Exception e) {
            log.warn("保存 memory file 处理记录失败: {}", memoryPath, e);
        }
    }

    private void handleDeletedFiles(List<ProcessedMemoryFile> processedFiles, List<String> currentPaths) {
        for (ProcessedMemoryFile processed : processedFiles) {
            if (!currentPaths.contains(processed.getMemoryPath())) {
                deleteMemoryFileVectors(processed);
                processedMemoryFileRepository.delete(processed);
                log.info("已移除删除的 memory file 索引: {}", processed.getMemoryPath());
            }
        }
    }

    private void deleteMemoryFileVectors(ProcessedMemoryFile processed) {
        try {
            List<String> vectorIds = processed.getVectorIds();
            if (vectorIds == null || vectorIds.isEmpty()) {
                return;
            }
            embeddingStore.removeAll(vectorIds);
        } catch (Exception e) {
            log.warn("删除 memory file 向量失败: {}", processed.getMemoryPath(), e);
        }
    }

    private FileStatus checkFileStatus(String memoryPath, Path path) {
        try {
            Optional<ProcessedMemoryFile> existing = processedMemoryFileRepository.findByMemoryPath(memoryPath);
            if (existing.isEmpty()) {
                return FileStatus.NEW;
            }
            String currentHash = calculateFileHash(path);
            return currentHash.equals(existing.get().getFileHash()) ? FileStatus.UNCHANGED : FileStatus.MODIFIED;
        } catch (Exception e) {
            log.warn("检查 memory file 状态失败: {}", memoryPath, e);
            return FileStatus.NEW;
        }
    }

    private String calculateFileHash(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(Files.readAllBytes(path));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return path.getFileName().toString();
        }
    }

    private boolean isSupportedMemoryFile(Path path) {
        String lowerName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".md") || lowerName.endsWith(".txt");
    }

    private String toMemoryPath(Path basePath, Path path) {
        return basePath.relativize(path).toString().replace('\\', '/');
    }

    private String titleFromPath(Path path) {
        String filename = path.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }

    private enum FileStatus {
        NEW,
        MODIFIED,
        UNCHANGED
    }
}

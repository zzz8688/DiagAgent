package io.github.zzz8688.diagagent.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.github.zzz8688.diagagent.entity.ProcessedDocumentFile;
import io.github.zzz8688.diagagent.repository.ProcessedDocumentFileRepository;
import io.github.zzz8688.diagagent.store.KnowledgeDocumentSplitter;
import io.github.zzz8688.diagagent.store.PdfDocumentLoaderService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class KnowledgeBaseRagConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRagConfig.class);
    private static final long MILVUS_SYNC_LOAD_WAIT_TIMEOUT_SECONDS = 60L;
    private static final long MILVUS_SYNC_LOAD_WAIT_INTERVAL_MILLIS = 500L;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final PdfDocumentLoaderService pdfDocumentLoaderService;
    private final ProcessedDocumentFileRepository processedDocumentFileRepository;
    private final MilvusServiceClient knowledgeMilvusClient;
    private final KnowledgeDocumentSplitter knowledgeDocumentSplitter;
    private final MongoTemplate mongoTemplate;
    private volatile EmbeddingStore<TextSegment> writableEmbeddingStore;

    @Value("${knowledge.base.path:./knowledge}")
    private String knowledgeBasePath;

    @Value("${milvus.knowledge.collection.name:diagagent_knowledge}")
    private String knowledgeCollectionName;

    @Value("${knowledge.rag.chunk.max-embedding-input-length:2000}")
    private int maxEmbeddingInputLength;

    @Value("${milvus.dimension:1024}")
    private int milvusDimension;

    @Value("${knowledge.rag.retrieval.vector-top-k:5}")
    private int vectorTopK;

    @Value("${knowledge.rag.retrieval.min-vector-score:0.1}")
    private double minVectorScore;

    public KnowledgeBaseRagConfig(
            @Qualifier("knowledgeEmbeddingStore") EmbeddingStore<TextSegment> knowledgeEmbeddingStore,
            EmbeddingModel embeddingModel,
            PdfDocumentLoaderService pdfDocumentLoaderService,
            ProcessedDocumentFileRepository processedDocumentFileRepository,
            @Qualifier("knowledgeMilvusClient") MilvusServiceClient knowledgeMilvusClient,
            KnowledgeDocumentSplitter knowledgeDocumentSplitter,
            MongoTemplate mongoTemplate) {
        this.embeddingStore = knowledgeEmbeddingStore;
        this.embeddingModel = embeddingModel;
        this.pdfDocumentLoaderService = pdfDocumentLoaderService;
        this.processedDocumentFileRepository = processedDocumentFileRepository;
        this.knowledgeMilvusClient = knowledgeMilvusClient;
        this.knowledgeDocumentSplitter = knowledgeDocumentSplitter;
        this.mongoTemplate = mongoTemplate;
        this.writableEmbeddingStore = knowledgeEmbeddingStore;
    }

    @Bean
    public ContentRetriever knowledgeBaseRetriever() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(vectorTopK)
                .minScore(minVectorScore)
                .build();
    }

    @PostConstruct
    public void loadKnowledgeBase() {
        log.info("知识库路径: {}", knowledgeBasePath);
        log.info("增量加载模式: 启用");

        try {
            repairProcessedDocumentFileCollection();
            loadKnowledgeBaseIncremental();
            log.info("========== 知识库加载完成 ==========");
        } catch (Exception e) {
            log.error("加载知识库文档失败", e);
        }
    }

    private void repairProcessedDocumentFileCollection() {
        try {
            List<Document> indexes = mongoTemplate.getCollection("processed_document_files")
                    .listIndexes(Document.class)
                    .into(new ArrayList<>());
            boolean hasLegacyFilenameIndex = indexes.stream().anyMatch(index -> "filename".equals(index.getString("name")));
            if (!hasLegacyFilenameIndex) {
                return;
            }
            log.warn("检测到 processed_document_files 的历史索引 filename，当前文档主键已使用 _id=filename，正在移除该旧索引以避免 duplicate key {{ filename: null }}");
            mongoTemplate.getCollection("processed_document_files").dropIndex("filename");
        } catch (Exception e) {
            log.warn("修复 processed_document_files 历史索引失败，后续写入可能继续受到旧索引影响", e);
        }
    }

    public void loadKnowledgeBaseIncremental() {
        log.info("========== 增量加载知识库 ==========");

        List<ProcessedDocumentFile> processedFiles = processedDocumentFileRepository.findAll();
        log.info("从MongoDB找到 {} 个已处理的文件记录", processedFiles.size());

        try {
            List<String> currentFilenames = new ArrayList<>();

            loadMarkdownFiles(currentFilenames);
            loadPdfFiles(currentFilenames);

            log.info("当前目录中有 {} 个知识库文件（已处理）", currentFilenames.size());

            handleDeletedFiles(processedFiles, currentFilenames);

            log.info("========== 知识库增量加载完成 ==========");
        } catch (Exception e) {
            log.error("增量加载知识库文档失败", e);
        }
    }

    private void handleDeletedFiles(List<ProcessedDocumentFile> processedFiles, List<String> currentFilenames) {
        log.info("检查删除的文件: MongoDB记录={}个, 当前目录={}个", processedFiles.size(), currentFilenames.size());
        int deletedCount = 0;
        
        for (ProcessedDocumentFile processed : processedFiles) {
            if (!currentFilenames.contains(processed.getFilename())) {
                log.info("[删除] 检测到已删除的知识库文件: {}, 从向量数据库删除", processed.getFilename());
                deleteDocumentFileFromVectorStore(processed);
                processedDocumentFileRepository.delete(processed);
                deletedCount++;
            }
        }
        
        if (deletedCount > 0) {
            log.info("[删除] 共删除了 {} 个知识库文件", deletedCount);
        }
    }

    private void deleteDocumentFileFromVectorStore(ProcessedDocumentFile processed) {
        try {
            List<String> vectorIds = processed.getVectorIds();
            log.info("[DELETE] 文件 {} 的 vectorIds 数量: {}", processed.getFilename(), vectorIds == null ? 0 : vectorIds.size());
            
            if (vectorIds == null || vectorIds.isEmpty()) {
                log.warn("[DELETE] 没有找到文件 {} 的向量ID记录，跳过删除", processed.getFilename());
                return;
            }
            
            log.info("[DELETE] 开始删除文件 {} 的 {} 个向量...", processed.getFilename(), vectorIds.size());
            log.debug("[DELETE] 向量ID列表: {}", vectorIds);
            
            activeEmbeddingStore().removeAll(vectorIds);
            
            log.info("[DELETE] 成功删除文件 {} 的向量", processed.getFilename());
        } catch (Exception e) {
            log.error("[DELETE] 删除文件 {} 的向量失败: {}", processed.getFilename(), e.getMessage(), e);
        }
    }

    private void loadMarkdownFiles(List<String> currentFilenames) throws IOException {
        Path basePath = Paths.get(knowledgeBasePath);

        if (Files.exists(basePath) && Files.isDirectory(basePath)) {
            try (Stream<Path> paths = Files.list(basePath)) {
                List<Path> mdFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .collect(Collectors.toList());

                for (Path path : mdFiles) {
                    String filename = path.getFileName().toString();
                    FileStatus status = checkFileStatus(path);

                    if (status == FileStatus.UNCHANGED) {
                        currentFilenames.add(filename);
                        continue;
                    }

                    Optional<ProcessedDocumentFile> existingProcessed = processedDocumentFileRepository.findByFilename(filename);
                    if (status == FileStatus.MODIFIED && existingProcessed.isPresent()) {
                        log.info("检测到修改的 Markdown 文件: {}", filename);
                        deleteDocumentFileFromVectorStore(existingProcessed.get());
                    }

                    log.info("加载 Markdown: {}", filename);
                    String content = Files.readString(path);
                    List<TextSegment> fileSegments = knowledgeDocumentSplitter.splitForKnowledgeFile(content, filename, "markdown");
                    List<String> vectorIds = ingestFileSegments(fileSegments, filename);
                    currentFilenames.add(filename);
                    saveFileRecord(path, filename, vectorIds);
                }
            }
        }
    }

    private void loadPdfFiles(List<String> currentFilenames) throws IOException {
        Path basePath = Paths.get(knowledgeBasePath);

        if (Files.exists(basePath) && Files.isDirectory(basePath)) {
            try (Stream<Path> paths = Files.list(basePath)) {
                List<Path> pdfFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .collect(Collectors.toList());

                for (Path path : pdfFiles) {
                    String filename = path.getFileName().toString();
                    FileStatus status = checkFileStatus(path);

                    if (status == FileStatus.UNCHANGED) {
                        currentFilenames.add(filename);
                        continue;
                    }

                    Optional<ProcessedDocumentFile> existingProcessed = processedDocumentFileRepository.findByFilename(filename);
                    if (status == FileStatus.MODIFIED && existingProcessed.isPresent()) {
                        log.info("检测到修改的 PDF 文件: {}", filename);
                        deleteDocumentFileFromVectorStore(existingProcessed.get());
                    }

                    log.info("加载 PDF: {}", filename);
                    try (InputStream is = Files.newInputStream(path)) {
                        String content = pdfDocumentLoaderService.loadPdfFromInputStream(is, filename);
                        List<TextSegment> fileSegments = knowledgeDocumentSplitter.splitForKnowledgeFile(content, filename, "pdf");
                        List<String> vectorIds = ingestFileSegments(fileSegments, filename);
                        currentFilenames.add(filename);
                        saveFileRecord(path, filename, vectorIds);
                    }
                }
            }
        }
    }

    private enum FileStatus {
        NEW, MODIFIED, UNCHANGED
    }

    private FileStatus checkFileStatus(Path path) {
        String filename = path.getFileName().toString();
        try {
            Optional<ProcessedDocumentFile> existing = processedDocumentFileRepository.findByFilename(filename);
            if (existing.isEmpty()) {
                return FileStatus.NEW;
            }
            ProcessedDocumentFile processed = existing.get();
            String currentHash = calculateFileHash(path);
            boolean sameContent = currentHash.equals(processed.getFileHash());
            log.debug("文件 {} 检查: MongoDB记录hash={}, 当前hash={}, 内容相同={}",
                filename, processed.getFileHash(), currentHash, sameContent);
            return sameContent ? FileStatus.UNCHANGED : FileStatus.MODIFIED;
        } catch (Exception e) {
            log.warn("检查文件 {} 处理状态失败: {}", filename, e.getMessage());
            return FileStatus.NEW;
        }
    }

    private String calculateFileHash(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] fileBytes = Files.readAllBytes(path);
            byte[] digest = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算文件hash失败: {}, 使用文件名代替", path.getFileName());
            return path.getFileName().toString();
        }
    }

    private List<String> ingestFileSegments(List<TextSegment> segments, String filename) {
        List<String> vectorIds = new ArrayList<>();
        int count = 0;
        int skipped = 0;
        for (TextSegment segment : segments) {
            try {
                String text = segment.text();

                if (text.length() > maxEmbeddingInputLength) {
                    log.warn("Segment too long ({} chars), skipping: {}", text.length(), text.substring(0, Math.min(50, text.length())));
                    skipped++;
                    continue;
                }

                if (text.length() == 0) {
                    skipped++;
                    continue;
                }

                Response<Embedding> response = embeddingModel.embed(segment);
                if (response != null && response.content() != null) {
                    String vectorId = activeEmbeddingStore().add(response.content(), segment);
                    if (vectorId != null) {
                        vectorIds.add(vectorId);
                    }
                    count++;
                } else {
                    log.warn("Failed to generate embedding for segment, skipping: {}", text.substring(0, Math.min(50, text.length())));
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Error embedding segment", e);
                skipped++;
            }
        }
        log.info("文件 {} 已向量化并存储 {} 个文档片段，跳过 {} 个", filename, count, skipped);
        return vectorIds;
    }

    private void saveFileRecord(Path path, String filename, List<String> vectorIds) {
        if (filename == null || filename.isBlank()) {
            log.warn("跳过保存知识库文件处理记录，因为 filename 为空。path={}", path);
            return;
        }
        try {
            String fileHash = calculateFileHash(path);
            ProcessedDocumentFile record = new ProcessedDocumentFile(
                filename,
                path.toString(),
                Files.size(path),
                fileHash,
                LocalDateTime.now()
            );
            record.setVectorIds(vectorIds);
            processedDocumentFileRepository.save(record);
            log.debug("已保存文件处理记录: {}, vectorIds数量: {}", filename, vectorIds.size());
        } catch (Exception e) {
            log.error("保存文件处理记录失败: {}", filename, e);
        }
    }

    private void ingestSegments(List<TextSegment> segments) {
        int count = 0;
        int skipped = 0;
        for (TextSegment segment : segments) {
            try {
                String text = segment.text();

                if (text.length() > maxEmbeddingInputLength) {
                    log.warn("Segment too long ({} chars), skipping: {}", text.length(), text.substring(0, Math.min(50, text.length())));
                    skipped++;
                    continue;
                }

                if (text.length() == 0) {
                    skipped++;
                    continue;
                }

                Response<Embedding> response = embeddingModel.embed(segment);
                if (response != null && response.content() != null) {
                    embeddingStore.add(response.content(), segment);
                    count++;
                } else {
                    log.warn("Failed to generate embedding for segment, skipping: {}", text.substring(0, Math.min(50, text.length())));
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Error embedding segment", e);
                skipped++;
            }
        }
        log.info("已向量化并存储 {} 个文档片段，跳过 {} 个", count, skipped);
    }

    public void reloadKnowledgeBase() {
        log.info("========== 重新加载知识库 ==========");
        loadKnowledgeBase();
    }

    public void rebuildKnowledgeBase() {
        log.info("========== 全量重建知识库 ==========");
        try {
            repairProcessedDocumentFileCollection();
            prepareWritableEmbeddingStoreForRebuild();
            clearAllProcessedKnowledgeVectors();
            loadKnowledgeBaseIncremental();
            validateRebuildState();
            log.info("========== 全量重建知识库完成 ==========");
        } catch (Exception e) {
            log.error("全量重建知识库失败", e);
            throw e;
        }
    }

    private void clearAllProcessedKnowledgeVectors() {
        List<ProcessedDocumentFile> processedFiles = processedDocumentFileRepository.findAll();
        log.info("[REBUILD] 准备清理 {} 个已处理知识文件的向量记录", processedFiles.size());
        if (resetMilvusKnowledgeCollection()) {
            processedDocumentFileRepository.deleteAll();
            log.info("[REBUILD] 已通过 collection 级重置清空知识向量，并删除全部 processed_document_files 记录");
            return;
        }
        int removedDocuments = 0;
        for (ProcessedDocumentFile processed : processedFiles) {
            deleteDocumentFileFromVectorStore(processed);
            removedDocuments++;
        }
        processedDocumentFileRepository.deleteAll();
        log.info("[REBUILD] 已删除 {} 条 processed_document_files 记录", removedDocuments);
    }

    private EmbeddingStore<TextSegment> activeEmbeddingStore() {
        return writableEmbeddingStore == null ? embeddingStore : writableEmbeddingStore;
    }

    private void prepareWritableEmbeddingStoreForRebuild() {
        this.writableEmbeddingStore = embeddingStore;
    }

    private boolean resetMilvusKnowledgeCollection() {
        if (!isMilvusBackedStore()) {
            log.warn("[REBUILD] 当前知识 embedding store 不是 Milvus，无法做 collection 级硬清空，将退回按历史 vectorIds 删除的弱保证模式");
            return false;
        }
        R<Boolean> hasCollectionResponse = knowledgeMilvusClient.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(knowledgeCollectionName)
                .build());
        ensureMilvusSuccess(hasCollectionResponse, "检查知识 collection 是否存在");
        boolean exists = Boolean.TRUE.equals(hasCollectionResponse.getData());
        if (exists) {
            log.info("[REBUILD] 检测到 Milvus collection={}，开始执行 dropCollection 硬清空", knowledgeCollectionName);
            R<?> dropResponse = knowledgeMilvusClient.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName(knowledgeCollectionName)
                    .build());
            ensureMilvusSuccess(dropResponse, "drop 知识 collection");
        } else {
            log.info("[REBUILD] Milvus collection={} 当前不存在，将直接重建", knowledgeCollectionName);
        }
        this.writableEmbeddingStore = MilvusEmbeddingStore.builder()
                .milvusClient(knowledgeMilvusClient)
                .collectionName(knowledgeCollectionName)
                .dimension(maxEmbeddingDimension())
                .build();
        return true;
    }

    private void validateRebuildState() {
        Set<String> currentKnowledgeFiles = currentKnowledgeFilenames();
        Set<String> processedKnowledgeFiles = processedDocumentFileRepository.findAll().stream()
                .map(ProcessedDocumentFile::getFilename)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!processedKnowledgeFiles.equals(currentKnowledgeFiles)) {
            Set<String> missing = new LinkedHashSet<>(currentKnowledgeFiles);
            missing.removeAll(processedKnowledgeFiles);
            Set<String> stale = new LinkedHashSet<>(processedKnowledgeFiles);
            stale.removeAll(currentKnowledgeFiles);
            throw new IllegalStateException("知识库重建后文件记录与当前目录不一致，missing=" + missing + ", stale=" + stale);
        }
        if (isMilvusBackedStore()) {
            R<Boolean> hasCollectionResponse = knowledgeMilvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(knowledgeCollectionName)
                    .build());
            ensureMilvusSuccess(hasCollectionResponse, "校验知识 collection 是否存在");
            if (!Boolean.TRUE.equals(hasCollectionResponse.getData())) {
                throw new IllegalStateException("知识库重建后 Milvus collection 不存在: " + knowledgeCollectionName);
            }
            R<?> loadResponse = knowledgeMilvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(knowledgeCollectionName)
                    .withSyncLoad(Boolean.TRUE)
                    // Milvus SDK expects timeout in seconds and rejects values greater than 300.
                    .withSyncLoadWaitingTimeout(MILVUS_SYNC_LOAD_WAIT_TIMEOUT_SECONDS)
                    .withSyncLoadWaitingInterval(MILVUS_SYNC_LOAD_WAIT_INTERVAL_MILLIS)
                    .build());
            ensureMilvusSuccess(loadResponse, "加载知识 collection");
            R<?> statsResponse = knowledgeMilvusClient.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder()
                    .withCollectionName(knowledgeCollectionName)
                    .withFlush(Boolean.TRUE)
                    .build());
            ensureMilvusSuccess(statsResponse, "读取知识 collection 统计");
            log.info("[REBUILD] Milvus collection={} 已重建并通过校验，stats={}", knowledgeCollectionName, statsResponse.getData());
        }
        log.info("[REBUILD] 当前知识文件记录校验通过，共 {} 个文件: {}", currentKnowledgeFiles.size(), currentKnowledgeFiles);
    }

    private Set<String> currentKnowledgeFilenames() {
        Path basePath = Paths.get(knowledgeBasePath);
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.list(basePath)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return lower.endsWith(".md") || lower.endsWith(".pdf");
                    })
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new IllegalStateException("读取当前知识目录失败: " + knowledgeBasePath, e);
        }
    }

    private boolean isMilvusBackedStore() {
        return embeddingStore != null
                && embeddingStore.getClass().getName().toLowerCase(Locale.ROOT).contains("milvus");
    }

    private int maxEmbeddingDimension() {
        return milvusDimension;
    }

    private void ensureMilvusSuccess(R<?> response, String action) {
        if (response == null) {
            throw new IllegalStateException(action + " 失败: Milvus 响应为空");
        }
        Integer status = response.getStatus();
        if (status == null || status.intValue() != R.Status.Success.getCode()) {
            throw new IllegalStateException(action + " 失败: status=" + status + ", message=" + response.getMessage());
        }
    }
}

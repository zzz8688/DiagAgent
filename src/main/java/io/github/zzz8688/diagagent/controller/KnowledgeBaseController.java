package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.config.KnowledgeBaseRagConfig;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import io.github.zzz8688.diagagent.store.KnowledgeRagGovernanceService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    @Value("${knowledge.base.path:./knowledge}")
    private String knowledgeBasePath;

    private final KnowledgeBaseRagConfig knowledgeBaseRagConfig;
    private final io.github.zzz8688.diagagent.store.DocumentIngestionService documentIngestionService;
    private final KnowledgeRagGovernanceService knowledgeRagGovernanceService;
    private final SandboxExecutionService sandboxExecutionService;

    public static class KnowledgeFile {
        private String id;
        private String title;
        private String type;
        private String size;
        private String updateTime;
        private String fileName;

        public KnowledgeFile() {
        }

        public KnowledgeFile(String id, String title, String type, String size, String updateTime, String fileName) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.size = size;
            this.updateTime = updateTime;
            this.fileName = fileName;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSize() {
            return size;
        }

        public void setSize(String size) {
            this.size = size;
        }

        public String getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(String updateTime) {
            this.updateTime = updateTime;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listKnowledgeFiles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            List<KnowledgeFile> allFiles = listAllKnowledgeFiles();

            // 按标题排序
            allFiles.sort(Comparator.comparing(KnowledgeFile::getTitle));

            int total = allFiles.size();
            int fromIndex = (page - 1) * size;
            int toIndex = Math.min(fromIndex + size, total);
            
            List<KnowledgeFile> pageFiles = fromIndex < total 
                ? allFiles.subList(fromIndex, toIndex) 
                : new ArrayList<>();

            log.info("找到 {} 个知识库文件，返回第 {} 页，共 {} 条", total, page, pageFiles.size());
            
            Map<String, Object> result = new HashMap<>();
            result.put("records", pageFiles);
            result.put("total", total);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("获取知识库文件列表失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    public List<KnowledgeFile> listAllKnowledgeFiles() throws IOException {
        List<KnowledgeFile> allFiles = new ArrayList<>();

        Path basePath = Paths.get(knowledgeBasePath);
        if (Files.exists(basePath) && Files.isDirectory(basePath)) {
            try (Stream<Path> paths = Files.list(basePath)) {
                List<Path> fileList = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String fileName = p.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".md") || fileName.endsWith(".pdf");
                    })
                    .collect(Collectors.toList());

                for (Path path : fileList) {
                    allFiles.add(toKnowledgeFile(path));
                }
            }
        }

        return allFiles;
    }

    private KnowledgeFile toKnowledgeFile(Path path) {
        try {
            String fileName = path.getFileName().toString();
            String title = fileName.replaceAll("\\.(md|pdf)$", "");
            String type = fileName.toLowerCase().endsWith(".pdf") ? "PDF" : "MD";

            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long sizeBytes = attrs.size();
            String size;
            if (sizeBytes < 1024) {
                size = sizeBytes + "B";
            } else if (sizeBytes < 1024 * 1024) {
                size = String.format("%.1fKB", sizeBytes / 1024.0);
            } else {
                size = String.format("%.1fMB", sizeBytes / (1024.0 * 1024.0));
            }

            Instant updateTime = attrs.lastModifiedTime().toInstant();
            String formattedTime = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(updateTime);

            String id = title.hashCode() + "";

            return new KnowledgeFile(id, title, type, size, formattedTime, fileName);
        } catch (IOException e) {
            log.warn("读取文件属性失败: {}", path, e);
            String fileName = path.getFileName().toString();
            String title = fileName.replaceAll("\\.(md|pdf)$", "");
            String type = fileName.toLowerCase().endsWith(".pdf") ? "PDF" : "MD";
            return new KnowledgeFile(title.hashCode() + "", title, type, "未知", "未知", fileName);
        }
    }

    @GetMapping("/content/{fileName}")
    public ResponseEntity<String> getFileContent(@PathVariable String fileName) {
        try {
            String content = readKnowledgeFileContent(fileName);
            if (content == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.error("读取文件内容失败: {}", fileName, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    public String readKnowledgeFileContent(String fileName) throws IOException {
        Path filePath = resolveKnowledgeFilePath(fileName);
        if (!Files.exists(filePath)) {
            return null;
        }
        return Files.readString(filePath);
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        try {
            String normalizedFileName = normalizeKnowledgeFileName(fileName);
            Path filePath = resolveKnowledgeFilePath(normalizedFileName);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            String contentType = "application/octet-stream";

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + java.net.URLEncoder.encode(normalizedFileName, "UTF-8") + "\"")
                .body(resource);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("下载文件失败: {}", fileName, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("文件不能为空");
            }

            String fileName = normalizeKnowledgeFileName(file.getOriginalFilename());
            String lowerFileName = fileName.toLowerCase(Locale.ROOT);
            if (!lowerFileName.endsWith(".md") && !lowerFileName.endsWith(".pdf")) {
                return ResponseEntity.badRequest().body("只支持 MD 和 PDF 文件");
            }

            Path normalizedBasePath = resolveKnowledgeBasePath();
            Files.createDirectories(normalizedBasePath);
            Path targetPath = resolveKnowledgeFilePath(fileName);
            SandboxProtectedActionEnvelope<Integer> envelope = sandboxExecutionService.executeProtectedAction(
                    "uploadKnowledge",
                    SandboxTargetType.FILE,
                    targetPath.toString(),
                    Map.of(
                            "fileName", fileName,
                            "contentType", file.getContentType() == null ? "" : file.getContentType(),
                            "fileSize", file.getSize(),
                            "uploadType", lowerFileName.endsWith(".pdf") ? "pdf" : "markdown"
                    ),
                    normalizedBasePath.toString(),
                    () -> {
                        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                        log.info("文件上传成功: {}, 开始向量化...", fileName);
                        if (lowerFileName.endsWith(".pdf")) {
                            return documentIngestionService.ingestPdf(targetPath.toString(), fileName, fileName);
                        }
                        String content = Files.readString(targetPath);
                        return documentIngestionService.ingestText(content, fileName, fileName, "markdown");
                    }
            );
            if (!envelope.success() || envelope.result() == null) {
                return ResponseEntity.internalServerError().body("文件上传失败: " + safe(envelope.errorMessage()));
            }

            log.info("文件向量化完成: {}, 片段数: {}", fileName, envelope.result());
            return ResponseEntity.ok("文件上传并向量化成功，共 " + envelope.result() + " 个片段");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResponseEntity.internalServerError().body("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/reload")
    public ResponseEntity<String> reloadKnowledgeBase() {
        try {
            knowledgeBaseRagConfig.reloadKnowledgeBase();
            return ResponseEntity.ok("知识库增量重扫成功");
        } catch (Exception e) {
            log.error("知识库重载失败", e);
            return ResponseEntity.internalServerError().body("知识库重载失败: " + e.getMessage());
        }
    }

    @PostMapping("/rebuild")
    public ResponseEntity<String> rebuildKnowledgeBase() {
        try {
            knowledgeBaseRagConfig.rebuildKnowledgeBase();
            return ResponseEntity.ok("知识库全量重建成功，已执行旧向量硬清空并完成重建校验");
        } catch (Exception e) {
            log.error("知识库全量重建失败", e);
            return ResponseEntity.internalServerError().body("知识库全量重建失败: " + e.getMessage());
        }
    }

    @GetMapping("/rag/governance")
    public ResponseEntity<KnowledgeRagGovernanceService.RagGovernanceView> getRagGovernance() {
        return ResponseEntity.ok(knowledgeRagGovernanceService.getGovernanceView());
    }

    @PostMapping("/rag/evaluate")
    public ResponseEntity<KnowledgeRagGovernanceService.RagEvaluationResult> evaluateRag(
            @RequestBody(required = false) KnowledgeRagGovernanceService.RagEvaluationRequest request) {
        return ResponseEntity.ok(knowledgeRagGovernanceService.evaluate(request));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Path resolveKnowledgeBasePath() {
        return Paths.get(knowledgeBasePath).toAbsolutePath().normalize();
    }

    private Path resolveKnowledgeFilePath(String fileName) {
        Path basePath = resolveKnowledgeBasePath();
        Path filePath = basePath.resolve(normalizeKnowledgeFileName(fileName)).normalize();
        if (!filePath.startsWith(basePath)) {
            throw new IllegalArgumentException("文件路径越界");
        }
        return filePath;
    }

    private String normalizeKnowledgeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalized = fileName.trim();
        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("文件名不合法");
        }
        try {
            String leafName = Paths.get(normalized).getFileName().toString();
            if (!leafName.equals(normalized)) {
                throw new IllegalArgumentException("文件名不合法");
            }
            return leafName;
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("文件名不合法", ex);
        }
    }
}

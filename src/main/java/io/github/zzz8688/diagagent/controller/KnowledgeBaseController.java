package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.config.KnowledgeBaseRagConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseController {

    @Value("${knowledge.base.path:src/main/resources/knowledge}")
    private String knowledgeBasePath;

    private final KnowledgeBaseRagConfig knowledgeBaseRagConfig;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeFile {
        private String id;
        private String title;
        private String type;
        private String size;
        private String updateTime;
        private String fileName;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listKnowledgeFiles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            Path basePath = Paths.get(knowledgeBasePath);

            if (!Files.exists(basePath)) {
                log.warn("知识库目录不存在: {}", basePath.toAbsolutePath());
                Map<String, Object> result = new HashMap<>();
                result.put("records", new ArrayList<>());
                result.put("total", 0);
                return ResponseEntity.ok(result);
            }

            try (Stream<Path> paths = Files.walk(basePath)) {
                List<KnowledgeFile> allFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".pdf");
                    })
                    .map(this::toKnowledgeFile)
                    .sorted(Comparator.comparing(KnowledgeFile::getTitle))
                    .collect(Collectors.toList());

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
            }
        } catch (IOException e) {
            log.error("获取知识库文件列表失败", e);
            return ResponseEntity.internalServerError().build();
        }
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
            Path filePath = Paths.get(knowledgeBasePath, fileName);

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            String content = Files.readString(filePath);
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            log.error("读取文件内容失败: {}", fileName, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        try {
            Path filePath = Paths.get(knowledgeBasePath, fileName);

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());

            String contentType = "application/octet-stream";

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + java.net.URLEncoder.encode(resource.getFilename(), "UTF-8") + "\"")
                .body(resource);
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

            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                return ResponseEntity.badRequest().body("文件名不能为空");
            }

            String lowerFileName = fileName.toLowerCase();
            if (!lowerFileName.endsWith(".md") && !lowerFileName.endsWith(".pdf")) {
                return ResponseEntity.badRequest().body("只支持 MD 和 PDF 文件");
            }

            Path basePath = Paths.get(knowledgeBasePath);
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
            }

            Path targetPath = basePath.resolve(fileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("文件上传成功: {}", fileName);
            return ResponseEntity.ok("文件上传成功");
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResponseEntity.internalServerError().body("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/reload")
    public ResponseEntity<String> reloadKnowledgeBase() {
        try {
            knowledgeBaseRagConfig.reloadKnowledgeBase();
            return ResponseEntity.ok("知识库重载成功");
        } catch (Exception e) {
            log.error("知识库重载失败", e);
            return ResponseEntity.internalServerError().body("知识库重载失败: " + e.getMessage());
        }
    }
}

package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.skills.SkillDoc;
import io.github.zzz8688.diagagent.skills.SkillExecutionPlan;
import io.github.zzz8688.diagagent.skills.SkillService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillManagementController {

    private static final Logger log = LoggerFactory.getLogger(SkillManagementController.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Value("${skills.base.path:./skills}")
    private String skillsBasePath;

    private final SkillService skillService;
    private final SandboxExecutionService sandboxExecutionService;

    public static class SkillFile {
        private String id;
        private String skillId;
        private String title;
        private String type;
        private String source;
        private String size;
        private String updateTime;
        private String description;

        public SkillFile() {
        }

        public SkillFile(String id, String skillId, String title, String type, String source, String size,
                         String updateTime, String description) {
            this.id = id;
            this.skillId = skillId;
            this.title = title;
            this.type = type;
            this.source = source;
            this.size = size;
            this.updateTime = updateTime;
            this.description = description;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSkillId() {
            return skillId;
        }

        public void setSkillId(String skillId) {
            this.skillId = skillId;
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

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
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

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            List<SkillFile> allFiles = skillService.listVisibleSkills().stream()
                    .map(this::toSkillFile)
                    .sorted(Comparator.comparing(SkillFile::getTitle, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());

            int total = allFiles.size();
            int fromIndex = Math.max((page - 1) * size, 0);
            int toIndex = Math.min(fromIndex + Math.max(size, 1), total);
            List<SkillFile> pageFiles = fromIndex < total ? allFiles.subList(fromIndex, toIndex) : List.of();

            Map<String, Object> result = new HashMap<>();
            result.put("records", pageFiles);
            result.put("total", total);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取技能列表失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/content/{skillId}")
    public ResponseEntity<String> getSkillContent(@PathVariable String skillId) {
        String content = skillService.readSkillBody(skillId);
        if (content == null || content.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(content);
    }

    @GetMapping("/execution/{skillId}")
    public ResponseEntity<SkillExecutionPlan> getExecutionPlan(@PathVariable String skillId,
                                                               @RequestParam(required = false) String arguments,
                                                               @RequestParam(required = false) String context) {
        SkillExecutionPlan plan = skillService.buildExecutionPlan(skillId, arguments, context);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(plan);
    }

    @GetMapping("/download/{skillId}")
    public ResponseEntity<Resource> downloadSkill(@PathVariable String skillId) {
        SkillDoc skill = skillService.findSkill(skillId);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] zipBytes = skillService.exportSkillArchive(skillId);
            if (zipBytes == null) {
                return ResponseEntity.notFound().build();
            }
            String downloadName = skill.id() + ".zip";
            ByteArrayResource resource = new ByteArrayResource(zipBytes);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8) + "\"")
                    .contentLength(zipBytes.length)
                    .body(resource);
        } catch (IOException e) {
            log.error("下载技能失败: {}", skillId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadSkill(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("文件不能为空");
            }

            String fileName = file.getOriginalFilename();
            if (fileName == null || fileName.isBlank()) {
                return ResponseEntity.badRequest().body("文件名不能为空");
            }

            String normalizedFileName = fileName.toLowerCase(Locale.ROOT);
            if (!normalizedFileName.endsWith(".zip") && !normalizedFileName.endsWith(".md")) {
                return ResponseEntity.badRequest().body("只支持 ZIP 和 MD 文件");
            }

            java.nio.file.Path basePath = resolveSkillsBasePath();
            Files.createDirectories(basePath);

            SandboxProtectedActionEnvelope<String> envelope = sandboxExecutionService.executeProtectedAction(
                    "uploadSkill",
                    SandboxTargetType.FILE,
                    basePath.toAbsolutePath().normalize().toString(),
                    Map.of(
                            "fileName", fileName,
                            "contentType", file.getContentType() == null ? "" : file.getContentType(),
                            "fileSize", file.getSize(),
                            "uploadType", normalizedFileName.endsWith(".zip") ? "zip" : "markdown"
                    ),
                    basePath.toAbsolutePath().normalize().toString(),
                    () -> {
                        if (normalizedFileName.endsWith(".zip")) {
                            return extractSkillZip(file, basePath, fileName);
                        }
                        return saveStandaloneSkillMarkdown(file, basePath, fileName);
                    }
            );
            if (!envelope.success() || envelope.result() == null || envelope.result().isBlank()) {
                return ResponseEntity.internalServerError().body("技能上传失败: " + safe(envelope.errorMessage()));
            }
            String skillId = envelope.result();

            return ResponseEntity.ok("技能上传成功：" + skillId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("技能上传失败", e);
            return ResponseEntity.internalServerError().body("技能上传失败: " + e.getMessage());
        }
    }

    private SkillFile toSkillFile(SkillDoc skill) {
        String title = (skill.title() == null || skill.title().isBlank()) ? skill.id() : skill.title();
        if ("bundled".equalsIgnoreCase(skill.loadedFrom())) {
            long totalSize = skill.body().getBytes(StandardCharsets.UTF_8).length;
            totalSize += skill.bundledResources().values().stream()
                    .mapToLong(content -> content.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
            totalSize += skill.bundledScripts().values().stream()
                    .mapToLong(content -> content.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
            return new SkillFile(
                    skill.id(),
                    skill.id(),
                    title,
                    "SKILL",
                    "BUNDLED",
                    formatSize(totalSize),
                    "内置",
                    skill.description()
            );
        }

        java.nio.file.Path rootPath = java.nio.file.Paths.get(skill.rootPath());
        try (java.util.stream.Stream<java.nio.file.Path> pathStream = Files.walk(rootPath)) {
            List<java.nio.file.Path> files = pathStream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());

            long totalSize = 0L;
            Instant latestUpdate = Instant.EPOCH;
            for (java.nio.file.Path file : files) {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                totalSize += attrs.size();
                Instant lastModified = attrs.lastModifiedTime().toInstant();
                if (lastModified.isAfter(latestUpdate)) {
                    latestUpdate = lastModified;
                }
            }

            return new SkillFile(
                    skill.id(),
                    skill.id(),
                    title,
                    "SKILL",
                    "FILESYSTEM",
                    formatSize(totalSize),
                    DATE_TIME_FORMATTER.format(latestUpdate),
                    skill.description()
            );
        } catch (IOException e) {
            log.warn("读取技能目录属性失败: {}", rootPath, e);
            return new SkillFile(skill.id(), skill.id(), title, "SKILL", "FILESYSTEM", "未知", "未知", skill.description());
        }
    }

    private String saveStandaloneSkillMarkdown(MultipartFile file, java.nio.file.Path basePath, String fileName) throws IOException {
        String skillId = normalizeSkillId(stripExtension(fileName));
        if (skillId.isBlank()) {
            throw new IllegalArgumentException("无法从文件名推导 skillId");
        }

        java.nio.file.Path skillDir = basePath.resolve(skillId);
        recreateDirectory(skillDir);
        java.nio.file.Path skillFile = skillDir.resolve("SKILL.md");
        Files.copy(file.getInputStream(), skillFile, StandardCopyOption.REPLACE_EXISTING);
        return skillId;
    }

    private String extractSkillZip(MultipartFile file, java.nio.file.Path basePath, String fileName) throws IOException {
        List<ZipEntryData> entries = readZipEntries(file.getInputStream());
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("ZIP 包为空");
        }

        String commonRoot = detectCommonRoot(entries);
        String fallbackSkillId = normalizeSkillId(stripExtension(fileName));
        String skillId = normalizeSkillId(commonRoot == null ? fallbackSkillId : commonRoot);
        if (skillId.isBlank()) {
            throw new IllegalArgumentException("无法从 ZIP 包推导 skillId");
        }

        java.nio.file.Path targetDir = basePath.resolve(skillId);
        recreateDirectory(targetDir);

        boolean hasSkillFile = false;
        for (ZipEntryData entry : entries) {
            String relativePath = stripCommonRoot(entry.path(), commonRoot);
            if (relativePath.isBlank()) {
                continue;
            }

            java.nio.file.Path outputPath = targetDir.resolve(relativePath).normalize();
            if (!outputPath.startsWith(targetDir)) {
                throw new IllegalArgumentException("ZIP 包包含非法路径");
            }

            if (entry.directory()) {
                Files.createDirectories(outputPath);
                continue;
            }

            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, entry.content());
            if ("SKILL.md".equalsIgnoreCase(relativePath)) {
                hasSkillFile = true;
            }
        }

        if (!hasSkillFile) {
            deleteDirectory(targetDir);
            throw new IllegalArgumentException("ZIP 包中缺少 SKILL.md");
        }

        return skillId;
    }

    private List<ZipEntryData> readZipEntries(InputStream inputStream) throws IOException {
        List<ZipEntryData> entries = new ArrayList<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String normalizedPath = normalizeZipEntryName(entry.getName());
                if (normalizedPath.isBlank()) {
                    continue;
                }

                if (entry.isDirectory()) {
                    entries.add(new ZipEntryData(normalizedPath, true, new byte[0]));
                } else {
                    entries.add(new ZipEntryData(normalizedPath, false, zipInputStream.readAllBytes()));
                }
            }
        }
        return entries;
    }

    private String detectCommonRoot(List<ZipEntryData> entries) {
        String commonRoot = null;
        for (ZipEntryData entry : entries) {
            String path = entry.path();
            int slashIndex = path.indexOf('/');
            if (slashIndex <= 0) {
                return null;
            }
            String firstSegment = path.substring(0, slashIndex);
            if (commonRoot == null) {
                commonRoot = firstSegment;
                continue;
            }
            if (!commonRoot.equals(firstSegment)) {
                return null;
            }
        }
        return commonRoot;
    }

    private String stripCommonRoot(String path, String commonRoot) {
        if (commonRoot == null || commonRoot.isBlank()) {
            return path;
        }
        if (path.equals(commonRoot)) {
            return "";
        }
        String prefix = commonRoot + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private String normalizeZipEntryName(String entryName) {
        if (entryName == null) {
            return "";
        }
        String normalized = entryName.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("ZIP 包包含非法路径");
        }
        return normalized;
    }

    private String normalizeSkillId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace('\\', '-')
                .replace('/', '-')
                .replace(' ', '-');
    }

    private String stripExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private java.nio.file.Path resolveSkillsBasePath() {
        String normalized = skillsBasePath == null || skillsBasePath.isBlank() ? "./skills" : skillsBasePath.trim();
        if (normalized.startsWith("file:")) {
            normalized = normalized.substring("file:".length());
        }
        return java.nio.file.Paths.get(normalized);
    }

    private void recreateDirectory(java.nio.file.Path directory) throws IOException {
        if (Files.exists(directory)) {
            deleteDirectory(directory);
        }
        Files.createDirectories(directory);
    }

    private void deleteDirectory(java.nio.file.Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(directory)) {
            List<java.nio.file.Path> paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (java.nio.file.Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String formatSize(long sizeBytes) {
        if (sizeBytes < 1024) {
            return sizeBytes + "B";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.1fKB", sizeBytes / 1024.0);
        }
        return String.format("%.1fMB", sizeBytes / (1024.0 * 1024.0));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ZipEntryData(String path, boolean directory, byte[] content) {
    }
}

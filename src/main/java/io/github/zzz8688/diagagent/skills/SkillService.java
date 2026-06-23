package io.github.zzz8688.diagagent.skills;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s,;|/]+");
    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern CJK_SEQUENCE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final BundledSkillRegistry bundledSkillRegistry;

    @Value("${skills.base.path:./skills}")
    private String skillsBasePath;

    public List<SkillDoc> listSkills() {
        List<SkillDoc> merged = new ArrayList<>();
        Map<String, Integer> indexById = new LinkedHashMap<>();

        for (SkillDoc skill : bundledSkillRegistry.listBundledSkills()) {
            registerSkill(merged, indexById, skill);
        }
        for (SkillDoc skill : loadSkillsFromFileSystem(resolveFileSystemBasePath(resolveSkillsBasePath()))) {
            registerSkill(merged, indexById, skill);
        }

        return List.copyOf(merged);
    }

    public List<SkillDoc> listVisibleSkills() {
        return listSkills().stream()
                .filter(SkillDoc::userVisible)
                .collect(Collectors.toList());
    }

    public List<SkillSearchResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        return listVisibleSkills().stream()
                .map(skill -> new SkillSearchResult(skill, scoreSkill(skill, query)))
                .filter(result -> result.score() > 0D)
                .sorted(Comparator.comparingDouble(SkillSearchResult::score).reversed())
                .limit(Math.max(maxResults, 1))
                .collect(Collectors.toList());
    }

    public SkillDoc findSkill(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        String normalizedSkillId = skillId.trim();
        return listSkills().stream()
                .filter(skill -> normalizedSkillId.equalsIgnoreCase(skill.id()))
                .findFirst()
                .orElse(null);
    }

    public String readSkillBody(String skillId) {
        SkillDoc skill = findSkill(skillId);
        if (skill == null) {
            return null;
        }
        return skill.body();
    }

    public SkillExecutionPlan buildExecutionPlan(String skillId, String rawArguments, String additionalContext) {
        SkillDoc skill = findSkill(skillId);
        if (skill == null) {
            return null;
        }
        Map<String, String> resolvedArguments = resolveSkillArguments(skill, rawArguments);
        String prompt = renderExecutionPrompt(skill, resolvedArguments, additionalContext);
        return new SkillExecutionPlan(
                skill.id(),
                skill.title(),
                skill.description(),
                skill.whenToUse(),
                skill.allowedTools(),
                skill.argumentHint(),
                skill.argumentNames(),
                resolvedArguments,
                skill.version(),
                skill.userInvocable(),
                skill.resourceFiles(),
                skill.executionSchema(),
                prompt
        );
    }

    public String readResource(String skillId, String resourcePath) {
        SkillDoc skill = findSkill(skillId);
        if (skill == null) {
            return null;
        }
        String normalizedPath = normalizeArtifactPath(resourcePath);
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        boolean allowed = skill.resourceFiles().stream()
                .map(this::normalizeArtifactPath)
                .filter(Objects::nonNull)
                .anyMatch(normalizedPath::equals);
        if (!allowed) {
            return null;
        }

        if ("bundled".equalsIgnoreCase(skill.loadedFrom())) {
            return skill.bundledResources().get(normalizedPath);
        }

        try {
            Path resourceFile = Paths.get(skill.rootPath(), normalizedPath);
            if (!Files.exists(resourceFile) || !Files.isRegularFile(resourceFile)) {
                return null;
            }
            return Files.readString(resourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取 Skill 资源失败: skillId={}, resourcePath={}", skillId, normalizedPath, e);
            return null;
        }
    }

    public byte[] exportSkillArchive(String skillId) throws IOException {
        SkillDoc skill = findSkill(skillId);
        if (skill == null) {
            return null;
        }

        if ("bundled".equalsIgnoreCase(skill.loadedFrom())) {
            return buildBundledArchive(skill);
        }
        if (skill.rootPath() == null || skill.rootPath().isBlank()) {
            return null;
        }
        return zipDirectory(Paths.get(skill.rootPath()));
    }

    public String buildSkillCatalogPrompt(String agentName) {
        List<SkillDoc> scopedSkills = listVisibleSkills().stream()
                .limit(8)
                .collect(Collectors.toList());

        if (scopedSkills.isEmpty()) {
            return "";
        }

        String skillList = scopedSkills.stream()
                .map(skill -> String.format("- %s: %s", skill.id(), defaultText(skill.description(), "未填写描述")))
                .collect(Collectors.joining("\n"));

        return """
                当前可用的技能模板如下。
                这些技能可能来自页面上传的外置 skill，也可能来自代码内置的 bundled skill registry。
                首轮只根据技能 metadata 做发现，不要默认认为技能正文已经加载。
                skill 不是事实工具；当主智能体选择 RUN_SKILL_STEP 时，runtime 会把对应 SKILL.md 正文作为方法资产注入上下文。
                skill 的静态资源只作为内部按需扩展材料，不应当被当成主链常规工具动作面。
                %s
                """.formatted(skillList);
    }

    private void registerSkill(List<SkillDoc> merged, Map<String, Integer> indexById, SkillDoc skill) {
        String key = normalizeSkillKey(skill.id());
        Integer existingIndex = indexById.get(key);
        if (existingIndex == null) {
            indexById.put(key, merged.size());
            merged.add(skill);
            return;
        }
        merged.set(existingIndex, skill);
    }

    private List<SkillDoc> loadSkillsFromFileSystem(Path basePath) {
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            log.warn("Skills 目录不存在: {}", basePath);
            return List.of();
        }

        try (Stream<Path> stream = Files.list(basePath)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(skillDir -> readSkillFromFileSystem(basePath, skillDir))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("读取 Skills 目录失败: {}", basePath, e);
            return List.of();
        }
    }

    private SkillDoc readSkillFromFileSystem(Path basePath, Path skillDir) {
        Path skillFilePath = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFilePath) || !Files.isRegularFile(skillFilePath)) {
            return null;
        }
        Path relativePath = basePath.relativize(skillFilePath);
        SkillArtifacts artifacts = scanArtifactsFromFileSystem(skillDir);
        try {
            String raw = Files.readString(skillFilePath, StandardCharsets.UTF_8);
            return parseFilesystemSkill(
                    raw,
                    relativePath.toString().replace('\\', '/'),
                    skillDir.toString(),
                    artifacts.resourceFiles(),
                    artifacts.scriptFiles()
            );
        } catch (IOException e) {
            log.error("读取 Skill 文件失败: {}", skillFilePath, e);
            return new SkillDoc(
                    skillDir.getFileName().toString(),
                    skillDir.getFileName().toString(),
                    "读取失败",
                    "",
                    "",
                    relativePath.toString().replace('\\', '/'),
                    skillDir.toString(),
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    null,
                    true,
                    "filesystem",
                    "skills",
                    true,
                    Map.of(),
                    Map.of(),
                    SkillExecutionSchema.platformDefault(List.of()),
                    Map.of()
            );
        }
    }

    private SkillDoc parseFilesystemSkill(String raw, String sourcePath, String rootPath, List<String> resourceFiles, List<String> scriptFiles) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        String body = normalized;

        if (normalized.startsWith("---\n")) {
            int end = normalized.indexOf("\n---\n", 4);
            if (end > 0) {
                String frontmatterBlock = normalized.substring(4, end);
                body = normalized.substring(end + 5).trim();
                frontmatter = parseFrontmatter(frontmatterBlock);
            }
        }

        String fileName = Paths.get(sourcePath).getFileName().toString();
        String fallbackId = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        String title = extractTitle(body);
        String id = valueOrDefault(stringValue(frontmatter, "name"), fallbackId);

        return new SkillDoc(
                id,
                valueOrDefault(title, id),
                valueOrDefault(stringValue(frontmatter, "description"), ""),
                valueOrDefault(stringValue(frontmatter, "when_to_use"), ""),
                body,
                sourcePath,
                rootPath,
                resourceFiles,
                parseList(frontmatter.get("allowed-tools")),
                stringValue(frontmatter, "argument-hint"),
                parseList(frontmatter.get("arguments")),
                stringValue(frontmatter, "version"),
                booleanValue(frontmatter.get("user-invocable"), true),
                "filesystem",
                "skills",
                true,
                Map.of(),
                Map.of(),
                buildExecutionSchema(frontmatter, scriptFiles),
                extractPublicMetadata(frontmatter)
        );
    }

    private double scoreSkill(SkillDoc skill, String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        if (normalizedQuery.isEmpty()) {
            return 0D;
        }

        double score = 0D;
        score += containsScore(skill.id(), normalizedQuery, 4D);
        score += containsScore(skill.title(), normalizedQuery, 3D);
        score += containsScore(skill.description(), normalizedQuery, 3D);
        score += containsScore(skill.whenToUse(), normalizedQuery, 3D);
        score += containsScore(String.join(" ", skill.resourceFiles()), normalizedQuery, 1D);
        score += containsScore(skill.body(), normalizedQuery, 1.5D);

        for (String token : extractSearchTerms(normalizedQuery)) {
            if (token == null || token.isBlank()) {
                continue;
            }
            score += containsScore(skill.id(), token, 2D);
            score += containsScore(skill.title(), token, 2D);
            score += containsScore(skill.description(), token, 1.5D);
            score += containsScore(skill.whenToUse(), token, 1.5D);
            score += containsScore(String.join(" ", skill.resourceFiles()), token, 0.5D);
            score += containsScore(skill.body(), token, 0.5D);
        }

        return score;
    }

    private Set<String> extractSearchTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return Set.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(normalized)) {
            if (token != null && !token.isBlank()) {
                terms.add(token.trim());
            }
        }

        Matcher latinMatcher = LATIN_TOKEN_PATTERN.matcher(normalized);
        while (latinMatcher.find()) {
            String token = latinMatcher.group();
            if (token != null && token.length() >= 3) {
                terms.add(token);
            }
        }

        Matcher cjkMatcher = CJK_SEQUENCE_PATTERN.matcher(normalized);
        while (cjkMatcher.find()) {
            addCjkTerms(terms, cjkMatcher.group());
        }
        return terms;
    }

    private void addCjkTerms(Set<String> terms, String sequence) {
        if (sequence == null || sequence.isBlank()) {
            return;
        }
        String trimmed = sequence.trim();
        if (trimmed.length() <= 8) {
            terms.add(trimmed);
        }
        for (int gram = 2; gram <= 4; gram++) {
            if (trimmed.length() < gram) {
                continue;
            }
            for (int index = 0; index <= trimmed.length() - gram; index++) {
                terms.add(trimmed.substring(index, index + gram));
            }
        }
    }

    private double containsScore(String text, String query, double weight) {
        if (text == null || text.isBlank()) {
            return 0D;
        }
        return text.toLowerCase(Locale.ROOT).contains(query) ? weight : 0D;
    }

    private Map<String, Object> parseFrontmatter(String block) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (String rawLine : block.split("\n")) {
            String line = rawLine == null ? "" : rawLine;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separatorIndex = line.indexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }

            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            if (!value.isBlank()) {
                result.put(key, parseFrontmatterValue(value));
            }
        }

        return result;
    }

    private String extractTitle(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return null;
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Object parseFrontmatterValue(String raw) {
        String value = stripQuotes(raw);
        if (value == null) {
            return null;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            String body = value.substring(1, value.length() - 1).trim();
            if (body.isBlank()) {
                return List.of();
            }
            return Stream.of(body.split(","))
                    .map(String::trim)
                    .map(this::stripQuotes)
                    .filter(Objects::nonNull)
                    .filter(token -> !token.isBlank())
                    .toList();
        }
        if (value.startsWith("{") && value.endsWith("}")) {
            Map<String, Object> object = new LinkedHashMap<>();
            String body = value.substring(1, value.length() - 1).trim();
            if (body.isBlank()) {
                return object;
            }
            for (String pair : body.split(",")) {
                int separatorIndex = pair.indexOf(':');
                if (separatorIndex <= 0) {
                    continue;
                }
                String key = stripQuotes(pair.substring(0, separatorIndex).trim());
                String itemValue = stripQuotes(pair.substring(separatorIndex + 1).trim());
                if (key != null && !key.isBlank()) {
                    object.put(key, itemValue);
                }
            }
            return object;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Map<String, Object> parseObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private SkillExecutionSchema buildExecutionSchema(Map<String, Object> frontmatter, List<String> scriptFiles) {
        String executionContext = valueOrDefault(stringValue(frontmatter, "context"), "inline");
        String targetAgent = stringValue(frontmatter, "agent");
        String modelSelector = stringValue(frontmatter, "model");
        Map<String, Object> hooks = parseObject(frontmatter.get("hooks"));
        String schemaSource = hasLegacyExecutionOverrides(frontmatter)
                ? "frontmatter-compat"
                : "platform-default";
        String executionMode = scriptFiles == null || scriptFiles.isEmpty()
                ? "inline-prompt-plan"
                : "sandbox-script";
        return new SkillExecutionSchema(
                executionMode,
                executionContext,
                valueOrDefault(targetAgent, ""),
                valueOrDefault(modelSelector, ""),
                hooks,
                scriptFiles,
                schemaSource
        );
    }

    private boolean hasLegacyExecutionOverrides(Map<String, Object> frontmatter) {
        return frontmatter.containsKey("context")
                || frontmatter.containsKey("agent")
                || frontmatter.containsKey("model")
                || frontmatter.containsKey("hooks");
    }

    private Map<String, Object> extractPublicMetadata(Map<String, Object> frontmatter) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyIfPresent(frontmatter, metadata, "name");
        copyIfPresent(frontmatter, metadata, "description");
        copyIfPresent(frontmatter, metadata, "when_to_use");
        copyIfPresent(frontmatter, metadata, "allowed-tools");
        copyIfPresent(frontmatter, metadata, "argument-hint");
        copyIfPresent(frontmatter, metadata, "arguments");
        copyIfPresent(frontmatter, metadata, "version");
        copyIfPresent(frontmatter, metadata, "user-invocable");
        return Map.copyOf(metadata);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private List<String> parseList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(token -> !token.isBlank())
                    .toList();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return List.of();
        }
        return Stream.of(text.split(","))
                .map(String::trim)
                .map(this::stripQuotes)
                .filter(Objects::nonNull)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(text);
    }

    private SkillArtifacts scanArtifactsFromFileSystem(Path skillDir) {
        return new SkillArtifacts(
                scanResourceFilesFromFileSystem(skillDir),
                scanScriptFilesFromFileSystem(skillDir.resolve("scripts"))
        );
    }

    private List<String> scanResourceFilesFromFileSystem(Path skillDir) {
        if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(skillDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSkillResourceFile)
                    .filter(path -> !SKILL_FILE_NAME.equalsIgnoreCase(path.getFileName().toString()))
                    .filter(path -> !isUnderScriptsDir(skillDir, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(skillDir::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("扫描 Skill 静态资源失败: {}", skillDir, e);
            return List.of();
        }
    }

    private boolean isSkillResourceFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String normalizedPath = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalizedPath.endsWith(".md");
    }

    private List<String> scanScriptFilesFromFileSystem(Path root) {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .map(root::relativize)
                    .map(path -> root.getFileName().toString() + "/" + path.toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("扫描 Skill 目录资源失败: {}", root, e);
            return List.of();
        }
    }

    private byte[] buildBundledArchive(SkillDoc skill) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            writeZipEntry(zipOutputStream, skill.id() + "/SKILL.md", renderSkillMarkdown(skill));
            for (Map.Entry<String, String> entry : skill.bundledResources().entrySet()) {
                writeZipEntry(zipOutputStream, skill.id() + "/" + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, String> entry : skill.bundledScripts().entrySet()) {
                writeZipEntry(zipOutputStream, skill.id() + "/" + entry.getKey(), entry.getValue());
            }
            zipOutputStream.finish();
            return outputStream.toByteArray();
        }
    }

    private void writeZipEntry(ZipOutputStream zipOutputStream, String entryName, String content) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }

    private String renderSkillMarkdown(SkillDoc skill) {
        return """
                ---
                name: %s
                description: %s
                ---

                %s
                """.formatted(
                skill.id(),
                defaultText(skill.description(), ""),
                defaultText(skill.body(), "")
        );
    }

    private Map<String, String> resolveSkillArguments(SkillDoc skill, String rawArguments) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> declaredArguments = skill.argumentNames();
        if (declaredArguments.isEmpty()) {
            if (rawArguments != null && !rawArguments.isBlank()) {
                resolved.put("input", rawArguments.trim());
            }
            return resolved;
        }
        List<String> provided = rawArguments == null || rawArguments.isBlank()
                ? List.of()
                : Stream.of(rawArguments.split("[,;\\n]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
        for (int index = 0; index < declaredArguments.size(); index++) {
            String argumentName = declaredArguments.get(index);
            String value = index < provided.size() ? provided.get(index) : "";
            resolved.put(argumentName, value);
        }
        return resolved;
    }

    private String renderExecutionPrompt(SkillDoc skill,
                                         Map<String, String> resolvedArguments,
                                         String additionalContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Skill Execution Plan").append('\n');
        builder.append("skillId: ").append(skill.id()).append('\n');
        builder.append("title: ").append(defaultText(skill.title(), skill.id())).append('\n');
        builder.append("description: ").append(defaultText(skill.description(), "")).append('\n');
        if (skill.whenToUse() != null && !skill.whenToUse().isBlank()) {
            builder.append("whenToUse: ").append(skill.whenToUse()).append('\n');
        }
        if (!skill.allowedTools().isEmpty()) {
            builder.append("allowedTools: ").append(String.join(", ", skill.allowedTools())).append('\n');
        }
        SkillExecutionSchema executionSchema = skill.executionSchema();
        if (executionSchema != null) {
            builder.append("executionMode: ").append(defaultText(executionSchema.executionMode(), "inline-prompt-plan")).append('\n');
            if (!defaultText(executionSchema.executionContext(), "").isBlank()) {
                builder.append("executionContext: ").append(executionSchema.executionContext()).append('\n');
            }
            if (!defaultText(executionSchema.targetAgent(), "").isBlank()) {
                builder.append("targetAgent: ").append(executionSchema.targetAgent()).append('\n');
            }
            if (!defaultText(executionSchema.modelSelector(), "").isBlank()) {
                builder.append("modelSelector: ").append(executionSchema.modelSelector()).append('\n');
            }
        }
        if (!resolvedArguments.isEmpty()) {
            builder.append("\n## Arguments\n");
            resolvedArguments.forEach((key, value) -> builder.append("- ").append(key).append(": ").append(defaultText(value, "")).append('\n'));
        }
        if (additionalContext != null && !additionalContext.isBlank()) {
            builder.append("\n## Additional Context\n").append(additionalContext.trim()).append('\n');
        }
        if (!skill.resourceFiles().isEmpty()) {
            builder.append("\n## Resources\n- ").append(String.join("\n- ", skill.resourceFiles())).append('\n');
        }
        if (executionSchema != null && !executionSchema.scriptFiles().isEmpty()) {
            builder.append("\n## Scripts\n- ").append(String.join("\n- ", executionSchema.scriptFiles())).append('\n');
        }
        builder.append("\n## Instructions\n").append(defaultText(skill.body(), "").trim());
        return builder.toString().trim();
    }

    private byte[] zipDirectory(Path directory) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8);
             Stream<Path> pathStream = Files.walk(directory)) {
            List<Path> files = pathStream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());

            for (Path file : files) {
                String entryName = directory.getFileName() + "/" + directory.relativize(file).toString().replace('\\', '/');
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zipOutputStream);
                zipOutputStream.closeEntry();
            }
            zipOutputStream.finish();
            return outputStream.toByteArray();
        }
    }

    private String resolveSkillsBasePath() {
        return skillsBasePath == null || skillsBasePath.isBlank() ? "./skills" : skillsBasePath.trim();
    }

    private Path resolveFileSystemBasePath(String baseLocation) {
        String normalized = baseLocation;
        if (normalized.startsWith("file:")) {
            normalized = normalized.substring("file:".length());
        }
        return Paths.get(normalized);
    }

    private String normalizeArtifactPath(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String normalized = resourcePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            return null;
        }
        return normalized;
    }

    private boolean isUnderScriptsDir(Path skillDir, Path path) {
        Path relative = skillDir.relativize(path);
        return relative.getNameCount() > 0 && "scripts".equalsIgnoreCase(relative.getName(0).toString());
    }

    private String normalizeSkillKey(String skillId) {
        return skillId == null ? "" : skillId.trim().toLowerCase(Locale.ROOT);
    }

    private record SkillArtifacts(List<String> resourceFiles, List<String> scriptFiles) {
    }
}

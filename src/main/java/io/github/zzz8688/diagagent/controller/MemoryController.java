package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.config.MemoryFileRagConfig;
import io.github.zzz8688.diagagent.agent.runtime.ExecutionEvent;
import io.github.zzz8688.diagagent.agent.runtime.ExecutionEventType;
import io.github.zzz8688.diagagent.agent.runtime.RuntimeTask;
import io.github.zzz8688.diagagent.agent.runtime.TaskManager;
import io.github.zzz8688.diagagent.entity.MemoryCandidate;
import io.github.zzz8688.diagagent.service.ManagedMemoryGovernanceService;
import io.github.zzz8688.diagagent.service.MemoryCandidateService;
import io.github.zzz8688.diagagent.service.MemoryMaintenanceAgent;
import io.github.zzz8688.diagagent.service.MemorySummaryService;
import io.github.zzz8688.diagagent.store.MemorySummaryDoc;
import io.github.zzz8688.diagagent.store.SummaryChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final MemorySummaryService memorySummaryService;
    private final MemoryCandidateService memoryCandidateService;
    private final MemoryMaintenanceAgent memoryMaintenanceAgent;
    private final MemoryFileRagConfig memoryFileRagConfig;
    private final ManagedMemoryGovernanceService managedMemoryGovernanceService;
    private final TaskManager taskManager;

    @Value("${memory.files.path:./memory}")
    private String memoryFilesPath;

    @Value("${chat.memory.enable-summary:false}")
    private boolean enableSummary;

    @Value("${chat.memory.max-messages-before-summary:30}")
    private int maxRecentMessages;

    @Value("${chat.memory.questions-per-summary:5}")
    private int questionsPerSummary;

    @Value("${chat.memory.max-tokens:4000}")
    private int chatMemoryMaxTokens;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        List<MemorySummaryDoc> summaries = memorySummaryService.listRecentSummaries(20);
        List<MemoryCandidate> candidates = memoryCandidateService.listRecentCandidates(20);
        List<Map<String, Object>> files = listManagedFiles(20);
        List<MemoryRuntimeEventView> recentMemoryEvents = buildRecentMemoryEvents(20, 12);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summaryCount", memorySummaryService.countSummaries());
        result.put("candidateStatusCounts", memoryCandidateService.countByStatus());
        result.put("managedFileCount", files.size());
        result.put("maintenanceRunning", memoryMaintenanceAgent.isRunning());
        result.put("maintenanceLastRun", memoryMaintenanceAgent.getLastRunSummary());
        result.put("compressionRules", SummaryChatMemory.buildRulesSnapshot(
                enableSummary,
                maxRecentMessages,
                questionsPerSummary,
                chatMemoryMaxTokens
        ));
        result.put("recentActivity", buildRecentActivity(recentMemoryEvents));
        result.put("recentEvents", recentMemoryEvents);
        result.put("summaries", summaries);
        result.put("candidates", candidates);
        result.put("managedFiles", files);
        result.put("recentMutations", managedMemoryGovernanceService.listRecentMutations(30));
        return ResponseEntity.ok(result);
    }

    private MemoryRecentActivityView buildRecentActivity(List<MemoryRuntimeEventView> recentEvents) {
        if (recentEvents == null || recentEvents.isEmpty()) {
            return MemoryRecentActivityView.empty();
        }
        int summaryRefreshCount = 0;
        int contextCompactionCount = 0;
        int minimalContextActivationCount = 0;
        int compactedMessageCount = 0;
        MemoryRuntimeEventView latestSummaryEvent = null;
        MemoryRuntimeEventView latestCompactionEvent = null;
        for (MemoryRuntimeEventView event : recentEvents) {
            if (event == null) {
                continue;
            }
            if ("MEMORY_SUMMARY_REFRESHED".equals(event.eventType())) {
                summaryRefreshCount++;
                if (latestSummaryEvent == null) {
                    latestSummaryEvent = event;
                }
                continue;
            }
            if ("MEMORY_CONTEXT_COMPACTED".equals(event.eventType())) {
                contextCompactionCount++;
                compactedMessageCount += Math.max(0, event.droppedMessageCount());
                if (event.minimalContextMode()) {
                    minimalContextActivationCount++;
                }
                if (latestCompactionEvent == null) {
                    latestCompactionEvent = event;
                }
            }
        }
        return new MemoryRecentActivityView(
                recentEvents.size(),
                summaryRefreshCount,
                contextCompactionCount,
                minimalContextActivationCount,
                compactedMessageCount,
                latestSummaryEvent == null ? "" : latestSummaryEvent.createdAt(),
                latestSummaryEvent == null ? "" : latestSummaryEvent.topic(),
                latestSummaryEvent == null ? 0 : latestSummaryEvent.nextVersion(),
                latestCompactionEvent == null ? "" : latestCompactionEvent.createdAt(),
                latestCompactionEvent == null ? "" : latestCompactionEvent.compactionLevel(),
                latestCompactionEvent != null && latestCompactionEvent.minimalContextMode(),
                latestCompactionEvent == null ? 0 : latestCompactionEvent.usedBudgetTokens(),
                latestCompactionEvent == null ? 0 : latestCompactionEvent.maxBudgetTokens(),
                latestCompactionEvent == null ? 0d : latestCompactionEvent.usageRatio(),
                latestCompactionEvent == null ? 0 : latestCompactionEvent.retainedMessageCount(),
                latestCompactionEvent == null ? 0 : latestCompactionEvent.droppedMessageCount()
        );
    }

    private List<MemoryRuntimeEventView> buildRecentMemoryEvents(int taskLimit, int eventLimit) {
        List<MemoryRuntimeEventView> events = new ArrayList<>();
        for (RuntimeTask task : taskManager.listRecentTasks(taskLimit <= 0 ? 20 : taskLimit)) {
            if (task == null || task.taskId() == null || task.taskId().isBlank()) {
                continue;
            }
            for (ExecutionEvent event : taskManager.replayWindow(task.taskId(), 0, 240)) {
                if (event == null || event.eventType() == null) {
                    continue;
                }
                if (event.eventType() != ExecutionEventType.MEMORY_SUMMARY_REFRESHED
                        && event.eventType() != ExecutionEventType.MEMORY_CONTEXT_COMPACTED) {
                    continue;
                }
                events.add(toMemoryRuntimeEventView(event));
            }
        }
        events.sort(Comparator
                .comparing(MemoryRuntimeEventView::createdAt, Comparator.reverseOrder())
                .thenComparing(MemoryRuntimeEventView::cursor, Comparator.reverseOrder()));
        if (events.size() <= Math.max(1, eventLimit)) {
            return List.copyOf(events);
        }
        return List.copyOf(events.subList(0, Math.max(1, eventLimit)));
    }

    private MemoryRuntimeEventView toMemoryRuntimeEventView(ExecutionEvent event) {
        Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
        int usedBudgetTokens = parseInt(payload.get("usedBudgetTokens"));
        int maxBudgetTokens = parseInt(payload.get("maxBudgetTokens"));
        double usageRatio = maxBudgetTokens <= 0 ? 0d : (double) usedBudgetTokens / maxBudgetTokens;
        return new MemoryRuntimeEventView(
                event.cursor(),
                event.eventType().name(),
                safe(event.sessionId()),
                safe(event.taskId()),
                safe(payload.get("memoryId")),
                safe(payload.get("topic")),
                safe(payload.get("summaryType")),
                parseInt(payload.get("baseVersion")),
                parseInt(payload.get("nextVersion")),
                parseInt(payload.get("deltaMessageCount")),
                parseInt(payload.get("deltaUserQuestionCount")),
                parseInt(payload.get("summarizedUserQuestionCount")),
                safe(payload.get("compactionLevel")),
                Boolean.parseBoolean(payload.getOrDefault("minimalContextMode", "false")),
                parseInt(payload.get("originalMessageCount")),
                parseInt(payload.get("retainedMessageCount")),
                parseInt(payload.get("droppedMessageCount")),
                usedBudgetTokens,
                maxBudgetTokens,
                usageRatio,
                Boolean.parseBoolean(payload.getOrDefault("summaryPresent", "false")),
                buildMemoryEventSummary(event.eventType(), payload, usageRatio),
                event.createdAt() == null ? "" : event.createdAt().toString()
        );
    }

    private String buildMemoryEventSummary(ExecutionEventType eventType, Map<String, String> payload, double usageRatio) {
        if (eventType == ExecutionEventType.MEMORY_SUMMARY_REFRESHED) {
            return "topic=" + safe(payload.get("topic"))
                    + " | version=" + parseInt(payload.get("baseVersion")) + "->" + parseInt(payload.get("nextVersion"))
                    + " | deltaMessages=" + parseInt(payload.get("deltaMessageCount"))
                    + " | deltaQuestions=" + parseInt(payload.get("deltaUserQuestionCount"));
        }
        return "level=" + safe(payload.get("compactionLevel"))
                + " | minimal=" + Boolean.parseBoolean(payload.getOrDefault("minimalContextMode", "false"))
                + " | retained=" + parseInt(payload.get("retainedMessageCount"))
                + " | dropped=" + parseInt(payload.get("droppedMessageCount"))
                + " | budget=" + parseInt(payload.get("usedBudgetTokens")) + "/" + parseInt(payload.get("maxBudgetTokens"))
                + " | usage=" + String.format("%.3f", usageRatio);
    }

    @PostMapping("/maintenance/run")
    public ResponseEntity<MemoryMaintenanceAgent.MaintenanceRunSummary> runMaintenance() {
        return ResponseEntity.ok(memoryMaintenanceAgent.runNow("manual-api"));
    }

    @PostMapping("/reload")
    public ResponseEntity<String> reloadMemoryFiles() {
        memoryFileRagConfig.loadMemoryFilesIncremental();
        return ResponseEntity.ok("memory files 索引已重载");
    }

    @GetMapping("/file-content")
    public ResponseEntity<String> getFileContent(@RequestParam("path") String relativePath) throws IOException {
        Path basePath = Paths.get(memoryFilesPath).toAbsolutePath().normalize();
        Path filePath = managedMemoryGovernanceService.resolveAbsolutePath(relativePath);
        if (!filePath.startsWith(basePath) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readString(filePath));
    }

    @GetMapping("/mutations")
    public ResponseEntity<List<?>> getRecentMutations(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(managedMemoryGovernanceService.listRecentMutations(limit));
    }

    @GetMapping("/versions")
    public ResponseEntity<List<?>> getVersions(@RequestParam("path") String relativePath,
                                               @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(managedMemoryGovernanceService.listVersions(relativePath, limit));
    }

    @GetMapping("/diff")
    public ResponseEntity<ManagedMemoryGovernanceService.VersionDiffResult> getVersionDiff(@RequestParam("path") String relativePath,
                                                                                           @RequestParam("fromVersion") int fromVersion,
                                                                                           @RequestParam("toVersion") int toVersion) throws IOException {
        return ResponseEntity.ok(managedMemoryGovernanceService.diff(relativePath, fromVersion, toVersion));
    }

    @PostMapping("/rollback")
    public ResponseEntity<ManagedMemoryGovernanceService.RollbackResult> rollback(@RequestBody MemoryRollbackRequest request) throws IOException {
        return ResponseEntity.ok(managedMemoryGovernanceService.rollbackManagedFile(
                request.path(),
                request.targetVersion(),
                "manual-api",
                request.reason()
        ));
    }

    private List<Map<String, Object>> listManagedFiles(int limit) {
        Path basePath = Paths.get(memoryFilesPath);
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return List.of();
        }
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(basePath)) {
            List<Path> managedFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(limit <= 0 ? 20 : limit)
                    .toList();
            for (Path path : managedFiles) {
                files.add(toFileDescriptor(basePath, path));
            }
        } catch (Exception e) {
            log.warn("列出 managed memory files 失败", e);
        }
        files.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("updateTime", "")), Comparator.reverseOrder()));
        return files;
    }

    private Map<String, Object> toFileDescriptor(Path basePath, Path path) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String relativePath = basePath.relativize(path).toString().replace('\\', '/');
            descriptor.put("path", relativePath);
            descriptor.put("fileName", path.getFileName().toString());
            descriptor.put("size", attributes.size());
            descriptor.put("updateTime", TIME_FORMATTER.format(attributes.lastModifiedTime().toInstant()));
            descriptor.put("status", readFrontMatterValue(path, "status"));
            descriptor.put("dedupeKey", readFrontMatterValue(path, "dedupe_key"));
            descriptor.put("memoryId", readFrontMatterValue(path, "memory_id"));
            descriptor.put("topic", readFrontMatterValue(path, "topic"));
            descriptor.put("lastOperation", readFrontMatterValue(path, "last_operation"));
            descriptor.put("latestVersion", managedMemoryGovernanceService.findLatestVersionNumber(relativePath));
            descriptor.put("versionCount", managedMemoryGovernanceService.countVersions(relativePath));
            descriptor.put("mutationCount", managedMemoryGovernanceService.countMutations(relativePath));
        } catch (Exception e) {
            descriptor.put("path", basePath.relativize(path).toString().replace('\\', '/'));
            descriptor.put("fileName", path.getFileName().toString());
            descriptor.put("size", -1);
            descriptor.put("updateTime", "");
            descriptor.put("status", "");
            descriptor.put("dedupeKey", "");
            descriptor.put("memoryId", "");
            descriptor.put("topic", "");
            descriptor.put("lastOperation", "");
            descriptor.put("latestVersion", 0);
            descriptor.put("versionCount", 0);
            descriptor.put("mutationCount", 0);
        }
        return descriptor;
    }

    private String readFrontMatterValue(Path path, String key) {
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty() || !"---".equals(lines.get(0).trim())) {
                return "";
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if ("---".equals(line.trim())) {
                    break;
                }
                int delimiter = line.indexOf(':');
                if (delimiter <= 0) {
                    continue;
                }
                String currentKey = line.substring(0, delimiter).trim();
                if (!currentKey.equals(key)) {
                    continue;
                }
                return line.substring(delimiter + 1).trim();
            }
        } catch (Exception e) {
            log.debug("读取 memory file front matter 失败: {}", path, e);
        }
        return "";
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record MemoryRollbackRequest(String path, int targetVersion, String reason) {
    }

    public record MemoryRecentActivityView(
            int eventCount,
            int summaryRefreshCount,
            int contextCompactionCount,
            int minimalContextActivationCount,
            int compactedMessageCount,
            String latestSummaryAt,
            String latestSummaryTopic,
            int latestSummaryVersion,
            String latestCompactionAt,
            String latestCompactionLevel,
            boolean latestMinimalContextMode,
            int latestUsedBudgetTokens,
            int latestMaxBudgetTokens,
            double latestUsageRatio,
            int latestRetainedMessageCount,
            int latestDroppedMessageCount
    ) {
        public static MemoryRecentActivityView empty() {
            return new MemoryRecentActivityView(0, 0, 0, 0, 0, "", "", 0, "", "", false, 0, 0, 0d, 0, 0);
        }
    }

    public record MemoryRuntimeEventView(
            long cursor,
            String eventType,
            String sessionId,
            String taskId,
            String memoryId,
            String topic,
            String summaryType,
            int baseVersion,
            int nextVersion,
            int deltaMessageCount,
            int deltaUserQuestionCount,
            int summarizedUserQuestionCount,
            String compactionLevel,
            boolean minimalContextMode,
            int originalMessageCount,
            int retainedMessageCount,
            int droppedMessageCount,
            int usedBudgetTokens,
            int maxBudgetTokens,
            double usageRatio,
            boolean summaryPresent,
            String summary,
            String createdAt
    ) {
    }
}

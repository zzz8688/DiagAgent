package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.sandbox.audit.SandboxAuditRecord;
import io.github.zzz8688.diagagent.sandbox.audit.SandboxAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeReplayService {

    private final TaskManager taskManager;
    private final EventReplayProjector eventReplayProjector;
    private final SandboxAuditService sandboxAuditService;

    public ReplayTimelineView buildTimelineBySession(String sessionId, int limit) {
        RuntimeTask task = taskManager.findLatestBySessionId(sessionId).orElse(null);
        if (task == null) {
            return ReplayTimelineView.empty(sessionId);
        }
        return buildTimeline(task, limit, sessionId);
    }

    public ReplayTimelineView buildTimelineByTask(String taskId, int limit) {
        RuntimeTask task = taskManager.findTask(taskId).orElse(null);
        if (task == null) {
            return ReplayTimelineView.empty("");
        }
        return buildTimeline(task, limit, task.sessionId());
    }

    public RuntimeReplayOverview buildOverview(int limit) {
        List<RuntimeTask> recentTasks = taskManager.listRecentTasks(limit <= 0 ? 20 : limit);
        long trackedSessionCount = recentTasks.stream()
                .map(RuntimeTask::sessionId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
        return new RuntimeReplayOverview(
                recentTasks,
                taskManager.countTasksByStatus(),
                taskManager.countLaneEntries(),
                trackedSessionCount
        );
    }

    public RuntimeMetricsView buildMetrics(int limit) {
        List<RuntimeTask> recentTasks = taskManager.listRecentTasks(limit <= 0 ? 20 : limit);
        RuntimeMetricsAccumulator accumulator = new RuntimeMetricsAccumulator();
        for (RuntimeTask task : recentTasks) {
            if (task == null || task.taskId() == null || task.taskId().isBlank()) {
                continue;
            }
            ReplayTimelineView timeline = buildTimeline(task, 240, task.sessionId());
            accumulator.recordTimeline(timeline);
        }
        return accumulator.toMetricsView(
                recentTasks.size(),
                recentTasks.stream()
                        .map(RuntimeTask::sessionId)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .count(),
                taskManager.countLaneEntries()
        );
    }

    public RuntimeAuditExport buildAuditExportBySession(String sessionId, int limit) {
        return buildAuditExport(buildTimelineBySession(sessionId, limit));
    }

    public RuntimeAuditExport buildAuditExportByTask(String taskId, int limit) {
        return buildAuditExport(buildTimelineByTask(taskId, limit));
    }

    private ReplayTimelineView buildTimeline(RuntimeTask task, int limit, String fallbackSessionId) {
        String sessionId = task == null || task.sessionId() == null || task.sessionId().isBlank()
                ? fallbackSessionId
                : task.sessionId();
        if (task == null || task.taskId() == null || task.taskId().isBlank()) {
            return ReplayTimelineView.empty(sessionId);
        }
        int safeLimit = limit <= 0 ? 120 : limit;
        List<ExecutionEvent> rawEvents = taskManager.replayWindow(task.taskId(), 0, safeLimit);
        EventReplayProjector.EventReplayProjection projection = rawEvents.isEmpty()
                ? EventReplayProjector.EventReplayProjection.empty()
                : eventReplayProjector.project(rawEvents);
        return new ReplayTimelineView(
                sessionId,
                task.taskId(),
                task,
                taskManager.findLaneState(sessionId).orElse(TaskManager.SessionLaneState.empty(sessionId)),
                projection.latestCursor(),
                rawEvents,
                projection.eventEntries(),
                projection.logEntries(),
                projection.approvalEntries(),
                projection.runtimeEntries(),
                new PromptReplayExport(
                        projection.renderRuntimePromptSection(),
                        projection.renderEventPromptSection(),
                        projection.renderLogPromptSection(),
                        projection.renderApprovalPromptSection()
                ),
                buildSummary(task, rawEvents, projection)
        );
    }

    private RuntimeAuditExport buildAuditExport(ReplayTimelineView timeline) {
        if (timeline == null) {
            return RuntimeAuditExport.empty();
        }
        RuntimeMetricsAccumulator accumulator = new RuntimeMetricsAccumulator();
        accumulator.recordTimeline(timeline);
        List<SandboxAuditRecord> sandboxRecords = loadSandboxAuditRecords(timeline.sessionId(), timeline.taskId());
        RuntimeMetricsView metrics = accumulator.toMetricsView(
                timeline.taskId() == null || timeline.taskId().isBlank() ? 0 : 1,
                timeline.sessionId() == null || timeline.sessionId().isBlank() ? 0 : 1,
                buildLaneQueueCounts(timeline.laneState())
        );
        Map<String, String> commonFields = new LinkedHashMap<>();
        commonFields.put("sessionId", safe(timeline.sessionId()));
        commonFields.put("taskId", safe(timeline.taskId()));
        commonFields.put("taskStatus", safe(timeline.task() == null || timeline.task().status() == null ? null : timeline.task().status().name()));
        commonFields.put("requestMode", safe(timeline.task() == null ? null : timeline.task().requestMode()));
        commonFields.put("engine", safe(timeline.task() == null ? null : timeline.task().engine()));
        commonFields.put("currentNode", safe(timeline.task() == null ? null : timeline.task().currentNode()));
        commonFields.put("latestCursor", Long.toString(timeline.latestCursor()));
        commonFields.put("latestStopReason", safe(timeline.observabilitySummary() == null ? null : timeline.observabilitySummary().latestStopReason()));
        CapabilityAuditSummary capabilitySummary = buildCapabilitySummary(timeline);
        commonFields.put("capabilityViewVersion", capabilitySummary.latestCapabilityViewVersion());
        commonFields.put("capabilityEntryCount", Integer.toString(capabilitySummary.latestCapabilityEntryCount()));
        commonFields.put("capabilityApprovalRequiredCount", Integer.toString(capabilitySummary.latestApprovalRequiredCount()));
        commonFields.put("capabilityEntriesWithSchema", Integer.toString(capabilitySummary.latestEntriesWithSchema()));
        return new RuntimeAuditExport(
                "runtime-audit-v1",
                Instant.now().toString(),
                timeline.sessionId(),
                timeline.taskId(),
                timeline.task(),
                Map.copyOf(commonFields),
                timeline.observabilitySummary(),
                metrics,
                buildApprovalSummary(timeline),
                buildBudgetSummary(timeline),
                buildMemoryCompressionSummary(timeline),
                capabilitySummary,
                buildSandboxSummary(sandboxRecords),
                timeline.promptReplay(),
                timeline.runtimeEntries(),
                timeline.eventEntries(),
                timeline.logEntries(),
                timeline.approvalEntries(),
                sandboxRecords,
                timeline.rawEvents()
        );
    }

    private ApprovalAuditSummary buildApprovalSummary(ReplayTimelineView timeline) {
        if (timeline == null || timeline.rawEvents() == null || timeline.rawEvents().isEmpty()) {
            return ApprovalAuditSummary.empty();
        }
        int requestedCount = 0;
        int approvedCount = 0;
        int deniedCount = 0;
        int timeoutCount = 0;
        int pendingCount = 0;
        long latestCursor = 0L;
        String latestState = "";
        for (ExecutionEvent event : timeline.rawEvents()) {
            if (event == null || event.eventType() == null) {
                continue;
            }
            Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
            switch (event.eventType()) {
                case APPROVAL_REQUESTED -> {
                    requestedCount++;
                    pendingCount++;
                    latestCursor = Math.max(latestCursor, event.cursor());
                    latestState = "PENDING";
                }
                case APPROVAL_RESULT_RECEIVED -> {
                    String state = normalizeKey(payload.get("approvalState"), normalizeKey(payload.get("status"), "UNKNOWN"));
                    latestCursor = Math.max(latestCursor, event.cursor());
                    latestState = state;
                    pendingCount = Math.max(0, pendingCount - 1);
                    if (isSuccessStatus(state)) {
                        approvedCount++;
                    } else if (state.contains("TIMEOUT")) {
                        timeoutCount++;
                    } else if (isFailureStatus(state) || state.contains("DENIED") || state.contains("REJECTED")) {
                        deniedCount++;
                    }
                }
                case SANDBOX_APPROVAL_RESOLVED -> {
                    String state = normalizeKey(payload.get("approvalStatus"), "UNKNOWN");
                    requestedCount++;
                    latestCursor = Math.max(latestCursor, event.cursor());
                    latestState = state;
                    if ("NOT_REQUIRED".equalsIgnoreCase(state) || isSuccessStatus(state)) {
                        approvedCount++;
                    } else if (state.contains("TIMEOUT")) {
                        timeoutCount++;
                    } else if (isFailureStatus(state) || state.contains("DENIED") || state.contains("REJECTED")) {
                        deniedCount++;
                    }
                }
                default -> {
                }
            }
        }
        return new ApprovalAuditSummary(
                requestedCount,
                approvedCount,
                deniedCount,
                timeoutCount,
                pendingCount,
                latestState,
                latestCursor
        );
    }

    private BudgetAuditSummary buildBudgetSummary(ReplayTimelineView timeline) {
        if (timeline == null || timeline.rawEvents() == null || timeline.rawEvents().isEmpty()) {
            return BudgetAuditSummary.empty();
        }
        int budgetCheckCount = 0;
        int minimalContextTriggerCount = 0;
        int hardStopCount = 0;
        long latestCursor = 0L;
        String latestStatus = "";
        String latestCompactionLevel = "";
        for (ExecutionEvent event : timeline.rawEvents()) {
            if (event == null || event.eventType() == null) {
                continue;
            }
            Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
            switch (event.eventType()) {
                case REACT_BUDGET_CHECKED -> {
                    budgetCheckCount++;
                    latestCursor = Math.max(latestCursor, event.cursor());
                    latestStatus = normalizeKey(payload.get("budgetStatus"), latestStatus);
                    latestCompactionLevel = normalizeKey(payload.get("compactionLevel"), latestCompactionLevel);
                    if (Boolean.parseBoolean(payload.getOrDefault("minimalContextMode", "false"))) {
                        minimalContextTriggerCount++;
                    }
                    if ("EXHAUSTED".equalsIgnoreCase(payload.get("budgetStatus"))) {
                        hardStopCount++;
                    }
                }
                case REACT_STOP_REASON_RECORDED -> {
                    if ("TOKEN_BUDGET_EXHAUSTED".equalsIgnoreCase(payload.get("reason"))) {
                        hardStopCount++;
                    }
                }
                default -> {
                }
            }
        }
        return new BudgetAuditSummary(
                budgetCheckCount,
                minimalContextTriggerCount,
                hardStopCount,
                latestStatus,
                latestCompactionLevel,
                latestCursor
        );
    }

    private MemoryCompressionAuditSummary buildMemoryCompressionSummary(ReplayTimelineView timeline) {
        if (timeline == null || timeline.rawEvents() == null || timeline.rawEvents().isEmpty()) {
            return MemoryCompressionAuditSummary.empty();
        }
        int summaryRefreshCount = 0;
        int contextCompactionCount = 0;
        int minimalContextActivationCount = 0;
        int compactedMessageCount = 0;
        String latestSummaryTopic = "";
        String latestCompactionLevel = "";
        long latestCursor = 0L;
        for (ExecutionEvent event : timeline.rawEvents()) {
            if (event == null || event.eventType() == null) {
                continue;
            }
            Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
            switch (event.eventType()) {
                case MEMORY_SUMMARY_REFRESHED -> {
                    summaryRefreshCount++;
                    latestSummaryTopic = normalizeKey(payload.get("topic"), latestSummaryTopic);
                    latestCursor = Math.max(latestCursor, event.cursor());
                }
                case MEMORY_CONTEXT_COMPACTED -> {
                    contextCompactionCount++;
                    latestCompactionLevel = normalizeKey(payload.get("compactionLevel"), latestCompactionLevel);
                    latestCursor = Math.max(latestCursor, event.cursor());
                    if (Boolean.parseBoolean(payload.getOrDefault("minimalContextMode", "false"))) {
                        minimalContextActivationCount++;
                    }
                    compactedMessageCount += parseInt(payload.get("droppedMessageCount"));
                }
                default -> {
                }
            }
        }
        return new MemoryCompressionAuditSummary(
                summaryRefreshCount,
                contextCompactionCount,
                minimalContextActivationCount,
                compactedMessageCount,
                latestSummaryTopic,
                latestCompactionLevel,
                latestCursor
        );
    }

    private SandboxAuditSummary buildSandboxSummary(List<SandboxAuditRecord> sandboxRecords) {
        if (sandboxRecords == null || sandboxRecords.isEmpty()) {
            return SandboxAuditSummary.empty();
        }
        int approvedCount = 0;
        int deniedCount = 0;
        int successCount = 0;
        int timeoutCount = 0;
        int cancelledCount = 0;
        int artifactRefCount = 0;
        Map<String, Long> targetTypeCounts = new LinkedHashMap<>();
        Map<String, Long> resultCounts = new LinkedHashMap<>();
        SandboxAuditRecord latest = sandboxRecords.get(0);
        for (SandboxAuditRecord record : sandboxRecords) {
            if (record == null) {
                continue;
            }
            if (record.approved()) {
                approvedCount++;
            } else {
                deniedCount++;
            }
            if (record.success()) {
                successCount++;
            }
            if (record.timedOut()) {
                timeoutCount++;
            }
            if (record.cancelled()) {
                cancelledCount++;
            }
            artifactRefCount += Math.max(0, record.artifactCount());
            incrementCount(targetTypeCounts, normalizeKey(record.targetType(), "UNKNOWN"));
            incrementCount(resultCounts, normalizeKey(record.resultLabel(), "UNKNOWN"));
        }
        return new SandboxAuditSummary(
                sandboxRecords.size(),
                approvedCount,
                deniedCount,
                successCount,
                timeoutCount,
                cancelledCount,
                artifactRefCount,
                Map.copyOf(targetTypeCounts),
                Map.copyOf(resultCounts),
                safe(latest == null ? null : latest.sandboxProfile()),
                safe(latest == null ? null : latest.approvalBackend()),
                safe(latest == null ? null : latest.targetType()),
                safe(latest == null ? null : latest.executionMode())
        );
    }

    private CapabilityAuditSummary buildCapabilitySummary(ReplayTimelineView timeline) {
        if (timeline == null || timeline.rawEvents() == null || timeline.rawEvents().isEmpty()) {
            return CapabilityAuditSummary.empty();
        }
        int snapshotEventCount = 0;
        String latestCapabilityViewVersion = "";
        int latestCapabilityEntryCount = 0;
        int latestApprovalRequiredCount = 0;
        int latestEntriesWithSchema = 0;
        long latestCursor = 0L;
        Map<String, Long> latestCountsByKind = Map.of();
        Map<String, Long> latestCountsBySource = Map.of();
        for (ExecutionEvent event : timeline.rawEvents()) {
            if (event == null || event.eventType() != ExecutionEventType.REACT_TURN_STARTED) {
                continue;
            }
            snapshotEventCount++;
            Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
            if (event.cursor() >= latestCursor) {
                latestCursor = event.cursor();
                latestCapabilityViewVersion = safe(payload.get("capabilityViewVersion"));
                latestCapabilityEntryCount = parseInt(payload.get("capabilityEntryCount"));
                latestApprovalRequiredCount = parseInt(payload.get("capabilityApprovalRequiredCount"));
                latestEntriesWithSchema = parseInt(payload.get("capabilityEntriesWithSchema"));
                latestCountsByKind = parseCountMap(payload.get("capabilityCountsByKind"));
                latestCountsBySource = parseCountMap(payload.get("capabilityCountsBySource"));
            }
        }
        return new CapabilityAuditSummary(
                snapshotEventCount,
                latestCapabilityViewVersion,
                latestCapabilityEntryCount,
                latestApprovalRequiredCount,
                latestEntriesWithSchema,
                latestCountsByKind,
                latestCountsBySource,
                latestCursor
        );
    }

    private List<SandboxAuditRecord> loadSandboxAuditRecords(String sessionId, String taskId) {
        if ((sessionId == null || sessionId.isBlank()) && (taskId == null || taskId.isBlank())) {
            return List.of();
        }
        return sandboxAuditService.recentRecords().stream()
                .filter(record -> record != null)
                .filter(record -> matchesSandboxAuditRecord(record, sessionId, taskId))
                .toList();
    }

    private boolean matchesSandboxAuditRecord(SandboxAuditRecord record, String sessionId, String taskId) {
        if (record == null) {
            return false;
        }
        if (taskId != null && !taskId.isBlank() && taskId.equals(record.taskId())) {
            return true;
        }
        return sessionId != null
                && !sessionId.isBlank()
                && sessionId.equals(record.sessionId())
                && (taskId == null || taskId.isBlank() || record.taskId() == null || record.taskId().isBlank());
    }

    private ReplayObservabilitySummary buildSummary(RuntimeTask task,
                                                   List<ExecutionEvent> rawEvents,
                                                   EventReplayProjector.EventReplayProjection projection) {
        int structuredEntries = projection.eventEntries().size()
                + projection.logEntries().size()
                + projection.approvalEntries().size()
                + projection.runtimeEntries().size();
        Map<String, Long> counts = new LinkedHashMap<>(projection.eventTypeCounts());
        return new ReplayObservabilitySummary(
                task == null || task.status() == null ? projection.latestTaskStatus() : task.status().name(),
                projection.latestStopReason(),
                rawEvents == null ? 0 : rawEvents.size(),
                structuredEntries,
                projection.runtimeEntries().size(),
                Map.copyOf(counts)
        );
    }

    private Map<String, Long> buildLaneQueueCounts(TaskManager.SessionLaneState laneState) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("FOLLOWUP", laneState == null || laneState.followups() == null ? 0L : laneState.followups().size());
        counts.put("STEER", laneState == null || laneState.steers() == null ? 0L : laneState.steers().size());
        counts.put("INTERRUPT", laneState == null || laneState.interrupts() == null ? 0L : laneState.interrupts().size());
        return Map.copyOf(counts);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeKey(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean isFailureStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("FAIL")
                || normalized.contains("ERROR")
                || normalized.contains("DENIED")
                || normalized.contains("TIMEOUT")
                || normalized.contains("CANCEL");
    }

    private static boolean isSuccessStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("SUCCESS")
                || normalized.contains("SUCCEEDED")
                || normalized.contains("APPROVED")
                || normalized.contains("OK")
                || normalized.contains("DONE")
                || normalized.contains("COMPLETED");
    }

    private void incrementCount(Map<String, Long> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0L) + 1L);
    }

    public record ReplayTimelineView(
            String sessionId,
            String taskId,
            RuntimeTask task,
            TaskManager.SessionLaneState laneState,
            long latestCursor,
            List<ExecutionEvent> rawEvents,
            List<EventReplayProjector.EventReplayEntry> eventEntries,
            List<EventReplayProjector.EventReplayEntry> logEntries,
            List<EventReplayProjector.EventReplayEntry> approvalEntries,
            List<EventReplayProjector.RuntimeReplayEntry> runtimeEntries,
            PromptReplayExport promptReplay,
            ReplayObservabilitySummary observabilitySummary
    ) {
        public static ReplayTimelineView empty(String sessionId) {
            return new ReplayTimelineView(
                    sessionId,
                    null,
                    null,
                    TaskManager.SessionLaneState.empty(sessionId),
                    0L,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    PromptReplayExport.empty(),
                    ReplayObservabilitySummary.empty()
            );
        }
    }

    public record PromptReplayExport(
            String runtimeSection,
            String eventSection,
            String logSection,
            String approvalSection
    ) {
        public static PromptReplayExport empty() {
            return new PromptReplayExport("", "", "", "");
        }
    }

    public record ReplayObservabilitySummary(
            String taskStatus,
            String latestStopReason,
            int rawEventCount,
            int structuredEntryCount,
            int runtimeEntryCount,
            Map<String, Long> eventTypeCounts
    ) {
        public static ReplayObservabilitySummary empty() {
            return new ReplayObservabilitySummary("", "", 0, 0, 0, Map.of());
        }
    }

    public record RuntimeReplayOverview(
            List<RuntimeTask> recentTasks,
            Map<String, Long> taskStatusCounts,
            Map<String, Long> laneEntryCounts,
            long trackedSessionCount
    ) {
    }

    public record RuntimeMetricsView(
            int trackedTaskCount,
            long trackedSessionCount,
            Map<String, Long> taskStatusCounts,
            Map<String, Long> laneQueueCounts,
            Map<String, Long> laneEventCounts,
            Map<String, Long> toolStateCounts,
            Map<String, Long> specialistStateCounts,
            Map<String, Long> approvalStateCounts,
            Map<String, Long> sandboxStateCounts,
            Map<String, Long> budgetStatusCounts,
            Map<String, Long> memoryStateCounts,
            Map<String, Long> capabilityViewVersionCounts,
            Map<String, Long> capabilityKindCounts,
            Map<String, Long> capabilitySourceCounts,
            Map<String, Long> compactionLevelCounts,
            Map<String, Long> stopReasonCounts,
            Map<String, Long> eventTypeCounts
    ) {
        public static RuntimeMetricsView empty() {
            return new RuntimeMetricsView(0, 0L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    public record RuntimeAuditExport(
            String exportVersion,
            String generatedAt,
            String sessionId,
            String taskId,
            RuntimeTask task,
            Map<String, String> commonFields,
            ReplayObservabilitySummary observabilitySummary,
            RuntimeMetricsView metrics,
            ApprovalAuditSummary approvalSummary,
            BudgetAuditSummary budgetSummary,
            MemoryCompressionAuditSummary memoryCompressionSummary,
            CapabilityAuditSummary capabilitySummary,
            SandboxAuditSummary sandboxSummary,
            PromptReplayExport promptReplay,
            List<EventReplayProjector.RuntimeReplayEntry> runtimeEntries,
            List<EventReplayProjector.EventReplayEntry> eventEntries,
            List<EventReplayProjector.EventReplayEntry> logEntries,
            List<EventReplayProjector.EventReplayEntry> approvalEntries,
            List<SandboxAuditRecord> sandboxAuditRecords,
            List<ExecutionEvent> rawEvents
    ) {
        public static RuntimeAuditExport empty() {
            return new RuntimeAuditExport(
                    "runtime-audit-v1",
                    Instant.now().toString(),
                    "",
                    "",
                    null,
                    Map.of(),
                    ReplayObservabilitySummary.empty(),
                    RuntimeMetricsView.empty(),
                    ApprovalAuditSummary.empty(),
                    BudgetAuditSummary.empty(),
                    MemoryCompressionAuditSummary.empty(),
                    CapabilityAuditSummary.empty(),
                    SandboxAuditSummary.empty(),
                    PromptReplayExport.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    public record ApprovalAuditSummary(
            int requestedCount,
            int approvedCount,
            int deniedCount,
            int timeoutCount,
            int pendingCount,
            String latestState,
            long latestCursor
    ) {
        public static ApprovalAuditSummary empty() {
            return new ApprovalAuditSummary(0, 0, 0, 0, 0, "", 0L);
        }
    }

    public record BudgetAuditSummary(
            int budgetCheckCount,
            int minimalContextTriggerCount,
            int hardStopCount,
            String latestStatus,
            String latestCompactionLevel,
            long latestCursor
    ) {
        public static BudgetAuditSummary empty() {
            return new BudgetAuditSummary(0, 0, 0, "", "", 0L);
        }
    }

    public record MemoryCompressionAuditSummary(
            int summaryRefreshCount,
            int contextCompactionCount,
            int minimalContextActivationCount,
            int compactedMessageCount,
            String latestSummaryTopic,
            String latestCompactionLevel,
            long latestCursor
    ) {
        public static MemoryCompressionAuditSummary empty() {
            return new MemoryCompressionAuditSummary(0, 0, 0, 0, "", "", 0L);
        }
    }

    public record CapabilityAuditSummary(
            int snapshotEventCount,
            String latestCapabilityViewVersion,
            int latestCapabilityEntryCount,
            int latestApprovalRequiredCount,
            int latestEntriesWithSchema,
            Map<String, Long> latestCountsByKind,
            Map<String, Long> latestCountsBySource,
            long latestCursor
    ) {
        public static CapabilityAuditSummary empty() {
            return new CapabilityAuditSummary(0, "", 0, 0, 0, Map.of(), Map.of(), 0L);
        }
    }

    public record SandboxAuditSummary(
            int recordCount,
            int approvedCount,
            int deniedCount,
            int successCount,
            int timeoutCount,
            int cancelledCount,
            int artifactRefCount,
            Map<String, Long> targetTypeCounts,
            Map<String, Long> resultCounts,
            String latestSandboxProfile,
            String latestApprovalBackend,
            String latestTargetType,
            String latestExecutionMode
    ) {
        public static SandboxAuditSummary empty() {
            return new SandboxAuditSummary(0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of(), "", "", "", "");
        }
    }

    private static final class RuntimeMetricsAccumulator {
        private final Map<String, Long> taskStatusCounts = new LinkedHashMap<>();
        private final Map<String, Long> laneEventCounts = new LinkedHashMap<>();
        private final Map<String, Long> toolStateCounts = new LinkedHashMap<>();
        private final Map<String, Long> specialistStateCounts = new LinkedHashMap<>();
        private final Map<String, Long> approvalStateCounts = new LinkedHashMap<>();
        private final Map<String, Long> sandboxStateCounts = new LinkedHashMap<>();
        private final Map<String, Long> budgetStatusCounts = new LinkedHashMap<>();
        private final Map<String, Long> memoryStateCounts = new LinkedHashMap<>();
        private final Map<String, Long> capabilityViewVersionCounts = new LinkedHashMap<>();
        private final Map<String, Long> capabilityKindCounts = new LinkedHashMap<>();
        private final Map<String, Long> capabilitySourceCounts = new LinkedHashMap<>();
        private final Map<String, Long> compactionLevelCounts = new LinkedHashMap<>();
        private final Map<String, Long> stopReasonCounts = new LinkedHashMap<>();
        private final Map<String, Long> eventTypeCounts = new LinkedHashMap<>();

        private void recordTimeline(ReplayTimelineView timeline) {
            if (timeline == null) {
                return;
            }
            if (timeline.task() != null && timeline.task().status() != null) {
                increment(taskStatusCounts, timeline.task().status().name());
            } else if (timeline.observabilitySummary() != null) {
                increment(taskStatusCounts, normalizeKey(timeline.observabilitySummary().taskStatus(), "UNKNOWN"));
            }
            if (timeline.rawEvents() == null) {
                return;
            }
            for (ExecutionEvent event : timeline.rawEvents()) {
                if (event == null || event.eventType() == null) {
                    continue;
                }
                String eventType = event.eventType().name();
                increment(eventTypeCounts, eventType);
                Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
                switch (event.eventType()) {
                    case REACT_TURN_STARTED -> {
                        increment(capabilityViewVersionCounts, normalizeKey(payload.get("capabilityViewVersion"), "unknown"));
                        mergeCounts(capabilityKindCounts, parseCountMap(payload.get("capabilityCountsByKind")));
                        mergeCounts(capabilitySourceCounts, parseCountMap(payload.get("capabilityCountsBySource")));
                    }
                    case SESSION_LANE_ENQUEUED, SESSION_LANE_CLEARED, SESSION_LANE_CONSUMED ->
                            increment(laneEventCounts, eventType);
                    case TOOL_CALLED -> increment(toolStateCounts, "CALLED");
                    case TOOL_RESULT_RECEIVED -> recordExecutionStatus(toolStateCounts, payload.get("status"));
                    case SKILL_CONTEXT_REQUESTED -> increment(toolStateCounts, "SKILL_REQUESTED");
                    case SKILL_CONTEXT_LOADED -> recordExecutionStatus(toolStateCounts, "SKILL_" + normalizeKey(payload.get("status"), "LOADED"));
                    case SPECIALIST_CALLED -> increment(specialistStateCounts, "CALLED");
                    case SPECIALIST_RESULT_RECEIVED -> recordExecutionStatus(specialistStateCounts, payload.get("status"));
                    case APPROVAL_REQUESTED -> increment(approvalStateCounts, "PENDING");
                    case APPROVAL_RESULT_RECEIVED -> increment(approvalStateCounts, normalizeKey(payload.get("approvalState"), normalizeKey(payload.get("status"), "UNKNOWN")));
                    case SANDBOX_PERMISSION_DECIDED ->
                            increment(sandboxStateCounts, "PERMISSION_" + normalizeKey(payload.get("permissionDecision"), "UNKNOWN"));
                    case SANDBOX_APPROVAL_RESOLVED -> {
                        String approvalStatus = normalizeKey(payload.get("approvalStatus"), "UNKNOWN");
                        increment(approvalStateCounts, approvalStatus);
                        increment(sandboxStateCounts, "APPROVAL_" + approvalStatus);
                    }
                    case SANDBOX_EXECUTION_RECORDED -> {
                        increment(sandboxStateCounts, "TOTAL");
                        increment(sandboxStateCounts, normalizeKey(payload.get("result"), "UNKNOWN"));
                    }
                    case MEMORY_SUMMARY_REFRESHED -> {
                        increment(memoryStateCounts, "SUMMARY_REFRESHED");
                        increment(memoryStateCounts, normalizeKey(payload.get("summaryType"), "UNKNOWN"));
                    }
                    case MEMORY_CONTEXT_COMPACTED -> {
                        increment(memoryStateCounts, "CONTEXT_COMPACTED");
                        increment(memoryStateCounts, Boolean.parseBoolean(payload.getOrDefault("minimalContextMode", "false"))
                                ? "MINIMAL_CONTEXT"
                                : "AUTO_COMPACT");
                        increment(compactionLevelCounts, normalizeKey(payload.get("compactionLevel"), "none"));
                    }
                    case REACT_BUDGET_CHECKED -> {
                        increment(budgetStatusCounts, normalizeKey(payload.get("budgetStatus"), "UNKNOWN"));
                        increment(compactionLevelCounts, normalizeKey(payload.get("compactionLevel"), "none"));
                        if (Boolean.parseBoolean(payload.getOrDefault("minimalContextMode", "false"))) {
                            increment(compactionLevelCounts, "minimal-context-enabled");
                        }
                    }
                    case REACT_STOP_REASON_RECORDED -> increment(stopReasonCounts, normalizeKey(payload.get("reason"), "UNKNOWN"));
                    default -> {
                    }
                }
            }
        }

        private void recordExecutionStatus(Map<String, Long> target, String status) {
            String normalized = normalizeKey(status, "UNKNOWN");
            increment(target, normalized);
            if (isSuccessStatus(normalized)) {
                increment(target, "SUCCESS");
                return;
            }
            if (isFailureStatus(normalized)) {
                increment(target, "FAILED");
            }
        }

        private void increment(Map<String, Long> target, String key) {
            target.put(key, target.getOrDefault(key, 0L) + 1L);
        }

        private RuntimeMetricsView toMetricsView(int trackedTaskCount,
                                                 long trackedSessionCount,
                                                 Map<String, Long> laneQueueCounts) {
            return new RuntimeMetricsView(
                    trackedTaskCount,
                    trackedSessionCount,
                    Map.copyOf(taskStatusCounts),
                    laneQueueCounts == null ? Map.of() : Map.copyOf(laneQueueCounts),
                    Map.copyOf(laneEventCounts),
                    Map.copyOf(toolStateCounts),
                    Map.copyOf(specialistStateCounts),
                    Map.copyOf(approvalStateCounts),
                    Map.copyOf(sandboxStateCounts),
                    Map.copyOf(budgetStatusCounts),
                    Map.copyOf(memoryStateCounts),
                    Map.copyOf(capabilityViewVersionCounts),
                    Map.copyOf(capabilityKindCounts),
                    Map.copyOf(capabilitySourceCounts),
                    Map.copyOf(compactionLevelCounts),
                    Map.copyOf(stopReasonCounts),
                    Map.copyOf(eventTypeCounts)
            );
        }

        private void mergeCounts(Map<String, Long> target, Map<String, Long> counts) {
            if (target == null || counts == null || counts.isEmpty()) {
                return;
            }
            counts.forEach((key, value) -> target.put(key, target.getOrDefault(key, 0L) + (value == null ? 0L : value)));
        }
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

    private static Map<String, Long> parseCountMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        String[] entries = value.split(",");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator >= entry.length() - 1) {
                continue;
            }
            String key = normalizeKey(entry.substring(0, separator), "unknown");
            String rawValue = entry.substring(separator + 1).trim();
            try {
                counts.put(key, Long.parseLong(rawValue));
            } catch (NumberFormatException ignored) {
                counts.put(key, 0L);
            }
        }
        return counts.isEmpty() ? Map.of() : Map.copyOf(counts);
    }
}

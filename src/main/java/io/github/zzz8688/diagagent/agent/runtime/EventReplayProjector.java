package io.github.zzz8688.diagagent.agent.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EventReplayProjector {

    public EventReplayProjection project(List<ExecutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return EventReplayProjection.empty();
        }
        List<ExecutionEvent> orderedEvents = events.stream()
                .filter(event -> event != null)
                .sorted(Comparator.comparingLong(ExecutionEvent::cursor))
                .toList();
        Map<String, ActionMetadata> actionMetadataById = new LinkedHashMap<>();
        Map<String, ObservationMetadata> observationMetadataByActionId = new LinkedHashMap<>();
        Map<String, MutableReplayEntry> eventEntries = new LinkedHashMap<>();
        Map<String, MutableReplayEntry> logEntries = new LinkedHashMap<>();
        Map<String, MutableReplayEntry> approvalEntries = new LinkedHashMap<>();
        Map<String, MutableRuntimeEntry> runtimeEntries = new LinkedHashMap<>();
        Map<String, Long> eventTypeCounts = new LinkedHashMap<>();
        long latestCursor = 0;
        String latestStopReason = "";
        String latestTaskStatus = "";

        for (ExecutionEvent event : orderedEvents) {
            latestCursor = Math.max(latestCursor, event.cursor());
            eventTypeCounts.put(event.eventType().name(), eventTypeCounts.getOrDefault(event.eventType().name(), 0L) + 1L);
            switch (event.eventType()) {
                case REACT_ACTION_SELECTED -> actionMetadataById.put(
                        safe(payload(event, "actionId")),
                        new ActionMetadata(
                                parseInt(payload(event, "turnIndex")),
                                safe(payload(event, "actionType")),
                                safe(payload(event, "target"))
                        )
                );
                case REACT_OBSERVATION_RECEIVED -> observationMetadataByActionId.put(
                        safe(payload(event, "actionId")),
                        new ObservationMetadata(
                                safe(payload(event, "status")),
                                safe(payload(event, "summary")),
                                safe(payload(event, "source")),
                                event.cursor(),
                                event.createdAt()
                        )
                );
                case TOOL_CALLED, TOOL_RESULT_RECEIVED,
                     SKILL_CONTEXT_REQUESTED, SKILL_CONTEXT_LOADED,
                     SPECIALIST_CALLED, SPECIALIST_RESULT_RECEIVED,
                     CRITIC_REVIEW_REQUESTED, CRITIC_OBSERVATION_RECEIVED -> mergeEvent(
                        eventEntries,
                        event,
                        actionMetadataById,
                        observationMetadataByActionId,
                        kindFromEvent(event)
                );
                case LOG_INGESTED, LOG_OBSERVATION_RECEIVED -> mergeEvent(
                        logEntries,
                        event,
                        actionMetadataById,
                        observationMetadataByActionId,
                        kindFromEvent(event)
                );
                case APPROVAL_REQUESTED, APPROVAL_RESULT_RECEIVED, SANDBOX_APPROVAL_RESOLVED -> {
                    mergeApprovalEvent(
                            approvalEntries,
                            event,
                            actionMetadataById,
                            observationMetadataByActionId
                    );
                    if (event.eventType() == ExecutionEventType.SANDBOX_APPROVAL_RESOLVED) {
                        mergeRuntimeEvent(runtimeEntries, event);
                    }
                }
                case TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED,
                     REACT_TURN_STARTED, REACT_TURN_FINISHED, REACT_BUDGET_CHECKED, REACT_STOP_REASON_RECORDED,
                     REACT_ACTION_CONTRACT_PARSE_FAILED, REACT_ACTION_CONTRACT_SCHEMA_REJECTED,
                     MEMORY_SUMMARY_REFRESHED, MEMORY_CONTEXT_COMPACTED,
                     FINAL_ANSWER_CONTRACT_PARSE_FAILED, FINAL_ANSWER_CONTRACT_SCHEMA_REJECTED,
                     SANDBOX_PERMISSION_DECIDED, SANDBOX_EXECUTION_RECORDED,
                     SESSION_LANE_ENQUEUED, SESSION_LANE_CLEARED, SESSION_LANE_CONSUMED -> {
                    mergeRuntimeEvent(runtimeEntries, event);
                    if (event.eventType() == ExecutionEventType.REACT_STOP_REASON_RECORDED) {
                        latestStopReason = normalize(payload(event, "reason"));
                    }
                    if (event.eventType() == ExecutionEventType.TASK_COMPLETED
                            || event.eventType() == ExecutionEventType.TASK_FAILED
                            || event.eventType() == ExecutionEventType.TASK_CANCELLED
                            || event.eventType() == ExecutionEventType.TASK_STARTED) {
                        latestTaskStatus = normalize(payload(event, "status"));
                    }
                }
                default -> {
                }
            }
        }

        return new EventReplayProjection(
                toEntries(eventEntries),
                toEntries(logEntries),
                toEntries(approvalEntries),
                toRuntimeEntries(runtimeEntries),
                latestCursor,
                Map.copyOf(eventTypeCounts),
                safe(latestStopReason),
                safe(latestTaskStatus)
        );
    }

    private void mergeEvent(Map<String, MutableReplayEntry> entries,
                            ExecutionEvent event,
                            Map<String, ActionMetadata> actionMetadataById,
                            Map<String, ObservationMetadata> observationMetadataByActionId,
                            String kind) {
        String actionId = safe(payload(event, "actionId"));
        if (actionId.isBlank()) {
            actionId = "event-" + event.eventId();
        }
        String finalActionId = actionId;
        MutableReplayEntry entry = entries.computeIfAbsent(finalActionId, ignored -> new MutableReplayEntry(finalActionId, kind));
        ActionMetadata actionMetadata = actionMetadataById.get(finalActionId);
        ObservationMetadata observationMetadata = observationMetadataByActionId.get(finalActionId);

        if (actionMetadata != null) {
            entry.turnIndex = entry.turnIndex >= 0 ? entry.turnIndex : actionMetadata.turnIndex();
            entry.actionType = normalizePreferCurrent(entry.actionType, actionMetadata.actionType());
            entry.target = normalizePreferCurrent(entry.target, actionMetadata.target());
        }
        entry.actionType = normalizePreferCurrent(entry.actionType, payload(event, "actionType"));
        entry.target = normalizePreferCurrent(entry.target, payload(event, "target"));
        entry.source = normalizePreferCurrent(entry.source, event.source());
        entry.specialistKind = normalizePreferCurrent(entry.specialistKind, payload(event, "specialistKind"));

        if (event.eventType() == ExecutionEventType.TOOL_CALLED
                || event.eventType() == ExecutionEventType.SKILL_CONTEXT_REQUESTED
                || event.eventType() == ExecutionEventType.SPECIALIST_CALLED
                || event.eventType() == ExecutionEventType.CRITIC_REVIEW_REQUESTED) {
            entry.phase = event.eventType() == ExecutionEventType.SKILL_CONTEXT_REQUESTED ? "requested" : "called";
            entry.cursor = Math.max(entry.cursor, event.cursor());
            entry.summary = normalizePreferCurrent(entry.summary, payload(event, "message"));
            entry.summary = normalizePreferCurrent(entry.summary, payload(event, "actionName"));
            entry.createdAt = newerOf(entry.createdAt, event.createdAt());
            return;
        }

        entry.phase = event.eventType() == ExecutionEventType.SKILL_CONTEXT_LOADED ? "loaded" : "result_received";
        entry.cursor = Math.max(entry.cursor, event.cursor());
        entry.status = normalizePreferCurrent(entry.status, payload(event, "status"));
        entry.summary = normalizePreferCurrent(entry.summary, payload(event, "message"));
        entry.createdAt = newerOf(entry.createdAt, event.createdAt());

        if (observationMetadata != null) {
            entry.status = normalizePreferCurrent(entry.status, observationMetadata.status());
            entry.summary = normalizePreferCurrent(entry.summary, observationMetadata.summary());
            entry.source = normalizePreferCurrent(entry.source, observationMetadata.source());
            entry.cursor = Math.max(entry.cursor, observationMetadata.cursor());
            entry.createdAt = newerOf(entry.createdAt, observationMetadata.createdAt());
        }
    }

    private void mergeApprovalEvent(Map<String, MutableReplayEntry> entries,
                                    ExecutionEvent event,
                                    Map<String, ActionMetadata> actionMetadataById,
                                    Map<String, ObservationMetadata> observationMetadataByActionId) {
        String actionId = safe(payload(event, "actionId"));
        if (actionId.isBlank()) {
            actionId = safe(payload(event, "executionId"));
        }
        if (actionId.isBlank()) {
            actionId = "approval-" + event.eventId();
        }
        String finalActionId = actionId;
        MutableReplayEntry entry = entries.computeIfAbsent(finalActionId, ignored -> new MutableReplayEntry(finalActionId, "approval"));
        ActionMetadata actionMetadata = actionMetadataById.get(finalActionId);
        ObservationMetadata observationMetadata = observationMetadataByActionId.get(finalActionId);

        if (actionMetadata != null) {
            entry.turnIndex = entry.turnIndex >= 0 ? entry.turnIndex : actionMetadata.turnIndex();
            entry.actionType = normalizePreferCurrent(entry.actionType, actionMetadata.actionType());
            entry.target = normalizePreferCurrent(entry.target, actionMetadata.target());
        }
        entry.actionType = normalizePreferCurrent(entry.actionType, payload(event, "actionType"));
        entry.actionType = normalizePreferCurrent(entry.actionType, payload(event, "targetType"));
        entry.target = normalizePreferCurrent(entry.target, payload(event, "target"));
        entry.target = normalizePreferCurrent(entry.target, payload(event, "targetName"));
        entry.source = normalizePreferCurrent(entry.source, event.source());
        entry.status = normalizePreferCurrent(entry.status, payload(event, "approvalState"));
        entry.status = normalizePreferCurrent(entry.status, payload(event, "approvalStatus"));
        entry.summary = normalizePreferCurrent(entry.summary, payload(event, "message"));
        entry.summary = normalizePreferCurrent(entry.summary, payload(event, "reason"));
        entry.cursor = Math.max(entry.cursor, event.cursor());
        entry.createdAt = newerOf(entry.createdAt, event.createdAt());
        entry.phase = event.eventType() == ExecutionEventType.APPROVAL_REQUESTED ? "requested" : "result_received";

        if (observationMetadata != null) {
            entry.status = normalizePreferCurrent(entry.status, observationMetadata.status());
            entry.summary = normalizePreferCurrent(entry.summary, observationMetadata.summary());
            entry.source = normalizePreferCurrent(entry.source, observationMetadata.source());
            entry.cursor = Math.max(entry.cursor, observationMetadata.cursor());
            entry.createdAt = newerOf(entry.createdAt, observationMetadata.createdAt());
        }
    }

    private void mergeRuntimeEvent(Map<String, MutableRuntimeEntry> entries, ExecutionEvent event) {
        String key = runtimeEntryKey(event);
        MutableRuntimeEntry entry = entries.computeIfAbsent(key, ignored -> new MutableRuntimeEntry(key));
        entry.cursor = Math.max(entry.cursor, event.cursor());
        entry.eventType = event.eventType() == null ? "" : event.eventType().name();
        entry.phase = runtimePhase(event);
        entry.status = normalizePreferCurrent(entry.status, payload(event, "status"));
        entry.status = normalizePreferCurrent(entry.status, payload(event, "result"));
        entry.summary = normalizePreferCurrent(entry.summary, runtimeSummary(event));
        entry.source = normalizePreferCurrent(entry.source, event.source());
        entry.turnIndex = entry.turnIndex >= 0 ? entry.turnIndex : parseInt(payload(event, "turnIndex"));
        entry.createdAt = newerOf(entry.createdAt, event.createdAt());
    }

    private List<EventReplayEntry> toEntries(Map<String, MutableReplayEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<EventReplayEntry> results = new ArrayList<>();
        for (MutableReplayEntry entry : entries.values()) {
            results.add(new EventReplayEntry(
                    entry.cursor,
                    entry.turnIndex,
                    safe(entry.actionId),
                    safe(entry.actionType),
                    safe(entry.target),
                    safe(entry.phase),
                    safe(entry.status),
                    safe(entry.summary),
                    safe(entry.source),
                    safe(entry.specialistKind),
                    entry.createdAt == null ? null : entry.createdAt.toString(),
                    safe(entry.kind)
            ));
        }
        results.sort(Comparator.comparingLong(EventReplayEntry::cursor));
        return List.copyOf(results);
    }

    private List<RuntimeReplayEntry> toRuntimeEntries(Map<String, MutableRuntimeEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<RuntimeReplayEntry> results = new ArrayList<>();
        for (MutableRuntimeEntry entry : entries.values()) {
            results.add(new RuntimeReplayEntry(
                    entry.cursor,
                    safe(entry.eventType),
                    safe(entry.phase),
                    safe(entry.status),
                    safe(entry.summary),
                    safe(entry.source),
                    entry.turnIndex,
                    entry.createdAt == null ? null : entry.createdAt.toString()
            ));
        }
        results.sort(Comparator.comparingLong(RuntimeReplayEntry::cursor));
        return List.copyOf(results);
    }

    private String normalizePreferCurrent(String current, String candidate) {
        return current == null || current.isBlank() ? normalize(candidate) : current;
    }

    private String kindFromEvent(ExecutionEvent event) {
        if (event == null || event.eventType() == null) {
            return "";
        }
        return switch (event.eventType()) {
            case TOOL_CALLED, TOOL_RESULT_RECEIVED -> "tool";
            case SKILL_CONTEXT_REQUESTED, SKILL_CONTEXT_LOADED -> "skill";
            case SPECIALIST_CALLED, SPECIALIST_RESULT_RECEIVED -> "specialist";
            case LOG_INGESTED, LOG_OBSERVATION_RECEIVED -> "log";
            case CRITIC_REVIEW_REQUESTED, CRITIC_OBSERVATION_RECEIVED -> "critic";
            default -> "";
        };
    }

    private String runtimeEntryKey(ExecutionEvent event) {
        if (event == null || event.eventType() == null) {
            return "runtime-unknown";
        }
        return switch (event.eventType()) {
            case TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED ->
                    "task-" + event.eventType().name().toLowerCase();
            case REACT_BUDGET_CHECKED ->
                    "budget-" + safe(payload(event, "turnIndex")) + "-" + event.eventType().name().toLowerCase();
            case REACT_TURN_STARTED, REACT_TURN_FINISHED ->
                    "turn-" + safe(payload(event, "turnIndex")) + "-" + event.eventType().name().toLowerCase();
            case REACT_STOP_REASON_RECORDED -> "stop-reason";
            case REACT_ACTION_CONTRACT_PARSE_FAILED, REACT_ACTION_CONTRACT_SCHEMA_REJECTED,
                 FINAL_ANSWER_CONTRACT_PARSE_FAILED, FINAL_ANSWER_CONTRACT_SCHEMA_REJECTED ->
                    "contract-" + safe(payload(event, "turnIndex")) + "-" + event.eventType().name().toLowerCase();
            case SESSION_LANE_ENQUEUED, SESSION_LANE_CLEARED, SESSION_LANE_CONSUMED ->
                    "lane-" + safe(payload(event, "laneEntryId")) + "-" + event.eventType().name().toLowerCase();
            default -> "runtime-" + event.eventType().name().toLowerCase();
        };
    }

    private String runtimePhase(ExecutionEvent event) {
        if (event == null || event.eventType() == null) {
            return "";
        }
        return switch (event.eventType()) {
            case TASK_STARTED -> "task_started";
            case TASK_COMPLETED -> "task_completed";
            case TASK_FAILED -> "task_failed";
            case TASK_CANCELLED -> "task_cancelled";
            case MEMORY_SUMMARY_REFRESHED -> "memory_summary_refreshed";
            case MEMORY_CONTEXT_COMPACTED -> "memory_context_compacted";
            case SANDBOX_PERMISSION_DECIDED -> "sandbox_permission_decided";
            case SANDBOX_APPROVAL_RESOLVED -> "sandbox_approval_resolved";
            case SANDBOX_EXECUTION_RECORDED -> "sandbox_execution_recorded";
            case REACT_BUDGET_CHECKED -> "budget_checked";
            case REACT_TURN_STARTED -> "turn_started";
            case REACT_TURN_FINISHED -> "turn_finished";
            case REACT_STOP_REASON_RECORDED -> "stop_reason_recorded";
            case REACT_ACTION_CONTRACT_PARSE_FAILED -> "action_contract_parse_failed";
            case REACT_ACTION_CONTRACT_SCHEMA_REJECTED -> "action_contract_schema_rejected";
            case FINAL_ANSWER_CONTRACT_PARSE_FAILED -> "final_answer_contract_parse_failed";
            case FINAL_ANSWER_CONTRACT_SCHEMA_REJECTED -> "final_answer_contract_schema_rejected";
            case SESSION_LANE_ENQUEUED -> "lane_enqueued";
            case SESSION_LANE_CLEARED -> "lane_cleared";
            case SESSION_LANE_CONSUMED -> "lane_consumed";
            default -> "";
        };
    }

    private String runtimeSummary(ExecutionEvent event) {
        if (event == null || event.eventType() == null) {
            return "";
        }
        String reason = normalize(payload(event, "reason"));
        String message = normalize(payload(event, "message"));
        return switch (event.eventType()) {
            case TASK_STARTED -> normalize(payload(event, "description"));
            case TASK_COMPLETED, TASK_CANCELLED -> normalize(payload(event, "outputSummary"));
            case TASK_FAILED -> normalize(payload(event, "lastError"));
            case MEMORY_SUMMARY_REFRESHED ->
                    "memoryId=" + safe(payload(event, "memoryId"))
                            + " | topic=" + safe(payload(event, "topic"))
                            + " | version=" + safe(payload(event, "baseVersion")) + "->" + safe(payload(event, "nextVersion"))
                            + " | deltaQuestions=" + safe(payload(event, "deltaUserQuestionCount"));
            case MEMORY_CONTEXT_COMPACTED ->
                    "memoryId=" + safe(payload(event, "memoryId"))
                            + " | level=" + safe(payload(event, "compactionLevel"))
                            + " | retained=" + safe(payload(event, "retainedMessageCount")) + "/" + safe(payload(event, "originalMessageCount"))
                            + " | minimal=" + safe(payload(event, "minimalContextMode"));
            case SANDBOX_PERMISSION_DECIDED ->
                    "tool=" + safe(payload(event, "toolName"))
                            + " | target=" + safe(payload(event, "targetType")) + ":" + safe(payload(event, "targetName"))
                            + " | decision=" + safe(payload(event, "permissionDecision"))
                            + " | rule=" + safe(payload(event, "matchedRule"));
            case SANDBOX_APPROVAL_RESOLVED ->
                    "target=" + safe(payload(event, "targetType")) + ":" + safe(payload(event, "targetName"))
                            + " | status=" + safe(payload(event, "approvalStatus"))
                            + " | backend=" + safe(payload(event, "approvalBackend"));
            case SANDBOX_EXECUTION_RECORDED ->
                    "tool=" + safe(payload(event, "toolName"))
                            + " | mode=" + safe(payload(event, "executionMode"))
                            + " | target=" + safe(payload(event, "targetType")) + ":" + safe(payload(event, "targetName"))
                            + " | result=" + safe(payload(event, "result"))
                            + " | exitCode=" + safe(payload(event, "exitCode"))
                            + " | artifacts=" + safe(payload(event, "artifactCount"))
                            + " | profile=" + safe(payload(event, "sandboxProfile"));
            case REACT_BUDGET_CHECKED ->
                    "budgetStatus=" + safe(payload(event, "budgetStatus"))
                            + " | used=" + safe(payload(event, "usedBudgetTokens"))
                            + "/" + safe(payload(event, "maxBudgetTokens"))
                            + " | compaction=" + safe(payload(event, "compactionLevel"))
                            + " | minimal=" + safe(payload(event, "minimalContextMode"))
                            + " | capabilityVersion=" + safe(payload(event, "capabilityViewVersion"));
            case REACT_TURN_STARTED ->
                    "mode=" + safe(payload(event, "mode"))
                            + " | readOnly=" + safe(payload(event, "readOnly"))
                            + " | capabilityVersion=" + safe(payload(event, "capabilityViewVersion"))
                            + " | capabilityEntries=" + safe(payload(event, "capabilityEntryCount"));
            case REACT_TURN_FINISHED ->
                    "result=" + safe(payload(event, "result"))
                            + " | capabilityVersion=" + safe(payload(event, "capabilityViewVersion"))
                            + " | capabilityEntries=" + safe(payload(event, "capabilityEntryCount"));
            case REACT_STOP_REASON_RECORDED -> (reason == null ? "" : reason) + (message == null ? "" : " | " + message);
            case REACT_ACTION_CONTRACT_PARSE_FAILED, REACT_ACTION_CONTRACT_SCHEMA_REJECTED,
                 FINAL_ANSWER_CONTRACT_PARSE_FAILED, FINAL_ANSWER_CONTRACT_SCHEMA_REJECTED ->
                    "issues=" + safe(payload(event, "message"))
                            + " | rawPreview=" + safe(payload(event, "rawPreview"));
            case SESSION_LANE_ENQUEUED ->
                    "laneType=" + safe(payload(event, "laneType")) + " | payload=" + safe(payload(event, "payloadSummary"));
            case SESSION_LANE_CLEARED -> normalize(payload(event, "message"));
            case SESSION_LANE_CONSUMED ->
                    "laneType=" + safe(payload(event, "laneType")) + " | consumed=" + safe(payload(event, "payloadSummary"));
            default -> "";
        };
    }

    private Instant newerOf(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    private String payload(ExecutionEvent event, String key) {
        if (event == null || event.payload() == null || key == null) {
            return "";
        }
        return safe(event.payload().get(key));
    }

    private int parseInt(String value) {
        try {
            return value == null || value.isBlank() ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record EventReplayProjection(
            List<EventReplayEntry> eventEntries,
            List<EventReplayEntry> logEntries,
            List<EventReplayEntry> approvalEntries,
            List<RuntimeReplayEntry> runtimeEntries,
            long latestCursor,
            Map<String, Long> eventTypeCounts,
            String latestStopReason,
            String latestTaskStatus
    ) {
        public static EventReplayProjection empty() {
            return new EventReplayProjection(List.of(), List.of(), List.of(), List.of(), 0L, Map.of(), "", "");
        }

        public String renderEventPromptSection() {
            return renderPromptSection(eventEntries, true);
        }

        public String renderLogPromptSection() {
            return renderPromptSection(logEntries, true);
        }

        public String renderApprovalPromptSection() {
            return renderPromptSection(approvalEntries, false);
        }

        public String renderRuntimePromptSection() {
            if (runtimeEntries == null || runtimeEntries.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (RuntimeReplayEntry entry : runtimeEntries) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append("<runtime_entry>\n");
                builder.append("  <cursor>").append(entry.cursor()).append("</cursor>\n");
                builder.append("  <event_type>").append(entry.eventType()).append("</event_type>\n");
                builder.append("  <phase>").append(entry.phase()).append("</phase>\n");
                builder.append("  <status>").append(entry.status()).append("</status>\n");
                builder.append("  <summary>").append(entry.summary()).append("</summary>\n");
                builder.append("  <source>").append(entry.source()).append("</source>\n");
                builder.append("  <turn_index>").append(entry.turnIndex()).append("</turn_index>\n");
                builder.append("  <created_at>").append(entry.createdAt()).append("</created_at>\n");
                builder.append("</runtime_entry>");
            }
            return builder.toString();
        }

        private static String renderPromptSection(List<EventReplayEntry> entries, boolean includeKind) {
            if (entries == null || entries.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (EventReplayEntry entry : entries) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append("<entry>\n");
                builder.append("  <cursor>").append(entry.cursor()).append("</cursor>\n");
                builder.append("  <turn_index>").append(entry.turnIndex()).append("</turn_index>\n");
                builder.append("  <action_id>").append(entry.actionId()).append("</action_id>\n");
                builder.append("  <action_type>").append(entry.actionType()).append("</action_type>\n");
                builder.append("  <target>").append(entry.target()).append("</target>\n");
                if (includeKind && entry.kind() != null && !entry.kind().isBlank()) {
                    builder.append("  <kind>").append(entry.kind()).append("</kind>\n");
                }
                if (entry.specialistKind() != null && !entry.specialistKind().isBlank()) {
                    builder.append("  <specialist_kind>").append(entry.specialistKind()).append("</specialist_kind>\n");
                }
                builder.append("  <phase>").append(entry.phase()).append("</phase>\n");
                builder.append("  <status>").append(entry.status()).append("</status>\n");
                builder.append("  <summary>").append(entry.summary()).append("</summary>\n");
                builder.append("  <source>").append(entry.source()).append("</source>\n");
                builder.append("  <created_at>").append(entry.createdAt()).append("</created_at>\n");
                builder.append("</entry>");
            }
            return builder.toString();
        }
    }

    public record EventReplayEntry(
            long cursor,
            int turnIndex,
            String actionId,
            String actionType,
            String target,
            String phase,
            String status,
            String summary,
            String source,
            String specialistKind,
            String createdAt,
            String kind
    ) {
    }

    public record RuntimeReplayEntry(
            long cursor,
            String eventType,
            String phase,
            String status,
            String summary,
            String source,
            int turnIndex,
            String createdAt
    ) {
    }

    private record ActionMetadata(int turnIndex, String actionType, String target) {
    }

    private record ObservationMetadata(String status, String summary, String source, long cursor, Instant createdAt) {
    }

    private static final class MutableReplayEntry {
        private long cursor;
        private int turnIndex = -1;
        private final String actionId;
        private String actionType;
        private String target;
        private String phase = "called";
        private String status;
        private String summary;
        private String source;
        private String specialistKind;
        private Instant createdAt;
        private final String kind;

        private MutableReplayEntry(String actionId, String kind) {
            this.actionId = actionId;
            this.kind = kind;
        }
    }

    private static final class MutableRuntimeEntry {
        private long cursor;
        private final String entryId;
        private String eventType;
        private String phase;
        private String status;
        private String summary;
        private String source;
        private int turnIndex = -1;
        private Instant createdAt;

        private MutableRuntimeEntry(String entryId) {
            this.entryId = entryId;
        }
    }
}

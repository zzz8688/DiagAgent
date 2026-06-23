package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRuntime {

    private final SessionAssembler sessionAssembler;
    private final ReactLoopExecutor reactLoopExecutor;
    private final TaskManager taskManager;
    private final ExecutionEventBus executionEventBus;

    public DiagnosisConclusion execute(RuntimeRequest request) {
        RuntimeTask task = taskManager.startTask(request, "sync");
        SessionAssembler.CapabilitySnapshot capabilitySnapshot = sessionAssembler.loadCapabilitySnapshot(request.readOnly());
        log.info("进入 QueryRuntime，同步执行诊断: taskId={}, sessionId={}, readOnly={}",
                task.taskId(), task.sessionId(), request.readOnly());
        executionEventBus.publish(
                ExecutionEventType.REACT_TURN_STARTED,
                task.taskId(),
                task.sessionId(),
                "query-runtime",
                buildTurnStartedPayload("sync", request.readOnly(), capabilitySnapshot)
        );
        ReactTurnState turnState = sessionAssembler.initializeTurnState(
                request.userQuestion(),
                task.sessionId(),
                request.readOnly(),
                request.maxTurns(),
                request.maxBudgetTokens(),
                capabilitySnapshot
        ).withTaskId(task.taskId());
        publishBudgetChecked(task, turnState);
        try {
            DiagnosisConclusion conclusion = reactLoopExecutor.execute(
                    request.userQuestion(),
                    task.sessionId(),
                    request.readOnly(),
                    turnState
            );
            String completionReason = resolveCompletionReason(task.taskId(), conclusion);
            executionEventBus.publish(
                    ExecutionEventType.REACT_TURN_FINISHED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    buildTurnFinishedPayload("sync", summarizeTurnResult(completionReason), capabilitySnapshot)
            );
            executionEventBus.publish(
                    ExecutionEventType.REACT_STOP_REASON_RECORDED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    Map.of("reason", completionReason)
            );
            if (!isTaskCancelled(task.taskId())) {
                taskManager.markCompleted(task.taskId(), summarizeConclusion(conclusion));
            }
            return conclusion;
        } catch (Exception e) {
            executionEventBus.publish(
                    ExecutionEventType.REACT_STOP_REASON_RECORDED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    Map.of("reason", "failed", "message", safe(e.getMessage()))
            );
            taskManager.markFailed(task.taskId(), e.getMessage());
            throw e;
        }
    }

    public Flux<String> stream(RuntimeRequest request) {
        RuntimeTask task = taskManager.startTask(request, "stream");
        SessionAssembler.CapabilitySnapshot capabilitySnapshot = sessionAssembler.loadCapabilitySnapshot(request.readOnly());
        log.info("进入 QueryRuntime，流式执行诊断: taskId={}, sessionId={}, readOnly={}",
                task.taskId(), task.sessionId(), request.readOnly());
        executionEventBus.publish(
                ExecutionEventType.REACT_TURN_STARTED,
                task.taskId(),
                task.sessionId(),
                "query-runtime",
                buildTurnStartedPayload("stream", request.readOnly(), capabilitySnapshot)
        );
        ReactTurnState turnState = sessionAssembler.initializeTurnState(
                request.userQuestion(),
                task.sessionId(),
                request.readOnly(),
                request.maxTurns(),
                request.maxBudgetTokens(),
                capabilitySnapshot
        ).withTaskId(task.taskId());
        publishBudgetChecked(task, turnState);
        AtomicReference<String> lastStreamChunk = new AtomicReference<>("");
        return reactLoopExecutor.executeStream(
                request.userQuestion(),
                task.sessionId(),
                request.readOnly(),
                turnState
        ).doOnNext(chunk -> lastStreamChunk.set(safe(chunk)))
        .doOnComplete(() -> {
            String completionReason = resolveStreamCompletionReason(task.taskId(), lastStreamChunk.get());
            executionEventBus.publish(
                    ExecutionEventType.REACT_TURN_FINISHED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    buildTurnFinishedPayload("stream", summarizeTurnResult(completionReason), capabilitySnapshot)
            );
            executionEventBus.publish(
                    ExecutionEventType.REACT_STOP_REASON_RECORDED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    Map.of("reason", completionReason)
            );
            if (!isTaskCancelled(task.taskId())) {
                taskManager.markCompleted(task.taskId(), summarizeStreamOutput(lastStreamChunk.get()));
            }
        }).doOnCancel(() -> {
            executionEventBus.publish(
                    ExecutionEventType.REACT_STOP_REASON_RECORDED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    Map.of("reason", "cancelled")
            );
            taskManager.markCancelled(task.taskId(), "stream cancelled");
        }).doOnError(error -> {
            executionEventBus.publish(
                    ExecutionEventType.REACT_STOP_REASON_RECORDED,
                    task.taskId(),
                    task.sessionId(),
                    "query-runtime",
                    Map.of("reason", "failed", "message", safe(error.getMessage()))
            );
            taskManager.markFailed(task.taskId(), error.getMessage());
        });
    }

    private String summarizeConclusion(DiagnosisConclusion conclusion) {
        if (conclusion == null || conclusion.getFinalConclusion() == null) {
            return "diagnosis completed";
        }
        String text = conclusion.getFinalConclusion().trim();
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void publishBudgetChecked(RuntimeTask task, ReactTurnState turnState) {
        if (task == null || turnState == null) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("turnIndex", Integer.toString(turnState.turnIndex()));
        payload.put("usedBudgetTokens", Integer.toString(turnState.usedBudgetTokens()));
        payload.put("maxBudgetTokens", Integer.toString(turnState.maxBudgetTokens()));
        payload.put("budgetStatus", turnState.budgetStatus() == null ? "" : turnState.budgetStatus().name());
        payload.put("compactionLevel", safe(turnState.compactionLevel()));
        payload.put("minimalContextMode", Boolean.toString(turnState.minimalContextMode()));
        payload.put("capabilityViewVersion", safe(turnState.capabilityViewVersion()));
        executionEventBus.publish(
                ExecutionEventType.REACT_BUDGET_CHECKED,
                task.taskId(),
                task.sessionId(),
                "query-runtime",
                Map.copyOf(payload)
        );
    }

    private boolean isTaskCancelled(String taskId) {
        return taskManager.findTask(taskId)
                .map(RuntimeTask::status)
                .filter(RuntimeTaskStatus.CANCELLED::equals)
                .isPresent();
    }

    private String resolveCompletionReason(String taskId, DiagnosisConclusion conclusion) {
        String recordedStopReason = resolveRecordedStopReason(taskId);
        if (!recordedStopReason.isBlank()) {
            return recordedStopReason;
        }
        if (isTaskCancelled(taskId)) {
            return "cancelled";
        }
        if (conclusion == null || conclusion.getFinalConclusion() == null || conclusion.getFinalConclusion().isBlank()) {
            return "stopped";
        }
        return "completed";
    }

    private String resolveStreamCompletionReason(String taskId, String lastStreamChunk) {
        String recordedStopReason = resolveRecordedStopReason(taskId);
        if (!recordedStopReason.isBlank()) {
            return recordedStopReason;
        }
        if (isTaskCancelled(taskId)) {
            return "cancelled";
        }
        String normalizedChunk = safe(lastStreamChunk).trim();
        if (normalizedChunk.isEmpty()
                || "[STOPPED]".equals(normalizedChunk)
                || normalizedChunk.startsWith("上下文预算已耗尽")
                || normalizedChunk.startsWith("诊断达到最大轮次限制")
                || normalizedChunk.startsWith("诊断在运行时提前结束")) {
            return "stopped";
        }
        return "completed";
    }

    private String resolveRecordedStopReason(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return "";
        }
        java.util.List<ExecutionEvent> events = taskManager.replay(taskId);
        for (int index = events.size() - 1; index >= 0; index--) {
            ExecutionEvent event = events.get(index);
            if (event == null || event.eventType() != ExecutionEventType.REACT_STOP_REASON_RECORDED) {
                continue;
            }
            String reason = safe(event.payload().get("reason")).trim();
            if (!reason.isEmpty() && !"completed".equals(reason)) {
                return reason;
            }
        }
        return "";
    }

    private String summarizeTurnResult(String completionReason) {
        if (completionReason == null || completionReason.isBlank()) {
            return "completed";
        }
        return switch (completionReason) {
            case "completed", "final_answer_returned" -> "completed";
            case "cancelled", "user_cancelled" -> "cancelled";
            default -> "stopped";
        };
    }

    private String summarizeStreamOutput(String lastStreamChunk) {
        String text = safe(lastStreamChunk).trim();
        if (text.isEmpty()) {
            return "stream completed";
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private Map<String, String> buildTurnStartedPayload(String mode,
                                                        boolean readOnly,
                                                        SessionAssembler.CapabilitySnapshot capabilitySnapshot) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("turnIndex", "0");
        payload.put("mode", safe(mode));
        payload.put("readOnly", Boolean.toString(readOnly));
        appendCapabilityPayload(payload, capabilitySnapshot);
        return Map.copyOf(payload);
    }

    private Map<String, String> buildTurnFinishedPayload(String mode,
                                                         String result,
                                                         SessionAssembler.CapabilitySnapshot capabilitySnapshot) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("mode", safe(mode));
        payload.put("result", safe(result));
        appendCapabilityPayload(payload, capabilitySnapshot);
        return Map.copyOf(payload);
    }

    private void appendCapabilityPayload(Map<String, String> payload,
                                         SessionAssembler.CapabilitySnapshot capabilitySnapshot) {
        if (payload == null) {
            return;
        }
        CapabilityViewAssembler.CapabilityManifestOverview overview = capabilitySnapshot == null ? null : capabilitySnapshot.overview();
        CapabilityViewAssembler.CapabilityView capabilityView = capabilitySnapshot == null ? null : capabilitySnapshot.capabilityView();
        payload.put("capabilityViewVersion", safe(capabilityView == null ? null : capabilityView.viewVersion()));
        payload.put("capabilityEntryCount", Integer.toString(capabilityView == null ? 0 : capabilityView.totalEntryCount()));
        payload.put("capabilityApprovalRequiredCount", Integer.toString(overview == null ? 0 : overview.approvalRequiredCount()));
        payload.put("capabilityEntriesWithSchema", Integer.toString(overview == null ? 0 : overview.entriesWithSchema()));
        payload.put("capabilityCountsByKind", serializeCounts(overview == null ? Map.of() : overview.countsByKind()));
        payload.put("capabilityCountsBySource", serializeCounts(overview == null ? Map.of() : overview.countsBySource()));
    }

    private String serializeCounts(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        return counts.entrySet().stream()
                .map(entry -> safe(entry.getKey()) + "=" + Math.max(0L, entry.getValue() == null ? 0L : entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}

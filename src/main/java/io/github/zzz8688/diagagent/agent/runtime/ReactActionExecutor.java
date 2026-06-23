package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.service.StopGenerateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
public class ReactActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReactActionExecutor.class);

    private final StopGenerateService stopGenerateService;
    private final List<ReactActionAdapter> actionAdapters;
    private final ExecutionEventBus executionEventBus;
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    public List<ReactActionExecutionResult> execute(List<ReactAction> actions,
                                                    String userQuestion,
                                                    String sessionId,
                                                    String taskId,
                                                    boolean readOnly) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<ReactActionExecutionResult>> futures = actions.stream()
                .map(descriptor -> CompletableFuture.supplyAsync(
                        () -> executeSingleAction(descriptor, userQuestion, sessionId, taskId, readOnly),
                        executorService
                ))
                .toList();

        while (!futures.stream().allMatch(CompletableFuture::isDone)) {
            if (!stopGenerateService.shouldContinue(sessionId)) {
                log.info("Detected stop signal, cancel all specialist actions: sessionId={}", sessionId);
                futures.forEach(future -> future.cancel(true));
                return new ArrayList<>();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
                return new ArrayList<>();
            }
        }

        List<ReactActionExecutionResult> results = new ArrayList<>();
        for (CompletableFuture<ReactActionExecutionResult> future : futures) {
            try {
                results.add(future.join());
            } catch (Exception e) {
                log.error("Action execution join failed", e);
                if (!stopGenerateService.shouldContinue(sessionId)) {
                    return new ArrayList<>();
                }
                ReactAction fallbackAction = new ReactAction(
                        UUID.randomUUID().toString(),
                        ReactActionType.QUERY_KNOWLEDGE,
                        "unknown-action",
                        "unknown",
                        java.util.Map.of(),
                        "async join fallback",
                        false
                );
                results.add(ReactActionExecutionResult.failed(fallbackAction, null, e.getMessage()));
            }
        }
        return results;
    }

    private ReactActionExecutionResult executeSingleAction(ReactAction action,
                                                           String userQuestion,
                                                           String sessionId,
                                                           String taskId,
                                                           boolean readOnly) {
        if (action == null) {
            return ReactActionExecutionResult.failed(null, null, "react action is null");
        }
        publishActionStarted(action, sessionId, taskId);
        Map<ReactActionType, ReactActionAdapter> adaptersByType = adaptersByType();
        ReactActionAdapter adapter = adaptersByType.get(action.actionType());
        ReactActionExecutionResult result;
        if (adapter != null) {
            result = adapter.execute(action, userQuestion, sessionId, readOnly);
        } else {
            result = switch (action.actionType()) {
            case WAIT_APPROVAL -> ReactActionExecutionResult.waiting(action, null, "approval pending");
            case RETURN_FINAL -> ReactActionExecutionResult.completed(action, null, "final answer requested by runtime");
            case CALL_LOCAL_TOOL,
                 CALL_MCP_TOOL,
                 CALL_REMOTE_SPECIALIST,
                 CALL_LOCAL_SPECIALIST,
                 QUERY_KNOWLEDGE,
                 RUN_SKILL_STEP -> ReactActionExecutionResult.unsupported(
                    action,
                    "No ReactActionAdapter registered for actionType: " + action.actionType().name()
            );
        };
        }
        publishActionFinished(action, result, sessionId, taskId);
        return result;
    }

    private Map<ReactActionType, ReactActionAdapter> adaptersByType() {
        Map<ReactActionType, ReactActionAdapter> adapters = new LinkedHashMap<>();
        for (ReactActionAdapter actionAdapter : actionAdapters) {
            if (actionAdapter == null || actionAdapter.actionType() == null) {
                continue;
            }
            if (!adapters.containsKey(actionAdapter.actionType())) {
                adapters.put(actionAdapter.actionType(), actionAdapter);
            } else {
                log.warn("Duplicate ReactActionAdapter ignored: actionType={}, adapter={}",
                        actionAdapter.actionType(), actionAdapter.getClass().getName());
            }
        }
        return adapters;
    }

    private boolean shouldPublishToolEvent(ReactAction action) {
        if (action == null || action.actionType() == null) {
            return false;
        }
        return switch (action.actionType()) {
            case CALL_LOCAL_TOOL, CALL_MCP_TOOL, QUERY_KNOWLEDGE -> true;
            default -> false;
        };
    }

    private boolean shouldPublishSkillEvent(ReactAction action) {
        return action != null && action.actionType() == ReactActionType.RUN_SKILL_STEP;
    }

    private boolean shouldPublishSpecialistEvent(ReactAction action) {
        if (action == null || action.actionType() == null) {
            return false;
        }
        return switch (action.actionType()) {
            case CALL_LOCAL_SPECIALIST, CALL_REMOTE_SPECIALIST -> true;
            default -> false;
        };
    }

    private boolean shouldPublishApprovalEvent(ReactAction action) {
        return action != null && action.actionType() == ReactActionType.WAIT_APPROVAL;
    }

    private void publishActionStarted(ReactAction action, String sessionId, String taskId) {
        if (shouldPublishToolEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.TOOL_CALLED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    basePayload(action)
            );
            return;
        }
        if (shouldPublishSkillEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.SKILL_CONTEXT_REQUESTED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    basePayload(action)
            );
            return;
        }
        if (shouldPublishApprovalEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.APPROVAL_REQUESTED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    approvalPayload(action, null)
            );
            return;
        }
        if (shouldPublishSpecialistEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.SPECIALIST_CALLED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    specialistPayload(action, null)
            );
        }
    }

    private void publishActionFinished(ReactAction action,
                                       ReactActionExecutionResult result,
                                       String sessionId,
                                       String taskId) {
        if (shouldPublishToolEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.TOOL_RESULT_RECEIVED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    resultPayload(action, result)
            );
            return;
        }
        if (shouldPublishSkillEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.SKILL_CONTEXT_LOADED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    resultPayload(action, result)
            );
            return;
        }
        if (shouldPublishApprovalEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.APPROVAL_RESULT_RECEIVED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    approvalPayload(action, result)
            );
            return;
        }
        if (shouldPublishSpecialistEvent(action)) {
            executionEventBus.publish(
                    ExecutionEventType.SPECIALIST_RESULT_RECEIVED,
                    taskId,
                    sessionId,
                    "react-action-executor",
                    specialistPayload(action, result)
            );
        }
    }

    private Map<String, String> basePayload(ReactAction action) {
        return Map.of(
                "actionId", safe(action == null ? null : action.actionId()),
                "actionType", action == null || action.actionType() == null ? "" : action.actionType().name(),
                "target", safe(action == null ? null : action.target()),
                "actionName", safe(action == null ? null : action.actionName())
        );
    }

    private Map<String, String> resultPayload(ReactAction action, ReactActionExecutionResult result) {
        return Map.of(
                "actionId", safe(action == null ? null : action.actionId()),
                "actionType", action == null || action.actionType() == null ? "" : action.actionType().name(),
                "target", safe(action == null ? null : action.target()),
                "status", result == null || result.status() == null ? "" : result.status().name(),
                "message", safe(result == null ? null : result.message())
        );
    }

    private Map<String, String> specialistPayload(ReactAction action, ReactActionExecutionResult result) {
        Map<String, String> payload = new LinkedHashMap<>(basePayload(action));
        payload.put("specialistKind", specialistKind(action));
        if (result != null) {
            payload.put("status", result.status() == null ? "" : result.status().name());
            payload.put("message", safe(result.message()));
        }
        return Map.copyOf(payload);
    }

    private Map<String, String> approvalPayload(ReactAction action, ReactActionExecutionResult result) {
        Map<String, String> payload = new LinkedHashMap<>(basePayload(action));
        payload.put("approvalState", result == null || result.status() == null ? "PENDING" : result.status().name());
        payload.put("requiresApproval", Boolean.toString(action != null && action.requiresApproval()));
        if (result != null) {
            payload.put("status", result.status() == null ? "" : result.status().name());
            payload.put("message", safe(result.message()));
        }
        return Map.copyOf(payload);
    }

    private String specialistKind(ReactAction action) {
        if (action == null || action.actionType() == null) {
            return "";
        }
        return switch (action.actionType()) {
            case CALL_LOCAL_SPECIALIST -> "local";
            case CALL_REMOTE_SPECIALIST -> "remote";
            default -> "";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

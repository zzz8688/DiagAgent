package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MainAgentActionPlanningService {

    private static final Logger log = LoggerFactory.getLogger(MainAgentActionPlanningService.class);

    private final EngineAdapter engineAdapter;
    private final ReactActionSchemaValidator reactActionSchemaValidator;
    private final ExecutionEventBus executionEventBus;
    private final RuntimeActionGuard runtimeActionGuard;
    private final Gson gson = new Gson();

    public ReactAction planNextAction(MainAgentPromptAssembler.RenderedPrompt renderedPrompt,
                                      CapabilityViewAssembler.CapabilityView capabilityView,
                                      String userQuestion,
                                      ReactTurnState turnState,
                                      EvidenceCoverage evidenceCoverage,
                                      List<ReactObservation> recentObservations,
                                      String taskId,
                                      String sessionId,
                                      boolean readOnly,
                                      int turnIndex) {
        EngineRequest request = EngineRequest.forEnvelope(
                sessionId,
                engineAdapter.adapterId(),
                "main-agent-action-selection",
                renderedPrompt.toPromptEnvelope(),
                readOnly,
                Map.of(
                        "promptMode", safe(renderedPrompt.promptMode()),
                        "turnIndex", Integer.toString(turnIndex)
                )
        );
        String rawResponse = engineAdapter.invoke(request).rawText();
        try {
            ReactAction action = parseAction(rawResponse);
            return runtimeActionGuard.apply(
                    normalize(action, capabilityView),
                    new ActionPlanningContext(
                            capabilityView,
                            userQuestion,
                            turnState,
                            evidenceCoverage,
                            recentObservations
                    ),
                    fallbackAction(capabilityView)
            );
        } catch (ActionContractException e) {
            publishContractFailure(e.eventType, taskId, sessionId, turnIndex, e.issues, rawResponse);
            log.warn("MainAgent action contract rejected, fallback to runtime default action. issues={}, rawResponse={}",
                    e.issues, rawResponse, e);
            return fallbackAction(capabilityView);
        } catch (Exception e) {
            publishContractFailure(
                    ExecutionEventType.REACT_ACTION_CONTRACT_PARSE_FAILED,
                    taskId,
                    sessionId,
                    turnIndex,
                    java.util.List.of("action contract parsing failed unexpectedly"),
                    rawResponse
            );
            log.warn("MainAgent action planning failed, fallback to runtime default action. rawResponse={}", rawResponse, e);
            return fallbackAction(capabilityView);
        }
    }

    private ReactAction parseAction(String rawResponse) {
        JsonObject root = parseActionRoot(rawResponse);
        ReactActionSchemaValidator.ValidationResult validationResult = reactActionSchemaValidator.validate(root);
        if (!validationResult.valid()) {
            throw new ActionContractException(
                    ExecutionEventType.REACT_ACTION_CONTRACT_SCHEMA_REJECTED,
                    validationResult.issues()
            );
        }

        ReactActionType actionType = root.has("actionType")
                ? ReactActionType.valueOf(root.get("actionType").getAsString().trim())
                : ReactActionType.CALL_LOCAL_TOOL;
        String actionName = text(root, "actionName");
        String target = text(root, "target");
        String reason = text(root, "reason");
        boolean requiresApproval = root.has("requiresApproval") && root.get("requiresApproval").getAsBoolean();

        Map<String, String> arguments = new LinkedHashMap<>();
        if (root.has("arguments") && root.get("arguments").isJsonObject()) {
            JsonObject argsNode = root.getAsJsonObject("arguments");
            for (String key : argsNode.keySet()) {
                arguments.put(key, argsNode.get(key).isJsonNull() ? "" : argsNode.get(key).getAsString());
            }
        }

        return new ReactAction(
                UUID.randomUUID().toString(),
                actionType,
                actionName,
                target,
                arguments,
                reason,
                requiresApproval
        );
    }

    private JsonObject parseActionRoot(String rawResponse) {
        try {
            String jsonContent = extractJson(rawResponse);
            return JsonParser.parseString(jsonContent).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new ActionContractException(
                    ExecutionEventType.REACT_ACTION_CONTRACT_PARSE_FAILED,
                    java.util.List.of("action contract is not valid JSON object"),
                    ex
            );
        }
    }

    private ReactAction normalize(ReactAction action,
                                  CapabilityViewAssembler.CapabilityView capabilityView) {
        if (action == null) {
            return fallbackAction(capabilityView);
        }
        return switch (action.actionType()) {
            case CALL_LOCAL_SPECIALIST -> normalizeLocalSpecialist(action, capabilityView);
            case CALL_LOCAL_TOOL -> normalizeLocalTool(action, capabilityView);
            case CALL_MCP_TOOL -> normalizeMcpTool(action, capabilityView);
            case CALL_REMOTE_SPECIALIST -> normalizeRemoteSpecialist(action, capabilityView);
            case QUERY_KNOWLEDGE -> new ReactAction(
                    action.actionId(),
                    ReactActionType.CALL_LOCAL_TOOL,
                    defaultActionName(action, "queryKnowledgeBase"),
                    defaultTarget(action.target(), "queryKnowledgeBase"),
                    mergeDefaultArguments(action.arguments(), "query", "当前症状相关知识"),
                    defaultReason(action.reason(), "query knowledge base"),
                    false
            );
            case RUN_SKILL_STEP -> normalizeSkillStep(action, capabilityView);
            case WAIT_APPROVAL, RETURN_FINAL -> new ReactAction(
                    action.actionId(),
                    action.actionType(),
                    defaultActionName(action, action.actionType().name().toLowerCase(Locale.ROOT)),
                    defaultTarget(action.target(), action.actionType() == ReactActionType.RETURN_FINAL ? "final-answer" : "approval"),
                    action.arguments(),
                    defaultReason(action.reason(), "runtime control action"),
                    action.requiresApproval()
            );
        };
    }

    private ReactAction normalizeLocalSpecialist(ReactAction action,
                                                 CapabilityViewAssembler.CapabilityView capabilityView) {
        String target = normalizeText(action.target());
        if (target != null && capabilityView != null && capabilityView.hasLocalSpecialist(target)) {
            return withDefaults(action, target, target, false);
        }
        return fallbackAction(capabilityView);
    }

    private ReactAction normalizeSkillStep(ReactAction action,
                                           CapabilityViewAssembler.CapabilityView capabilityView) {
        String target = normalizeText(action.target());
        String normalizedSkillTarget = hasSkill(capabilityView, target) ? target : fallbackSkillTarget(capabilityView);
        if (normalizedSkillTarget == null) {
            return fallbackAction(capabilityView);
        }
        Map<String, String> arguments = new LinkedHashMap<>();
        if (action.arguments() != null) {
            arguments.putAll(action.arguments());
        }
        arguments.putIfAbsent("skillId", normalizedSkillTarget);
        return new ReactAction(
                action.actionId(),
                ReactActionType.RUN_SKILL_STEP,
                "loadSkillContext",
                normalizedSkillTarget,
                Map.copyOf(arguments),
                defaultReason(action.reason(), "load selected skill into runtime context"),
                false
        );
    }

    private ReactAction normalizeLocalTool(ReactAction action,
                                           CapabilityViewAssembler.CapabilityView capabilityView) {
        String target = normalizeText(action.target());
        if (target != null && capabilityView != null && capabilityView.hasLocalTool(target)) {
            return withDefaults(action, target, target, false);
        }
        return new ReactAction(
                action.actionId(),
                ReactActionType.CALL_LOCAL_TOOL,
                defaultActionName(action, "queryLogs"),
                "queryLogs",
                mergeDefaultArguments(action.arguments(), "query", "最近错误日志"),
                defaultReason(action.reason(), "fallback to query logs"),
                false
        );
    }

    private ReactAction normalizeMcpTool(ReactAction action,
                                         CapabilityViewAssembler.CapabilityView capabilityView) {
        String target = normalizeText(action.target());
        if (isNotificationMcpTarget(target)) {
            return fallbackAction(capabilityView);
        }
        if (target != null && target.contains("/")) {
            String[] parts = target.split("/", 2);
            if (parts.length == 2 && capabilityView != null && capabilityView.hasMcpTool(parts[0], parts[1])) {
                return withDefaults(action, target, parts[1], false);
            }
        }
        String fallbackTarget = capabilityView == null ? "prometheus-mcp/queryPrometheusMetrics" : capabilityView.defaultMcpTarget();
        return withDefaults(action, fallbackTarget, action.actionName(), false);
    }

    private ReactAction normalizeRemoteSpecialist(ReactAction action,
                                                  CapabilityViewAssembler.CapabilityView capabilityView) {
        String target = normalizeText(action.target());
        if (target != null && capabilityView != null && capabilityView.hasRemoteSpecialist(target)) {
            return withDefaults(action, target, target, false);
        }
        return fallbackAction(capabilityView);
    }

    private ReactAction fallbackAction(CapabilityViewAssembler.CapabilityView capabilityView) {
        return new ReactAction(
                UUID.randomUUID().toString(),
                ReactActionType.CALL_LOCAL_TOOL,
                "queryLogs",
                "queryLogs",
                Map.of("query", "最近错误日志"),
                "fallback query logs",
                false
        );
    }

    private ReactAction withDefaults(ReactAction action, String target, String actionName, boolean requiresApproval) {
        return new ReactAction(
                action.actionId(),
                action.actionType(),
                defaultActionName(action, actionName),
                defaultTarget(action.target(), target),
                action.arguments(),
                defaultReason(action.reason(), "planned by MainAgent"),
                requiresApproval || action.requiresApproval()
        );
    }

    private Map<String, String> mergeDefaultArguments(Map<String, String> original,
                                                      String key,
                                                      String fallbackValue) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (original != null) {
            merged.putAll(original);
        }
        merged.putIfAbsent(key, fallbackValue);
        return merged;
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String text(JsonObject root, String fieldName) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            return null;
        }
        return normalizeText(root.get(fieldName).getAsString());
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultActionName(ReactAction action, String fallback) {
        if (action != null && action.actionName() != null && !action.actionName().isBlank()) {
            return action.actionName();
        }
        return fallback;
    }

    private String defaultTarget(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isNotificationMcpTarget(String target) {
        if (target == null || !target.contains("/")) {
            return false;
        }
        String normalized = target.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("dingtalk-mcp/")
                || normalized.contains("notification")
                || normalized.contains("notify");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasSkill(CapabilityViewAssembler.CapabilityView capabilityView, String skillId) {
        if (capabilityView == null || skillId == null || skillId.isBlank() || capabilityView.skills() == null) {
            return false;
        }
        return capabilityView.skills().stream()
                .filter(entry -> entry != null && entry.id() != null)
                .anyMatch(entry -> entry.id().equalsIgnoreCase(skillId));
    }

    private String fallbackSkillTarget(CapabilityViewAssembler.CapabilityView capabilityView) {
        if (capabilityView == null || capabilityView.skills() == null || capabilityView.skills().isEmpty()) {
            return null;
        }
        return capabilityView.skills().stream()
                .filter(entry -> entry != null && entry.id() != null)
                .map(CapabilityViewAssembler.CapabilityEntry::id)
                .filter(id -> "diagnosis-playbook-skill".equalsIgnoreCase(id))
                .findFirst()
                .orElseGet(() -> capabilityView.skills().stream()
                        .filter(entry -> entry != null && entry.id() != null && !entry.id().isBlank())
                        .map(CapabilityViewAssembler.CapabilityEntry::id)
                        .findFirst()
                        .orElse(null));
    }

    private void publishContractFailure(ExecutionEventType eventType,
                                        String taskId,
                                        String sessionId,
                                        int turnIndex,
                                        java.util.List<String> issues,
                                        String rawResponse) {
        executionEventBus.publish(
                eventType,
                taskId,
                sessionId,
                "main-agent-action-planning",
                Map.of(
                        "turnIndex", Integer.toString(turnIndex),
                        "message", joinIssues(issues),
                        "rawPreview", summarizeRawResponse(rawResponse)
                )
        );
    }

    private String joinIssues(java.util.List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        return String.join(" | ", issues);
    }

    private String summarizeRawResponse(String rawResponse) {
        String normalized = safe(rawResponse).trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private static final class ActionContractException extends RuntimeException {
        private final ExecutionEventType eventType;
        private final java.util.List<String> issues;

        private ActionContractException(ExecutionEventType eventType,
                                        java.util.List<String> issues) {
            super(issues == null || issues.isEmpty() ? "action contract rejected" : String.join(" | ", issues));
            this.eventType = eventType;
            this.issues = issues == null ? java.util.List.of() : java.util.List.copyOf(issues);
        }

        private ActionContractException(ExecutionEventType eventType,
                                        java.util.List<String> issues,
                                        Throwable cause) {
            super(issues == null || issues.isEmpty() ? "action contract rejected" : String.join(" | ", issues), cause);
            this.eventType = eventType;
            this.issues = issues == null ? java.util.List.of() : java.util.List.copyOf(issues);
        }
    }
}

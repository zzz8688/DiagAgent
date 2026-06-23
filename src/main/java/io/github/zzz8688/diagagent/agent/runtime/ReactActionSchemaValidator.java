package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReactActionSchemaValidator {

    public ValidationResult validate(JsonObject root) {
        List<String> issues = new ArrayList<>();
        if (root == null) {
            issues.add("action contract root is missing");
            return new ValidationResult(false, List.copyOf(issues));
        }

        String actionTypeText = requireString(root, "actionType", issues, true);
        String actionName = requireString(root, "actionName", issues, true);
        String target = requireString(root, "target", issues, true);
        requireObject(root, "arguments", issues);
        requireString(root, "reason", issues, true);
        requireBoolean(root, "requiresApproval", issues);

        ReactActionType actionType = parseActionType(actionTypeText, issues);
        if (actionType != null) {
            validateTypeSpecificRules(actionType, actionName, target, issues);
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private ReactActionType parseActionType(String actionTypeText, List<String> issues) {
        if (actionTypeText == null || actionTypeText.isBlank()) {
            return null;
        }
        try {
            return ReactActionType.valueOf(actionTypeText.trim());
        } catch (IllegalArgumentException ex) {
            issues.add("actionType is unsupported: " + actionTypeText);
            return null;
        }
    }

    private void validateTypeSpecificRules(ReactActionType actionType,
                                           String actionName,
                                           String target,
                                           List<String> issues) {
        switch (actionType) {
            case CALL_MCP_TOOL -> {
                if (target == null || !target.contains("/")) {
                    issues.add("CALL_MCP_TOOL target must use serverId/toolName");
                }
            }
            case RETURN_FINAL -> {
                if (!"final-answer".equals(target)) {
                    issues.add("RETURN_FINAL target must be final-answer");
                }
                if (!"return_final".equals(actionName)) {
                    issues.add("RETURN_FINAL actionName must be return_final");
                }
            }
            case WAIT_APPROVAL -> {
                if (!"approval".equals(target)) {
                    issues.add("WAIT_APPROVAL target must be approval");
                }
            }
            case CALL_LOCAL_SPECIALIST, CALL_LOCAL_TOOL, CALL_REMOTE_SPECIALIST, QUERY_KNOWLEDGE, RUN_SKILL_STEP -> {
                if (target == null || target.isBlank()) {
                    issues.add(actionType.name() + " target must not be blank");
                }
            }
        }
    }

    private String requireString(JsonObject root, String fieldName, List<String> issues, boolean nonBlank) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            issues.add(fieldName + " is required");
            return null;
        }
        JsonElement element = root.get(fieldName);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            issues.add(fieldName + " must be a string");
            return null;
        }
        String value = element.getAsString();
        if (nonBlank && (value == null || value.isBlank())) {
            issues.add(fieldName + " must not be blank");
            return null;
        }
        return value == null ? null : value.trim();
    }

    private void requireObject(JsonObject root, String fieldName, List<String> issues) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            issues.add(fieldName + " is required");
            return;
        }
        if (!root.get(fieldName).isJsonObject()) {
            issues.add(fieldName + " must be an object");
        }
    }

    private void requireBoolean(JsonObject root, String fieldName, List<String> issues) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            issues.add(fieldName + " is required");
            return;
        }
        JsonElement element = root.get(fieldName);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            issues.add(fieldName + " must be a boolean");
        }
    }

    public record ValidationResult(
            boolean valid,
            List<String> issues
    ) {
    }
}

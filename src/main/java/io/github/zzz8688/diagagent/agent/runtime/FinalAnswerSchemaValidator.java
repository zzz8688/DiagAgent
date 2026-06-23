package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FinalAnswerSchemaValidator {

    public ValidationResult validate(JsonObject root) {
        List<String> issues = new ArrayList<>();
        if (root == null) {
            issues.add("final answer contract root is missing");
            return new ValidationResult(false, List.copyOf(issues));
        }

        requireString(root, "summary", issues, true);
        requireArray(root, "evidence", issues);
        requireOptionalString(root, "rootCause", issues);
        requireArray(root, "nextActions", issues);
        requireNumber(root, "confidence", issues);
        requireBoolean(root, "needsHandoff", issues);

        if (root.has("evidence") && root.get("evidence").isJsonArray()) {
            validateEvidence(root.getAsJsonArray("evidence"), issues);
        }
        if (root.has("nextActions") && root.get("nextActions").isJsonArray()) {
            validateStringArray(root.getAsJsonArray("nextActions"), "nextActions", issues);
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private void validateEvidence(JsonArray evidenceArray, List<String> issues) {
        for (int i = 0; i < evidenceArray.size(); i++) {
            JsonElement item = evidenceArray.get(i);
            if (!item.isJsonObject()) {
                issues.add("evidence[" + i + "] must be an object");
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            requireString(object, "sourceType", issues, true, "evidence[" + i + "].sourceType");
            requireString(object, "sourceId", issues, true, "evidence[" + i + "].sourceId");
            requireString(object, "summary", issues, true, "evidence[" + i + "].summary");
        }
    }

    private void validateStringArray(JsonArray array, String fieldName, List<String> issues) {
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                issues.add(fieldName + "[" + i + "] must be a string");
                continue;
            }
            String value = element.getAsString();
            if (value == null || value.isBlank()) {
                issues.add(fieldName + "[" + i + "] must not be blank");
            }
        }
    }

    private void requireArray(JsonObject root, String fieldName, List<String> issues) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            issues.add(fieldName + " is required");
            return;
        }
        if (!root.get(fieldName).isJsonArray()) {
            issues.add(fieldName + " must be an array");
        }
    }

    private void requireOptionalString(JsonObject root, String fieldName, List<String> issues) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            return;
        }
        JsonElement element = root.get(fieldName);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            issues.add(fieldName + " must be a string when present");
        }
    }

    private void requireNumber(JsonObject root, String fieldName, List<String> issues) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            issues.add(fieldName + " is required");
            return;
        }
        JsonElement element = root.get(fieldName);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            issues.add(fieldName + " must be a number");
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

    private void requireString(JsonObject root, String fieldName, List<String> issues, boolean nonBlank) {
        requireString(root, fieldName, issues, nonBlank, fieldName);
    }

    private void requireString(JsonObject root,
                               String fieldName,
                               List<String> issues,
                               boolean nonBlank,
                               String issueLabel) {
        if (!root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            issues.add(issueLabel + " is required");
            return;
        }
        JsonElement element = root.get(fieldName);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            issues.add(issueLabel + " must be a string");
            return;
        }
        String value = element.getAsString();
        if (nonBlank && (value == null || value.isBlank())) {
            issues.add(issueLabel + " must not be blank");
        }
    }

    public record ValidationResult(
            boolean valid,
            List<String> issues
    ) {
    }
}

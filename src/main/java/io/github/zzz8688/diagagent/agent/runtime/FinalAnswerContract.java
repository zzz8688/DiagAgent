package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public record FinalAnswerContract(
        String summary,
        List<EvidenceItem> evidence,
        String rootCause,
        List<String> nextActions,
        double confidence,
        boolean needsHandoff
) {

    public static FinalAnswerContract fromJson(String rawResponse) {
        String jsonContent = extractJson(rawResponse);
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
        return fromJsonObject(root);
    }

    public static FinalAnswerContract fromJsonObject(JsonObject root) {
        return new FinalAnswerContract(
                text(root, "summary"),
                parseEvidence(root.get("evidence")),
                text(root, "rootCause"),
                parseStringList(root.get("nextActions")),
                parseConfidence(root.get("confidence")),
                root.has("needsHandoff") && !root.get("needsHandoff").isJsonNull() && root.get("needsHandoff").getAsBoolean()
        );
    }

    public String renderMarkdown() {
        StringBuilder builder = new StringBuilder();
        builder.append("### 诊断结论\n\n");
        builder.append(safe(summary)).append("\n\n");
        String normalizedRootCause = safe(rootCause);
        String normalizedSummary = safe(summary);
        if (!normalizedRootCause.isBlank() && !normalizedRootCause.equals(normalizedSummary)) {
            builder.append("### 根因判断\n\n");
            builder.append(normalizedRootCause).append("\n");
        }
        return builder.toString().trim();
    }

    private static List<EvidenceItem> parseEvidence(JsonElement evidenceElement) {
        if (evidenceElement == null || !evidenceElement.isJsonArray()) {
            return List.of();
        }
        List<EvidenceItem> items = new ArrayList<>();
        JsonArray array = evidenceElement.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            items.add(new EvidenceItem(
                    text(object, "sourceType"),
                    text(object, "sourceId"),
                    text(object, "summary")
            ));
        }
        return List.copyOf(items);
    }

    private static List<String> parseStringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            String value = item.getAsString();
            if (value != null && !value.isBlank()) {
                results.add(value.trim());
            }
        }
        return List.copyOf(results);
    }

    private static double parseConfidence(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return 0.0;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    public static String extractJson(String content) {
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

    private static String text(JsonObject root, String fieldName) {
        if (root == null || fieldName == null || !root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            return null;
        }
        String value = root.get(fieldName).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record EvidenceItem(
            String sourceType,
            String sourceId,
            String summary
    ) {
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Locale;

public final class ObservationSemantics {

    private static final String MEMORY_QUERY_TOOL = "querymemoryfiles";
    private static final String KNOWLEDGE_QUERY_TOOL = "queryknowledgebase";

    private ObservationSemantics() {
    }

    public static ObservationKind classify(ReactObservation observation) {
        if (observation == null) {
            return ObservationKind.CONTROL;
        }
        if (isHistoricalExperienceSource(observation.source())) {
            return ObservationKind.HISTORICAL_EXPERIENCE;
        }
        return classify(observation.actionType());
    }

    public static ObservationKind classify(ReactActionType actionType) {
        if (actionType == null) {
            return ObservationKind.CONTROL;
        }
        return switch (actionType) {
            case CALL_LOCAL_TOOL, CALL_MCP_TOOL, QUERY_KNOWLEDGE,
                 CALL_LOCAL_SPECIALIST, CALL_REMOTE_SPECIALIST -> ObservationKind.FACTUAL;
            case RUN_SKILL_STEP -> ObservationKind.PLANNING;
            case WAIT_APPROVAL, RETURN_FINAL -> ObservationKind.CONTROL;
        };
    }

    public static ObservationKind classify(String actionType, String targetOrSource) {
        if (isHistoricalExperienceSource(targetOrSource)) {
            return ObservationKind.HISTORICAL_EXPERIENCE;
        }
        if (actionType == null || actionType.isBlank()) {
            return ObservationKind.CONTROL;
        }
        if ("LOG_STREAM".equalsIgnoreCase(actionType.trim())) {
            return ObservationKind.FACTUAL;
        }
        try {
            return classify(ReactActionType.valueOf(actionType.trim()));
        } catch (IllegalArgumentException ignored) {
            return ObservationKind.CONTROL;
        }
    }

    public static boolean isFactual(ReactObservation observation) {
        return classify(observation) == ObservationKind.FACTUAL;
    }

    public static boolean isHistoricalExperience(ReactObservation observation) {
        return classify(observation) == ObservationKind.HISTORICAL_EXPERIENCE;
    }

    public static boolean isPlanning(ReactObservation observation) {
        return classify(observation) == ObservationKind.PLANNING;
    }

    public static boolean isControl(ReactObservation observation) {
        return classify(observation) == ObservationKind.CONTROL;
    }

    public static boolean isFactualActionType(String actionType) {
        return classify(actionType, null) == ObservationKind.FACTUAL;
    }

    public static boolean isFactualAction(String actionType, String targetOrSource) {
        return classify(actionType, targetOrSource) == ObservationKind.FACTUAL;
    }

    public static boolean isHistoricalExperienceAction(String actionType, String targetOrSource) {
        return classify(actionType, targetOrSource) == ObservationKind.HISTORICAL_EXPERIENCE;
    }

    public static boolean isHistoricalExperienceSource(String source) {
        String normalized = normalize(source);
        return normalized.equals(MEMORY_QUERY_TOOL)
                || normalized.endsWith("/" + MEMORY_QUERY_TOOL)
                || normalized.contains(MEMORY_QUERY_TOOL);
    }

    public static boolean isKnowledgeAction(String actionType, String targetOrSource) {
        if (isKnowledgeSource(targetOrSource)) {
            return true;
        }
        if (actionType == null || actionType.isBlank()) {
            return false;
        }
        return "QUERY_KNOWLEDGE".equalsIgnoreCase(actionType.trim());
    }

    public static boolean isKnowledgeSource(String source) {
        String normalized = normalize(source);
        return normalized.equals(KNOWLEDGE_QUERY_TOOL)
                || normalized.endsWith("/" + KNOWLEDGE_QUERY_TOOL)
                || normalized.contains(KNOWLEDGE_QUERY_TOOL)
                || normalized.contains("knowledge");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public enum ObservationKind {
        FACTUAL,
        HISTORICAL_EXPERIENCE,
        PLANNING,
        CONTROL
    }
}

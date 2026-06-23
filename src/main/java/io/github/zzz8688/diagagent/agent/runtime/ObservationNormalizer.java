package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;
import io.github.zzz8688.diagagent.a2a.A2aClientInvocationResult;
import io.github.zzz8688.diagagent.a2a.A2aMessage;
import io.github.zzz8688.diagagent.a2a.A2aPayload;
import io.github.zzz8688.diagagent.a2a.A2aSendMessageParams;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ObservationNormalizer {

    public List<ReactObservation> normalizeActionResults(List<ReactActionExecutionResult> actionResults) {
        List<ReactObservation> observations = new ArrayList<>();
        if (actionResults == null) {
            return observations;
        }
        for (ReactActionExecutionResult actionResult : actionResults) {
            observations.add(normalizeActionResult(actionResult));
        }
        return observations;
    }

    public List<AgentDiagnosisResponse> toDiagnosisResponses(List<ReactObservation> observations) {
        List<AgentDiagnosisResponse> responses = new ArrayList<>();
        if (observations == null) {
            return responses;
        }
        for (ReactObservation observation : observations) {
            AgentDiagnosisResponse response = toDiagnosisResponse(observation);
            if (response != null) {
                responses.add(response);
            }
        }
        return responses;
    }

    public String summarizeObservations(List<ReactObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return "observations=0";
        }
        long successCount = observations.stream()
                .filter(observation -> observation.status() == ReactActionStatus.COMPLETED)
                .count();
        long factualCount = observations.stream().filter(ObservationSemantics::isFactual).count();
        long historicalCount = observations.stream().filter(ObservationSemantics::isHistoricalExperience).count();
        long planningCount = observations.stream().filter(ObservationSemantics::isPlanning).count();
        long controlCount = observations.stream().filter(ObservationSemantics::isControl).count();
        return "observations=%d, successful=%d, factual=%d, historical=%d, planning=%d, control=%d"
                .formatted(observations.size(), successCount, factualCount, historicalCount, planningCount, controlCount);
    }

    public String summarizeSpecialistObservations(List<AgentDiagnosisResponse> responses) {
        int count = responses == null ? 0 : responses.size();
        return "specialistResponses=" + count;
    }

    private ReactObservation normalizeActionResult(ReactActionExecutionResult result) {
        if (result == null || result.action() == null) {
            return ReactObservation.fromActionResult(result, null, "runtime", "action result is null");
        }
        return switch (result.action().actionType()) {
            case CALL_LOCAL_SPECIALIST, CALL_REMOTE_SPECIALIST -> normalizeSpecialistAction(result);
            case CALL_LOCAL_TOOL, CALL_MCP_TOOL, QUERY_KNOWLEDGE, RUN_SKILL_STEP, WAIT_APPROVAL, RETURN_FINAL ->
                    ReactObservation.fromActionResult(
                            result,
                            result.payload(),
                            sourceFromAction(result.action()),
                            defaultSummary(result)
                    );
        };
    }

    private ReactObservation normalizeSpecialistAction(ReactActionExecutionResult result) {
        Object payload = result.payload();
        if (payload instanceof AgentTaskResult taskResult
                && taskResult.status() == AgentTaskStatus.COMPLETED
                && taskResult.response() != null) {
            return ReactObservation.fromActionResult(
                    result,
                    taskResult.response(),
                    sourceFromAction(result.action()),
                    "specialist observation received from " + taskResult.agentId()
            );
        }
        return ReactObservation.fromActionResult(
                result,
                payload,
                sourceFromAction(result.action()),
                defaultSummary(result)
        );
    }

    private AgentDiagnosisResponse toDiagnosisResponse(ReactObservation observation) {
        if (!ObservationSemantics.isFactual(observation)) {
            return null;
        }
        if (observation != null && observation.payload() instanceof AgentDiagnosisResponse response) {
            return response;
        }
        if (observation != null && observation.payload() instanceof String textPayload) {
            return buildGenericResponse(observation, textPayload, "tool observation received");
        }
        if (observation != null && observation.payload() instanceof A2aClientInvocationResult a2aResult) {
            String textPayload = stringifyA2aPayload(a2aResult.payload());
            return buildGenericResponse(observation, textPayload, "remote specialist observation received");
        }
        String agentId = observation == null || observation.source() == null ? "unknown" : observation.source();
        String errorMessage = observation == null ? "observation is null" : observation.summary();
        return buildGenericResponse(observation, errorMessage,
                "action observation 未形成可直接消费的诊断结果");
    }

    private String sourceFromAction(ReactAction action) {
        if (action == null) {
            return "unknown";
        }
        if (action.target() != null) {
            return action.target();
        }
        return action.actionType().name().toLowerCase();
    }

    private String defaultSummary(ReactActionExecutionResult result) {
        if (result == null) {
            return "action result is null";
        }
        String payloadSummary = summarizePayload(result.payload());
        if (!payloadSummary.isBlank()) {
            return payloadSummary;
        }
        if (result.message() != null) {
            return result.message();
        }
        ReactAction action = result.action();
        return action == null ? "action result has no action metadata" : action.actionType().name();
    }

    private String summarizePayload(Object payload) {
        if (!(payload instanceof String textPayload) || textPayload.isBlank()) {
            return "";
        }
        String normalized = textPayload.replace('\r', '\n');
        String[] lines = normalized.split("\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("results:") || trimmed.startsWith("series:")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(trimmed);
            if (builder.length() >= 240 || builder.toString().contains("traceId=") || builder.toString().contains("requestId=")) {
                break;
            }
            if (builder.toString().contains("traceId:") || builder.toString().contains("requestId:")) {
                break;
            }
        }
        String summary = builder.toString().trim();
        if (summary.length() > 240) {
            return summary.substring(0, 240) + "...";
        }
        return summary;
    }

    private String stringifyA2aPayload(A2aPayload payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof A2aMessage message) {
            return message.parts().stream()
                    .filter(A2aSendMessageParams.TextPart.class::isInstance)
                    .map(A2aSendMessageParams.TextPart.class::cast)
                    .map(A2aSendMessageParams.TextPart::text)
                    .findFirst()
                    .orElse(payload.toString());
        }
        return payload.toString();
    }

    private AgentDiagnosisResponse buildGenericResponse(ReactObservation observation,
                                                        String payloadText,
                                                        String prefix) {
        String agentId = observation == null || observation.source() == null ? "unknown" : observation.source();
        String safePayload = payloadText == null ? "" : payloadText;
        AgentDiagnosisResponse response = new AgentDiagnosisResponse();
        response.setAgent(agentId);
        response.setAnalysis(prefix + ": " + safePayload);
        response.setConfidence(resolveObservationConfidence(observation, safePayload));
        response.setConclusion(observation != null && observation.status() == ReactActionStatus.COMPLETED
                ? "该事实 observation 已进入主智能体证据面，可继续用于最终诊断收敛。"
                : "该 observation 已记录，但执行状态异常，请结合 summary 判断是否需要补动作。");
        return response;
    }

    private double resolveObservationConfidence(ReactObservation observation, String payloadText) {
        if (observation == null || observation.status() != ReactActionStatus.COMPLETED) {
            return 0.0;
        }
        String normalizedSource = observation.source() == null
                ? ""
                : observation.source().trim().toLowerCase(Locale.ROOT);
        String normalizedPayload = payloadText == null ? "" : payloadText.toLowerCase(Locale.ROOT);

        double confidence;
        if (normalizedSource.contains("querylogs") || normalizedSource.contains("log")) {
            confidence = 0.82;
        } else if (normalizedSource.contains("queryprometheusmetrics")
                || normalizedSource.contains("querymetrics")
                || normalizedSource.contains("prometheus")
                || normalizedSource.contains("metric")) {
            confidence = 0.74;
        } else if (normalizedSource.contains("querytopology")
                || normalizedSource.contains("getdependencies")
                || normalizedSource.contains("dependency")
                || normalizedSource.contains("topology")) {
            confidence = 0.64;
        } else if (normalizedSource.contains("queryknowledgebase") || normalizedSource.contains("knowledge")) {
            confidence = 0.52;
        } else if (normalizedSource.contains("querymemoryfiles") || normalizedSource.contains("memory")) {
            confidence = 0.48;
        } else if (normalizedSource.contains("specialist")) {
            confidence = 0.58;
        } else {
            confidence = 0.60;
        }

        if (normalizedSource.contains("querylogs")
                && (normalizedPayload.contains("traceid=")
                || normalizedPayload.contains("traceid:")
                || normalizedPayload.contains("requestid=")
                || normalizedPayload.contains("requestid:"))) {
            confidence += 0.04;
        }
        if ((normalizedSource.contains("queryprometheusmetrics")
                || normalizedSource.contains("querymetrics")
                || normalizedSource.contains("prometheus")
                || normalizedSource.contains("metric"))
                && (normalizedPayload.contains("error_rate")
                || normalizedPayload.contains("p99")
                || normalizedPayload.contains("latency")
                || normalizedPayload.contains("slow query")
                || normalizedPayload.contains("connection_pool")
                || normalizedPayload.contains("连接池"))) {
            confidence += 0.04;
        }
        return Math.min(0.92, confidence);
    }
}

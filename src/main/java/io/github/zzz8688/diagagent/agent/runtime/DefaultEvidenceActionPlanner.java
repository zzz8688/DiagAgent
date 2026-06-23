package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class DefaultEvidenceActionPlanner implements EvidenceActionPlanner {

    private final DomainEvidenceHeuristics domainEvidenceHeuristics;

    @Override
    public ReactAction planFallbackEvidenceAction(ActionPlanningContext context, String reason) {
        String criticObservation = context.criticObservation();
        if (criticObservation != null && !criticObservation.isBlank()) {
            if (domainEvidenceHeuristics.criticismRequiresTraceLogs(criticObservation)) {
                ReactAction traceAction = buildCriticDrivenTraceAction(context, criticObservation, reason);
                if (traceAction != null) {
                    return traceAction;
                }
            }
            if (domainEvidenceHeuristics.criticismRequiresMetrics(criticObservation)) {
                String criticDrivenTarget = parseCriticDrivenMcpTarget(context.capabilityView(), criticObservation);
                if (criticDrivenTarget != null) {
                    return buildMcpEvidenceAction(
                            criticDrivenTarget,
                            domainEvidenceHeuristics.metricArgumentsForCriticism(criticObservation),
                            reason
                    );
                }
            }
            if (domainEvidenceHeuristics.criticismRequiresChangeLogs(criticObservation)) {
                return buildLocalLogAction(domainEvidenceHeuristics.buildChangeLogQuery(context.userQuestion(), criticObservation), reason);
            }
            String serviceName = domainEvidenceHeuristics.extractServiceName(criticObservation);
            if (serviceName != null) {
                return buildDependencyAction(serviceName, reason);
            }
            String traceQuery = domainEvidenceHeuristics.buildTraceLogQuery(context.userQuestion(), criticObservation);
            if (traceQuery != null) {
                return buildLocalLogAction(traceQuery, reason);
            }
        }
        if (domainEvidenceHeuristics.shouldPreferMetrics(context.userQuestion())) {
            String metricsTarget = defaultMetricsTarget(context.capabilityView());
            if (metricsTarget != null) {
                return buildMcpEvidenceAction(
                        metricsTarget,
                        domainEvidenceHeuristics.defaultMetricArguments(context.userQuestion()),
                        reason
                );
            }
        }
        return buildLocalLogAction(domainEvidenceHeuristics.defaultLogQuery(context.userQuestion()), reason);
    }

    @Override
    public ReactAction planAlternateEvidenceAction(ActionPlanningContext context,
                                                   String latestFactualSource,
                                                   String reason) {
        String criticObservation = context.criticObservation();
        if (criticObservation != null && !criticObservation.isBlank()) {
            boolean latestIsLogs = latestFactualSource != null && latestFactualSource.equalsIgnoreCase("queryLogs");
            boolean latestIsMetrics = latestFactualSource != null
                    && latestFactualSource.toLowerCase(Locale.ROOT).contains("queryprometheusmetrics");
            boolean latestIsTopology = latestFactualSource != null
                    && latestFactualSource.toLowerCase(Locale.ROOT).contains("dependenc");

            if (latestIsLogs && domainEvidenceHeuristics.criticismRequiresMetrics(criticObservation)) {
                String metricsTarget = defaultMetricsTarget(context.capabilityView());
                if (metricsTarget != null) {
                    return buildMcpEvidenceAction(
                            metricsTarget,
                            domainEvidenceHeuristics.metricArgumentsForCriticism(criticObservation),
                            reason
                    );
                }
            }
            if (latestIsMetrics && domainEvidenceHeuristics.criticismRequiresChangeLogs(criticObservation)) {
                return buildLocalLogAction(domainEvidenceHeuristics.buildChangeLogQuery(context.userQuestion(), criticObservation), reason);
            }
            if (latestIsMetrics && domainEvidenceHeuristics.criticismRequiresTraceLogs(criticObservation)) {
                ReactAction traceAction = buildCriticDrivenTraceAction(context, criticObservation, reason);
                if (traceAction != null) {
                    return traceAction;
                }
            }
            if ((latestIsLogs || latestIsMetrics) && domainEvidenceHeuristics.extractServiceName(criticObservation) != null) {
                return buildDependencyAction(domainEvidenceHeuristics.extractServiceName(criticObservation), reason);
            }
            if (latestIsTopology && domainEvidenceHeuristics.criticismRequiresChangeLogs(criticObservation)) {
                return buildLocalLogAction(domainEvidenceHeuristics.buildChangeLogQuery(context.userQuestion(), criticObservation), reason);
            }
        }
        if (latestFactualSource != null && latestFactualSource.equalsIgnoreCase("queryLogs")) {
            String metricsTarget = defaultMetricsTarget(context.capabilityView());
            if (metricsTarget != null) {
                return buildMcpEvidenceAction(
                        metricsTarget,
                        domainEvidenceHeuristics.defaultMetricArguments(context.userQuestion()),
                        reason
                );
            }
        }
        if (latestFactualSource != null
                && latestFactualSource.toLowerCase(Locale.ROOT).contains("queryprometheusmetrics")) {
            return buildLocalLogAction(domainEvidenceHeuristics.defaultLogQuery(context.userQuestion()), reason);
        }
        String metricsTarget = defaultMetricsTarget(context.capabilityView());
        if (metricsTarget != null) {
            return buildMcpEvidenceAction(
                    metricsTarget,
                    domainEvidenceHeuristics.defaultMetricArguments(context.userQuestion()),
                    reason
            );
        }
        return buildLocalLogAction(domainEvidenceHeuristics.defaultLogQuery(context.userQuestion()), reason);
    }

    private ReactAction buildLocalLogAction(String query, String reason) {
        return buildLocalLogAction(query, null, reason);
    }

    private ReactAction buildLocalLogAction(String query, String traceId, String reason) {
        Map<String, String> arguments = new java.util.LinkedHashMap<>();
        arguments.put("query", query == null || query.isBlank() ? "最近错误日志" : query.trim());
        arguments.put("topK", "6");
        if (traceId == null || traceId.isBlank()) {
            arguments.put("timeRange", "30m");
        } else {
            arguments.put("traceId", traceId.trim());
            arguments.put("timeRange", "6h");
        }
        return new ReactAction(
                UUID.randomUUID().toString(),
                ReactActionType.CALL_LOCAL_TOOL,
                "queryLogs",
                "queryLogs",
                arguments,
                reason,
                false
        );
    }

    private ReactAction buildDependencyAction(String serviceName, String reason) {
        return new ReactAction(
                UUID.randomUUID().toString(),
                ReactActionType.CALL_LOCAL_TOOL,
                "getDependencies",
                "getDependencies",
                Map.of("serviceName", serviceName == null || serviceName.isBlank() ? "payment-service" : serviceName.trim()),
                reason,
                false
        );
    }

    private ReactAction buildMcpEvidenceAction(String target, Map<String, String> arguments, String reason) {
        return new ReactAction(
                UUID.randomUUID().toString(),
                ReactActionType.CALL_MCP_TOOL,
                "queryPrometheusMetrics",
                target,
                arguments == null || arguments.isEmpty()
                        ? Map.of("metricName", "service_error_rate", "timeRange", "15m")
                        : arguments,
                reason,
                false
        );
    }

    private String parseCriticDrivenMcpTarget(CapabilityViewAssembler.CapabilityView capabilityView,
                                              String criticObservation) {
        if (domainEvidenceHeuristics.criticismRequiresMetrics(criticObservation)) {
            return defaultMetricsTarget(capabilityView);
        }
        return null;
    }

    private ReactAction buildCriticDrivenTraceAction(ActionPlanningContext context,
                                                     String criticObservation,
                                                     String reason) {
        String traceQuery = domainEvidenceHeuristics.buildTraceLogQuery(context.userQuestion(), criticObservation);
        if (traceQuery == null) {
            return null;
        }
        return buildLocalLogAction(
                traceQuery,
                domainEvidenceHeuristics.extractTraceOrRequestId(criticObservation),
                reason
        );
    }

    private String defaultMetricsTarget(CapabilityViewAssembler.CapabilityView capabilityView) {
        if (capabilityView == null || capabilityView.mcpTools() == null) {
            return "prometheus-mcp/queryPrometheusMetrics";
        }
        return capabilityView.mcpTools().stream()
                .filter(entry -> entry != null && entry.id() != null)
                .map(CapabilityViewAssembler.CapabilityEntry::id)
                .filter(id -> id.equalsIgnoreCase("prometheus-mcp/queryPrometheusMetrics")
                        || id.toLowerCase(Locale.ROOT).endsWith("/queryprometheusmetrics"))
                .findFirst()
                .orElse(capabilityView.defaultMcpTarget());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

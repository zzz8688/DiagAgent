package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EvidenceCoverageEvaluator {

    private static final Pattern TOTAL_OBSERVATIONS_PATTERN = Pattern.compile("totalObservations=(\\d+)");
    private static final Pattern SUCCESSFUL_OBSERVATIONS_PATTERN = Pattern.compile("successfulObservations=(\\d+)");
    private static final Pattern FACTUAL_OBSERVATIONS_PATTERN = Pattern.compile("factualObservations=(\\d+)");
    private static final Pattern SUCCESSFUL_FACTUAL_OBSERVATIONS_PATTERN = Pattern.compile("successfulFactualObservations=(\\d+)");
    private static final Pattern PLANNING_OBSERVATIONS_PATTERN = Pattern.compile("planningObservations=(\\d+)");
    private static final Pattern CONTROL_OBSERVATIONS_PATTERN = Pattern.compile("controlObservations=(\\d+)");
    private static final Pattern FACTUAL_SOURCES_PATTERN = Pattern.compile("factualSources=\\[(.*?)]");

    public EvidenceCoverage evaluate(List<ReactObservation> observations,
                                     List<AgentDiagnosisResponse> responses,
                                     ReactTurnState turnState,
                                     EvidenceCoverage previousCoverage) {
        AggregatedCoverageSnapshot previous = previousCoverage == null
                ? parsePreviousCoverage(turnState == null ? null : turnState.lastObservationSummary())
                : AggregatedCoverageSnapshot.from(previousCoverage);
        int currentTotalObservationCount = observations == null ? 0 : observations.size();
        int currentSuccessfulObservationCount = 0;
        int currentFactualObservationCount = 0;
        int currentSuccessfulFactualObservationCount = 0;
        int currentPlanningObservationCount = 0;
        int currentControlObservationCount = 0;
        List<String> missingObservations = new ArrayList<>();
        int responseIndex = 0;
        Set<String> factualSources = new LinkedHashSet<>(previous.factualSources());
        if (observations != null) {
            for (ReactObservation observation : observations) {
                if (observation != null && observation.status() == ReactActionStatus.COMPLETED) {
                    currentSuccessfulObservationCount++;
                }
                ObservationSemantics.ObservationKind kind = ObservationSemantics.classify(observation);
                if (kind == ObservationSemantics.ObservationKind.HISTORICAL_EXPERIENCE) {
                    continue;
                }
                if (kind == ObservationSemantics.ObservationKind.PLANNING) {
                    currentPlanningObservationCount++;
                    continue;
                }
                if (kind == ObservationSemantics.ObservationKind.CONTROL) {
                    currentControlObservationCount++;
                    continue;
                }
                currentFactualObservationCount++;
                AgentDiagnosisResponse response = responses != null && responseIndex < responses.size()
                        ? responses.get(responseIndex)
                        : null;
                responseIndex++;
                boolean failed = observation == null
                        || observation.status() != ReactActionStatus.COMPLETED
                        || isFailedResponse(response);
                if (failed) {
                    missingObservations.add(missingObservationId(observation, response));
                    continue;
                }
                currentSuccessfulFactualObservationCount++;
                String source = observation == null ? null : observation.source();
                if (source != null && !source.isBlank()) {
                    factualSources.add(source.trim());
                }
            }
        }
        int totalObservationCount = previous.totalObservationCount() + currentTotalObservationCount;
        int successfulObservationCount = previous.successfulObservationCount() + currentSuccessfulObservationCount;
        int factualObservationCount = previous.factualObservationCount() + currentFactualObservationCount;
        int successfulFactualObservationCount = previous.successfulFactualObservationCount() + currentSuccessfulFactualObservationCount;
        int planningObservationCount = previous.planningObservationCount() + currentPlanningObservationCount;
        int controlObservationCount = previous.controlObservationCount() + currentControlObservationCount;
        boolean pendingState = turnState != null
                && (turnState.pendingApproval()
                || (turnState.pendingRemoteEventCursor() != null && !turnState.pendingRemoteEventCursor().isBlank()));
        boolean lacksFactualEvidence = successfulFactualObservationCount == 0;
        boolean sourceDiversitySatisfied = factualSources.size() >= 2 || successfulFactualObservationCount >= 3;
        boolean evidenceSufficient = successfulFactualObservationCount >= 2
                && sourceDiversitySatisfied
                && missingObservations.isEmpty()
                && !pendingState;
        int pendingActionCount = missingObservations.size()
                + (pendingState ? 1 : 0)
                + (evidenceSufficient ? 0 : 1)
                + (lacksFactualEvidence ? 1 : 0);
        String summary = "totalObservations=" + totalObservationCount
                + ", successfulObservations=" + successfulObservationCount
                + ", factualObservations=" + factualObservationCount
                + ", successfulFactualObservations=" + successfulFactualObservationCount
                + ", distinctFactualSources=" + factualSources.size()
                + ", planningObservations=" + planningObservationCount
                + ", controlObservations=" + controlObservationCount
                + ", pendingState=" + pendingState
                + ", lacksFactualEvidence=" + lacksFactualEvidence
                + ", factualSources=" + factualSources
                + ", missingObservations=" + missingObservations;
        return new EvidenceCoverage(
                evidenceSufficient,
                pendingState,
                pendingActionCount,
                totalObservationCount,
                successfulObservationCount,
                factualObservationCount,
                successfulFactualObservationCount,
                factualSources.size(),
                planningObservationCount,
                controlObservationCount,
                List.copyOf(factualSources),
                missingObservations,
                summary
        );
    }

    private AggregatedCoverageSnapshot parsePreviousCoverage(String summary) {
        if (summary == null || summary.isBlank()) {
            return AggregatedCoverageSnapshot.empty();
        }
        return new AggregatedCoverageSnapshot(
                parseInt(summary, TOTAL_OBSERVATIONS_PATTERN),
                parseInt(summary, SUCCESSFUL_OBSERVATIONS_PATTERN),
                parseInt(summary, FACTUAL_OBSERVATIONS_PATTERN),
                parseInt(summary, SUCCESSFUL_FACTUAL_OBSERVATIONS_PATTERN),
                parseInt(summary, PLANNING_OBSERVATIONS_PATTERN),
                parseInt(summary, CONTROL_OBSERVATIONS_PATTERN),
                parseFactualSources(summary)
        );
    }

    private int parseInt(String summary, Pattern pattern) {
        Matcher matcher = pattern.matcher(summary);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<String> parseFactualSources(String summary) {
        Matcher matcher = FACTUAL_SOURCES_PATTERN.matcher(summary == null ? "" : summary);
        if (!matcher.find()) {
            return List.of();
        }
        String group = matcher.group(1);
        if (group == null || group.isBlank()) {
            return List.of();
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String item : group.split(",")) {
            String normalized = item == null ? "" : item.trim();
            if (!normalized.isBlank()) {
                sources.add(normalized);
            }
        }
        return List.copyOf(sources);
    }

    private boolean isFailedResponse(AgentDiagnosisResponse response) {
        return response == null
                || response.getConclusion() == null
                || response.getConclusion().contains("执行失败")
                || response.getConclusion().contains("解析失败");
    }

    private String missingObservationId(ReactObservation observation, AgentDiagnosisResponse response) {
        if (response != null && response.getAgent() != null && !response.getAgent().isBlank()) {
            return response.getAgent();
        }
        if (observation != null && observation.source() != null && !observation.source().isBlank()) {
            return observation.source();
        }
        return "unknown";
    }

    private record AggregatedCoverageSnapshot(int totalObservationCount,
                                              int successfulObservationCount,
                                              int factualObservationCount,
                                              int successfulFactualObservationCount,
                                              int planningObservationCount,
                                              int controlObservationCount,
                                              List<String> factualSources) {

        private static AggregatedCoverageSnapshot empty() {
            return new AggregatedCoverageSnapshot(0, 0, 0, 0, 0, 0, List.of());
        }

        private static AggregatedCoverageSnapshot from(EvidenceCoverage coverage) {
            if (coverage == null) {
                return empty();
            }
            return new AggregatedCoverageSnapshot(
                    coverage.totalObservationCount(),
                    coverage.successfulObservationCount(),
                    coverage.factualObservationCount(),
                    coverage.successfulFactualObservationCount(),
                    coverage.planningObservationCount(),
                    coverage.controlObservationCount(),
                    coverage.factualSources()
            );
        }
    }
}

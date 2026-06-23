package io.github.zzz8688.diagagent.agent.runtime;

import java.util.List;

record ActionPlanningContext(
        CapabilityViewAssembler.CapabilityView capabilityView,
        String userQuestion,
        ReactTurnState turnState,
        EvidenceCoverage evidenceCoverage,
        List<ReactObservation> recentObservations
) {

    EvidenceCoverage coverage() {
        return evidenceCoverage == null
                ? new EvidenceCoverage(false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), "")
                : evidenceCoverage;
    }

    String criticObservation() {
        return turnState == null ? null : turnState.finalAnswerCriticism();
    }

    int criticMissingEvidenceCount() {
        String criticism = criticObservation();
        if (criticism == null || criticism.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : criticism.split("\n")) {
            String normalized = line == null ? "" : line.trim();
            if (normalized.startsWith("- missingEvidence:")) {
                count++;
            }
        }
        return count;
    }

    int minimumFactualObservationsForFinalAfterCritic() {
        int criticMissing = criticMissingEvidenceCount();
        if (criticMissing <= 0) {
            return 2;
        }
        return Math.min(3, Math.max(2, criticMissing));
    }

    boolean hasFreshSuccessfulFactualObservation() {
        if (recentObservations == null || recentObservations.isEmpty()) {
            return false;
        }
        return recentObservations.stream()
                .filter(observation -> observation != null && observation.status() == ReactActionStatus.COMPLETED)
                .anyMatch(ObservationSemantics::isFactual);
    }

    String latestCompletedObservationSource() {
        if (recentObservations == null || recentObservations.isEmpty()) {
            return null;
        }
        for (int i = recentObservations.size() - 1; i >= 0; i--) {
            ReactObservation observation = recentObservations.get(i);
            if (observation == null || observation.status() != ReactActionStatus.COMPLETED) {
                continue;
            }
            if (observation.source() != null && !observation.source().isBlank()) {
                return observation.source().trim();
            }
        }
        return null;
    }

    String latestSuccessfulFactualSource() {
        if (recentObservations == null || recentObservations.isEmpty()) {
            List<String> factualSources = coverage().factualSources();
            return factualSources.isEmpty() ? null : factualSources.get(factualSources.size() - 1);
        }
        for (int i = recentObservations.size() - 1; i >= 0; i--) {
            ReactObservation observation = recentObservations.get(i);
            if (observation == null || observation.status() != ReactActionStatus.COMPLETED) {
                continue;
            }
            if (!ObservationSemantics.isFactual(observation)) {
                continue;
            }
            if (observation.source() != null && !observation.source().isBlank()) {
                return observation.source().trim();
            }
        }
        List<String> factualSources = coverage().factualSources();
        return factualSources.isEmpty() ? null : factualSources.get(factualSources.size() - 1);
    }
}

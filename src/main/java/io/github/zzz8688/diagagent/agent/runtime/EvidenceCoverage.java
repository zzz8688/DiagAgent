package io.github.zzz8688.diagagent.agent.runtime;

import java.util.List;

public record EvidenceCoverage(
        boolean evidenceSufficient,
        boolean pendingState,
        int pendingActionCount,
        int totalObservationCount,
        int successfulObservationCount,
        int factualObservationCount,
        int successfulFactualObservationCount,
        int distinctFactualSourceCount,
        int planningObservationCount,
        int controlObservationCount,
        List<String> factualSources,
        List<String> missingObservations,
        String summary
) {

    public boolean hasFactualObservation() {
        return factualObservationCount > 0;
    }

    public boolean hasSuccessfulFactualObservation() {
        return successfulFactualObservationCount > 0;
    }

    public boolean planningOnly() {
        return planningObservationCount > 0
                && factualObservationCount == 0
                && controlObservationCount == 0;
    }

    public boolean hasSourceDiversity() {
        return distinctFactualSourceCount >= 2 || successfulFactualObservationCount >= 3;
    }
}

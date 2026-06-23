package io.github.zzz8688.diagagent.agent.runtime;

import org.springframework.stereotype.Component;

@Component
public class TurnStatePromptProjectionAssembler {

    public SessionAssembler.TurnStatePromptProjection assemble(ReactTurnState turnState,
                                                               boolean readOnly,
                                                               EvidenceCoverage evidenceCoverage,
                                                               String promptMode) {
        String evidenceSummary = buildEvidenceSummary(turnState, evidenceCoverage);
        boolean evidenceSufficient = evidenceCoverage != null
                ? evidenceCoverage.evidenceSufficient()
                : turnState != null && turnState.evidenceSufficient();
        int pendingActionCount = evidenceCoverage != null
                ? evidenceCoverage.pendingActionCount()
                : turnState == null ? 0 : turnState.pendingActionCount();
        return SessionAssembler.TurnStatePromptProjection.fromTurnState(
                turnState,
                readOnly,
                evidenceSummary,
                evidenceSufficient,
                pendingActionCount,
                promptMode
        );
    }

    private String buildEvidenceSummary(ReactTurnState turnState, EvidenceCoverage evidenceCoverage) {
        if (evidenceCoverage != null && evidenceCoverage.summary() != null && !evidenceCoverage.summary().isBlank()) {
            return evidenceCoverage.summary().trim();
        }
        if (turnState == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendSegment(builder, "lastObservation", turnState.lastObservationSummary());
        appendSegment(builder, "laneGuidance", turnState.laneGuidanceSummary());
        appendSegment(builder, "pendingApproval", Boolean.toString(turnState.pendingApproval()));
        appendSegment(builder, "pendingRemoteEventCursor", turnState.pendingRemoteEventCursor());
        appendSegment(builder, "budgetStatus", turnState.budgetStatus() == null ? null : turnState.budgetStatus().name());
        appendSegment(builder, "compactionLevel", turnState.compactionLevel());
        appendSegment(builder, "evidenceSufficient", Boolean.toString(turnState.evidenceSufficient()));
        appendSegment(builder, "pendingActionCount", Integer.toString(turnState.pendingActionCount()));
        return builder.toString();
    }

    private void appendSegment(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(key).append("=").append(value.trim());
    }
}

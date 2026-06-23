package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import reactor.core.publisher.Flux;

import java.util.List;

public record ReactTurnResult(
        Outcome outcome,
        ReactTurnState turnState,
        List<ReactAction> actions,
        List<ReactObservation> observations,
        EvidenceCoverage evidenceCoverage,
        DiagnosisConclusion conclusion,
        Flux<String> streamResult
) {

    public ReactTurnResult {
        outcome = outcome == null ? Outcome.AWAITING_ACTION_SELECTION : outcome;
        actions = actions == null ? List.of() : List.copyOf(actions);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public static ReactTurnResult awaitingActionSelection(ReactTurnState turnState) {
        return new ReactTurnResult(Outcome.AWAITING_ACTION_SELECTION, turnState, List.of(), List.of(), null, null, null);
    }

    public static ReactTurnResult continueWithActions(ReactTurnState turnState, List<ReactAction> actions) {
        return new ReactTurnResult(Outcome.ACTIONS_SELECTED, turnState, actions, List.of(), null, null, null);
    }

    public static ReactTurnResult continueWithObservations(ReactTurnState turnState,
                                                           List<ReactObservation> observations,
                                                           EvidenceCoverage evidenceCoverage) {
        return new ReactTurnResult(Outcome.OBSERVATIONS_READY, turnState, List.of(), observations, evidenceCoverage, null, null);
    }

    public static ReactTurnResult complete(ReactTurnState turnState, DiagnosisConclusion conclusion) {
        return new ReactTurnResult(Outcome.COMPLETED, turnState, List.of(), List.of(), null, conclusion, null);
    }

    public static ReactTurnResult completeStream(ReactTurnState turnState, Flux<String> streamResult) {
        return new ReactTurnResult(Outcome.COMPLETED, turnState, List.of(), List.of(), null, null, streamResult);
    }

    public boolean completed() {
        return outcome == Outcome.COMPLETED;
    }

    public enum Outcome {
        AWAITING_ACTION_SELECTION,
        ACTIONS_SELECTED,
        OBSERVATIONS_READY,
        COMPLETED
    }
}

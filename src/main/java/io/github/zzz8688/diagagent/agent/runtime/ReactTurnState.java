package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Objects;

public record ReactTurnState(
        String taskId,
        String sessionId,
        int turnIndex,
        int maxTurns,
        boolean continueAllowed,
        StopReason stopReason,
        int maxBudgetTokens,
        int usedBudgetTokens,
        TokenBudgetManager.BudgetStatus budgetStatus,
        String compactionLevel,
        String capabilityViewVersion,
        String lastAction,
        String lastObservationSummary,
        String laneGuidanceSummary,
        boolean pendingApproval,
        String pendingRemoteEventCursor,
        boolean evidenceSufficient,
        int pendingActionCount,
        boolean minimalContextMode,
        String finalAnswerDraft,
        String finalAnswerCriticism
) {

    public ReactTurnState {
        stopReason = stopReason == null ? StopReason.NONE : stopReason;
        budgetStatus = budgetStatus == null ? TokenBudgetManager.BudgetStatus.NORMAL : budgetStatus;
        compactionLevel = normalize(compactionLevel, "none");
        capabilityViewVersion = capabilityViewVersion == null ? null : capabilityViewVersion.trim();
        lastAction = normalizeNullable(lastAction);
        lastObservationSummary = normalizeNullable(lastObservationSummary);
        laneGuidanceSummary = normalizeNullable(laneGuidanceSummary);
        pendingRemoteEventCursor = normalizeNullable(pendingRemoteEventCursor);
        pendingActionCount = Math.max(0, pendingActionCount);
        finalAnswerDraft = normalizeNullable(finalAnswerDraft);
        finalAnswerCriticism = normalizeNullable(finalAnswerCriticism);
    }

    public static ReactTurnState start(String sessionId, int maxTurns, int maxBudgetTokens) {
        return new ReactTurnState(
                null,
                normalizeNullable(sessionId),
                0,
                Math.max(1, maxTurns),
                true,
                StopReason.NONE,
                Math.max(1, maxBudgetTokens),
                0,
                TokenBudgetManager.BudgetStatus.NORMAL,
                "none",
                null,
                null,
                null,
                null,
                false,
                null,
                false,
                0,
                false,
                null,
                null
        );
    }

    public ReactTurnState nextTurn() {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex + 1,
                maxTurns,
                continueAllowed && turnIndex + 1 < maxTurns,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withTaskId(String newTaskId) {
        return new ReactTurnState(
                normalizeNullable(newTaskId),
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withCapabilityViewVersion(String version) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                version,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withAction(String action) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                action,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withObservation(String observationSummary) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                observationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withBudget(TokenBudgetManager.BudgetSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed && !snapshot.requiresHardStop(),
                snapshot.requiresHardStop() ? StopReason.TOKEN_BUDGET_EXHAUSTED : stopReason,
                snapshot.maxBudgetTokens(),
                snapshot.usedTokens(),
                snapshot.status(),
                snapshot.compactionLevel(),
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                snapshot.requiresMinimalContextMode(),
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withLaneGuidance(String laneGuidance) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidance,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState waitingApproval() {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                false,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                true,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withPendingRemoteEventCursor(String cursor) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                normalizeNullable(cursor),
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withLoopState(boolean evidenceReady, int pendingActions) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceReady,
                pendingActions,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withFinalAnswerDraft(String answer) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                answer,
                finalAnswerCriticism
        );
    }

    public ReactTurnState withFinalAnswerCriticism(String criticism) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                criticism
        );
    }

    public ReactTurnState withFinalAnswerReview(String answer, String criticism) {
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                continueAllowed,
                stopReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                answer,
                criticism
        );
    }

    public ReactTurnState stop(StopReason reason) {
        StopReason safeReason = reason == null ? StopReason.NO_FURTHER_ACTION : reason;
        return new ReactTurnState(
                taskId,
                sessionId,
                turnIndex,
                maxTurns,
                false,
                safeReason,
                maxBudgetTokens,
                usedBudgetTokens,
                budgetStatus,
                compactionLevel,
                capabilityViewVersion,
                lastAction,
                lastObservationSummary,
                laneGuidanceSummary,
                pendingApproval,
                pendingRemoteEventCursor,
                evidenceSufficient,
                pendingActionCount,
                minimalContextMode,
                finalAnswerDraft,
                finalAnswerCriticism
        );
    }

    private static String normalize(String value, String fallback) {
        String normalized = normalizeNullable(value);
        return normalized == null ? fallback : normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

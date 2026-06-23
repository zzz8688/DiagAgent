package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAssembler {

    private final TokenBudgetManager tokenBudgetManager;
    private final McpPromptInjectionAssembler mcpPromptInjectionAssembler;
    private final TurnStatePromptProjectionAssembler turnStatePromptProjectionAssembler;
    private final ReplayPromptProjectionLoader replayPromptProjectionLoader;
    private final KnowledgePromptProjectionLoader knowledgePromptProjectionLoader;
    private final CapabilitySnapshotLoader capabilitySnapshotLoader;

    public CapabilitySnapshot loadCapabilitySnapshot(boolean readOnly) {
        return capabilitySnapshotLoader.load(readOnly);
    }

    public ReactTurnState initializeTurnState(String userQuestion,
                                              String sessionId,
                                              boolean readOnly,
                                              int maxTurns,
                                              int maxBudgetTokens) {
        return initializeTurnState(
                userQuestion,
                sessionId,
                readOnly,
                maxTurns,
                maxBudgetTokens,
                capabilitySnapshotLoader.load(readOnly)
        );
    }

    public ReactTurnState initializeTurnState(String userQuestion,
                                              String sessionId,
                                              boolean readOnly,
                                              int maxTurns,
                                              int maxBudgetTokens,
                                              CapabilitySnapshot capabilitySnapshot) {
        CapabilitySnapshot resolvedSnapshot = capabilitySnapshot == null
                ? capabilitySnapshotLoader.load(readOnly)
                : capabilitySnapshot;
        CapabilityViewAssembler.CapabilityView capabilityView = resolvedSnapshot.capabilityView();
        TokenBudgetManager.BudgetSnapshot budgetSnapshot = tokenBudgetManager.evaluateTexts(
                List.of(
                        safeText(userQuestion),
                        mcpPromptInjectionAssembler.renderPromptSection(),
                        resolvedSnapshot.capabilitySnapshotSummary()
                ),
                maxBudgetTokens
        );
        ReactTurnState turnState = ReactTurnState.start(sessionId, maxTurns, maxBudgetTokens)
                .withCapabilityViewVersion(capabilityView.viewVersion())
                .withObservation("mcpPromptInjections="
                        + mcpPromptInjectionAssembler.listVisiblePromptInjections().size()
                        + "; capabilityEntries="
                        + capabilityView.totalEntryCount())
                .withBudget(budgetSnapshot);
        if (budgetSnapshot.requiresHardStop()) {
            log.warn("初始 token budget 已触发硬停止: sessionId={}, usedTokens={}, maxTokens={}",
                    sessionId, budgetSnapshot.usedTokens(), budgetSnapshot.maxBudgetTokens());
            return turnState.stop(StopReason.TOKEN_BUDGET_EXHAUSTED);
        }
        return turnState;
    }

    public PromptAssembly buildPromptAssembly(ReactTurnState turnState,
                                              boolean readOnly,
                                              EvidenceCoverage evidenceCoverage,
                                              String promptMode,
                                              String userQuestion) {
        TurnStatePromptProjection turnStateProjection = turnStatePromptProjectionAssembler.assemble(
                turnState,
                readOnly,
                evidenceCoverage,
                promptMode
        );
        ReplayPromptProjection replayProjection = replayPromptProjectionLoader.load(turnState);
        KnowledgePromptProjection knowledgeProjection = knowledgePromptProjectionLoader.load(
                userQuestion,
                turnState,
                evidenceCoverage,
                turnStateProjection.evidenceSummary()
        );
        CapabilitySnapshot capabilitySnapshot = capabilitySnapshotLoader.load(readOnly);
        MainAgentPromptAssembler.MainAgentPromptContext promptContext =
                MainAgentPromptAssembler.MainAgentPromptContext.fromAssemblySources(
                        turnStateProjection,
                        knowledgeProjection,
                        replayProjection,
                        capabilitySnapshot
                );
        return new PromptAssembly(
                promptContext,
                turnStateProjection,
                replayProjection,
                knowledgeProjection,
                capabilitySnapshot
        );
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    public record PromptAssembly(
            MainAgentPromptAssembler.MainAgentPromptContext promptContext,
            TurnStatePromptProjection turnStateProjection,
            ReplayPromptProjection replayProjection,
            KnowledgePromptProjection knowledgeProjection,
            CapabilitySnapshot capabilitySnapshot
    ) {
    }

    public record TurnStatePromptProjection(
            String taskId,
            String sessionId,
            int turnIndex,
            int maxTurns,
            String capabilityViewVersion,
            String lastAction,
            String lastObservationSummary,
            String laneGuidanceSummary,
            boolean pendingApproval,
            String pendingRemoteEventCursor,
            boolean minimalContextMode,
            boolean readOnly,
            boolean evidenceSufficient,
            int pendingActionCount,
            String evidenceSummary,
            String promptMode,
            int maxBudgetTokens,
            int usedBudgetTokens,
            String budgetStatus,
            String compactionLevel,
            String finalAnswerDraft,
            String finalAnswerCriticism
    ) {
        static TurnStatePromptProjection fromTurnState(ReactTurnState turnState,
                                                       boolean readOnly,
                                                       String evidenceSummary,
                                                       boolean evidenceSufficient,
                                                       int pendingActionCount,
                                                       String promptMode) {
            if (turnState == null) {
                return new TurnStatePromptProjection(
                        null,
                        null,
                        0,
                        0,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        false,
                        readOnly,
                        evidenceSufficient,
                        Math.max(0, pendingActionCount),
                        safe(evidenceSummary),
                        promptMode,
                        0,
                        0,
                        TokenBudgetManager.BudgetStatus.NORMAL.name(),
                        "none",
                        null,
                        null
                );
            }
            return new TurnStatePromptProjection(
                    turnState.taskId(),
                    turnState.sessionId(),
                    turnState.turnIndex(),
                    turnState.maxTurns(),
                    turnState.capabilityViewVersion(),
                    turnState.lastAction(),
                    turnState.lastObservationSummary(),
                    turnState.laneGuidanceSummary(),
                    turnState.pendingApproval(),
                    turnState.pendingRemoteEventCursor(),
                    turnState.minimalContextMode(),
                    readOnly,
                    evidenceSufficient,
                    Math.max(0, pendingActionCount),
                    safe(evidenceSummary),
                    promptMode,
                    turnState.maxBudgetTokens(),
                    turnState.usedBudgetTokens(),
                    turnState.budgetStatus() == null ? TokenBudgetManager.BudgetStatus.NORMAL.name() : turnState.budgetStatus().name(),
                    turnState.compactionLevel(),
                    turnState.finalAnswerDraft(),
                    turnState.finalAnswerCriticism()
            );
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    public record ReplayPromptProjection(
            String runtimeReplaySummary,
            String eventReplaySummary,
            String approvalReplaySummary
    ) {
    }

    public record KnowledgePromptProjection(
            String memorySummary,
            String ragEvidenceSummary
    ) {
    }

    public record CapabilitySnapshot(
            CapabilityViewAssembler.CapabilityView capabilityView,
            String capabilitySnapshotSummary,
            CapabilityViewAssembler.CapabilityManifestOverview overview
    ) {
    }
}

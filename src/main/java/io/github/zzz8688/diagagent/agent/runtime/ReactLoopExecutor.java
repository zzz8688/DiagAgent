package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;
import io.github.zzz8688.diagagent.config.SessionContext;
import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import io.github.zzz8688.diagagent.service.NotificationService;
import io.github.zzz8688.diagagent.service.StopGenerateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ReactLoopExecutor {

    private static final double LOW_CONFIDENCE_NOTIFICATION_THRESHOLD = 0.72;

    private static final Logger log = LoggerFactory.getLogger(ReactLoopExecutor.class);

    private final MainAgentPromptAssembler mainAgentPromptAssembler;
    private final MainAgentActionPlanningService mainAgentActionPlanningService;
    private final CapabilityViewAssembler capabilityViewAssembler;
    private final ReactActionExecutor reactActionExecutor;
    private final ObservationNormalizer observationNormalizer;
    private final EvidenceCoverageEvaluator evidenceCoverageEvaluator;
    private final MainAgentFinalAnswerRuntime mainAgentFinalAnswerRuntime;
    private final SessionAssembler sessionAssembler;
    private final StopGenerateService stopGenerateService;
    private final NotificationService notificationService;
    private final List<StopHook> stopHooks;
    private final ExecutionEventBus executionEventBus;
    private final TaskManager taskManager;

    public DiagnosisConclusion execute(String userQuestion,
                                       String sessionId,
                                       boolean readOnly,
                                       ReactTurnState initialTurnState) {
        ReactTurnState guardedInitialTurnState = applyLoopGuards(initialTurnState);
        LoopRuntime runtime = LoopRuntime.start(userQuestion, sessionId, readOnly, guardedInitialTurnState, false);
        while (runtime.turnState().continueAllowed()) {
            runtime = advance(runtime);
            if (runtime.turnResult().completed()) {
                return runtime.conclusion();
            }
        }
        publishStopReason(runtime.turnState());
        return stopConclusion(runtime.turnState());
    }

    public Flux<String> executeStream(String userQuestion,
                                      String sessionId,
                                      boolean readOnly,
                                      ReactTurnState initialTurnState) {
        ReactTurnState guardedInitialTurnState = applyLoopGuards(initialTurnState);
        LoopRuntime runtime = LoopRuntime.start(userQuestion, sessionId, readOnly, guardedInitialTurnState, true);
        while (runtime.turnState().continueAllowed()) {
            runtime = advance(runtime);
            if (runtime.turnResult().completed()) {
                if (runtime.streamResult() != null) {
                    return runtime.streamResult();
                }
                if (runtime.conclusion() != null) {
                    return Flux.just(runtime.conclusion().getFinalConclusion());
                }
                return Flux.just("[STOPPED]");
            }
        }
        publishStopReason(runtime.turnState());
        return Flux.just(stopConclusion(runtime.turnState()).getFinalConclusion());
    }

    private LoopRuntime advance(LoopRuntime runtime) {
        LoopRuntime interruptedRuntime = applyInterruptLane(runtime);
        if (interruptedRuntime != null) {
            return interruptedRuntime;
        }
        return switch (runtime.turnResult().outcome()) {
            case AWAITING_ACTION_SELECTION -> routeTurn(runtime);
            case ACTIONS_SELECTED -> dispatchTurn(runtime);
            case OBSERVATIONS_READY -> finalizeTurn(runtime);
            case COMPLETED -> runtime;
        };
    }

    private LoopRuntime routeTurn(LoopRuntime runtime) {
        ReactTurnState laneAdjustedTurn = applyLaneDirectivesForSelection(runtime);
        String actionName = runtime.streaming() ? "select-actions-stream" : "select-actions";
        ReactTurnState currentTurn = laneAdjustedTurn.withAction(actionName);
        SessionAssembler.PromptAssembly promptAssembly = sessionAssembler.buildPromptAssembly(
                currentTurn,
                runtime.readOnly(),
                runtime.evidenceCoverage(),
                "action-selection",
                runtime.userQuestion()
        );
        MainAgentPromptAssembler.MainAgentPromptContext promptContext = promptAssembly.promptContext();
        CapabilityViewAssembler.CapabilityView capabilityView = promptAssembly.capabilitySnapshot().capabilityView();
        MainAgentPromptAssembler.RenderedPrompt renderedPrompt = mainAgentPromptAssembler.buildActionSelectionPrompt(
                runtime.userQuestion(),
                promptContext
        );
        ReactAction plannedAction = mainAgentActionPlanningService.planNextAction(
                renderedPrompt,
                capabilityView,
                runtime.userQuestion(),
                currentTurn,
                runtime.evidenceCoverage(),
                runtime.observations(),
                currentTurn.taskId(),
                runtime.sessionId(),
                runtime.readOnly(),
                currentTurn.turnIndex()
        );
        List<ReactAction> actions = plannedAction == null ? List.of() : List.of(plannedAction);
        publishActionSelected(runtime, currentTurn, plannedAction);
        log.info("动作选择完成，selectedActions={}", actions.stream().map(ReactAction::target).toList());
        if (actions.isEmpty()) {
            ReactTurnState finalTurnState = applyStopHooks(currentTurn.stop(StopReason.NO_FURTHER_ACTION));
            DiagnosisConclusion conclusion = new DiagnosisConclusion(
                    0.0,
                    true,
                    "### 诊断结论\n\n当前主链没有形成可继续推进的有效动作，因此未能收敛出可交付的最终答案。"
            );
            return runtime.completeWith(finalTurnState, ReactTurnResult.complete(finalTurnState, conclusion));
        }
        if (plannedAction.actionType() == ReactActionType.RETURN_FINAL) {
            ReactTurnState nextTurn = applyLoopGuards(currentTurn
                    .withAction("finalize-answer")
                    .withObservation("selectedAction=RETURN_FINAL"));
            return runtime.withTurnState(nextTurn)
                    .withActions(List.of())
                    .withTurnResult(ReactTurnResult.continueWithObservations(
                            nextTurn,
                            List.of(),
                            runtime.evidenceCoverage()
                    ));
        }
        ReactTurnState nextTurn = applyLoopGuards(currentTurn
                .withAction("execute-actions")
                .withObservation("selectedActions=" + actions.size()));
        return runtime.withTurnState(nextTurn)
                .withActions(actions)
                .withTurnResult(ReactTurnResult.continueWithActions(nextTurn, actions));
    }

    private LoopRuntime dispatchTurn(LoopRuntime runtime) {
        List<ReactActionExecutionResult> actionResults;
        SessionContext.setExecutionContext(runtime.sessionId(), runtime.turnState() == null ? null : runtime.turnState().taskId());
        try {
            actionResults = reactActionExecutor.execute(
                    runtime.actions(),
                    runtime.userQuestion(),
                    runtime.sessionId(),
                    runtime.turnState().taskId(),
                    runtime.readOnly()
            );
        } finally {
            SessionContext.clear();
        }
        if (actionResults.isEmpty() && !stopGenerateService.shouldContinue(runtime.sessionId())) {
            ReactTurnState finalTurnState = applyStopHooks(runtime.turnState().stop(StopReason.USER_CANCELLED));
            publishStopReason(finalTurnState);
            return runtime.completeWith(finalTurnState,
                    ReactTurnResult.complete(finalTurnState, new DiagnosisConclusion(0.0, false, "")));
        }
        List<ReactObservation> observations = observationNormalizer.normalizeActionResults(actionResults);
        List<AgentDiagnosisResponse> responses = observationNormalizer.toDiagnosisResponses(observations);
        publishObservations(runtime, observations);

        EvidenceCoverage evidenceCoverage = evidenceCoverageEvaluator.evaluate(
                observations,
                responses,
                runtime.turnState(),
                runtime.evidenceCoverage()
        );
        log.info("动作执行完成，observations={}, evidenceCoverage={}", observations.size(), evidenceCoverage.summary());
        if (!observations.isEmpty()) {
            log.info("本轮 observation 明细: {}", summarizeObservationDetails(observations));
        }
        ReactTurnState nextTurn = applyLoopGuards(runtime.turnState().nextTurn()
                .withAction("select-actions-after-observation")
                .withObservation(observationNormalizer.summarizeObservations(observations) + "; " + evidenceCoverage.summary())
                .withLoopState(evidenceCoverage.evidenceSufficient(), evidenceCoverage.pendingActionCount()));
        return runtime.withActions(List.of())
                .withObservations(observations)
                .withResponses(responses)
                .withEvidenceCoverage(evidenceCoverage)
                .withTurnState(nextTurn)
                .withTurnResult(ReactTurnResult.awaitingActionSelection(nextTurn));
    }

    private void publishActionSelected(LoopRuntime runtime,
                                       ReactTurnState currentTurn,
                                       ReactAction plannedAction) {
        if (runtime == null || runtime.turnState() == null || plannedAction == null) {
            return;
        }
        executionEventBus.publish(
                ExecutionEventType.REACT_ACTION_SELECTED,
                runtime.turnState().taskId(),
                runtime.sessionId(),
                "react-loop-executor",
                Map.of(
                        "turnIndex", Integer.toString(currentTurn.turnIndex()),
                        "actionId", safe(plannedAction.actionId()),
                        "actionType", plannedAction.actionType().name(),
                        "target", safe(plannedAction.target()),
                        "reason", safe(plannedAction.reason())
                )
        );
    }

    private void publishObservations(LoopRuntime runtime, List<ReactObservation> observations) {
        if (runtime == null || runtime.turnState() == null || observations == null) {
            return;
        }
        for (ReactObservation observation : observations) {
            executionEventBus.publish(
                    ExecutionEventType.REACT_OBSERVATION_RECEIVED,
                    runtime.turnState().taskId(),
                    runtime.sessionId(),
                    "react-loop-executor",
                    Map.of(
                            "turnIndex", Integer.toString(runtime.turnState().turnIndex()),
                            "actionId", safe(observation == null ? null : observation.actionId()),
                            "source", safe(observation == null ? null : observation.source()),
                            "status", observation == null || observation.status() == null ? "" : observation.status().name(),
                            "summary", safe(observation == null ? null : observation.summary())
                    )
            );
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String summarizeObservationDetails(List<ReactObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return "[]";
        }
        Map<ObservationSemantics.ObservationKind, List<String>> grouped = new EnumMap<>(ObservationSemantics.ObservationKind.class);
        for (ObservationSemantics.ObservationKind kind : ObservationSemantics.ObservationKind.values()) {
            grouped.put(kind, new ArrayList<>());
        }
        for (ReactObservation observation : observations) {
            ObservationSemantics.ObservationKind kind = ObservationSemantics.classify(observation);
            grouped.computeIfAbsent(kind, ignored -> new ArrayList<>())
                    .add(summarizeObservation(observation));
        }
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> entry.getKey().name() + "[" + entry.getValue().size() + "]: "
                        + String.join(" || ", entry.getValue()))
                .reduce((left, right) -> left + " ### " + right)
                .orElse("[]");
    }

    private String summarizeObservation(ReactObservation observation) {
        if (observation == null) {
            return "unknown";
        }
        return "source=%s, type=%s, status=%s, summary=%s".formatted(
                safe(observation.source()),
                observation.actionType() == null ? "" : observation.actionType().name(),
                observation.status() == null ? "" : observation.status().name(),
                clip(observation.summary(), 220)
        );
    }

    private String clip(String value, int maxLength) {
        String normalized = safe(value).replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= Math.max(0, maxLength)) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)) + "...";
    }

    private LoopRuntime finalizeTurn(LoopRuntime runtime) {
        MainAgentFinalAnswerRuntime.FinalAnswerReview review = mainAgentFinalAnswerRuntime.reviewFinalAnswer(
                runtime.userQuestion(),
                runtime.responses(),
                runtime.sessionId(),
                runtime.turnState(),
                runtime.evidenceCoverage()
        );
        if (!review.completed()) {
            if (!stopGenerateService.shouldContinue(runtime.sessionId())) {
                ReactTurnState finalTurnState = applyStopHooks(runtime.turnState().stop(StopReason.USER_CANCELLED));
                publishStopReason(finalTurnState);
                return runtime.completeWith(finalTurnState,
                        runtime.streaming()
                                ? ReactTurnResult.completeStream(finalTurnState, Flux.just("[STOPPED]"))
                                : ReactTurnResult.complete(finalTurnState, new DiagnosisConclusion(0.0, false, "")));
            }
            ReactTurnState nextTurn = applyLoopGuards(runtime.turnState().nextTurn()
                    .withAction("select-actions-after-critic")
                    .withObservation(review.criticismObservation())
                    .withFinalAnswerReview(review.finalAnswerDraft(), review.criticismObservation())
                    .withLoopState(runtime.evidenceCoverage() != null && runtime.evidenceCoverage().evidenceSufficient(),
                            runtime.evidenceCoverage() == null ? 0 : runtime.evidenceCoverage().pendingActionCount()));
            return runtime.withTurnState(nextTurn)
                    .withActions(List.of())
                    .withTurnResult(ReactTurnResult.awaitingActionSelection(nextTurn));
        }

        LoopRuntime continuedByLane = continueWithPendingLane(runtime, review);
        if (continuedByLane != null) {
            return continuedByLane;
        }

        DiagnosisConclusion conclusion = review.conclusion();
        ReactTurnState finalTurnState = applyStopHooks(runtime.turnState()
                .withFinalAnswerDraft(conclusion.getFinalConclusion())
                .withFinalAnswerCriticism(null)
                .stop(runtime.evidenceCoverage() != null && runtime.evidenceCoverage().evidenceSufficient()
                        ? StopReason.FINAL_ANSWER_RETURNED
                        : StopReason.INSUFFICIENT_EVIDENCE));
        publishStopReason(finalTurnState);
        maybeNotifyManualIntervention(runtime.sessionId(), conclusion);
        log.info("诊断完成，总体置信度: {}, 是否需要人工介入: {}",
                conclusion.getOverallConfidence(), conclusion.isManualInterventionNeeded());
        if (runtime.streaming()) {
            return runtime.completeWith(finalTurnState, ReactTurnResult.completeStream(
                    finalTurnState,
                    Flux.just(conclusion.getFinalConclusion())
            ));
        }
        return runtime.completeWith(finalTurnState, ReactTurnResult.complete(finalTurnState, conclusion));
    }

    private void maybeNotifyManualIntervention(String sessionId, DiagnosisConclusion conclusion) {
        if (conclusion == null || !conclusion.isManualInterventionNeeded()) {
            return;
        }
        String title = "智能体诊断需要人工介入";
        String content = String.format(
                "sessionId: %s%n是否需要人工介入: %s%n总体置信度: %.2f%n%n最终结论:%n%s",
                safe(sessionId),
                conclusion.isManualInterventionNeeded(),
                conclusion.getOverallConfidence(),
                safe(conclusion.getFinalConclusion())
        );
        String level = conclusion.getOverallConfidence() < 0.5 ? "critical" : "warning";
        notificationService.sendNotificationOnce("manual-intervention:" + safe(sessionId), title, content, level);
    }

    private LoopRuntime continueWithPendingLane(LoopRuntime runtime,
                                                MainAgentFinalAnswerRuntime.FinalAnswerReview review) {
        LaneDirectives pendingDirectives = consumeGuidanceLane(runtime.sessionId());
        if (pendingDirectives.isEmpty()) {
            return null;
        }
        String laneSummary = pendingDirectives.summary();
        ReactTurnState nextTurn = applyLoopGuards(runtime.turnState().nextTurn()
                .withAction("session-lane-continue")
                .withObservation(review.criticismObservation())
                .withLaneGuidance(laneSummary)
                .withFinalAnswerReview(review.finalAnswerDraft(), review.criticismObservation())
                .withLoopState(runtime.evidenceCoverage() != null && runtime.evidenceCoverage().evidenceSufficient(),
                        runtime.evidenceCoverage() == null ? 0 : runtime.evidenceCoverage().pendingActionCount()));
        return runtime.withTurnState(nextTurn)
                .withActions(List.of())
                .withTurnResult(ReactTurnResult.awaitingActionSelection(nextTurn));
    }

    private ReactTurnState applyLaneDirectivesForSelection(LoopRuntime runtime) {
        if (runtime == null || runtime.turnState() == null) {
            return runtime == null ? null : runtime.turnState();
        }
        LaneDirectives directives = consumeGuidanceLane(runtime.sessionId());
        if (directives.isEmpty()) {
            return runtime.turnState();
        }
        return runtime.turnState().withLaneGuidance(directives.summary());
    }

    private LoopRuntime applyInterruptLane(LoopRuntime runtime) {
        if (runtime == null || runtime.turnState() == null) {
            return null;
        }
        LaneDirectives interrupts = consumeInterruptLane(runtime.sessionId());
        if (interrupts.interruptEntries().isEmpty()) {
            return null;
        }
        ReactTurnState finalTurnState = applyStopHooks(runtime.turnState()
                .withLaneGuidance(interrupts.summary())
                .stop(StopReason.USER_CANCELLED));
        publishStopReason(finalTurnState);
        return runtime.completeWith(
                finalTurnState,
                runtime.streaming()
                        ? ReactTurnResult.completeStream(finalTurnState, Flux.just("[STOPPED]"))
                        : ReactTurnResult.complete(finalTurnState, new DiagnosisConclusion(0.0, false, ""))
        );
    }

    private LaneDirectives consumeGuidanceLane(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return LaneDirectives.empty();
        }
        List<TaskManager.SessionLaneEntry> steerEntries = taskManager.consumeLaneEntries(sessionId, TaskManager.SessionLaneType.STEER);
        List<TaskManager.SessionLaneEntry> followupEntries = taskManager.consumeLaneEntries(sessionId, TaskManager.SessionLaneType.FOLLOWUP);
        return new LaneDirectives(followupEntries, steerEntries, List.of());
    }

    private LaneDirectives consumeInterruptLane(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return LaneDirectives.empty();
        }
        List<TaskManager.SessionLaneEntry> interruptEntries = taskManager.consumeLaneEntries(sessionId, TaskManager.SessionLaneType.INTERRUPT);
        return new LaneDirectives(List.of(), List.of(), interruptEntries);
    }

    private ReactTurnState applyLoopGuards(ReactTurnState turnState) {
        ReactTurnState current = turnState;
        if (current != null
                && !current.continueAllowed()
                && current.stopReason() == StopReason.NONE
                && current.turnIndex() >= current.maxTurns() - 1) {
            current = current.stop(StopReason.MAX_TURNS_REACHED);
        }
        return applyStopHooks(current);
    }

    private void publishStopReason(ReactTurnState turnState) {
        if (turnState == null || turnState.taskId() == null || turnState.taskId().isBlank()) {
            return;
        }
        executionEventBus.publish(
                ExecutionEventType.REACT_STOP_REASON_RECORDED,
                turnState.taskId(),
                turnState.sessionId(),
                "react-loop-executor",
                Map.of(
                        "reason", normalizeStopReason(turnState.stopReason()),
                        "turnIndex", Integer.toString(turnState.turnIndex())
                )
        );
    }

    private String normalizeStopReason(StopReason stopReason) {
        return stopReason == null ? StopReason.NONE.name().toLowerCase(java.util.Locale.ROOT)
                : stopReason.name().toLowerCase(java.util.Locale.ROOT);
    }

    private ReactTurnState applyStopHooks(ReactTurnState turnState) {
        ReactTurnState current = turnState;
        for (StopHook stopHook : stopHooks) {
            StopHook.StopHookResult result = stopHook.evaluate(current);
            if (!result.continueAllowed()) {
                log.info("Stop hook 触发终止: hook={}, sessionId={}, reason={}, metadata={}",
                        stopHook.name(), current.sessionId(), result.stopReason(), result.metadata());
                return current.stop(result.stopReason());
            }
        }
        return current;
    }

    private DiagnosisConclusion stopConclusion(ReactTurnState turnState) {
        if (turnState.stopReason() == StopReason.TOKEN_BUDGET_EXHAUSTED) {
            return new DiagnosisConclusion(0.0, false, "上下文预算已耗尽，已进入最小上下文保护并终止当前诊断。");
        }
        if (turnState.stopReason() == StopReason.USER_CANCELLED) {
            return new DiagnosisConclusion(0.0, false, "");
        }
        if (turnState.stopReason() == StopReason.MAX_TURNS_REACHED) {
            return new DiagnosisConclusion(0.0, false, "诊断达到最大轮次限制，当前 loop 已被停止。");
        }
        return new DiagnosisConclusion(0.0, false,
                "诊断在运行时提前结束，stopReason=" + turnState.stopReason().name());
    }

    private record LoopRuntime(
            String userQuestion,
            String sessionId,
            boolean readOnly,
            boolean streaming,
            ReactTurnState turnState,
            List<ReactAction> actions,
            List<ReactObservation> observations,
            List<AgentDiagnosisResponse> responses,
            EvidenceCoverage evidenceCoverage,
            ReactTurnResult turnResult
    ) {
        private static LoopRuntime start(String userQuestion,
                                         String sessionId,
                                         boolean readOnly,
                                         ReactTurnState initialTurnState,
                                         boolean streaming) {
            return new LoopRuntime(
                    userQuestion,
                    sessionId,
                    readOnly,
                    streaming,
                    initialTurnState,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    ReactTurnResult.awaitingActionSelection(initialTurnState)
            );
        }

        private LoopRuntime withTurnState(ReactTurnState newTurnState) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, newTurnState,
                    actions, observations, responses, evidenceCoverage, turnResult);
        }

        private LoopRuntime withActions(List<ReactAction> newActions) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, turnState,
                    newActions, observations, responses, evidenceCoverage, turnResult);
        }

        private LoopRuntime withObservations(List<ReactObservation> newObservations) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, turnState,
                    actions, newObservations, responses, evidenceCoverage, turnResult);
        }

        private LoopRuntime withResponses(List<AgentDiagnosisResponse> newResponses) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, turnState,
                    actions, observations, newResponses, evidenceCoverage, turnResult);
        }

        private LoopRuntime withEvidenceCoverage(EvidenceCoverage newEvidenceCoverage) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, turnState,
                    actions, observations, responses, newEvidenceCoverage, turnResult);
        }

        private LoopRuntime withTurnResult(ReactTurnResult newTurnResult) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, turnState,
                    actions, observations, responses, evidenceCoverage, newTurnResult);
        }

        private LoopRuntime completeWith(ReactTurnState newTurnState, ReactTurnResult newTurnResult) {
            return new LoopRuntime(userQuestion, sessionId, readOnly, streaming, newTurnState,
                    actions, observations, responses, evidenceCoverage, newTurnResult);
        }

        private DiagnosisConclusion conclusion() {
            return turnResult == null ? null : turnResult.conclusion();
        }

        private Flux<String> streamResult() {
            return turnResult == null ? null : turnResult.streamResult();
        }
    }

    private record LaneDirectives(
            List<TaskManager.SessionLaneEntry> followupEntries,
            List<TaskManager.SessionLaneEntry> steerEntries,
            List<TaskManager.SessionLaneEntry> interruptEntries
    ) {
        private static LaneDirectives empty() {
            return new LaneDirectives(List.of(), List.of(), List.of());
        }

        private boolean isEmpty() {
            return followupEntries.isEmpty() && steerEntries.isEmpty() && interruptEntries.isEmpty();
        }

        private String summary() {
            List<String> segments = new ArrayList<>();
            if (!steerEntries.isEmpty()) {
                segments.add("laneSteer=" + summarizeEntries(steerEntries));
            }
            if (!followupEntries.isEmpty()) {
                segments.add("laneFollowup=" + summarizeEntries(followupEntries));
            }
            if (!interruptEntries.isEmpty()) {
                segments.add("laneInterrupt=" + summarizeEntries(interruptEntries));
            }
            return String.join(" | ", segments);
        }

        private String summarizeEntries(List<TaskManager.SessionLaneEntry> entries) {
            return entries.stream()
                    .map(entry -> entry == null ? "" : safe(entry.payloadSummary()))
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + " || " + right)
                    .orElse("");
        }

        private String safe(String value) {
            return value == null ? "" : value;
        }
    }
}

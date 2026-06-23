package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class RuntimeActionGuard {

    private final EvidenceActionPlanner evidenceActionPlanner;
    private final SkillIntentAdvisor skillIntentAdvisor;
    private final DomainEvidenceHeuristics domainEvidenceHeuristics;

    ReactAction apply(ReactAction plannedAction,
                      ActionPlanningContext context,
                      ReactAction fallbackAction) {
        ReactAction action = plannedAction == null ? fallbackAction : plannedAction;
        boolean allowSkillBootstrap = shouldForceSkillBootstrap(context);

        if (allowSkillBootstrap && action.actionType() != ReactActionType.RUN_SKILL_STEP) {
            ReactAction skillFirstAction = buildSkillFirstAction(context);
            if (skillFirstAction != null) {
                return skillFirstAction;
            }
        }

        if (action.actionType() == ReactActionType.RETURN_FINAL
                && (!context.coverage().hasSuccessfulFactualObservation() || !context.coverage().evidenceSufficient())) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: 缺少足够 factual evidence，禁止提前 RETURN_FINAL"
            );
        }

        if (action.actionType() == ReactActionType.RETURN_FINAL
                && context.criticMissingEvidenceCount() > 0
                && context.coverage().successfulFactualObservationCount() < context.minimumFactualObservationsForFinalAfterCritic()) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: critic 已指出明确缺证据，未补到最小事实证据数量前禁止再次 RETURN_FINAL"
            );
        }

        if (hasOutstandingCriticismWithoutFreshEvidence(context) && !isEvidenceGatheringAction(action)) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: critic 打回后必须先补新的 factual evidence"
            );
        }

        if (action.actionType() == ReactActionType.RUN_SKILL_STEP
                && isRepeatPlanningSkill(action, context)
                && !context.coverage().hasSuccessfulFactualObservation()) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: 同一 skill 已完成规划注入但仍无 factual evidence，禁止重复加载，必须转向真实取证"
            );
        }

        if (action.actionType() == ReactActionType.RUN_SKILL_STEP
                && !context.coverage().hasSuccessfulFactualObservation()
                && !context.hasFreshSuccessfulFactualObservation()
                && !allowSkillBootstrap) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: skill 只允许在首个 bootstrap 轮次优先注入，之后必须进入真实事实取证"
            );
        }

        if (isKnowledgeBaseAction(action)
                && !context.coverage().hasSuccessfulFactualObservation()
                && !domainEvidenceHeuristics.isExplicitKnowledgeIntent(context.userQuestion())) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: 常规诊断流程应先拿当前事实证据，再决定是否查询知识库"
            );
        }

        if (isKnowledgeBaseAction(action)
                && ObservationSemantics.isKnowledgeSource(context.latestCompletedObservationSource())
                && !context.hasFreshSuccessfulFactualObservation()) {
            return evidenceActionPlanner.planAlternateEvidenceAction(
                    context,
                    context.latestSuccessfulFactualSource(),
                    "runtime guard: 避免连续重复查询知识库，先回到 logs/metrics/依赖等事实链补证据"
            );
        }

        if (isHistoricalMemoryRecallAction(action) && !context.coverage().hasSuccessfulFactualObservation()) {
            return evidenceActionPlanner.planFallbackEvidenceAction(
                    context,
                    "runtime guard: queryMemoryFiles 只允许在已有部分事实证据但仍未闭环时作为历史经验备选"
            );
        }

        if (isHistoricalMemoryRecallAction(action) && context.hasFreshSuccessfulFactualObservation()) {
            return evidenceActionPlanner.planAlternateEvidenceAction(
                    context,
                    context.latestSuccessfulFactualSource(),
                    "runtime guard: 当前轮刚拿到新的 factual evidence，优先继续补当前事实链，不要立即转向历史经验"
            );
        }

        if (isRepeatFactualTool(action, context.latestSuccessfulFactualSource())) {
            return evidenceActionPlanner.planAlternateEvidenceAction(
                    context,
                    context.latestSuccessfulFactualSource(),
                    "runtime guard: 避免连续重复调用同类事实工具"
            );
        }

        return action;
    }

    private boolean shouldForceSkillBootstrap(ActionPlanningContext context) {
        if (context == null) {
            return false;
        }
        if (context.coverage().hasSuccessfulFactualObservation()) {
            return false;
        }
        if (context.coverage().planningObservationCount() > 0) {
            return false;
        }
        return skillIntentAdvisor.shouldForceSkillFirst(context.userQuestion());
    }

    private ReactAction buildSkillFirstAction(ActionPlanningContext context) {
        String skillId = skillIntentAdvisor.firstAvailableSkill(
                context.capabilityView(),
                context.userQuestion(),
                context.turnState() == null ? "" : context.turnState().lastObservationSummary()
        );
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        return new ReactAction(
                java.util.UUID.randomUUID().toString(),
                ReactActionType.RUN_SKILL_STEP,
                "loadSkillContext",
                skillId,
                java.util.Map.of("skillId", skillId),
                "runtime guard: 当前用户请求直接匹配 skill，先注入对应 skill 的方法上下文",
                false
        );
    }

    private boolean hasOutstandingCriticismWithoutFreshEvidence(ActionPlanningContext context) {
        String criticism = context.criticObservation();
        return criticism != null
                && !criticism.isBlank()
                && !context.hasFreshSuccessfulFactualObservation();
    }

    private boolean isEvidenceGatheringAction(ReactAction action) {
        if (action == null || action.actionType() == null) {
            return false;
        }
        if (isHistoricalMemoryRecallAction(action)) {
            return false;
        }
        return switch (action.actionType()) {
            case CALL_LOCAL_TOOL, CALL_MCP_TOOL, CALL_LOCAL_SPECIALIST, CALL_REMOTE_SPECIALIST -> true;
            case QUERY_KNOWLEDGE, RUN_SKILL_STEP, WAIT_APPROVAL, RETURN_FINAL -> false;
        };
    }

    private boolean isHistoricalMemoryRecallAction(ReactAction action) {
        if (action == null) {
            return false;
        }
        return ObservationSemantics.isHistoricalExperienceAction(
                action.actionType() == null ? null : action.actionType().name(),
                action.target()
        );
    }

    private boolean isKnowledgeBaseAction(ReactAction action) {
        if (action == null) {
            return false;
        }
        return ObservationSemantics.isKnowledgeAction(
                action.actionType() == null ? null : action.actionType().name(),
                action.target()
        );
    }

    private boolean isRepeatPlanningSkill(ReactAction action, ActionPlanningContext context) {
        if (action == null || context == null) {
            return false;
        }
        String latestSource = context.latestCompletedObservationSource();
        if (latestSource == null || latestSource.isBlank()) {
            return false;
        }
        return latestSource.equalsIgnoreCase(safe(action.target()));
    }

    private boolean isRepeatFactualTool(ReactAction action, String latestFactualSource) {
        if (action == null || latestFactualSource == null || latestFactualSource.isBlank()) {
            return false;
        }
        if (action.actionType() != ReactActionType.CALL_LOCAL_TOOL
                && action.actionType() != ReactActionType.CALL_MCP_TOOL) {
            return false;
        }
        return latestFactualSource.equalsIgnoreCase(safe(action.target()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

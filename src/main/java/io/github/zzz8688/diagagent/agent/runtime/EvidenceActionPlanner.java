package io.github.zzz8688.diagagent.agent.runtime;

interface EvidenceActionPlanner {

    ReactAction planFallbackEvidenceAction(ActionPlanningContext context, String reason);

    ReactAction planAlternateEvidenceAction(ActionPlanningContext context,
                                            String latestFactualSource,
                                            String reason);
}

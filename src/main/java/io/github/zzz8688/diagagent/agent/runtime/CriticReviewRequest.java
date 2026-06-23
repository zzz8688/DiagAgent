package io.github.zzz8688.diagagent.agent.runtime;

public record CriticReviewRequest(
        String userQuestion,
        String specialistResults,
        String rawDraft,
        FinalAnswerContract contract,
        MainAgentPromptAssembler.MainAgentPromptContext promptContext,
        FinalAnswerRuleValidator.ValidationResult validationResult,
        EvidenceCoverage coverage,
        EvidenceGroundingChecker.GroundingResult groundingResult
) {
}

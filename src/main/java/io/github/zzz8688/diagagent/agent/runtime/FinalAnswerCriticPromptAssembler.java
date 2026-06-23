package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.config.SystemPromptConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FinalAnswerCriticPromptAssembler {

    private final SystemPromptConfig systemPromptConfig;

    public PromptEnvelope buildPrompt(String userQuestion,
                                      String specialistResults,
                                      String rawDraft,
                                      MainAgentPromptAssembler.MainAgentPromptContext promptContext,
                                      FinalAnswerRuleValidator.ValidationResult validationResult,
                                      EvidenceGroundingChecker.GroundingResult groundingResult,
                                      EvidenceCoverage coverage) {
        MainAgentPromptAssembler.KnowledgePromptState knowledgeState = promptContext == null
                ? new MainAgentPromptAssembler.KnowledgePromptState(null, null)
                : promptContext.knowledgeState();
        MainAgentPromptAssembler.ReplayPromptState replayState = promptContext == null
                ? new MainAgentPromptAssembler.ReplayPromptState(null, null, null)
                : promptContext.replayState();
        MainAgentPromptAssembler.LoopPromptState loopState = promptContext == null
                ? new MainAgentPromptAssembler.LoopPromptState(null, null, null, false, null, false, false, 0, null, null, null)
                : promptContext.loopState();
        String systemPrompt = systemPromptConfig.getCriticAgentPrompt();

        String userPrompt = """
                <critic_context>
                  <user_question><![CDATA[%s]]></user_question>
                  <candidate_final_answer><![CDATA[%s]]></candidate_final_answer>
                  <memory_snapshot><![CDATA[%s]]></memory_snapshot>
                  <rag_evidence><![CDATA[%s]]></rag_evidence>
                  <runtime_replay><![CDATA[%s]]></runtime_replay>
                  <event_replay><![CDATA[%s]]></event_replay>
                  <approval_result_replay><![CDATA[%s]]></approval_result_replay>
                  <specialist_observations><![CDATA[%s]]></specialist_observations>
                  <evidence_summary><![CDATA[%s]]></evidence_summary>
                  <validation_issues><![CDATA[%s]]></validation_issues>
                  <grounding_issues><![CDATA[%s]]></grounding_issues>
                  <coverage_summary><![CDATA[%s]]></coverage_summary>
                </critic_context>
                """.formatted(
                safe(userQuestion),
                safe(rawDraft),
                safe(knowledgeState.memorySummary()),
                safe(knowledgeState.ragEvidenceSummary()),
                safe(replayState.runtimeReplaySummary()),
                safe(replayState.eventReplaySummary()),
                safe(replayState.approvalReplaySummary()),
                safe(specialistResults),
                safe(loopState.evidenceSummary()),
                joinIssues(validationResult == null ? null : validationResult.issues()),
                joinIssues(groundingResult == null ? null : groundingResult.issues()),
                formatCoverage(coverage)
        );
        return PromptEnvelope.systemAndUser(systemPrompt, userPrompt, "final-answer-critic");
    }

    private String joinIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        return String.join("\n", issues);
    }

    private String formatCoverage(EvidenceCoverage coverage) {
        if (coverage == null) {
            return "";
        }
        return """
                evidenceSufficient=%s
                pendingState=%s
                pendingActionCount=%s
                missingObservations=%s
                successfulObservationCount=%s
                totalObservationCount=%s
                summary=%s
                """.formatted(
                coverage.evidenceSufficient(),
                coverage.pendingState(),
                coverage.pendingActionCount(),
                String.join(", ", coverage.missingObservations()),
                coverage.successfulObservationCount(),
                coverage.totalObservationCount(),
                safe(coverage.summary())
        ).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

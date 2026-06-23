package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalAnswerCriticService {

    private final CriticA2aClient criticA2aClient;

    public CriticResult critique(String userQuestion,
                                 String specialistResults,
                                 String rawDraft,
                                 FinalAnswerContract contract,
                                 MainAgentPromptAssembler.MainAgentPromptContext promptContext,
                                 FinalAnswerRuleValidator.ValidationResult validationResult,
                                 EvidenceCoverage coverage,
                                 EvidenceGroundingChecker.GroundingResult groundingResult) {
        CriticReviewRequest request = new CriticReviewRequest(
                userQuestion,
                specialistResults,
                rawDraft,
                contract,
                promptContext,
                validationResult,
                coverage,
                groundingResult
        );
        if (!criticA2aClient.enabled()) {
            throw new IllegalStateException("critic A2A remote 未启用或未配置");
        }
        try {
            return criticA2aClient.critique(request);
        } catch (Exception ex) {
            throw new IllegalStateException("critic A2A 调用失败", ex);
        }
    }

    public record CriticResult(
            boolean grounded,
            String overclaimRisk,
            List<String> missingEvidence,
            List<String> blockingMissingEvidence,
            List<String> unreachableMissingEvidence,
            String decision,
            String reason,
            String criticismObservation,
            boolean heuristicOnly
    ) {
        public boolean requiresRevision() {
            return "REVISE".equalsIgnoreCase(decision);
        }
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FinalAnswerRuleValidator {

    public ValidationResult validate(FinalAnswerContract contract, EvidenceCoverage coverage) {
        List<String> issues = new ArrayList<>();
        if (contract == null) {
            issues.add("final answer contract is missing");
            return new ValidationResult(false, List.copyOf(issues));
        }
        if (isBlank(contract.summary())) {
            issues.add("summary is blank");
        }
        if (contract.evidence() == null || contract.evidence().isEmpty()) {
            issues.add("evidence is empty");
        }
        if (Double.isNaN(contract.confidence()) || Double.isInfinite(contract.confidence())
                || contract.confidence() < 0.0 || contract.confidence() > 1.0) {
            issues.add("confidence must be between 0 and 1");
        }
        if (!contract.needsHandoff() && isBlank(contract.rootCause())) {
            issues.add("rootCause is required when needsHandoff is false");
        }
        if (contract.needsHandoff() && (contract.nextActions() == null || contract.nextActions().isEmpty())) {
            issues.add("nextActions is required when needsHandoff is true");
        }
        if (coverage != null && (coverage.pendingState() || coverage.pendingActionCount() > 0)) {
            issues.add("final answer cannot be closed while pending state exists");
        }
        if (contract.evidence() != null) {
            for (FinalAnswerContract.EvidenceItem item : contract.evidence()) {
                if (item == null) {
                    issues.add("evidence item is null");
                    continue;
                }
                if (isBlank(item.sourceType())) {
                    issues.add("evidence sourceType is blank");
                }
                if (isBlank(item.sourceId())) {
                    issues.add("evidence sourceId is blank");
                }
                if (isBlank(item.summary())) {
                    issues.add("evidence summary is blank");
                }
            }
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ValidationResult(
            boolean valid,
            List<String> issues
    ) {
    }
}

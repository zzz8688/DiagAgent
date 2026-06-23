package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalAnswerCriticEngine {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("(?i)traceid|requestid|链路");

    private final CriticModelGateway criticModelGateway;
    private final FinalAnswerCriticPromptAssembler finalAnswerCriticPromptAssembler;

    public FinalAnswerCriticService.CriticResult critique(CriticReviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("critic review request must not be null");
        }
        PromptEnvelope promptEnvelope = finalAnswerCriticPromptAssembler.buildPrompt(
                request.userQuestion(),
                request.specialistResults(),
                request.rawDraft(),
                request.promptContext(),
                request.validationResult(),
                request.groundingResult(),
                request.coverage()
        );
        String rawCriticResponse = criticModelGateway.complete(promptEnvelope);
        FinalAnswerCriticService.CriticResult parsed = parseCriticResult(
                rawCriticResponse,
                request.contract(),
                request.validationResult(),
                request.groundingResult(),
                request.coverage()
        );
        if (parsed == null) {
            throw new IllegalStateException("critic model returned invalid response: " + rawCriticResponse);
        }
        return enforceRuntimeContract(parsed, request.contract(), request.groundingResult(), request.coverage());
    }

    private FinalAnswerCriticService.CriticResult parseCriticResult(String rawResponse,
                                                                    FinalAnswerContract contract,
                                                                    FinalAnswerRuleValidator.ValidationResult validationResult,
                                                                    EvidenceGroundingChecker.GroundingResult groundingResult,
                                                                    EvidenceCoverage coverage) {
        String jsonContent = extractJson(rawResponse);
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
        String decision = normalizeDecision(text(root, "decision"));
        String overclaimRisk = normalizeRisk(text(root, "overclaimRisk"));
        String reason = defaultIfBlank(text(root, "reason"), "critic reason is empty");
        List<String> missingEvidence = parseStringList(root.get("missingEvidence"));
        List<String> criticismPoints = parseStringList(root.get("criticismPoints"));
        if (decision == null || overclaimRisk == null) {
            return null;
        }
        boolean grounded = validationResult != null && validationResult.valid()
                && (groundingResult == null || groundingResult.grounded());
        return new FinalAnswerCriticService.CriticResult(
                grounded,
                overclaimRisk,
                List.copyOf(missingEvidence),
                List.copyOf(missingEvidence),
                List.of(),
                decision,
                reason,
                renderCriticismObservation(contract, criticismPoints, missingEvidence, List.of(), coverage, overclaimRisk, reason),
                false
        );
    }

    private FinalAnswerCriticService.CriticResult enforceRuntimeContract(FinalAnswerCriticService.CriticResult candidate,
                                                                         FinalAnswerContract contract,
                                                                         EvidenceGroundingChecker.GroundingResult groundingResult,
                                                                         EvidenceCoverage coverage) {
        if (candidate == null) {
            return null;
        }
        Set<String> missingEvidence = new LinkedHashSet<>(safeList(candidate.missingEvidence()));
        if (coverage != null && !coverage.hasSuccessfulFactualObservation()) {
            missingEvidence.add("no factual observation available for final answer");
        }
        if (coverage != null && coverage.planningOnly()) {
            missingEvidence.add("planning observations cannot be used as final evidence");
        }
        if (groundingResult != null && groundingResult.issues() != null) {
            missingEvidence.addAll(groundingResult.issues());
        }
        List<String> blockingMissingEvidence = new ArrayList<>();
        List<String> unreachableMissingEvidence = new ArrayList<>();
        for (String issue : missingEvidence) {
            if (issue == null || issue.isBlank()) {
                continue;
            }
            if (mustRemainBlocking(issue) || isReachableIssue(issue)) {
                blockingMissingEvidence.add(issue);
            } else {
                unreachableMissingEvidence.add(issue);
            }
        }
        if (blockingMissingEvidence.isEmpty()) {
            String acceptanceReason = unreachableMissingEvidence.isEmpty()
                    ? candidate.reason()
                    : "remaining critic gaps are currently unreachable in this environment; accept grounded answer with caveats";
            String overclaimRisk = unreachableMissingEvidence.isEmpty()
                    ? candidate.overclaimRisk()
                    : lowerRisk(candidate.overclaimRisk());
            return new FinalAnswerCriticService.CriticResult(
                    candidate.grounded(),
                    overclaimRisk,
                    List.copyOf(missingEvidence),
                    List.of(),
                    List.copyOf(unreachableMissingEvidence),
                    candidate.grounded() ? "ACCEPT" : candidate.decision(),
                    acceptanceReason,
                    renderCriticismObservation(
                            contract,
                            new ArrayList<>(safeList(candidate.blockingMissingEvidence()).isEmpty()
                                    ? safeList(candidate.missingEvidence())
                                    : safeList(candidate.blockingMissingEvidence())),
                            List.of(),
                            unreachableMissingEvidence,
                            coverage,
                            overclaimRisk,
                            acceptanceReason
                    ),
                    candidate.heuristicOnly()
            );
        }
        String reason = "runtime final-answer contract requires reachable factual evidence before accepting draft";
        return new FinalAnswerCriticService.CriticResult(
                false,
                "high",
                List.copyOf(missingEvidence),
                List.copyOf(blockingMissingEvidence),
                List.copyOf(unreachableMissingEvidence),
                "REVISE",
                reason,
                renderCriticismObservation(
                        contract,
                        new ArrayList<>(blockingMissingEvidence),
                        blockingMissingEvidence,
                        unreachableMissingEvidence,
                        coverage,
                        "high",
                        reason
                ),
                candidate.heuristicOnly()
        );
    }

    private String renderCriticismObservation(FinalAnswerContract contract,
                                              List<String> criticismPoints,
                                              List<String> missingEvidence,
                                              List<String> unreachableEvidence,
                                              EvidenceCoverage coverage,
                                              String overclaimRisk,
                                              String reason) {
        StringBuilder builder = new StringBuilder();
        builder.append("critic 对当前 final answer draft 的批评如下：\n");
        builder.append("- decision: ").append(safe(reason)).append("\n");
        builder.append("- overclaimRisk: ").append(safe(overclaimRisk)).append("\n");
        if (contract != null) {
            builder.append("- currentSummary: ").append(safe(contract.summary())).append("\n");
            builder.append("- currentRootCause: ").append(safe(contract.rootCause())).append("\n");
            builder.append("- currentConfidence: ").append(contract.confidence()).append("\n");
        }
        for (String issue : safeList(criticismPoints)) {
            builder.append("- criticismPoint: ").append(safe(issue)).append("\n");
        }
        for (String issue : safeList(missingEvidence)) {
            builder.append("- missingEvidence: ").append(safe(issue)).append("\n");
        }
        for (String issue : safeList(unreachableEvidence)) {
            builder.append("- unreachableEvidence: ").append(safe(issue)).append("\n");
        }
        if (coverage != null && coverage.pendingActionCount() > 0) {
            builder.append("- pendingActionCount: ").append(coverage.pendingActionCount()).append("\n");
        }
        if (coverage != null && coverage.pendingState()) {
            builder.append("- pendingState: true\n");
        }
        builder.append("- instruction: 结合以上批评自行判断，是继续补证据，还是在回应批评后再次尝试 RETURN_FINAL。");
        return builder.toString().trim();
    }

    private boolean mustRemainBlocking(String issue) {
        String normalized = safe(issue).toLowerCase(Locale.ROOT);
        return normalized.contains("no factual observation")
                || normalized.contains("planning observations cannot be used")
                || normalized.contains("grounding")
                || normalized.contains("citation")
                || normalized.contains("invalid")
                || normalized.contains("schema")
                || normalized.contains("evidence reference");
    }

    private boolean isReachableIssue(String issue) {
        String normalized = safe(issue).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (TRACE_ID_PATTERN.matcher(issue).find()) {
            return true;
        }
        if ((normalized.contains("payment-service") || normalized.contains("支付服务"))
                && (normalized.contains("连接池") || normalized.contains("慢查询")
                || normalized.contains("latency") || normalized.contains("error") || normalized.contains("错误"))) {
            return true;
        }
        if ((normalized.contains("发布") || normalized.contains("配置变更")
                || normalized.contains("变更事件") || normalized.contains("deployment")
                || normalized.contains("release") || normalized.contains("rollback"))) {
            return true;
        }
        if (normalized.contains("payment-gateway")
                || normalized.contains("gateway")
                || normalized.contains("健康")
                || normalized.contains("依赖")
                || normalized.contains("topology")
                || normalized.contains("dependency")) {
            return true;
        }
        return false;
    }

    private String lowerRisk(String risk) {
        String normalized = safe(risk).toLowerCase(Locale.ROOT);
        if ("high".equals(normalized)) {
            return "medium";
        }
        if ("medium".equals(normalized) || "low".equals(normalized)) {
            return normalized;
        }
        return "medium";
    }

    private List<String> parseStringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            String value = item.getAsString();
            if (value != null && !value.isBlank()) {
                results.add(value.trim());
            }
        }
        return List.copyOf(results);
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String normalizeDecision(String value) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        return "ACCEPT".equals(normalized) || "REVISE".equals(normalized) ? normalized : null;
    }

    private String normalizeRisk(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        return "low".equals(normalized) || "medium".equals(normalized) || "high".equals(normalized) ? normalized : null;
    }

    private String text(JsonObject root, String fieldName) {
        if (root == null || fieldName == null || !root.has(fieldName) || root.get(fieldName).isJsonNull()) {
            return null;
        }
        return root.get(fieldName).getAsString();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

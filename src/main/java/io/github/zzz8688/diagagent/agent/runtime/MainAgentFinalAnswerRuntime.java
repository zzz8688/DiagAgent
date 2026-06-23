package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;
import io.github.zzz8688.diagagent.config.SessionContext;
import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import io.github.zzz8688.diagagent.skills.SkillService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MainAgentFinalAnswerRuntime {

    private static final Logger log = LoggerFactory.getLogger(MainAgentFinalAnswerRuntime.class);
    private static final String TEMPLATE_SKILL_ID = "diagnosis-playbook-skill";
    private static final String TEMPLATE_RESOURCE_PATH = "resources/final-answer-runtime-templates.md";
    private static final String TEMPLATE_USER_FACING = "user-facing";
    private static final String TEMPLATE_FAILURE = "failure";
    private static final String TEMPLATE_CRITIC_OBSERVATION = "critic-observation";
    private static final String TEMPLATE_GATE_OBSERVATION = "gate-observation";
    private static final String EMPTY_MARKER = "-";

    private final MainAgentFinalAnswerService mainAgentFinalAnswerService;
    private final SessionAssembler sessionAssembler;
    private final FinalAnswerSchemaValidator finalAnswerSchemaValidator;
    private final FinalAnswerRuleValidator finalAnswerRuleValidator;
    private final EvidenceGroundingChecker evidenceGroundingChecker;
    private final FinalAnswerCriticService finalAnswerCriticService;
    private final ExecutionEventBus executionEventBus;
    private final SkillService skillService;
    private final Gson gson = new Gson();

    public FinalAnswerReview reviewFinalAnswer(String userQuestion,
                                               List<AgentDiagnosisResponse> responses,
                                               String sessionId,
                                               ReactTurnState turnState,
                                               EvidenceCoverage evidenceCoverage) {
        EvidenceCoverage coverage = defaultCoverage(evidenceCoverage, responses);
        if (!coverage.hasSuccessfulFactualObservation()) {
            return FinalAnswerReview.continueLoop(
                    "",
                    buildEvidenceGapObservation(coverage, "NO_FACTUAL_EVIDENCE"),
                    0.0,
                    true
            );
        }
        if (!coverage.evidenceSufficient()) {
            return FinalAnswerReview.continueLoop(
                    "",
                    buildEvidenceGapObservation(coverage, "EVIDENCE_NOT_SUFFICIENT"),
                    0.0,
                    true
            );
        }
        FinalizationMetadata metadata = calculateMetadata(responses, coverage);

        if (!metadata.hasValidDiagnosis()) {
            return FinalAnswerReview.completed(new DiagnosisConclusion(
                    metadata.evidenceWeightedConfidence(),
                    metadata.manualInterventionNeeded(),
                    buildFailureConclusion(responses)
            ));
        }
        String specialistResults = buildSpecialistResults(responses);
        try {
            SessionContext.setSessionId(sessionId);
            SessionAssembler.PromptAssembly promptAssembly =
                    sessionAssembler.buildPromptAssembly(turnState, false, coverage, "final-answer", userQuestion);
            MainAgentPromptAssembler.MainAgentPromptContext promptContext = promptAssembly.promptContext();
            return resolveFinalAnswer(userQuestion, specialistResults, promptContext, responses, coverage, metadata);
        } catch (Exception e) {
            throw new IllegalStateException("MainAgent final answer generation failed", e);
        } finally {
            SessionContext.clear();
        }
    }

    public DiagnosisConclusion finalizeAnswer(String userQuestion,
                                              List<AgentDiagnosisResponse> responses,
                                              String sessionId,
                                              ReactTurnState turnState,
                                              EvidenceCoverage evidenceCoverage) {
        FinalAnswerReview review = reviewFinalAnswer(userQuestion, responses, sessionId, turnState, evidenceCoverage);
        if (review.completed()) {
            return review.conclusion();
        }
        return new DiagnosisConclusion(
                review.overallConfidence(),
                review.manualInterventionNeeded(),
                buildCriticObservationConclusion(review.finalAnswerDraft(), review.criticismObservation(), evidenceCoverage)
        );
    }

    private FinalAnswerReview resolveFinalAnswer(String userQuestion,
                                                 String specialistResults,
                                                 MainAgentPromptAssembler.MainAgentPromptContext promptContext,
                                                 List<AgentDiagnosisResponse> responses,
                                                 EvidenceCoverage coverage,
                                                 FinalizationMetadata metadata) {
        String rawFinalAnswer = mainAgentFinalAnswerService.answer(userQuestion, specialistResults, promptContext);
        ParseAttempt parseAttempt = parseAttempt(rawFinalAnswer, promptContext);
        FinalAnswerContract contract = evidenceGroundingChecker.repairContractEvidence(
                parseAttempt.contract(),
                promptContext,
                responses
        );

        FinalAnswerRuleValidator.ValidationResult validationResult = parseAttempt.issues().isEmpty()
                ? finalAnswerRuleValidator.validate(contract, coverage)
                : new FinalAnswerRuleValidator.ValidationResult(false, parseAttempt.issues());
        EvidenceGroundingChecker.GroundingResult groundingResult =
                validationResult.valid() && contract != null
                        ? evidenceGroundingChecker.check(contract, promptContext, responses)
                        : new EvidenceGroundingChecker.GroundingResult(false, List.of("grounding skipped because contract is invalid"));

        FinalAnswerCriticService.CriticResult criticResult =
                critiqueDraft(
                        userQuestion,
                        specialistResults,
                        rawFinalAnswer,
                        contract,
                        promptContext,
                        validationResult,
                        coverage,
                        groundingResult
                );
        if (!criticResult.requiresRevision() && validationResult.valid() && groundingResult.grounded() && contract != null) {
            double resolvedConfidence = mergeConfidence(metadata.evidenceWeightedConfidence(), contract.confidence(), coverage);
            return FinalAnswerReview.completed(new DiagnosisConclusion(
                    resolvedConfidence,
                    metadata.manualInterventionNeeded() || contract.needsHandoff(),
                    renderUserFacingConclusion(
                            userQuestion,
                            contract,
                            coverage,
                            metadata,
                            resolvedConfidence,
                            criticResult.unreachableMissingEvidence()
                    )
            ));
        }

        log.warn("Final answer draft received criticism: decision={}, reason={}, issues={}",
                criticResult.decision(), criticResult.reason(), criticResult.missingEvidence());
        return FinalAnswerReview.continueLoop(
                safe(rawFinalAnswer),
                criticResult.criticismObservation(),
                mergeConfidence(metadata.evidenceWeightedConfidence(), contract == null ? 0.0 : contract.confidence(), coverage),
                true
        );
    }

    private FinalAnswerCriticService.CriticResult critiqueDraft(String userQuestion,
                                                                String specialistResults,
                                                                String rawFinalAnswer,
                                                                FinalAnswerContract contract,
                                                                MainAgentPromptAssembler.MainAgentPromptContext promptContext,
                                                                FinalAnswerRuleValidator.ValidationResult validationResult,
                                                                EvidenceCoverage coverage,
                                                                EvidenceGroundingChecker.GroundingResult groundingResult) {
        String actionId = criticActionId(promptContext);
        log.info("主智能体 -> critic，请求评审: actionId={}, turnIndex={}, draftSummary={}, coverage={}",
                safe(actionId),
                promptContext == null ? 0 : promptContext.turnIndex(),
                summarizeDraft(rawFinalAnswer),
                formatCoverageCompact(coverage));
        publishCriticReviewRequested(actionId, rawFinalAnswer, promptContext);
        FinalAnswerCriticService.CriticResult criticResult = finalAnswerCriticService.critique(
                userQuestion,
                specialistResults,
                rawFinalAnswer,
                contract,
                promptContext,
                validationResult,
                coverage,
                groundingResult
        );
        log.info("critic -> 主智能体，返回评审: actionId={}, decision={}, reason={}, missingEvidence={}, criticismSummary={}",
                safe(actionId),
                safe(criticResult == null ? null : criticResult.decision()),
                clip(criticResult == null ? null : criticResult.reason(), 180),
                formatInlineList(criticResult == null ? List.of() : criticResult.missingEvidence()),
                clip(criticResult == null ? null : criticResult.criticismObservation(), 220));
        publishCriticObservationReceived(actionId, criticResult, promptContext);
        return criticResult;
    }

    private FinalizationMetadata calculateMetadata(List<AgentDiagnosisResponse> responses,
                                                   EvidenceCoverage coverage) {
        if (responses == null || responses.isEmpty()) {
            return new FinalizationMetadata(0.0, 0.0, false, false, List.of());
        }

        double totalConfidence = 0.0;
        double weightedConfidence = 0.0;
        double totalWeight = 0.0;
        boolean hasValidDiagnosis = false;
        List<String> lowConfidenceAgents = new ArrayList<>();
        for (AgentDiagnosisResponse response : responses) {
            double confidence = clampConfidence(response == null ? 0.0 : response.getConfidence());
            totalConfidence += confidence;
            double sourceWeight = confidenceWeight(response == null ? null : response.getAgent());
            weightedConfidence += confidence * sourceWeight;
            totalWeight += sourceWeight;
            boolean failed = response.getConclusion() != null &&
                    safe(response.getConclusion()).isBlank() && safe(response.getAnalysis()).isBlank();
            if (!failed) {
                hasValidDiagnosis = true;
            }
            if (confidence < lowConfidenceThreshold(response == null ? null : response.getAgent())) {
                lowConfidenceAgents.add(safe(response.getAgent()) + ":" + String.format("%.2f", confidence));
            }
        }

        double averageConfidence = totalConfidence / responses.size();
        double evidenceWeightedConfidence = totalWeight <= 0.0 ? averageConfidence : weightedConfidence / totalWeight;
        evidenceWeightedConfidence = applyCoverageConfidenceBoost(evidenceWeightedConfidence, coverage);
        boolean manualInterventionNeeded = evidenceWeightedConfidence < 0.72 || !lowConfidenceAgents.isEmpty();
        return new FinalizationMetadata(
                averageConfidence,
                evidenceWeightedConfidence,
                manualInterventionNeeded,
                hasValidDiagnosis,
                lowConfidenceAgents
        );
    }

    private String buildSpecialistResults(List<AgentDiagnosisResponse> responses) {
        StringBuilder builder = new StringBuilder();
        for (AgentDiagnosisResponse response : responses) {
            builder.append(gson.toJson(response)).append("\n\n");
        }
        return builder.toString();
    }

    private String buildFailureConclusion(List<AgentDiagnosisResponse> responses) {
        return renderTemplate(TEMPLATE_FAILURE, Map.of(
                "summary", defaultIfBlank(buildBestEffortSummary(responses, null), "FINAL_ANSWER_FAILURE"),
                "responseSection", defaultIfBlank(summarizeReturnedObservations(responses), EMPTY_MARKER)
        ));
    }

    private String buildFallbackConclusion(String userQuestion,
                                           List<AgentDiagnosisResponse> responses,
                                           EvidenceCoverage coverage,
                                           FinalizationMetadata metadata) {
        FinalAnswerContract fallbackContract = buildFallbackContract(responses, coverage, metadata);
        double resolvedConfidence = mergeConfidence(
                metadata == null ? 0.0 : metadata.evidenceWeightedConfidence(),
                fallbackContract.confidence(),
                coverage
        );
        return renderUserFacingConclusion(
                userQuestion,
                fallbackContract,
                coverage,
                metadata,
                resolvedConfidence,
                List.of()
        );
    }

    private String buildEvidenceGapObservation(EvidenceCoverage coverage, String reasonCode) {
        return renderTemplate(TEMPLATE_GATE_OBSERVATION, Map.of(
                "reasonCode", safe(reasonCode),
                "coverageSection", formatCoverageSection(coverage),
                "nextStepSection", "NEXT_STEP=CONTINUE_EVIDENCE_COLLECTION"
        ));
    }

    private String buildCriticObservationConclusion(String rawDraft,
                                                    String criticismObservation,
                                                    EvidenceCoverage coverage) {
        return renderTemplate(TEMPLATE_CRITIC_OBSERVATION, Map.of(
                "summary", defaultIfBlank(safe(rawDraft), "FINAL_ANSWER_REVIEW_PENDING"),
                "criticismObservation", defaultIfBlank(safe(criticismObservation), EMPTY_MARKER),
                "coverageSection", formatCoverageSection(coverage)
        ));
    }

    private String renderUserFacingConclusion(String userQuestion,
                                              FinalAnswerContract contract,
                                              EvidenceCoverage coverage,
                                              FinalizationMetadata metadata,
                                              double resolvedConfidence,
                                              List<String> unreachableEvidence) {
        boolean resolvedManualIntervention =
                (metadata != null && metadata.manualInterventionNeeded())
                        || (contract != null && contract.needsHandoff());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("summary", defaultIfBlank(safe(contract == null ? null : contract.summary()), EMPTY_MARKER));
        values.put("userQuestion", defaultIfBlank(safe(userQuestion), EMPTY_MARKER));
        values.put("factualObservationCount", Integer.toString(coverage == null ? 0 : coverage.successfulFactualObservationCount()));
        values.put("factualSources", formatCoverageSources(coverage));
        values.put("evidenceSection", formatEvidenceSection(contract));
        values.put("rootCauseSection", formatRootCauseSection(contract == null ? null : contract.rootCause()));
        values.put("confidence", formatConfidence(resolvedConfidence));
        values.put("manualIntervention", Boolean.toString(resolvedManualIntervention));
        values.put("needsHandoff", Boolean.toString(contract != null && contract.needsHandoff()));
        values.put("lowConfidenceAgents", formatInlineList(metadata == null ? List.of() : metadata.lowConfidenceAgents()));
        values.put("unreachableEvidence", formatInlineList(unreachableEvidence));
        values.put("sourceDiversity", Boolean.toString(coverage != null && coverage.hasSourceDiversity()));
        values.put("coverageCompact", formatCoverageCompact(coverage));
        values.put("nextActionsSection", formatNumberedList(contract == null ? List.of() : contract.nextActions()));
        values.put("pendingActionCount", Integer.toString(coverage == null ? 0 : coverage.pendingActionCount()));
        return renderTemplate(TEMPLATE_USER_FACING, values);
    }

    private void publishCriticReviewRequested(String actionId,
                                              String rawDraft,
                                              MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        if (promptContext == null) {
            return;
        }
        executionEventBus.publish(
                ExecutionEventType.CRITIC_REVIEW_REQUESTED,
                promptContext.taskId(),
                promptContext.sessionId(),
                "final-answer-critic",
                java.util.Map.of(
                        "actionId", safe(actionId),
                        "actionType", "CRITIC_REVIEW",
                        "target", "final-answer-draft",
                        "message", summarizeDraft(rawDraft),
                        "turnIndex", Integer.toString(promptContext.turnIndex())
                )
        );
    }

    private void publishCriticObservationReceived(String actionId,
                                                  FinalAnswerCriticService.CriticResult criticResult,
                                                  MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        if (promptContext == null || criticResult == null) {
            return;
        }
        executionEventBus.publish(
                ExecutionEventType.CRITIC_OBSERVATION_RECEIVED,
                promptContext.taskId(),
                promptContext.sessionId(),
                "final-answer-critic",
                java.util.Map.of(
                        "actionId", safe(actionId),
                        "actionType", "CRITIC_REVIEW",
                        "target", "final-answer-draft",
                        "status", criticResult.requiresRevision() ? "REVISE" : "ACCEPT",
                        "message", safe(criticResult.reason()),
                        "summary", safe(criticResult.criticismObservation()),
                        "turnIndex", Integer.toString(promptContext.turnIndex())
                )
        );
    }

    private double mergeConfidence(double metadataConfidence,
                                   double contractConfidence,
                                   EvidenceCoverage coverage) {
        if (Double.isNaN(contractConfidence) || Double.isInfinite(contractConfidence) || contractConfidence <= 0.0) {
            return clampConfidence(metadataConfidence);
        }
        if (Double.isNaN(metadataConfidence) || Double.isInfinite(metadataConfidence) || metadataConfidence <= 0.0) {
            return clampConfidence(contractConfidence);
        }
        boolean includesLogs = containsSource(coverage, "querylogs") || containsSource(coverage, "log");
        boolean includesMetrics = containsSource(coverage, "queryprometheusmetrics")
                || containsSource(coverage, "querymetrics")
                || containsSource(coverage, "prometheus")
                || containsSource(coverage, "metric");
        double metadataWeight = 0.45;
        if (includesLogs) {
            metadataWeight = 0.55;
        }
        if (includesLogs && includesMetrics && coverage != null && coverage.hasSourceDiversity()) {
            metadataWeight = 0.60;
        }
        double contractWeight = 1.0 - metadataWeight;
        return clampConfidence((contractConfidence * contractWeight) + (metadataConfidence * metadataWeight));
    }

    private double confidenceWeight(String sourceId) {
        String normalized = safe(sourceId).toLowerCase(Locale.ROOT);
        if (normalized.contains("querylogs") || normalized.contains("log")) {
            return 1.60;
        }
        if (normalized.contains("queryprometheusmetrics")
                || normalized.contains("querymetrics")
                || normalized.contains("prometheus")
                || normalized.contains("metric")) {
            return 1.25;
        }
        if (normalized.contains("querytopology")
                || normalized.contains("getdependencies")
                || normalized.contains("dependency")
                || normalized.contains("topology")) {
            return 0.90;
        }
        if (normalized.contains("queryknowledgebase") || normalized.contains("knowledge")) {
            return 0.70;
        }
        if (normalized.contains("querymemoryfiles") || normalized.contains("memory")) {
            return 0.60;
        }
        return 0.85;
    }

    private double lowConfidenceThreshold(String sourceId) {
        String normalized = safe(sourceId).toLowerCase(Locale.ROOT);
        if (normalized.contains("querylogs") || normalized.contains("log")) {
            return 0.78;
        }
        if (normalized.contains("queryprometheusmetrics")
                || normalized.contains("querymetrics")
                || normalized.contains("prometheus")
                || normalized.contains("metric")) {
            return 0.70;
        }
        if (normalized.contains("querytopology")
                || normalized.contains("getdependencies")
                || normalized.contains("dependency")
                || normalized.contains("topology")) {
            return 0.58;
        }
        if (normalized.contains("queryknowledgebase") || normalized.contains("knowledge")) {
            return 0.45;
        }
        if (normalized.contains("querymemoryfiles") || normalized.contains("memory")) {
            return 0.40;
        }
        if (normalized.contains("specialist")) {
            return 0.55;
        }
        return 0.58;
    }

    private double applyCoverageConfidenceBoost(double baseConfidence, EvidenceCoverage coverage) {
        double boosted = baseConfidence;
        if (coverage == null) {
            return clampConfidence(boosted);
        }
        if (coverage.successfulFactualObservationCount() >= 2) {
            boosted += 0.04;
        }
        if (coverage.hasSourceDiversity()) {
            boosted += 0.04;
        }
        boolean includesLogs = containsSource(coverage, "querylogs") || containsSource(coverage, "log");
        boolean includesMetrics = containsSource(coverage, "queryprometheusmetrics")
                || containsSource(coverage, "querymetrics")
                || containsSource(coverage, "prometheus")
                || containsSource(coverage, "metric");
        if (includesLogs) {
            boosted += 0.05;
        }
        if (includesMetrics) {
            boosted += 0.03;
        }
        if (includesLogs && includesMetrics) {
            boosted += 0.04;
        }
        if (coverage.successfulFactualObservationCount() >= 3) {
            boosted += 0.02;
        }
        return clampConfidence(boosted);
    }

    private boolean containsSource(EvidenceCoverage coverage, String expected) {
        if (coverage == null || coverage.factualSources() == null || expected == null || expected.isBlank()) {
            return false;
        }
        String normalizedExpected = expected.toLowerCase(Locale.ROOT);
        return coverage.factualSources().stream()
                .filter(source -> source != null && !source.isBlank())
                .map(source -> source.toLowerCase(Locale.ROOT))
                .anyMatch(source -> source.contains(normalizedExpected));
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(0.95, confidence));
    }

    private String buildBestEffortSummary(List<AgentDiagnosisResponse> responses, FinalizationMetadata metadata) {
        AgentDiagnosisResponse bestResponse = bestResponse(responses);
        if (bestResponse != null && !safe(bestResponse.getConclusion()).isBlank()) {
            return safe(bestResponse.getConclusion());
        }
        return summarizeReturnedObservations(responses);
    }

    private FinalAnswerContract buildFallbackContract(List<AgentDiagnosisResponse> responses,
                                                      EvidenceCoverage coverage,
                                                      FinalizationMetadata metadata) {
        return new FinalAnswerContract(
                defaultIfBlank(buildBestEffortSummary(responses, metadata), "FINAL_ANSWER_FALLBACK"),
                buildFallbackEvidence(responses),
                inferFallbackRootCause(responses, metadata),
                inferFallbackNextActions(responses, coverage),
                metadata == null ? 0.0 : metadata.evidenceWeightedConfidence(),
                metadata == null || metadata.manualInterventionNeeded()
        );
    }

    private List<FinalAnswerContract.EvidenceItem> buildFallbackEvidence(List<AgentDiagnosisResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return List.of();
        }
        List<FinalAnswerContract.EvidenceItem> evidence = new ArrayList<>();
        for (AgentDiagnosisResponse response : responses) {
            if (response == null) {
                continue;
            }
            String agent = safe(response.getAgent());
            String summary = firstNonBlank(response.getConclusion(), response.getAnalysis());
            if (agent.isBlank() || summary.isBlank()) {
                continue;
            }
            evidence.add(new FinalAnswerContract.EvidenceItem("SPECIALIST_OBSERVATION", agent, summary));
            if (evidence.size() >= 4) {
                break;
            }
        }
        return List.copyOf(evidence);
    }

    private String inferFallbackRootCause(List<AgentDiagnosisResponse> responses,
                                          FinalizationMetadata metadata) {
        if (metadata != null && metadata.manualInterventionNeeded()) {
            return "";
        }
        AgentDiagnosisResponse bestResponse = bestResponse(responses);
        return bestResponse == null ? "" : safe(bestResponse.getConclusion());
    }

    private List<String> inferFallbackNextActions(List<AgentDiagnosisResponse> responses,
                                                  EvidenceCoverage coverage) {
        return List.of();
    }

    private AgentDiagnosisResponse bestResponse(List<AgentDiagnosisResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        AgentDiagnosisResponse bestResponse = null;
        for (AgentDiagnosisResponse response : responses) {
            if (response == null) {
                continue;
            }
            if (bestResponse == null || response.getConfidence() > bestResponse.getConfidence()) {
                bestResponse = response;
            }
        }
        return bestResponse;
    }

    private String formatCoverageSources(EvidenceCoverage coverage) {
        if (coverage == null || coverage.factualSources() == null || coverage.factualSources().isEmpty()) {
            return EMPTY_MARKER;
        }
        return String.join(", ", coverage.factualSources());
    }

    private String summarizeReturnedObservations(List<AgentDiagnosisResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentDiagnosisResponse response : responses) {
            if (response == null) {
                continue;
            }
            String conclusion = safe(response.getConclusion());
            if (conclusion.isBlank()) {
                continue;
            }
            builder.append("- ")
                    .append("[")
                    .append(safe(response.getAgent()).toUpperCase())
                    .append("] ")
                    .append(conclusion)
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String formatEvidenceSection(FinalAnswerContract contract) {
        if (contract == null || contract.evidence() == null || contract.evidence().isEmpty()) {
            return EMPTY_MARKER;
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (FinalAnswerContract.EvidenceItem item : contract.evidence()) {
            if (item == null) {
                continue;
            }
            builder.append(index++)
                    .append(". [")
                    .append(safe(item.sourceType()))
                    .append("/")
                    .append(safe(item.sourceId()))
                    .append("] ")
                    .append(safe(item.summary()))
                    .append("\n");
        }
        return defaultIfBlank(builder.toString().trim(), EMPTY_MARKER);
    }

    private String formatCoverageSection(EvidenceCoverage coverage) {
        if (coverage == null) {
            return EMPTY_MARKER;
        }
        List<String> lines = new ArrayList<>();
        lines.add("- totalObservations=" + coverage.totalObservationCount());
        lines.add("- successfulObservations=" + coverage.successfulObservationCount());
        lines.add("- factualObservations=" + coverage.factualObservationCount());
        lines.add("- successfulFactualObservations=" + coverage.successfulFactualObservationCount());
        lines.add("- distinctFactualSources=" + coverage.distinctFactualSourceCount());
        lines.add("- pendingActionCount=" + coverage.pendingActionCount());
        lines.add("- pendingState=" + coverage.pendingState());
        if (!coverage.missingObservations().isEmpty()) {
            lines.add("- missingObservations=" + String.join(", ", coverage.missingObservations()));
        }
        return String.join("\n", lines);
    }

    private String formatCoverageCompact(EvidenceCoverage coverage) {
        if (coverage == null) {
            return EMPTY_MARKER;
        }
        return "total=" + coverage.totalObservationCount()
                + ", factual=" + coverage.successfulFactualObservationCount()
                + ", sources=" + coverage.distinctFactualSourceCount()
                + ", pending=" + coverage.pendingActionCount();
    }

    private String formatNumberedList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY_MARKER;
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (String item : items) {
            String normalized = safe(item);
            if (normalized.isBlank()) {
                continue;
            }
            builder.append(index++).append(". ").append(normalized).append("\n");
        }
        return defaultIfBlank(builder.toString().trim(), EMPTY_MARKER);
    }

    private String formatBulletList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY_MARKER;
        }
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            String normalized = safe(item);
            if (normalized.isBlank()) {
                continue;
            }
            builder.append("- ").append(normalized).append("\n");
        }
        return defaultIfBlank(builder.toString().trim(), EMPTY_MARKER);
    }

    private String formatInlineList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY_MARKER;
        }
        List<String> normalized = new ArrayList<>();
        for (String item : items) {
            String value = safe(item);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return EMPTY_MARKER;
        }
        return String.join("; ", normalized);
    }

    private String formatRootCauseSection(String rootCause) {
        String normalized = safe(rootCause);
        if (normalized.isBlank()) {
            return "";
        }
        return "### 根因判断\n\n" + normalized;
    }

    private String formatConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            return "0.00";
        }
        return String.format("%.2f", value);
    }

    private String renderTemplate(String templateName, Map<String, String> values) {
        String template = loadTemplate(templateName);
        if (template.isBlank()) {
            return gson.toJson(values == null ? Map.of() : values);
        }
        String rendered = template;
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
            }
        }
        return rendered.replaceAll("\\{\\{[^}]+}}", EMPTY_MARKER).trim();
    }

    private String loadTemplate(String templateName) {
        String content = safe(skillService.readResource(TEMPLATE_SKILL_ID, TEMPLATE_RESOURCE_PATH));
        if (content.isBlank()) {
            return "";
        }
        String startMarker = "<!-- template:" + templateName + " -->";
        String endMarker = "<!-- /template:" + templateName + " -->";
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker);
        if (start == -1 || end == -1 || end <= start) {
            return "";
        }
        return content.substring(start + startMarker.length(), end).trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return safe(second);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private ParseAttempt parseAttempt(String rawFinalAnswer,
                                      MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        try {
            String jsonContent = FinalAnswerContract.extractJson(rawFinalAnswer);
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            FinalAnswerSchemaValidator.ValidationResult validationResult = finalAnswerSchemaValidator.validate(root);
            if (!validationResult.valid()) {
                publishFinalAnswerContractFailure(
                        ExecutionEventType.FINAL_ANSWER_CONTRACT_SCHEMA_REJECTED,
                        validationResult.issues(),
                        rawFinalAnswer,
                        promptContext
                );
                return new ParseAttempt(null, validationResult.issues());
            }
            return new ParseAttempt(FinalAnswerContract.fromJsonObject(root), List.of());
        } catch (RuntimeException ex) {
            log.warn("Parse final answer draft failed", ex);
            List<String> issues = List.of("final answer draft is not valid JSON contract");
            publishFinalAnswerContractFailure(
                    ExecutionEventType.FINAL_ANSWER_CONTRACT_PARSE_FAILED,
                    issues,
                    rawFinalAnswer,
                    promptContext
            );
            return new ParseAttempt(null, issues);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String clip(String value, int maxLength) {
        String normalized = safe(value).replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= Math.max(0, maxLength)) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String summarizeDraft(String rawDraft) {
        String normalized = safe(rawDraft);
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private String criticActionId(MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        if (promptContext == null) {
            return "critic-review";
        }
        return "critic-review-" + promptContext.turnIndex();
    }

    private EvidenceCoverage defaultCoverage(EvidenceCoverage evidenceCoverage,
                                             List<AgentDiagnosisResponse> responses) {
        if (evidenceCoverage != null) {
            return evidenceCoverage;
        }
        int observationCount = responses == null ? 0 : responses.size();
        return new EvidenceCoverage(
                false,
                false,
                0,
                observationCount,
                observationCount,
                observationCount,
                observationCount,
                0,
                0,
                0,
                List.of(),
                List.of(),
                "runtime coverage missing"
        );
    }

    private void publishFinalAnswerContractFailure(ExecutionEventType eventType,
                                                   List<String> issues,
                                                   String rawFinalAnswer,
                                                   MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        if (promptContext == null) {
            return;
        }
        executionEventBus.publish(
                eventType,
                promptContext.taskId(),
                promptContext.sessionId(),
                "main-agent-final-answer-runtime",
                java.util.Map.of(
                        "turnIndex", Integer.toString(promptContext.turnIndex()),
                        "message", joinIssues(issues),
                        "rawPreview", summarizeDraft(rawFinalAnswer)
                )
        );
    }

    private String joinIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        return String.join(" | ", issues);
    }

    private record FinalizationMetadata(
            double averageConfidence,
            double evidenceWeightedConfidence,
            boolean manualInterventionNeeded,
            boolean hasValidDiagnosis,
            List<String> lowConfidenceAgents
    ) {
    }

    public record FinalAnswerReview(
            boolean completed,
            DiagnosisConclusion conclusion,
            String finalAnswerDraft,
            String criticismObservation,
            double overallConfidence,
            boolean manualInterventionNeeded
    ) {
        public static FinalAnswerReview completed(DiagnosisConclusion conclusion) {
            double confidence = conclusion == null ? 0.0 : conclusion.getOverallConfidence();
            boolean manualIntervention = conclusion != null && conclusion.isManualInterventionNeeded();
            return new FinalAnswerReview(true, conclusion, null, null, confidence, manualIntervention);
        }

        public static FinalAnswerReview continueLoop(String finalAnswerDraft,
                                                     String criticismObservation,
                                                     double overallConfidence,
                                                     boolean manualInterventionNeeded) {
            return new FinalAnswerReview(false, null, finalAnswerDraft, criticismObservation, overallConfidence, manualInterventionNeeded);
        }
    }

    private record ParseAttempt(
            FinalAnswerContract contract,
            List<String> issues
    ) {
    }
}

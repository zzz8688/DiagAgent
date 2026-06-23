package io.github.zzz8688.diagagent.agent.runtime;

import dev.langchain4j.data.segment.TextSegment;
import io.github.zzz8688.diagagent.service.MemorySummaryService;
import io.github.zzz8688.diagagent.store.KnowledgeBaseService;
import io.github.zzz8688.diagagent.store.KnowledgeSearchResult;
import io.github.zzz8688.diagagent.store.MemoryFileSearchResult;
import io.github.zzz8688.diagagent.store.MemoryFileService;
import io.github.zzz8688.diagagent.store.MemorySummaryDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class KnowledgePromptProjectionLoader {

    private static final int MEMORY_MATCH_LIMIT = 3;
    private static final int RAG_MATCH_LIMIT = 4;

    private final MemorySummaryService memorySummaryService;
    private final MemoryFileService memoryFileService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DomainEvidenceHeuristics domainEvidenceHeuristics;

    public SessionAssembler.KnowledgePromptProjection load(String userQuestion,
                                                           ReactTurnState turnState,
                                                           EvidenceCoverage evidenceCoverage,
                                                           String evidenceSummary) {
        String memorySummary = buildMemorySummary(turnState, userQuestion, evidenceCoverage, evidenceSummary);
        String ragEvidenceSummary = buildRagEvidenceSummary(userQuestion, turnState, evidenceCoverage, evidenceSummary);
        return new SessionAssembler.KnowledgePromptProjection(memorySummary, ragEvidenceSummary);
    }

    private String buildMemorySummary(ReactTurnState turnState,
                                      String userQuestion,
                                      EvidenceCoverage evidenceCoverage,
                                      String evidenceSummary) {
        StringBuilder builder = new StringBuilder();
        MemorySummaryDoc summaryDoc = loadRollingSummary(turnState);
        if (summaryDoc != null && summaryDoc.getContent() != null && !summaryDoc.getContent().isBlank()) {
            builder.append("rolling_summary");
            if (summaryDoc.getTopic() != null && !summaryDoc.getTopic().isBlank()) {
                builder.append("[").append(summaryDoc.getTopic().trim()).append("]");
            }
            if (summaryDoc.getVersion() != null) {
                builder.append(" v").append(summaryDoc.getVersion());
            }
            builder.append(":\n").append(summaryDoc.getContent().trim());
        }
        List<MemoryFileSearchResult> durableMemoryMatches = loadDurableMemoryMatches(
                userQuestion,
                turnState,
                evidenceCoverage,
                evidenceSummary
        );
        if (!durableMemoryMatches.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("historical_experience_matches:\n");
            for (MemoryFileSearchResult match : durableMemoryMatches) {
                builder.append("- ").append(formatMemoryFileMatch(match)).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String buildRagEvidenceSummary(String userQuestion,
                                           ReactTurnState turnState,
                                           EvidenceCoverage evidenceCoverage,
                                           String evidenceSummary) {
        if (!shouldRecallKnowledge(userQuestion, turnState, evidenceCoverage, evidenceSummary)) {
            return "";
        }
        String ragQuery = buildRecallQuery(userQuestion, turnState, evidenceCoverage, evidenceSummary);
        if (ragQuery.isBlank()) {
            return "";
        }
        List<KnowledgeSearchResult> ragMatches = loadRagMatches(ragQuery);
        if (ragMatches.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("knowledge_base_matches:\n");
        for (KnowledgeSearchResult match : ragMatches) {
            builder.append("- ").append(formatKnowledgeMatch(match)).append("\n");
        }
        return builder.toString().trim();
    }

    private boolean shouldRecallKnowledge(String userQuestion,
                                          ReactTurnState turnState,
                                          EvidenceCoverage evidenceCoverage,
                                          String evidenceSummary) {
        if (domainEvidenceHeuristics.isExplicitKnowledgeIntent(userQuestion)) {
            return true;
        }
        if (!hasNonKnowledgeFactualSeed(evidenceCoverage)) {
            return false;
        }
        String normalizedEvidence = safe(evidenceSummary);
        String normalizedObservation = turnState == null ? "" : safe(turnState.lastObservationSummary());
        return !normalizedEvidence.isBlank() || !normalizedObservation.isBlank();
    }

    private boolean hasNonKnowledgeFactualSeed(EvidenceCoverage evidenceCoverage) {
        if (evidenceCoverage == null || evidenceCoverage.factualSources() == null || evidenceCoverage.factualSources().isEmpty()) {
            return false;
        }
        return evidenceCoverage.factualSources().stream()
                .filter(source -> source != null && !source.isBlank())
                .anyMatch(source -> !ObservationSemantics.isKnowledgeSource(source)
                        && !ObservationSemantics.isHistoricalExperienceSource(source));
    }

    private MemorySummaryDoc loadRollingSummary(ReactTurnState turnState) {
        String memoryId = resolveMemoryId(turnState);
        if (memoryId == null) {
            return null;
        }
        return memorySummaryService.getSummary(memoryId).orElse(null);
    }

    private List<MemoryFileSearchResult> loadDurableMemoryMatches(String userQuestion,
                                                                  ReactTurnState turnState,
                                                                  EvidenceCoverage evidenceCoverage,
                                                                  String evidenceSummary) {
        if (!shouldRecallHistoricalExperience(evidenceCoverage)) {
            return List.of();
        }
        String recallQuery = buildRecallQuery(userQuestion, turnState, null, evidenceSummary);
        if (recallQuery.isBlank()) {
            return List.of();
        }
        return memoryFileService.search(recallQuery, MEMORY_MATCH_LIMIT).stream()
                .limit(MEMORY_MATCH_LIMIT)
                .toList();
    }

    private boolean shouldRecallHistoricalExperience(EvidenceCoverage evidenceCoverage) {
        return evidenceCoverage != null
                && evidenceCoverage.hasSuccessfulFactualObservation()
                && !evidenceCoverage.evidenceSufficient();
    }

    private List<KnowledgeSearchResult> loadRagMatches(String ragQuery) {
        return knowledgeBaseService.search(ragQuery, RAG_MATCH_LIMIT, false, "prompt-auto-recall").results().stream()
                .limit(RAG_MATCH_LIMIT)
                .toList();
    }

    private String buildRecallQuery(String userQuestion,
                                    ReactTurnState turnState,
                                    EvidenceCoverage evidenceCoverage,
                                    String evidenceSummary) {
        List<String> parts = new ArrayList<>();
        appendPart(parts, userQuestion);
        if (evidenceCoverage != null) {
            appendPart(parts, evidenceCoverage.summary());
            if (!evidenceCoverage.missingObservations().isEmpty()) {
                appendPart(parts, "missing observations: " + String.join(", ", evidenceCoverage.missingObservations()));
            }
        }
        appendPart(parts, evidenceSummary);
        appendPart(parts, turnState == null ? null : turnState.lastObservationSummary());
        appendPart(parts, turnState == null ? null : turnState.laneGuidanceSummary());
        appendPart(parts, turnState == null ? null : turnState.finalAnswerCriticism());
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(" | ", parts);
    }

    private String resolveMemoryId(ReactTurnState turnState) {
        if (turnState == null) {
            return null;
        }
        if (turnState.sessionId() != null && !turnState.sessionId().isBlank()) {
            return turnState.sessionId().trim();
        }
        if (turnState.taskId() != null && !turnState.taskId().isBlank()) {
            return turnState.taskId().trim();
        }
        return null;
    }

    private void appendPart(List<String> parts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (!parts.contains(normalized)) {
            parts.add(normalized);
        }
    }

    private String formatKnowledgeMatch(KnowledgeSearchResult match) {
        if (match == null) {
            return "";
        }
        return "%s [%s] score=%.2f snippet=%s".formatted(
                resolveTitle(match.segment()),
                safe(match.retrievalMode()).toLowerCase(Locale.ROOT),
                match.finalScore(),
                snip(match.segment())
        );
    }

    private String formatMemoryFileMatch(MemoryFileSearchResult match) {
        if (match == null) {
            return "";
        }
        return "%s [%s] score=%.2f snippet=%s".formatted(
                resolveTitle(match.segment()),
                safe(match.retrievalMode()).toLowerCase(Locale.ROOT),
                match.finalScore(),
                snip(match.segment())
        );
    }

    private String resolveTitle(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return "unknown";
        }
        String title = segment.metadata().getString("title");
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        String source = segment.metadata().getString("source");
        return source == null || source.isBlank() ? "unknown" : source.trim();
    }

    private String snip(TextSegment segment) {
        if (segment == null || segment.text() == null) {
            return "";
        }
        String normalized = segment.text().replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

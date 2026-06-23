package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.document.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

@Service
public class LogVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(LogVectorStoreService.class);

    private static final int RANK_FUSION_K = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Bm25Scorer bm25Scorer;
    private final ConcurrentMap<String, IndexedLogDocument> indexedDocuments = new ConcurrentHashMap<>();
    private final LongAdder ingestAttemptCount = new LongAdder();
    private final LongAdder ingestSuccessCount = new LongAdder();
    private final LongAdder ingestDuplicateCount = new LongAdder();
    private final LongAdder ingestFailedCount = new LongAdder();
    private final ConcurrentMap<String, LongAdder> indexedByService = new ConcurrentHashMap<>();
    private final Deque<String> recentIndexedSamples = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Instant> lastIndexedAt = new AtomicReference<>();
    private final AtomicBoolean firstSuccessfulIngestLogged = new AtomicBoolean(false);

    private static final int RECENT_SAMPLE_LIMIT = 5;

    @Value("${logs.retrieval.vector-top-k:15}")
    private int vectorTopK;

    @Value("${logs.retrieval.keyword-top-k:15}")
    private int keywordTopK;

    @Value("${logs.retrieval.final-top-k:8}")
    private int finalTopK;

    @Value("${logs.retrieval.min-vector-score:0.2}")
    private double minVectorScore;

    @Value("${logs.ingest.monitor.enabled:true}")
    private boolean ingestMonitorEnabled;

    @Value("${logs.ingest.monitor.fixed-delay-ms:300000}")
    private long ingestMonitorFixedDelayMs;

    public LogVectorStoreService(EmbeddingModel embeddingModel,
                                 @Qualifier("logEmbeddingStore") EmbeddingStore<TextSegment> logEmbeddingStore,
                                 Bm25Scorer bm25Scorer) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = logEmbeddingStore;
        this.bm25Scorer = bm25Scorer;
    }

    public void ingest(LogDocument document) {
        if (document == null || document.messageId() == null || document.messageId().isBlank()) {
            return;
        }
        ingestAttemptCount.increment();
        if (indexedDocuments.containsKey(document.messageId())) {
            ingestDuplicateCount.increment();
            return;
        }

        TextSegment segment = toTextSegment(document);
        try {
            Response<Embedding> response = embeddingModel.embed(segment.text());
            if (response == null || response.content() == null) {
                log.warn("日志向量化结果为空，跳过 messageId={}", document.messageId());
                return;
            }
            embeddingStore.add(response.content(), segment);
            indexedDocuments.putIfAbsent(document.messageId(), new IndexedLogDocument(document, segment));
            ingestSuccessCount.increment();
            indexedByService.computeIfAbsent(safe(document.service()), ignored -> new LongAdder()).increment();
            lastIndexedAt.set(document.timestamp());
            rememberIndexedSample(document);
            maybeLogInitialSuccess(document);
        } catch (Exception e) {
            ingestFailedCount.increment();
            log.warn("日志实时入索引失败，messageId={}", document.messageId(), e);
        }
    }

    @Scheduled(fixedDelayString = "${logs.ingest.monitor.fixed-delay-ms:300000}")
    public void emitIngestMonitorLog() {
        if (!ingestMonitorEnabled) {
            return;
        }
        long attempts = ingestAttemptCount.sum();
        if (attempts <= 0) {
            return;
        }
        LogIngestSnapshot snapshot = snapshot();
        log.info("日志向量入库监控: attempts={}, indexed={}, duplicates={}, failed={}, inMemoryIndexed={}, lastIndexedAt={}, topServices={}, recentSamples={}",
                snapshot.attemptCount(),
                snapshot.successCount(),
                snapshot.duplicateCount(),
                snapshot.failedCount(),
                snapshot.inMemoryIndexedCount(),
                snapshot.lastIndexedAt(),
                snapshot.topServices(),
                snapshot.recentSamples());
    }

    public List<LogSearchResult> search(LogSearchRequest request) {
        if (request == null) {
            return List.of();
        }
        LogSearchRequest normalizedRequest = request.normalize(finalTopK);
        if (normalizedRequest.isEmpty()) {
            return List.of();
        }

        List<Candidate> vectorCandidates = loadVectorCandidates(normalizedRequest);
        List<Candidate> keywordCandidates = loadKeywordCandidates(normalizedRequest);
        return mergeCandidates(vectorCandidates, keywordCandidates, normalizedRequest.limit());
    }

    private List<Candidate> loadVectorCandidates(LogSearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return List.of();
        }
        try {
            Response<Embedding> response = embeddingModel.embed(request.query());
            if (response == null || response.content() == null) {
                return List.of();
            }

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(response.content())
                    .maxResults(Math.max(request.limit(), vectorTopK))
                    .minScore(minVectorScore)
                    .build();
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(searchRequest);

            List<Candidate> candidates = new ArrayList<>();
            int rank = 1;
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                TextSegment segment = match.embedded();
                if (!matchesFilters(segment, request)) {
                    continue;
                }
                Candidate candidate = new Candidate(segment);
                candidate.vectorScore = safeScore(match.score());
                candidate.vectorRank = rank++;
                candidates.add(candidate);
            }
            return candidates;
        } catch (Exception e) {
            log.warn("日志向量召回失败，query={}", request.query(), e);
            return List.of();
        }
    }

    private List<Candidate> loadKeywordCandidates(LogSearchRequest request) {
        List<TextSegment> corpus = indexedDocuments.values().stream()
                .map(IndexedLogDocument::segment)
                .filter(segment -> matchesFilters(segment, request))
                .collect(Collectors.toList());
        if (corpus.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        if (request.query() == null || request.query().isBlank()) {
            for (TextSegment segment : corpus) {
                Candidate candidate = new Candidate(segment);
                candidate.keywordScore = 0D;
                candidates.add(candidate);
            }
            candidates.sort(Comparator
                    .comparing((Candidate candidate) -> metadataInstant(candidate.segment, "timestamp"), Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            for (TextSegment segment : corpus) {
                double keywordScore = bm25Scorer.score(request.query(), segment, corpus);
                if (keywordScore <= 0D) {
                    continue;
                }
                Candidate candidate = new Candidate(segment);
                candidate.keywordScore = keywordScore;
                candidates.add(candidate);
            }
            candidates.sort(Comparator
                    .comparingDouble(Candidate::keywordScore).reversed());
        }

        int rank = 1;
        for (Candidate candidate : candidates) {
            candidate.keywordRank = rank++;
        }

        int limit = Math.max(request.limit(), keywordTopK);
        if (candidates.size() <= limit) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, limit));
    }

    private List<LogSearchResult> mergeCandidates(List<Candidate> vectorCandidates, List<Candidate> keywordCandidates, int limit) {
        Map<String, Candidate> merged = new LinkedHashMap<>();

        for (Candidate candidate : vectorCandidates) {
            mergeCandidate(merged, candidate);
        }
        for (Candidate candidate : keywordCandidates) {
            mergeCandidate(merged, candidate);
        }

        return merged.values().stream()
                .peek(candidate -> {
                    candidate.fusionScore = rankFusion(candidate.vectorRank) + rankFusion(candidate.keywordRank);
                    candidate.finalScore = candidate.fusionScore;
                })
                .sorted(Comparator
                        .comparingDouble(Candidate::finalScore).reversed()
                        .thenComparingDouble(Candidate::vectorScore).reversed()
                        .thenComparingDouble(Candidate::keywordScore).reversed())
                .limit(limit)
                .map(candidate -> new LogSearchResult(
                        candidate.segment,
                        candidate.finalScore,
                        candidate.fusionScore,
                        candidate.vectorScore,
                        candidate.keywordScore,
                        resolveRetrievalMode(candidate)
                ))
                .collect(Collectors.toList());
    }

    private void mergeCandidate(Map<String, Candidate> merged, Candidate candidate) {
        String key = metadata(candidate.segment, "messageId");
        if (key == null || key.isBlank()) {
            key = Integer.toString(candidate.segment.text().hashCode());
        }
        Candidate mergedCandidate = merged.computeIfAbsent(key, ignored -> new Candidate(candidate.segment));
        mergedCandidate.vectorScore = Math.max(mergedCandidate.vectorScore, candidate.vectorScore);
        mergedCandidate.keywordScore = Math.max(mergedCandidate.keywordScore, candidate.keywordScore);
        mergedCandidate.vectorRank = Math.min(mergedCandidate.vectorRank, candidate.vectorRank);
        mergedCandidate.keywordRank = Math.min(mergedCandidate.keywordRank, candidate.keywordRank);
    }

    private TextSegment toTextSegment(LogDocument document) {
        Metadata metadata = new Metadata();
        metadata.put("messageId", safe(document.messageId()));
        metadata.put("timestamp", document.timestamp().toString());
        metadata.put("level", safe(document.level()));
        metadata.put("service", safe(document.service()));
        metadata.put("sourceType", safe(document.sourceType()));
        metadata.put("sourceId", safe(document.sourceId()));
        metadata.put("host", safe(document.host()));
        metadata.put("traceId", safe(document.traceId()));
        metadata.put("taskId", safe(document.taskId()));
        metadata.put("sessionId", safe(document.sessionId()));
        metadata.put("summary", safe(document.summary()));
        metadata.put("template", safe(document.template()));
        for (Map.Entry<String, String> entry : document.attributes().entrySet()) {
            metadata.put("attr." + entry.getKey(), safe(entry.getValue()));
        }

        String text = """
                service=%s
                level=%s
                summary=%s
                template=%s
                content=%s
                """.formatted(
                safe(document.service()),
                safe(document.level()),
                safe(document.summary()),
                safe(document.template()),
                safe(document.content())
        ).trim();
        return TextSegment.from(text, metadata);
    }

    private boolean matchesFilters(TextSegment segment, LogSearchRequest request) {
        if (segment == null || request == null) {
            return false;
        }
        if (!matchesExactFilter(metadata(segment, "service"), request.service())) {
            return false;
        }
        if (!matchesExactFilter(metadata(segment, "level"), request.level())) {
            return false;
        }
        if (!matchesExactFilter(metadata(segment, "traceId"), request.traceId())) {
            return false;
        }
        Instant lowerBound = parseTimeRange(request.timeRange());
        if (lowerBound == null) {
            return true;
        }
        Instant timestamp = metadataInstant(segment, "timestamp");
        return timestamp == null || !timestamp.isBefore(lowerBound);
    }

    private boolean matchesExactFilter(String value, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return safe(value).equalsIgnoreCase(expected.trim());
    }

    private Instant parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return null;
        }
        String normalized = timeRange.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("m")) {
                return Instant.now().minus(Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1))));
            }
            if (normalized.endsWith("h")) {
                return Instant.now().minus(Duration.ofHours(Long.parseLong(normalized.substring(0, normalized.length() - 1))));
            }
            if (normalized.endsWith("d")) {
                return Instant.now().minus(Duration.ofDays(Long.parseLong(normalized.substring(0, normalized.length() - 1))));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private String resolveRetrievalMode(Candidate candidate) {
        boolean hasVector = candidate.vectorScore > 0D;
        boolean hasKeyword = candidate.keywordScore > 0D;
        if (hasVector && hasKeyword) {
            return "hybrid";
        }
        if (hasVector) {
            return "vector";
        }
        if (hasKeyword) {
            return "keyword";
        }
        return "filter";
    }

    private double rankFusion(int rank) {
        if (rank == Integer.MAX_VALUE) {
            return 0D;
        }
        return 1D / (RANK_FUSION_K + rank);
    }

    private double safeScore(Double score) {
        return score == null ? 0D : score;
    }

    private String metadata(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null || key == null) {
            return "";
        }
        return safe(segment.metadata().getString(key));
    }

    private Instant metadataInstant(TextSegment segment, String key) {
        try {
            String value = metadata(segment, key);
            return value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public LogIngestSnapshot snapshot() {
        Map<String, Long> topServices = indexedByService.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().sum(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return new LogIngestSnapshot(
                ingestAttemptCount.sum(),
                ingestSuccessCount.sum(),
                ingestDuplicateCount.sum(),
                ingestFailedCount.sum(),
                indexedDocuments.size(),
                lastIndexedAt.get(),
                topServices,
                List.copyOf(recentIndexedSamples)
        );
    }

    private void rememberIndexedSample(LogDocument document) {
        String sample = "messageId=%s, service=%s, level=%s, traceId=%s, summary=%s".formatted(
                safe(document.messageId()),
                safe(document.service()),
                safe(document.level()),
                safe(document.traceId()),
                compact(safe(document.summary()), 120)
        );
        recentIndexedSamples.addFirst(sample);
        while (recentIndexedSamples.size() > RECENT_SAMPLE_LIMIT) {
            recentIndexedSamples.pollLast();
        }
    }

    private void maybeLogInitialSuccess(LogDocument document) {
        long successCount = ingestSuccessCount.sum();
        if (firstSuccessfulIngestLogged.compareAndSet(false, true)) {
            log.info("日志已成功写入向量库: indexed={}, messageId={}, service={}, level={}, traceId={}, summary={}",
                    successCount,
                    safe(document.messageId()),
                    safe(document.service()),
                    safe(document.level()),
                    safe(document.traceId()),
                    compact(safe(document.summary()), 160));
            return;
        }
        if (successCount <= 3) {
            log.info("日志向量入库样本: indexed={}, messageId={}, service={}, level={}, traceId={}, summary={}",
                    successCount,
                    safe(document.messageId()),
                    safe(document.service()),
                    safe(document.level()),
                    safe(document.traceId()),
                    compact(safe(document.summary()), 160));
        }
    }

    private String compact(String value, int maxLength) {
        String normalized = safe(value).replace('\n', ' ');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    public record LogDocument(
            String messageId,
            Instant timestamp,
            String level,
            String service,
            String sourceType,
            String sourceId,
            String host,
            String traceId,
            String taskId,
            String sessionId,
            String summary,
            String content,
            String template,
            Map<String, String> attributes
    ) {
        public LogDocument {
            timestamp = timestamp == null ? Instant.now() : timestamp;
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record LogSearchRequest(
            String query,
            String timeRange,
            String service,
            String level,
            String traceId,
            int limit
    ) {
        public LogSearchRequest normalize(int fallbackLimit) {
            int safeLimit = limit <= 0 ? fallbackLimit : limit;
            return new LogSearchRequest(
                    trim(query),
                    trim(timeRange),
                    trim(service),
                    trim(level),
                    trim(traceId),
                    Math.max(1, safeLimit)
            );
        }

        public boolean isEmpty() {
            return blank(query) && blank(timeRange) && blank(service) && blank(level) && blank(traceId);
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record LogSearchResult(
            TextSegment segment,
            double finalScore,
            double fusionScore,
            double vectorScore,
            double keywordScore,
            String retrievalMode
    ) {
    }

    private record IndexedLogDocument(LogDocument document, TextSegment segment) {
    }

    public record LogIngestSnapshot(
            long attemptCount,
            long successCount,
            long duplicateCount,
            long failedCount,
            int inMemoryIndexedCount,
            Instant lastIndexedAt,
            Map<String, Long> topServices,
            List<String> recentSamples
    ) {
    }

    private static final class Candidate {
        private final TextSegment segment;
        private double vectorScore;
        private double keywordScore;
        private double fusionScore;
        private double finalScore;
        private int vectorRank = Integer.MAX_VALUE;
        private int keywordRank = Integer.MAX_VALUE;

        private Candidate(TextSegment segment) {
            this.segment = segment;
        }

        private double finalScore() {
            return finalScore;
        }

        private double vectorScore() {
            return vectorScore;
        }

        private double keywordScore() {
            return keywordScore;
        }
    }
}

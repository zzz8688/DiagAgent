package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.zzz8688.diagagent.client.RerankerClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private static final int RANK_FUSION_K = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KnowledgeDocumentSplitter knowledgeDocumentSplitter;
    private final PdfDocumentLoaderService pdfDocumentLoaderService;
    private final Bm25Scorer bm25Scorer;
    private final RerankerClient rerankerClient;

    @Value("${knowledge.base.path:./knowledge}")
    private String knowledgeBasePath;

    @Value("${knowledge.rag.retrieval.vector-top-k:15}")
    private int vectorTopK;

    @Value("${knowledge.rag.retrieval.bm25-top-k:15}")
    private int keywordTopK;

    @Value("${knowledge.rag.retrieval.final-top-k:5}")
    private int finalTopK;

    @Value("${knowledge.rag.retrieval.fusion-top-k:25}")
    private int fusionTopK;

    @Value("${knowledge.rag.retrieval.rerank-top-n:8}")
    private int rerankTopN;

    @Value("${knowledge.rag.retrieval.min-vector-score:0.2}")
    private double minVectorScore;

    public record RagRetrievalConfig(
            int vectorTopK,
            int keywordTopK,
            int finalTopK,
            int fusionTopK,
            int rerankTopN,
            double minVectorScore,
            boolean rerankerConfigured
    ) {
    }

    public record RagSearchReport(
            String query,
            RagRetrievalConfig config,
            int requestedTopK,
            int vectorCandidateCount,
            int keywordCandidateCount,
            int fusedCandidateCount,
            List<KnowledgeSearchResult> results
    ) {
    }

    private record QueryProfile(
            String originalQuery,
            String retrievalQuery,
            List<String> lexicalTerms
    ) {
    }

    public KnowledgeBaseService(EmbeddingModel embeddingModel,
                                @Qualifier("knowledgeEmbeddingStore") EmbeddingStore<TextSegment> knowledgeEmbeddingStore,
                                KnowledgeDocumentSplitter knowledgeDocumentSplitter,
                                PdfDocumentLoaderService pdfDocumentLoaderService,
                                Bm25Scorer bm25Scorer,
                                RerankerClient rerankerClient) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = knowledgeEmbeddingStore;
        this.knowledgeDocumentSplitter = knowledgeDocumentSplitter;
        this.pdfDocumentLoaderService = pdfDocumentLoaderService;
        this.bm25Scorer = bm25Scorer;
        this.rerankerClient = rerankerClient;
    }

    @PostConstruct
    public void initKnowledgeBase() {
        log.info("========== KnowledgeBaseService 初始化 ==========");
        log.info("知识库将由 KnowledgeBaseRagConfig 从文件系统加载 (Docker 挂载目录)");
    }

    public List<KnowledgeSearchResult> search(String query, int maxResults) {
        return search(query, maxResults, true, "knowledge-tool").results();
    }

    public RagSearchReport search(String query,
                                  int maxResults,
                                  boolean verboseLogging,
                                  String caller) {
        return searchReport(query, maxResults, snapshotConfig(), verboseLogging, caller);
    }

    public RagSearchReport searchReport(String query, int maxResults) {
        return searchReport(query, maxResults, snapshotConfig(), true, "rag-search-report");
    }

    public RagSearchReport searchReport(String query, int maxResults, RagRetrievalConfig config) {
        return searchReport(query, maxResults, config, true, "rag-search-report");
    }

    public RagSearchReport searchReport(String query,
                                        int maxResults,
                                        RagRetrievalConfig config,
                                        boolean verboseLogging,
                                        String caller) {
        if (verboseLogging) {
            log.info("[RAG] 开始搜索知识库, caller={}, query={}", safe(caller), query);
        }

        if (query == null || query.isBlank()) {
            if (verboseLogging) {
                log.warn("[RAG] 查询为空，直接返回空结果, caller={}", safe(caller));
            }
            return new RagSearchReport(query, normalizeConfig(config), 0, 0, 0, 0, List.of());
        }

        try {
            RagRetrievalConfig effectiveConfig = normalizeConfig(config);
            int requestedTopK = maxResults > 0 ? maxResults : effectiveConfig.finalTopK();
            QueryProfile queryProfile = buildQueryProfile(query);
            List<Candidate> vectorCandidates = loadVectorCandidates(queryProfile, Math.max(requestedTopK, effectiveConfig.vectorTopK()), effectiveConfig.minVectorScore());
            List<Candidate> keywordCandidates = loadKeywordCandidates(queryProfile, Math.max(requestedTopK, effectiveConfig.keywordTopK()));
            List<Candidate> fusedCandidates = rerank(queryProfile, vectorCandidates, keywordCandidates, requestedTopK, effectiveConfig);
            List<KnowledgeSearchResult> results = fusedCandidates.stream()
                    .map(candidate -> new KnowledgeSearchResult(
                            candidate.segment,
                            candidate.finalScore,
                            candidate.fusionScore,
                            candidate.rerankScore,
                            candidate.vectorScore,
                            candidate.keywordScore,
                            resolveRetrievalMode(candidate)
                    ))
                    .collect(Collectors.toList());

            if (verboseLogging) {
                log.info("[RAG] 搜索完成, caller={}, query={}, vectorCandidates={}, keywordCandidates={}, finalResults={}",
                        safe(caller), query, vectorCandidates.size(), keywordCandidates.size(), results.size());
                results.forEach(result -> log.info(
                        "[RAG] result caller={}, mode={}, finalScore={}, rerankScore={}, fusionScore={}, vectorScore={}, bm25Score={}, title={}",
                        safe(caller),
                        result.retrievalMode(),
                        formatScore(result.finalScore()),
                        formatScore(result.rerankScore()),
                        formatScore(result.fusionScore()),
                        formatScore(result.vectorScore()),
                        formatScore(result.keywordScore()),
                        result.segment().metadata().getString("title")
                ));
            }
            return new RagSearchReport(
                    query,
                    effectiveConfig,
                    requestedTopK,
                    vectorCandidates.size(),
                    keywordCandidates.size(),
                    fusedCandidates.size(),
                    results
            );
        } catch (Exception e) {
            log.error("[RAG] 搜索失败, caller={}, query={}", safe(caller), query, e);
            return new RagSearchReport(query, normalizeConfig(config), 0, 0, 0, 0, List.of());
        }
    }

    public RagRetrievalConfig snapshotConfig() {
        return normalizeConfig(new RagRetrievalConfig(
                vectorTopK,
                keywordTopK,
                finalTopK,
                fusionTopK,
                rerankTopN,
                minVectorScore,
                rerankerClient.isConfigured()
        ));
    }

    private List<Candidate> loadVectorCandidates(QueryProfile queryProfile, int limit, double minScore) {
        try {
            Response<Embedding> response = embeddingModel.embed(queryProfile.retrievalQuery());
            if (response == null || response.content() == null || response.content().vector() == null) {
                log.warn("[RAG] 向量召回失败，embedding 结果为空");
                return List.of();
            }

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(response.content())
                    .maxResults(limit)
                    .minScore(minScore)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            List<Candidate> candidates = new ArrayList<>();
            int rank = 1;
            for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
                Candidate candidate = new Candidate(match.embedded());
                candidate.vectorScore = safeScore(match.score());
                candidate.vectorRank = rank++;
                candidates.add(candidate);
            }
            return candidates;
        } catch (Exception e) {
            log.error("[RAG] 向量召回异常, query={}", queryProfile.originalQuery(), e);
            return List.of();
        }
    }

    private List<Candidate> loadKeywordCandidates(QueryProfile queryProfile, int limit) {
        List<TextSegment> corpus = loadKnowledgeCorpus();
        if (corpus.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (TextSegment segment : corpus) {
            double bm25Score = bm25Scorer.score(queryProfile.retrievalQuery(), segment, corpus);
            if (bm25Score <= 0D) {
                continue;
            }
            Candidate candidate = new Candidate(segment);
            candidate.keywordScore = bm25Score;
            candidates.add(candidate);
        }

        candidates.sort(Comparator
                .comparingDouble(Candidate::keywordScore).reversed()
                .thenComparing(candidate -> candidate.segment.metadata().getString("title"), Comparator.nullsLast(String::compareTo)));

        int rank = 1;
        for (Candidate candidate : candidates) {
            candidate.keywordRank = rank++;
        }

        if (candidates.size() <= limit) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, limit));
    }

    private List<Candidate> rerank(QueryProfile queryProfile,
                                   List<Candidate> vectorCandidates,
                                   List<Candidate> keywordCandidates,
                                   int requestedTopK,
                                   RagRetrievalConfig config) {
        Map<String, Candidate> merged = new LinkedHashMap<>();

        for (Candidate candidate : keywordCandidates) {
            Candidate mergedCandidate = merged.computeIfAbsent(buildCandidateKey(candidate.segment), key -> new Candidate(candidate.segment));
            mergedCandidate.keywordScore = Math.max(mergedCandidate.keywordScore, candidate.keywordScore);
            mergedCandidate.keywordRank = Math.min(mergedCandidate.keywordRank, candidate.keywordRank);
        }

        for (Candidate candidate : vectorCandidates) {
            Candidate mergedCandidate = merged.computeIfAbsent(buildCandidateKey(candidate.segment), key -> new Candidate(candidate.segment));
            mergedCandidate.vectorScore = Math.max(mergedCandidate.vectorScore, candidate.vectorScore);
            mergedCandidate.vectorRank = Math.min(mergedCandidate.vectorRank, candidate.vectorRank);
        }

        List<Candidate> fusedCandidates = merged.values().stream()
                .peek(candidate -> candidate.fusionScore = calculateFusionScore(candidate, queryProfile))
                .sorted(Comparator
                        .comparingDouble(Candidate::fusionScore).reversed()
                        .thenComparingDouble(Candidate::vectorScore).reversed()
                        .thenComparingDouble(Candidate::keywordScore).reversed())
                .limit(Math.max(requestedTopK, config.fusionTopK()))
                .collect(Collectors.toList());

        applyBgeRerank(queryProfile.originalQuery(), fusedCandidates, config);

        return fusedCandidates.stream()
                .peek(candidate -> candidate.finalScore = candidate.rerankScore > 0D ? candidate.rerankScore : candidate.fusionScore)
                .sorted(Comparator
                        .comparingDouble(Candidate::finalScore).reversed()
                        .thenComparingDouble(Candidate::fusionScore).reversed()
                        .thenComparingDouble(Candidate::vectorScore).reversed()
                        .thenComparingDouble(Candidate::keywordScore).reversed())
                .limit(requestedTopK)
                .collect(Collectors.toList());
    }

    private void applyBgeRerank(String query, List<Candidate> fusedCandidates, RagRetrievalConfig config) {
        if (fusedCandidates.isEmpty()) {
            return;
        }

        int rerankCandidateCount = Math.min(Math.max(config.rerankTopN(), config.finalTopK()), fusedCandidates.size());
        List<Candidate> rerankCandidates = fusedCandidates.subList(0, rerankCandidateCount);
        List<String> documents = rerankCandidates.stream()
                .map(candidate -> candidate.segment.text())
                .collect(Collectors.toList());

        Map<Integer, Double> rerankScores = rerankerClient.rerank(query, documents, Math.min(rerankCandidateCount, config.finalTopK()));
        for (int index = 0; index < rerankCandidates.size(); index++) {
            rerankCandidates.get(index).rerankScore = rerankScores.getOrDefault(index, 0D);
        }

        log.debug("[RAG] 精排完成, rerankEnabled={}, rerankCandidates={}, rerankReturned={}",
                rerankerClient.isConfigured(),
                rerankCandidates.size(),
                rerankScores.size());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private RagRetrievalConfig normalizeConfig(RagRetrievalConfig config) {
        RagRetrievalConfig fallback = config == null
                ? new RagRetrievalConfig(vectorTopK, keywordTopK, finalTopK, fusionTopK, rerankTopN, minVectorScore, rerankerClient.isConfigured())
                : config;
        int normalizedFinalTopK = Math.max(1, fallback.finalTopK());
        return new RagRetrievalConfig(
                Math.max(1, fallback.vectorTopK()),
                Math.max(1, fallback.keywordTopK()),
                normalizedFinalTopK,
                Math.max(normalizedFinalTopK, fallback.fusionTopK()),
                Math.max(normalizedFinalTopK, fallback.rerankTopN()),
                Math.max(0D, fallback.minVectorScore()),
                fallback.rerankerConfigured()
        );
    }

    private double calculateFusionScore(Candidate candidate, QueryProfile queryProfile) {
        double baseScore = rankFusion(candidate.vectorRank) + rankFusion(candidate.keywordRank);
        return baseScore * documentSpecificityWeight(candidate.segment, queryProfile);
    }

    private List<TextSegment> loadKnowledgeCorpus() {
        Path basePath = Paths.get(knowledgeBasePath);
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            log.warn("[RAG] 知识库目录不存在: {}", knowledgeBasePath);
            return List.of();
        }

        List<Path> files;
        try (Stream<Path> paths = Files.list(basePath)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String lowerName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lowerName.endsWith(".md") || lowerName.endsWith(".pdf");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[RAG] 读取知识库目录失败: {}", knowledgeBasePath, e);
            return List.of();
        }

        List<TextSegment> corpus = new ArrayList<>();
        for (Path path : files) {
            String filename = path.getFileName().toString();
            String type = filename.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "pdf" : "markdown";
            String content = readKnowledgeContent(path, type);
            if (content == null || content.isBlank()) {
                continue;
            }
            corpus.addAll(knowledgeDocumentSplitter.splitForKnowledgeFile(content, filename, type));
        }
        return corpus;
    }

    private String readKnowledgeContent(Path path, String type) {
        try {
            if ("pdf".equals(type)) {
                try (InputStream inputStream = Files.newInputStream(path)) {
                    return pdfDocumentLoaderService.loadPdfFromInputStream(inputStream, path.getFileName().toString());
                }
            }
            return Files.readString(path);
        } catch (Exception e) {
            log.warn("[RAG] 读取知识文件失败: {}", path, e);
            return null;
        }
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
        return "unknown";
    }

    private String buildCandidateKey(TextSegment segment) {
        String source = segment.metadata().getString("source");
        return (source == null ? "" : source) + "|" + segment.text().hashCode();
    }

    private double rankFusion(int rank) {
        if (rank == Integer.MAX_VALUE) {
            return 0D;
        }
        return 1D / (RANK_FUSION_K + rank);
    }

    private double documentSpecificityWeight(TextSegment segment, QueryProfile queryProfile) {
        if (segment == null || segment.metadata() == null) {
            return 1D;
        }
        String title = normalizeMetadata(segment.metadata().getString("title"));
        String source = normalizeMetadata(segment.metadata().getString("source"));
        String combined = (title + " " + source).trim();
        double weight = 1D;
        if (source.endsWith(".pdf")) {
            weight -= 0.08D;
        }
        weight += metadataQualityWeight(title, source);
        if (queryProfile != null) {
            double metadataOverlap = overlapRatio(combined, queryProfile.lexicalTerms());
            weight += metadataOverlap * 0.45D;
            weight -= metadataMismatchPenalty(title, source, metadataOverlap);
        } else {
            weight -= metadataMismatchPenalty(title, source, 0D);
        }
        return Math.max(0.45D, Math.min(1.45D, weight));
    }

    private double metadataQualityWeight(String title, String source) {
        double weight = 0D;
        String sourceStem = stripExtension(source);
        int titleSignalLength = signalLength(title);
        if (!title.isBlank()) {
            weight += 0.04D;
        }
        if (!source.isBlank()) {
            weight += 0.02D;
        }
        if (titleSignalLength >= 6) {
            weight += 0.04D;
        }
        if (titleSignalLength >= 10) {
            weight += 0.03D;
        }
        if (!title.isBlank() && !sourceStem.isBlank() && !title.equals(sourceStem)) {
            weight += 0.05D;
        }
        return weight;
    }

    private double metadataMismatchPenalty(String title, String source, double metadataOverlap) {
        double penalty = 0D;
        String sourceStem = stripExtension(source);
        int titleSignalLength = signalLength(title);
        if (title.isBlank()) {
            penalty += 0.10D;
        }
        if (source.isBlank()) {
            penalty += 0.04D;
        }
        if (!title.isBlank() && !sourceStem.isBlank() && title.equals(sourceStem)) {
            penalty += 0.06D;
        }
        if (titleSignalLength > 0 && titleSignalLength <= 4) {
            penalty += 0.05D;
        }
        if (metadataOverlap == 0D) {
            penalty += 0.10D;
        }
        return penalty;
    }

    private QueryProfile buildQueryProfile(String query) {
        String originalQuery = query == null ? "" : query.trim();
        String normalized = normalizeMetadata(originalQuery);
        Set<String> expandedTerms = new LinkedHashSet<>(bm25Scorer.tokenize(originalQuery));

        if (normalized.contains("转圈")) {
            expandedTerms.addAll(List.of("轮询", "等待", "回执", "pending", "重试"));
        }
        if (normalized.contains("页面") && (normalized.contains("慢") || normalized.contains("缓"))) {
            expandedTerms.addAll(List.of("首屏", "缓存", "静态资源", "依赖"));
        }
        if (normalized.contains("系统") && normalized.contains("异常")) {
            expandedTerms.addAll(List.of("健康", "巡检", "实例", "评分"));
        }
        if (normalized.contains("数据库") && normalized.contains("超时")) {
            expandedTerms.addAll(List.of("连接池", "连接", "等待", "慢sql", "锁"));
        }
        if (normalized.contains("凌晨") || normalized.contains("偶尔") || normalized.contains("偶发")) {
            expandedTerms.addAll(List.of("波动", "摇摆", "依赖", "窗口"));
        }

        List<String> lexicalTerms = List.copyOf(expandedTerms);
        String retrievalQuery = lexicalTerms.isEmpty() ? originalQuery : String.join(" ", lexicalTerms);
        return new QueryProfile(originalQuery, retrievalQuery, lexicalTerms);
    }

    private double overlapRatio(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return 0D;
        }
        long matched = terms.stream()
                .filter(term -> term != null && !term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT)))
                .count();
        return matched == 0 ? 0D : (double) matched / terms.size();
    }

    private String normalizeMetadata(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int lastSlash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        String filename = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        int dotIndex = filename.lastIndexOf('.');
        String stem = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        return normalizeMetadata(stem);
    }

    private int signalLength(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                count++;
            }
        }
        return count;
    }

    private double safeScore(Double score) {
        return score == null ? 0D : score;
    }

    private String formatScore(double score) {
        return String.format(Locale.ROOT, "%.4f", score);
    }

    private static final class Candidate {
        private final TextSegment segment;
        private double vectorScore;
        private double keywordScore;
        private double fusionScore;
        private double rerankScore;
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

        private double fusionScore() {
            return fusionScore;
        }
    }
}

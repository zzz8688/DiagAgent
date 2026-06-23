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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MemoryFileService {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileService.class);
    private static final int RANK_FUSION_K = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KnowledgeDocumentSplitter knowledgeDocumentSplitter;
    private final Bm25Scorer bm25Scorer;
    private final RerankerClient rerankerClient;

    @Value("${memory.files.path:./memory}")
    private String memoryFilesPath;

    @Value("${memory.files.rag.retrieval.vector-top-k:12}")
    private int vectorTopK;

    @Value("${memory.files.rag.retrieval.bm25-top-k:12}")
    private int keywordTopK;

    @Value("${memory.files.rag.retrieval.final-top-k:5}")
    private int finalTopK;

    @Value("${memory.files.rag.retrieval.fusion-top-k:20}")
    private int fusionTopK;

    @Value("${memory.files.rag.retrieval.rerank-top-n:8}")
    private int rerankTopN;

    @Value("${memory.files.rag.retrieval.min-vector-score:0.2}")
    private double minVectorScore;

    public MemoryFileService(EmbeddingModel embeddingModel,
                             @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> memoryEmbeddingStore,
                             KnowledgeDocumentSplitter knowledgeDocumentSplitter,
                             Bm25Scorer bm25Scorer,
                             RerankerClient rerankerClient) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = memoryEmbeddingStore;
        this.knowledgeDocumentSplitter = knowledgeDocumentSplitter;
        this.bm25Scorer = bm25Scorer;
        this.rerankerClient = rerankerClient;
    }

    public List<MemoryFileSearchResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int requestedTopK = maxResults > 0 ? maxResults : finalTopK;
            List<Candidate> vectorCandidates = loadVectorCandidates(query, Math.max(requestedTopK, vectorTopK));
            List<Candidate> keywordCandidates = loadKeywordCandidates(query, Math.max(requestedTopK, keywordTopK));
            return rerank(query, vectorCandidates, keywordCandidates, requestedTopK);
        } catch (Exception e) {
            log.error("memory files 检索失败: query={}", query, e);
            return List.of();
        }
    }

    private List<Candidate> loadVectorCandidates(String query, int limit) {
        try {
            Response<Embedding> response = embeddingModel.embed(query);
            if (response == null || response.content() == null || response.content().vector() == null) {
                return List.of();
            }
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(response.content())
                    .maxResults(limit)
                    .minScore(minVectorScore)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
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
            log.warn("memory files 向量召回失败: query={}", query, e);
            return List.of();
        }
    }

    private List<Candidate> loadKeywordCandidates(String query, int limit) {
        List<TextSegment> corpus = loadMemoryCorpus();
        if (corpus.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (TextSegment segment : corpus) {
            double bm25Score = bm25Scorer.score(query, segment, corpus);
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

        return candidates.size() <= limit ? candidates : new ArrayList<>(candidates.subList(0, limit));
    }

    private List<MemoryFileSearchResult> rerank(String query,
                                                List<Candidate> vectorCandidates,
                                                List<Candidate> keywordCandidates,
                                                int requestedTopK) {
        Map<String, Candidate> merged = new LinkedHashMap<>();
        for (Candidate candidate : vectorCandidates) {
            Candidate mergedCandidate = merged.computeIfAbsent(buildCandidateKey(candidate.segment), key -> new Candidate(candidate.segment));
            mergedCandidate.vectorScore = Math.max(mergedCandidate.vectorScore, candidate.vectorScore);
            mergedCandidate.vectorRank = Math.min(mergedCandidate.vectorRank, candidate.vectorRank);
        }
        for (Candidate candidate : keywordCandidates) {
            Candidate mergedCandidate = merged.computeIfAbsent(buildCandidateKey(candidate.segment), key -> new Candidate(candidate.segment));
            mergedCandidate.keywordScore = Math.max(mergedCandidate.keywordScore, candidate.keywordScore);
            mergedCandidate.keywordRank = Math.min(mergedCandidate.keywordRank, candidate.keywordRank);
        }

        List<Candidate> fusedCandidates = merged.values().stream()
                .peek(candidate -> candidate.fusionScore = calculateFusionScore(candidate))
                .sorted(Comparator
                        .comparingDouble(Candidate::fusionScore).reversed()
                        .thenComparingDouble(Candidate::vectorScore).reversed()
                        .thenComparingDouble(Candidate::keywordScore).reversed())
                .limit(Math.max(requestedTopK, fusionTopK))
                .collect(Collectors.toList());

        applyBgeRerank(query, fusedCandidates);

        return fusedCandidates.stream()
                .peek(candidate -> candidate.finalScore = candidate.rerankScore > 0D ? candidate.rerankScore : candidate.fusionScore)
                .sorted(Comparator
                        .comparingDouble(Candidate::finalScore).reversed()
                        .thenComparingDouble(Candidate::fusionScore).reversed()
                        .thenComparingDouble(Candidate::vectorScore).reversed()
                        .thenComparingDouble(Candidate::keywordScore).reversed())
                .limit(requestedTopK)
                .map(candidate -> new MemoryFileSearchResult(
                        candidate.segment,
                        candidate.finalScore,
                        candidate.fusionScore,
                        candidate.rerankScore,
                        candidate.vectorScore,
                        candidate.keywordScore,
                        resolveRetrievalMode(candidate)
                ))
                .collect(Collectors.toList());
    }

    private void applyBgeRerank(String query, List<Candidate> fusedCandidates) {
        if (fusedCandidates.isEmpty()) {
            return;
        }
        int rerankCandidateCount = Math.min(Math.max(rerankTopN, finalTopK), fusedCandidates.size());
        List<Candidate> rerankCandidates = fusedCandidates.subList(0, rerankCandidateCount);
        List<String> documents = rerankCandidates.stream()
                .map(candidate -> candidate.segment.text())
                .toList();
        Map<Integer, Double> rerankScores = rerankerClient.rerank(query, documents, Math.min(rerankCandidateCount, finalTopK));
        for (int index = 0; index < rerankCandidates.size(); index++) {
            rerankCandidates.get(index).rerankScore = rerankScores.getOrDefault(index, 0D);
        }
    }

    private List<TextSegment> loadMemoryCorpus() {
        Path basePath = Paths.get(memoryFilesPath);
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return List.of();
        }

        List<Path> files;
        try (Stream<Path> paths = Files.walk(basePath)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedMemoryFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("读取 memory files 目录失败: {}", basePath.toAbsolutePath(), e);
            return List.of();
        }

        List<TextSegment> corpus = new ArrayList<>();
        for (Path path : files) {
            try {
                String content = Files.readString(path);
                String memoryPath = basePath.relativize(path).toString().replace('\\', '/');
                String title = titleFromPath(path);
                List<TextSegment> segments = knowledgeDocumentSplitter.splitForDocument(
                        content,
                        memoryPath,
                        title,
                        "memory-file"
                );
                segments.stream()
                        .map(segment -> TextSegment.from(segment.text(), buildMetadata(segment, memoryPath, title, path)))
                        .forEach(corpus::add);
            } catch (Exception e) {
                log.warn("读取 memory file 内容失败: {}", path, e);
            }
        }
        return corpus;
    }

    private dev.langchain4j.data.document.Metadata buildMetadata(TextSegment segment,
                                                                  String memoryPath,
                                                                  String title,
                                                                  Path path) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        metadata.put("id", memoryPath);
        metadata.put("title", title);
        metadata.put("source", memoryPath);
        metadata.put("type", "memory-file");
        metadata.put("memoryPath", memoryPath);
        metadata.put("filename", path.getFileName().toString());
        return metadata;
    }

    private boolean isSupportedMemoryFile(Path path) {
        String lowerName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".md") || lowerName.endsWith(".txt");
    }

    private String titleFromPath(Path path) {
        String filename = path.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }

    private double calculateFusionScore(Candidate candidate) {
        return rankFusion(candidate.vectorRank) + rankFusion(candidate.keywordRank);
    }

    private double rankFusion(int rank) {
        if (rank == Integer.MAX_VALUE) {
            return 0D;
        }
        return 1D / (RANK_FUSION_K + rank);
    }

    private String buildCandidateKey(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return "";
        }
        return segment.metadata().getString("source") + "#" + segment.text().hashCode();
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

    private double safeScore(Double score) {
        return score == null ? 0D : score;
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

        private double vectorScore() {
            return vectorScore;
        }

        private double keywordScore() {
            return keywordScore;
        }

        private double fusionScore() {
            return fusionScore;
        }

        private double finalScore() {
            return finalScore;
        }
    }
}

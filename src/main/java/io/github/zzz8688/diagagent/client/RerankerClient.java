package io.github.zzz8688.diagagent.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.zzz8688.diagagent.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RerankerClient {

    private static final Logger log = LoggerFactory.getLogger(RerankerClient.class);

    private final WebClient webClient;
    private final ModelConfig modelConfig;

    @Value("${knowledge.rag.reranker.enabled:true}")
    private boolean enabled;

    @Value("${knowledge.rag.reranker.base-url:}")
    private String baseUrl;

    @Value("${knowledge.rag.reranker.path:/v1/rerank}")
    private String path;

    @Value("${knowledge.rag.reranker.api-key:}")
    private String apiKey;

    @Value("${knowledge.rag.reranker.timeout-seconds:10}")
    private int timeoutSeconds;

    public RerankerClient(ModelConfig modelConfig) {
        this.webClient = WebClient.builder().build();
        this.modelConfig = modelConfig;
    }

    public boolean isConfigured() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }

    public Map<Integer, Double> rerank(String query, List<String> documents, int topN) {
        if (!enabled) {
            log.info("[RAG] Reranker 已禁用，跳过精排");
            return Collections.emptyMap();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("[RAG] Reranker 未配置 baseUrl，跳过精排");
            return Collections.emptyMap();
        }
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyMap();
        }

        int requestTopN = Math.min(Math.max(topN, 1), documents.size());
        String rerankerModel = modelConfig.getRerankerModelName();
        RerankRequest request = new RerankRequest(
                rerankerModel,
                new RerankInput(query, documents),
                new RerankParameters(requestTopN, false)
        );

        try {
            WebClient.RequestBodySpec requestSpec = webClient.post()
                    .uri(baseUrl + path)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            if (apiKey != null && !apiKey.isBlank()) {
                requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }

            RerankResponse response = requestSpec
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            List<RerankResult> results = extractResults(response);
            if (results.isEmpty()) {
                log.warn("[RAG] Reranker 返回空结果, model={}, path={}", rerankerModel, path);
                return Collections.emptyMap();
            }

            Map<Integer, Double> rerankScores = results.stream()
                    .filter(result -> result.index() != null && result.relevanceScore() != null)
                    .collect(Collectors.toMap(
                            RerankResult::index,
                            RerankResult::relevanceScore,
                            Math::max
                    ));
            log.debug("[RAG] Reranker 精排完成, model={}, docs={}, returned={}",
                    rerankerModel, documents.size(), rerankScores.size());
            return rerankScores;
        } catch (Exception e) {
            log.error(String.format(Locale.ROOT,
                    "[RAG] 调用 Reranker 失败, model=%s, endpoint=%s%s",
                    modelConfig.getRerankerModelName(),
                    baseUrl,
                    path), e);
            return Collections.emptyMap();
        }
    }

    private List<RerankResult> extractResults(RerankResponse response) {
        if (response == null) {
            return List.of();
        }
        if (response.results() != null && !response.results().isEmpty()) {
            return response.results();
        }
        if (response.output() != null && response.output().results() != null && !response.output().results().isEmpty()) {
            return response.output().results();
        }
        if (response.data() != null && !response.data().isEmpty()) {
            return response.data();
        }
        return List.of();
    }

    private record RerankRequest(
            String model,
            RerankInput input,
            RerankParameters parameters
    ) {
    }

    private record RerankInput(
            String query,
            List<String> documents
    ) {
    }

    private record RerankParameters(
            Integer top_n,
            Boolean return_documents
    ) {
    }

    private record RerankResponse(
            List<RerankResult> results,
            OutputWrapper output,
            List<RerankResult> data
    ) {
    }

    private record OutputWrapper(
            List<RerankResult> results
    ) {
    }

    private record RerankResult(
            Integer index,
            @JsonAlias({"relevanceScore", "score"})
            Double relevanceScore
    ) {
        @JsonProperty("relevance_score")
        public Double relevanceScore() {
            return relevanceScore;
        }
    }
}

package io.github.zzz8688.diagagent.store;

import io.github.zzz8688.diagagent.parser.LogEntry;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import dev.langchain4j.model.output.Response;

import java.util.List;

@Service
@Slf4j
public class LogVectorStoreService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public LogVectorStoreService(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    public void ingest(LogEntry logEntry) {
        log.debug("========== [INGEST] 开始写入日志 ==========");
        if (logEntry == null || logEntry.getTemplate() == null || logEntry.getTemplate().isBlank()) {
            log.warn("[INGEST] 日志条目为空或模板为空，跳过");
            return;
        }

        log.debug("[INGEST] 日志模板: {}", logEntry.getTemplate());
        log.debug("[INGEST] 服务名: {}, TraceId: {}, Level: {}", 
                  logEntry.getServiceName(), logEntry.getTraceId(), logEntry.getLevel());

        Metadata metadata = new Metadata();
        metadata.put("serviceName", logEntry.getServiceName());
        metadata.put("traceId", logEntry.getTraceId());
        metadata.put("timestamp", logEntry.getTimestamp().toString());
        metadata.put("level", logEntry.getLevel());
        metadata.put("rawLog", logEntry.getRawLog());

        TextSegment segment = TextSegment.from(logEntry.getTemplate(), metadata);
        log.debug("[INGEST] 创建TextSegment成功, text长度: {}", segment.text().length());

        try {
            log.debug("[INGEST] 调用embeddingModel.embed()...");
            Response<Embedding> response = embeddingModel.embed(segment);
            
            if (response == null) {
                log.error("[INGEST] embeddingModel.embed() 返回 null response!");
                return;
            }
            
            log.debug("[INGEST] embed response不为null");
            
            if (response.content() == null) {
                log.error("[INGEST] response.content() 为 null!");
                return;
            }
            
            Embedding embedding = response.content();
            log.debug("[INGEST] 获取到Embedding对象");
            
            if (embedding.vector() == null) {
                log.error("[INGEST] embedding.vector() 为 null!");
                return;
            }
            
            log.debug("[INGEST] 向量维度: {}, 前5个值: [{}, {}, {}, {}, {}]", 
                      embedding.vector().length,
                      embedding.vector().length > 0 ? embedding.vector()[0] : "N/A",
                      embedding.vector().length > 1 ? embedding.vector()[1] : "N/A",
                      embedding.vector().length > 2 ? embedding.vector()[2] : "N/A",
                      embedding.vector().length > 3 ? embedding.vector()[3] : "N/A",
                      embedding.vector().length > 4 ? embedding.vector()[4] : "N/A");
            
            log.debug("[INGEST] 调用embeddingStore.add()...");
            embeddingStore.add(embedding, segment);
            log.info("[INGEST] 成功写入Milvus: {}", logEntry.getTemplate().substring(0, Math.min(50, logEntry.getTemplate().length())));
            
        } catch (Exception e) {
            log.error("[INGEST] 写入Milvus失败: {}", e.getMessage(), e);
        }
    }

    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        log.info("========== [SEARCH] 开始日志搜索 ==========");
        log.info("[SEARCH] 查询内容: '{}', maxResults: {}", query, maxResults);
        
        if (query == null || query.isBlank()) {
            log.warn("[SEARCH] 查询为空，返回空结果");
            return List.of();
        }

        try {
            log.info("[SEARCH] Step1: 调用embeddingModel.embed()生成查询向量...");
            log.info("[SEARCH] embeddingModel类型: {}", embeddingModel.getClass().getName());
            
            Response<Embedding> response = embeddingModel.embed(query);
            
            if (response == null) {
                log.error("[SEARCH] embeddingModel.embed() 返回 null response!");
                return List.of();
            }
            log.info("[SEARCH] Step2: embed response不为null");
            
            if (response.content() == null) {
                log.error("[SEARCH] response.content() 为 null!");
                return List.of();
            }
            log.info("[SEARCH] Step3: response.content()不为null");

            Embedding queryEmbedding = response.content();
            
            if (queryEmbedding.vector() == null) {
                log.error("[SEARCH] queryEmbedding.vector() 为 null!");
                return List.of();
            }
            
            log.info("[SEARCH] Step4: 查询向量生成成功, 维度: {}, 前3个值: [{}, {}, {}]", 
                     queryEmbedding.vector().length,
                     queryEmbedding.vector().length > 0 ? queryEmbedding.vector()[0] : "N/A",
                     queryEmbedding.vector().length > 1 ? queryEmbedding.vector()[1] : "N/A",
                     queryEmbedding.vector().length > 2 ? queryEmbedding.vector()[2] : "N/A");

            log.info("[SEARCH] Step5: 构建 EmbeddingSearchRequest...");
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.1)
                    .build();
            log.info("[SEARCH] SearchRequest: maxResults={}, minScore=0.1", maxResults);
            
            log.info("[SEARCH] Step6: 调用embeddingStore.search()...");
            log.info("[SEARCH] embeddingStore类型: {}", embeddingStore.getClass().getName());
            
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            
            log.info("[SEARCH] Step7: 搜索完成, 找到 {} 个匹配结果", searchResult.matches().size());
            
            for (int i = 0; i < searchResult.matches().size(); i++) {
                EmbeddingMatch<TextSegment> match = searchResult.matches().get(i);
                log.info("[SEARCH] 匹配{}: score={}, text={}", 
                         i, match.score(), 
                         match.embedded() != null ? match.embedded().text().substring(0, Math.min(50, match.embedded().text().length())) : "null");
            }
            
            return searchResult.matches();
        } catch (Exception e) {
            log.error("[SEARCH] 搜索失败: {}", e.getMessage());
            log.error("[SEARCH] 异常类型: {}", e.getClass().getName());
            log.error("[SEARCH] 异常堆栈: ", e);
            return List.of();
        }
    }
}

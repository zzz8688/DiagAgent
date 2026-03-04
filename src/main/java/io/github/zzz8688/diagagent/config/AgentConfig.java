package io.github.zzz8688.diagagent.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.github.zzz8688.diagagent.agent.SreAgent;
import io.github.zzz8688.diagagent.store.MongoChatMemoryStore;
import io.github.zzz8688.diagagent.tools.KnowledgeQueryTool;
import io.github.zzz8688.diagagent.tools.LogQueryTool;
import io.github.zzz8688.diagagent.tools.MetricQueryTool;
import io.github.zzz8688.diagagent.tools.TopologyQueryTool;
import io.github.zzz8688.diagagent.tools.TraceQueryTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AgentConfig {

    @Value("${langchain4j.dashscope.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.dashscope.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.community.dashscope.embedding-model.model-name:text-embedding-v1}")
    private String embeddingModelName;

    @Value("${milvus.host:localhost}")
    private String milvusHost;

    @Value("${milvus.port:19530}")
    private int milvusPort;

    @Value("${milvus.collection-name:diagagent-embeddings}")
    private String milvusCollectionName;

    @Value("${milvus.dimension:1024}")
    private int milvusDimension;

    private String getApiKey() {
        String finalApiKey = System.getenv("API_Key");
        if (finalApiKey == null || finalApiKey.isBlank()) {
            finalApiKey = apiKey;
        }
        if (finalApiKey == null || finalApiKey.isBlank()) {
            throw new IllegalStateException("API_Key for Dashscope is not configured. Please set the API_Key environment variable or the 'langchain4j.dashscope.chat-model.api-key' property in application.properties.");
        }
        return finalApiKey;
    }

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return QwenChatModel.builder()
                .apiKey(getApiKey())
                .modelName(modelName)
                .maxTokens(4000)
                .temperature(0.7f)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("========== 创建嵌入模型 ==========");
        log.info("模型名称: {}", embeddingModelName);
        
        QwenEmbeddingModel model = QwenEmbeddingModel.builder()
                .apiKey(getApiKey())
                .modelName(embeddingModelName)
                .build();
        
        log.info("嵌入模型类型: {}", model.getClass().getName());
        
        try {
            log.info("测试嵌入模型维度...");
            var response = model.embed("测试文本");
            if (response != null && response.content() != null) {
                log.info("嵌入维度: {}", response.content().vector().length);
            } else {
                log.warn("嵌入模型返回 null");
            }
        } catch (Exception e) {
            log.error("测试嵌入模型失败", e);
        }
        
        return model;
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return QwenStreamingChatModel.builder()
                .apiKey(getApiKey())
                .modelName(modelName)
                .maxTokens(8000)
                .temperature(0.7f)
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("尝试连接 Milvus: {}:{}", milvusHost, milvusPort);

        return MilvusEmbeddingStore.builder()
                .host(milvusHost)
                .port(milvusPort)
                .collectionName(milvusCollectionName)
                .dimension(milvusDimension)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(MongoChatMemoryStore mongoChatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(50)
                .build();
    }
}

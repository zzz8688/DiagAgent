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
import io.milvus.client.MilvusServiceClient;
import io.github.zzz8688.diagagent.service.MemorySummaryService;
import io.github.zzz8688.diagagent.service.MemoryCandidateService;
import io.github.zzz8688.diagagent.agent.runtime.MemoryCompressionEventPublisher;
import io.github.zzz8688.diagagent.store.MongoChatMemoryStore;
import io.github.zzz8688.diagagent.store.SummaryChatMemory;
import io.github.zzz8688.diagagent.tools.KnowledgeQueryTool;
import io.github.zzz8688.diagagent.tools.LogQueryTool;
import io.github.zzz8688.diagagent.tools.TopologyQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

@Configuration
@DependsOn("modelConfig")
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final ModelConfig modelConfig;

    @Value("${langchain4j.community.dashscope.chat-model.api-key}")
    private String apiKey;

    @Value("${milvus.host:localhost}")
    private String milvusHost;

    @Value("${milvus.port:19530}")
    private int milvusPort;

    @Value("${milvus.collection-name:diagagent-embeddings}")
    private String milvusCollectionName;

    @Value("${milvus.log-collection-name:diagagent-logs}")
    private String milvusLogCollectionName;

    @Value("${milvus.knowledge-collection-name:diagagent-knowledge}")
    private String milvusKnowledgeCollectionName;

    @Value("${milvus.memory-collection-name:diagagent-memory}")
    private String milvusMemoryCollectionName;

    @Value("${milvus.dimension:1024}")
    private int milvusDimension;

    @Value("${chat.memory.enable-summary:false}")
    private boolean enableSummary;

    @Value("${chat.memory.max-messages-before-summary:30}")
    private int maxRecentMessages;

    @Value("${chat.memory.questions-per-summary:5}")
    private int questionsPerSummary;

    @Value("${chat.memory.max-tokens:4000}")
    private int chatMemoryMaxTokens;

    public AgentConfig(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

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

    @Bean("chatLanguageModel")
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        String modelName = modelConfig.getChatModelName();
        log.info("========== 创建聊天模型 ==========");
        log.info("使用模型: {}", modelName);

        return QwenChatModel.builder()
                .apiKey(getApiKey())
                .modelName(modelName)
                .maxTokens(4000)
                .temperature(0.7f)
                .build();
    }

    @Bean("criticChatLanguageModel")
    public ChatLanguageModel criticChatLanguageModel() {
        String modelName = modelConfig.getCriticChatModelName();
        log.info("========== 创建 Critic 聊天模型 ==========");
        log.info("使用 Critic 模型: {}", modelName);

        return QwenChatModel.builder()
                .apiKey(getApiKey())
                .modelName(modelName)
                .maxTokens(3000)
                .temperature(0.2f)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        String modelName = modelConfig.getEmbeddingModelName();
        log.info("========== 创建嵌入模型 ==========");
        log.info("模型名称: {}", modelName);

        QwenEmbeddingModel model = QwenEmbeddingModel.builder()
                .apiKey(getApiKey())
                .modelName(modelName)
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
        String modelName = modelConfig.getStreamingChatModelName();
        log.info("========== 创建流式聊天模型 ==========");
        log.info("使用模型: {}", modelName);

        return QwenStreamingChatModel.builder()
                .apiKey(getApiKey())
                .modelName(modelName)
                .maxTokens(8000)
                .temperature(0.7f)
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> logEmbeddingStore() {
        log.info("尝试连接 Milvus (日志): {}:{}", milvusHost, milvusPort);
        
        // 先尝试连接 Milvus，最多尝试 3 次，每次间隔 5 秒
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Milvus (日志) 连接尝试 {}/{}", attempt, maxAttempts);
                return MilvusEmbeddingStore.builder()
                        .host(milvusHost)
                        .port(milvusPort)
                        .collectionName(milvusLogCollectionName)
                        .dimension(milvusDimension)
                        .build();
            } catch (Exception e) {
                log.warn("Milvus (日志) 连接失败 (尝试 {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        log.info("等待 5 秒后重试...");
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        // 如果所有尝试都失败，使用 InMemoryEmbeddingStore 回退
        log.error("所有 Milvus (日志) 连接尝试都失败了，使用 InMemoryEmbeddingStore 作为回退！");
        return new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
    }

    @Bean
    public EmbeddingStore<TextSegment> knowledgeEmbeddingStore() {
        log.info("尝试连接 Milvus (知识库): {}:{}", milvusHost, milvusPort);
        
        // 先尝试连接 Milvus，最多尝试 3 次，每次间隔 5 秒
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Milvus (知识库) 连接尝试 {}/{}", attempt, maxAttempts);
                return MilvusEmbeddingStore.builder()
                        .host(milvusHost)
                        .port(milvusPort)
                        .collectionName(milvusKnowledgeCollectionName)
                        .dimension(milvusDimension)
                        .build();
            } catch (Exception e) {
                log.warn("Milvus (知识库) 连接失败 (尝试 {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        log.info("等待 5 秒后重试...");
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        // 如果所有尝试都失败，使用 InMemoryEmbeddingStore 回退
        log.error("所有 Milvus (知识库) 连接尝试都失败了，使用 InMemoryEmbeddingStore 作为回退！");
        return new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
    }

    @Bean
    public EmbeddingStore<TextSegment> memoryEmbeddingStore() {
        log.info("尝试连接 Milvus (memory files): {}:{}", milvusHost, milvusPort);

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Milvus (memory files) 连接尝试 {}/{}", attempt, maxAttempts);
                return MilvusEmbeddingStore.builder()
                        .host(milvusHost)
                        .port(milvusPort)
                        .collectionName(milvusMemoryCollectionName)
                        .dimension(milvusDimension)
                        .build();
            } catch (Exception e) {
                log.warn("Milvus (memory files) 连接失败 (尝试 {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        log.info("等待 5 秒后重试...");
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        log.error("所有 Milvus (memory files) 连接尝试都失败了，使用 InMemoryEmbeddingStore 作为回退！");
        return new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
    }

    @Bean("logMilvusClient")
    public MilvusServiceClient logMilvusClient() {
        log.info("创建日志 Milvus 客户端: {}:{}", milvusHost, milvusPort);
        return new MilvusServiceClient(io.milvus.param.ConnectParam.newBuilder()
                .withHost(milvusHost)
                .withPort(milvusPort)
                .build());
    }

    @Bean("knowledgeMilvusClient")
    public MilvusServiceClient knowledgeMilvusClient() {
        log.info("创建知识库 Milvus 客户端: {}:{}", milvusHost, milvusPort);
        return new MilvusServiceClient(io.milvus.param.ConnectParam.newBuilder()
                .withHost(milvusHost)
                .withPort(milvusPort)
                .build());
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(MongoChatMemoryStore mongoChatMemoryStore,
                                                  @Qualifier("chatLanguageModel") ChatLanguageModel chatLanguageModel,
                                                  MemorySummaryService memorySummaryService,
                                                  MemoryCandidateService memoryCandidateService,
                                                  MemoryCompressionEventPublisher memoryCompressionEventPublisher) {
        return memoryId -> {
            if (enableSummary) {
                log.info("使用滚动摘要对话记忆，最近消息窗口：{}，每 {} 个新问题压缩一次",
                        maxRecentMessages, questionsPerSummary);
                return new SummaryChatMemory(
                    memoryId,
                    mongoChatMemoryStore,
                    chatLanguageModel,
                    memorySummaryService,
                    memoryCandidateService,
                    memoryCompressionEventPublisher,
                    maxRecentMessages,
                    questionsPerSummary,
                    chatMemoryMaxTokens
                );
            } else {
                log.info("使用消息窗口对话记忆，最大消息数：50");
                return MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(50)
                        .build();
            }
        };
    }
}

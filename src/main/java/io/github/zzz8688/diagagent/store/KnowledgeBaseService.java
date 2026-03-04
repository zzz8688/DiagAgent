package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;

import java.util.List;

@Service
@Slf4j
public class KnowledgeBaseService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public KnowledgeBaseService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        String apiKey = System.getenv("API_Key");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("API_Key environment variable is not set!");
        } else {
            log.info("API_Key found (length: {})", apiKey.length());
        }
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    // @PostConstruct
    public void initKnowledgeBase() {
        log.info("========== KnowledgeBaseService 初始化知识库 ==========");
        addRunbook("KB-000", "诊断指南",
            "诊断流程指导：\n" +
            "当用户提出故障问题时，按以下步骤执行：\n" +
            "\n" +
            "第一步：分析用户问题，确定搜索关键词\n" +
            "- 用户问\"结账失败\"、\"订单失败\" → 搜索关键词：checkout payment failed\n" +
            "- 用户问\"支付卡顿\"、\"支付超时\"、\"支付失败\" → 搜索关键词：payment gateway timeout\n" +
            "- 用户问\"商品加载慢\"、\"商品服务\"、\"商品无法加载\" → 搜索关键词：product database timeout slow query\n" +
            "- 用户问\"数据库连接不上\" → 搜索关键词：database connection timeout\n" +
            "- 用户问\"服务不可用\" → 搜索关键词：service unavailable error\n" +
            "\n" +
            "第二步：调用 queryLogs 搜索相关日志\n" +
            "第三步：分析日志中的错误类型\n" +
            "- 日志有\"Payment declined\" → 支付被拒绝\n" +
            "- 日志有\"503\" → 支付服务不可用\n" +
            "- 日志有\"Gateway timeout\" → 支付网关超时\n" +
            "- 日志有\"Connection timeout\" + \"inventory\" → 数据库连接超时\n" +
            "- 日志有\"Slow query\" → 数据库慢查询\n" +
            "\n" +
            "第四步：调用 queryTrace 追踪链路\n" +
            "第五步：调用 queryMetrics 查询相关服务指标\n" +
            "第六步：调用 queryKnowledgeBase 获取处理建议\n" +
            "第七步：综合所有信息，给出最终结论\n" +
            "\n" +
            "重要：先执行完所有步骤，最后一次性输出完整结论，不要让用户追问。");
        
        addRunbook("KB-001", "故障排查方法论",
            "故障排查最佳实践：\n" +
            "1. 先搜索日志获取错误信息\n" +
            "2. 从日志中提取Trace ID进行链路追踪\n" +
            "3. 查询相关服务的指标和拓扑\n" +
            "4. 匹配知识库获取解决方案\n" +
            "5. 综合分析给出根因结论\n" +
            "\n" +
            "常见故障模式匹配：\n" +
            "- 日志出现 Connection timeout + inventory-db → 数据库连接超时\n" +
            "- 日志出现 Gateway timeout + payment → 支付网关超时\n" +
            "- 日志出现 503 + payment → 支付服务不可用\n" +
            "- 日志出现 internal dependency error → 下游服务故障，需追踪Trace ID");
        
        addRunbook("KB-002", "数据库连接超时故障",
            "故障现象：日志中出现 'Connection to InventoryDB timed out' 或 'Connection timeout'\n" +
            "涉及服务：product-service → inventory-db\n" +
            "根因分析：高负载导致数据库响应缓慢，或网络拥塞导致连接超时\n" +
            "影响范围：product-service 无法获取库存数据，导致结账流程中断\n" +
            "处理步骤：\n" +
            "1. 检查数据库CPU使用率，若超过90%则需要扩容\n" +
            "2. 检查是否存在慢查询或锁竞争\n" +
            "3. 检查网络延迟和连接池配置\n" +
            "4. 优化查询语句或添加索引");
        
        addRunbook("KB-003", "支付网关超时故障",
            "故障现象：日志中出现 'Gateway timeout' 或 'Payment gateway 503 Service Unavailable'\n" +
            "涉及服务：payment-service → external-payment-gateway\n" +
            "根因分析：外部支付提供商服务宕机、网络延迟过高或触发了限流机制\n" +
            "影响范围：支付流程中断，用户无法完成付款\n" +
            "处理步骤：\n" +
            "1. 检查支付提供商官方状态页面确认是否宕机\n" +
            "2. 使用指数退避策略重试交易\n" +
            "3. 考虑切换备用支付网关\n" +
            "4. 在 payment-service 中增加超时和熔断逻辑");
        
        addRunbook("KB-004", "内部依赖错误",
            "故障现象：日志中出现 'internal dependency error' 或 'Failed to call'\n" +
            "涉及服务：order-service → product-service/payment-service\n" +
            "根因分析：下游服务出现故障或响应超时\n" +
            "影响范围：上游服务无法完成请求，导致级联失败\n" +
            "处理步骤：\n" +
            "1. 从日志中提取 Trace ID\n" +
            "2. 通过链路追踪定位具体故障服务\n" +
            "3. 检查故障服务的日志和指标\n" +
            "4. 确认故障服务恢复后重新尝试");
        
        addRunbook("KB-005", "服务健康检查",
            "健康检查流程：\n" +
            "1. 搜索最近日志中的 error、failed、timeout 等关键词\n" +
            "2. 如果没有发现相关错误，记录为正常\n" +
            "3. 检查各服务的 CPU、内存、延迟指标\n" +
            "4. 验证服务间依赖关系是否正常\n" +
            "5. 综合判断系统是否健康\n" +
            "\n" +
            "正常指标参考：\n" +
            "- CPU 使用率 < 80%\n" +
            "- 内存使用率 < 85%\n" +
            "- 错误率 < 1%\n" +
            "- P99 延迟 < 500ms");
        
        addRunbook("KB-006", "慢查询问题",
            "故障现象：数据库查询响应时间过长\n" +
            "涉及服务：inventory-db\n" +
            "根因分析：缺少索引、查询语句低效、数据量过大\n" +
            "影响范围：依赖数据库的所有服务响应变慢\n" +
            "处理步骤：\n" +
            "1. 开启慢查询日志\n" +
            "2. 分析执行计划优化查询\n" +
            "3. 添加必要的索引\n" +
            "4. 考虑分库分表或引入缓存");
        
        addRunbook("KB-007", "服务熔断与降级",
            "熔断机制：\n" +
            "当服务错误率超过阈值时触发熔断，快速失败保护系统\n" +
            "\n" +
            "降级策略：\n" +
            "1. 支付服务不可用时，可以暂时禁用支付功能\n" +
            "2. 商品服务不可用时，可以展示默认库存\n" +
            "3. 库存服务不可用时，可以延迟更新库存\n" +
            "\n" +
            "恢复策略：\n" +
            "1. 熔断后定期探测服务是否恢复\n" +
            "2. 恢复后逐步放开请求流量\n" +
            "3. 监控恢复过程中的指标变化");
        
        log.info("Knowledge Base initialized with comprehensive runbooks.");
    }

    public void addRunbook(String id, String title, String content) {
        Metadata metadata = new Metadata();
        metadata.put("id", id);
        metadata.put("title", title);

        TextSegment segment = TextSegment.from(content, metadata);
        embeddingStore.add(embeddingModel.embed(segment).content(), segment);
    }

    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        log.info("Searching knowledge base for: {}", query);

        if (query == null || query.isBlank()) {
            log.warn("Knowledge base search query is empty, returning empty results");
            return List.of();
        }

        try {
            Response<Embedding> response = embeddingModel.embed(query);
            if (response == null || response.content() == null) {
                log.error("Embedding model returned null for knowledge base query: {}", query);
                return List.of();
            }

            Embedding queryEmbedding = response.content();
            if (queryEmbedding.vector() == null) {
                log.error("Embedding vector is null for knowledge base query: {}", query);
                return List.of();
            }

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.1)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            log.info("Found {} knowledge matches", searchResult.matches().size());
            return searchResult.matches();
        } catch (Exception e) {
            log.error("Error during knowledge base search for query '{}': {}", query, e.getMessage(), e);
            return List.of();
        }
    }
}

package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.config.SystemPromptConfig;
import io.github.zzz8688.diagagent.service.NotificationService;
import io.github.zzz8688.diagagent.service.RedisCacheService;
import io.github.zzz8688.diagagent.service.RedisIdentityGuardService;
import io.github.zzz8688.diagagent.service.RedisRuntimeCoordinationService;
import io.github.zzz8688.diagagent.store.TopologyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final TopologyService topologyService;
    private final SystemPromptConfig systemPromptConfig;
    private final NotificationService notificationService;
    private final RedisCacheService redisCacheService;
    private final RedisIdentityGuardService redisIdentityGuardService;
    private final RedisRuntimeCoordinationService redisRuntimeCoordinationService;
    @GetMapping("/topology")
    public TopologyData getTopology() {
        log.info("获取系统拓扑图");

        List<TopologyNode> nodes = new ArrayList<>();
        List<TopologyLink> links = new ArrayList<>();

        Map<String, String> serviceStatus = getServiceStatus();

        nodes.add(new TopologyNode("frontend", "Frontend", serviceStatus.get("frontend"), 430, 90));
        nodes.add(new TopologyNode("auth-service", "Auth Service", serviceStatus.get("auth-service"), 180, 220));
        nodes.add(new TopologyNode("order-service", "Order Service", serviceStatus.get("order-service"), 430, 240));
        nodes.add(new TopologyNode("product-service", "Product Service", serviceStatus.get("product-service"), 180, 400));
        nodes.add(new TopologyNode("payment-service", "Payment Service", serviceStatus.get("payment-service"), 680, 400));
        nodes.add(new TopologyNode("recommendation-service", "Recommendation Service", serviceStatus.get("recommendation-service"), 180, 570));
        nodes.add(new TopologyNode("promotion-service", "Promotion Service", serviceStatus.get("promotion-service"), 430, 570));
        nodes.add(new TopologyNode("gateway-service", "Gateway Service", serviceStatus.get("gateway-service"), 680, 570));
        nodes.add(new TopologyNode("external-payment-gateway", "Payment Gateway", serviceStatus.get("external-payment-gateway"), 920, 570));
        nodes.add(new TopologyNode("inventory-db", "Inventory DB", serviceStatus.get("inventory-db"), 680, 730));

        links.add(new TopologyLink("frontend", "auth-service"));
        links.add(new TopologyLink("frontend", "order-service"));
        links.add(new TopologyLink("frontend", "product-service"));
        links.add(new TopologyLink("order-service", "payment-service"));
        links.add(new TopologyLink("order-service", "promotion-service"));
        links.add(new TopologyLink("product-service", "recommendation-service"));
        links.add(new TopologyLink("product-service", "inventory-db"));
        links.add(new TopologyLink("payment-service", "gateway-service"));
        links.add(new TopologyLink("gateway-service", "external-payment-gateway"));

        return new TopologyData(nodes, links);
    }

    @GetMapping("/health")
    public List<ServiceHealth> getHealthStatus() {
        log.info("获取服务健康状态");

        List<ServiceHealth> healthList = new ArrayList<>();

        healthList.add(createServiceHealth("frontend", "Frontend", "OK", 23, 31, 780, 1));
        healthList.add(createServiceHealth("order-service", "Order Service", "OK", 29, 39, 690, 1));
        healthList.add(createServiceHealth("product-service", "Product Service", "Warning", 54, 45, 2160, 5));
        healthList.add(createServiceHealth("payment-service", "Payment Service", "Warning", 42, 37, 1840, 8));
        healthList.add(createServiceHealth("auth-service", "Auth Service", "Warning", 36, 34, 1480, 4));
        healthList.add(createServiceHealth("recommendation-service", "Recommendation Service", "Warning", 49, 42, 1910, 5));
        healthList.add(createServiceHealth("promotion-service", "Promotion Service", "Warning", 33, 30, 1280, 3));
        healthList.add(createServiceHealth("gateway-service", "Gateway Service", "Error", 58, 36, 2710, 14));
        healthList.add(createServiceHealth("external-payment-gateway", "Payment Gateway", "Warning", 0, 0, 2960, 11));
        healthList.add(createServiceHealth("inventory-db", "Inventory DB", "OK", 41, 47, 560, 1));

        return healthList;
    }

    private Map<String, String> getServiceStatus() {
        Map<String, String> status = new HashMap<>();
        status.put("frontend", "OK");
        status.put("order-service", "OK");
        status.put("product-service", "Warning");
        status.put("payment-service", "Warning");
        status.put("auth-service", "Warning");
        status.put("recommendation-service", "Warning");
        status.put("promotion-service", "Warning");
        status.put("gateway-service", "Error");
        status.put("external-payment-gateway", "Warning");
        status.put("inventory-db", "OK");
        return status;
    }

    private ServiceHealth createServiceHealth(String id, String name, String status, int cpu, int memory, int latency, int errorRate) {
        return new ServiceHealth(id, name, status, cpu, memory, latency, errorRate);
    }
    
    @GetMapping("/markdown-titles")
    public Map<String, Object> getMarkdownTitles() {
        log.info("获取 Markdown 标题配置");
        try {
            return Map.of(
                "success", true,
                "titles", systemPromptConfig.getMarkdownTitles()
            );
        } catch (Exception e) {
            log.error("获取 Markdown 标题失败", e);
            return Map.of(
                "success", false,
                "error", e.getMessage(),
                "titles", List.of("诊断结论", "根因分析", "详细说明", "建议", "确认")
            );
        }
    }
    
    @GetMapping("/prompt-config")
    public Map<String, Object> getPromptConfig() {
        log.info("获取系统提示词配置");
        try {
            return Map.of(
                "success", true,
                "mainPrompt", systemPromptConfig.getMainAgentPrompt()
            );
        } catch (Exception e) {
            log.error("获取系统提示词配置失败", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    @GetMapping("/test-notification")
    public Mono<Map<String, Object>> testNotification() {
        String title = "系统异常通知";
        String message = "目前模块异常，需要人工介入处理";
        String level = "error";
        log.info("测试发送通知: {} - {}", title, message);

        try {
            String response = notificationService.sendNotification(title, message, level);
            return Mono.just(Map.<String, Object>of(
                    "success", response != null && response.contains("成功"),
                    "message", "通知已发送",
                    "title", title,
                    "content", message,
                    "level", level,
                    "response", response
            ));
        } catch (Exception e) {
            log.error("发送通知失败", e);
            return Mono.just(Map.<String, Object>of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/redis/overview")
    public RedisOverviewData getRedisOverview() {
        log.info("获取 Redis 运行态面板");
        List<RedisHotKeyItem> hotKeys = redisCacheService.getTopHotAnswerStats(10).stream()
                .map(item -> new RedisHotKeyItem(item.cacheKey(), item.score()))
                .toList();
        RedisBloomStats sessionBloom = new RedisBloomStats(
                RedisKeyNames.SESSION,
                redisIdentityGuardService.estimateSessionBloomBits(),
                redisIdentityGuardService.countSessionNullCacheKeys()
        );
        RedisBloomStats taskBloom = new RedisBloomStats(
                RedisKeyNames.TASK,
                redisIdentityGuardService.estimateTaskBloomBits(),
                redisIdentityGuardService.countTaskNullCacheKeys()
        );
        return new RedisOverviewData(
                redisCacheService.isRedisAvailable(),
                redisCacheService.isFallbackActive(),
                redisCacheService.getKnowledgeVersion(),
                redisRuntimeCoordinationService.countActiveSessionLocks(),
                redisRuntimeCoordinationService.countActiveTaskLocks(),
                redisRuntimeCoordinationService.countRateLimitKeys(),
                redisCacheService.countGeneratingSessions(),
                redisCacheService.countCachedAnswerKeys(),
                hotKeys,
                List.of(sessionBloom, taskBloom)
        );
    }

    @PostMapping("/redis/warmup")
    public RedisWarmupResponse warmupRedisHotKeys(@RequestParam(defaultValue = "10") long limit) {
        long requested = Math.max(1L, Math.min(limit, 50L));
        log.info("执行 Redis 热点预热: limit={}", requested);
        RedisCacheService.WarmupResult result = redisCacheService.warmTopHotAnswerKeys(requested);
        return new RedisWarmupResponse(
                requested,
                result.warmed(),
                result.missing(),
                result.failed(),
                "热点答案已从 Redis 预热到本地缓存"
        );
    }

    private static final class RedisKeyNames {
        private static final String SESSION = "session";
        private static final String TASK = "task";

        private RedisKeyNames() {
        }
    }

    public static class TopologyData {
        private List<TopologyNode> nodes;
        private List<TopologyLink> links;

        public TopologyData() {
        }

        public TopologyData(List<TopologyNode> nodes, List<TopologyLink> links) {
            this.nodes = nodes;
            this.links = links;
        }

        public List<TopologyNode> getNodes() {
            return nodes;
        }

        public void setNodes(List<TopologyNode> nodes) {
            this.nodes = nodes;
        }

        public List<TopologyLink> getLinks() {
            return links;
        }

        public void setLinks(List<TopologyLink> links) {
            this.links = links;
        }
    }

    public static class TopologyNode {
        private String id;
        private String name;
        private String status;
        private int x;
        private int y;

        public TopologyNode() {
        }

        public TopologyNode(String id, String name, String status, int x, int y) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.x = x;
            this.y = y;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }
    }

    public static class TopologyLink {
        private String source;
        private String target;

        public TopologyLink() {
        }

        public TopologyLink(String source, String target) {
            this.source = source;
            this.target = target;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }

    public static class ServiceHealth {
        private String id;
        private String name;
        private String status;
        private int cpu;
        private int memory;
        private int latency;
        private int errorRate;

        public ServiceHealth() {
        }

        public ServiceHealth(String id, String name, String status, int cpu, int memory, int latency, int errorRate) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.cpu = cpu;
            this.memory = memory;
            this.latency = latency;
            this.errorRate = errorRate;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getCpu() {
            return cpu;
        }

        public void setCpu(int cpu) {
            this.cpu = cpu;
        }

        public int getMemory() {
            return memory;
        }

        public void setMemory(int memory) {
            this.memory = memory;
        }

        public int getLatency() {
            return latency;
        }

        public void setLatency(int latency) {
            this.latency = latency;
        }

        public int getErrorRate() {
            return errorRate;
        }

        public void setErrorRate(int errorRate) {
            this.errorRate = errorRate;
        }
    }

    public static class RedisOverviewData {
        private boolean redisAvailable;
        private boolean fallbackActive;
        private String knowledgeVersion;
        private long activeSessionLocks;
        private long activeTaskLocks;
        private long rateLimitKeys;
        private long generatingSessions;
        private long cachedAnswerKeys;
        private List<RedisHotKeyItem> hotKeys;
        private List<RedisBloomStats> bloomStats;

        public RedisOverviewData(boolean redisAvailable,
                                 boolean fallbackActive,
                                 String knowledgeVersion,
                                 long activeSessionLocks,
                                 long activeTaskLocks,
                                 long rateLimitKeys,
                                 long generatingSessions,
                                 long cachedAnswerKeys,
                                 List<RedisHotKeyItem> hotKeys,
                                 List<RedisBloomStats> bloomStats) {
            this.redisAvailable = redisAvailable;
            this.fallbackActive = fallbackActive;
            this.knowledgeVersion = knowledgeVersion;
            this.activeSessionLocks = activeSessionLocks;
            this.activeTaskLocks = activeTaskLocks;
            this.rateLimitKeys = rateLimitKeys;
            this.generatingSessions = generatingSessions;
            this.cachedAnswerKeys = cachedAnswerKeys;
            this.hotKeys = hotKeys;
            this.bloomStats = bloomStats;
        }

        public boolean isRedisAvailable() {
            return redisAvailable;
        }

        public boolean isFallbackActive() {
            return fallbackActive;
        }

        public String getKnowledgeVersion() {
            return knowledgeVersion;
        }

        public long getActiveSessionLocks() {
            return activeSessionLocks;
        }

        public long getActiveTaskLocks() {
            return activeTaskLocks;
        }

        public long getRateLimitKeys() {
            return rateLimitKeys;
        }

        public long getGeneratingSessions() {
            return generatingSessions;
        }

        public long getCachedAnswerKeys() {
            return cachedAnswerKeys;
        }

        public List<RedisHotKeyItem> getHotKeys() {
            return hotKeys;
        }

        public List<RedisBloomStats> getBloomStats() {
            return bloomStats;
        }
    }

    public static class RedisHotKeyItem {
        private String cacheKey;
        private double score;

        public RedisHotKeyItem(String cacheKey, double score) {
            this.cacheKey = cacheKey;
            this.score = score;
        }

        public String getCacheKey() {
            return cacheKey;
        }

        public double getScore() {
            return score;
        }
    }

    public static class RedisBloomStats {
        private String kind;
        private long sampledSetBits;
        private long nullCacheKeys;

        public RedisBloomStats(String kind, long sampledSetBits, long nullCacheKeys) {
            this.kind = kind;
            this.sampledSetBits = sampledSetBits;
            this.nullCacheKeys = nullCacheKeys;
        }

        public String getKind() {
            return kind;
        }

        public long getSampledSetBits() {
            return sampledSetBits;
        }

        public long getNullCacheKeys() {
            return nullCacheKeys;
        }
    }

    public static class RedisWarmupResponse {
        private long requested;
        private long warmed;
        private long missing;
        private long failed;
        private String message;

        public RedisWarmupResponse(long requested, long warmed, long missing, long failed, String message) {
            this.requested = requested;
            this.warmed = warmed;
            this.missing = missing;
            this.failed = failed;
            this.message = message;
        }

        public long getRequested() {
            return requested;
        }

        public long getWarmed() {
            return warmed;
        }

        public long getMissing() {
            return missing;
        }

        public long getFailed() {
            return failed;
        }

        public String getMessage() {
            return message;
        }
    }
}

@RestController
class IndexController {

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "status", "ok",
                "message", "DiagAgent is running",
                "version", "1.0.0",
                "endpoints", Map.of(
                        "diagnose", "/api/diagnose (POST)",
                        "diagnose-stream", "/api/diagnose/stream (POST)",
                        "stop-generate", "/api/diagnose/stop/{sessionId} (POST)",
                        "sessions", "/api/sessions (GET)",
                        "health", "/actuator/health (GET)"
                )
        );
    }
}

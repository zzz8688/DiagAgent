package io.github.zzz8688.diagagent.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    private static final String NACOS_SERVER_ADDR = System.getenv("NACOS_SERVER_ADDR") == null
            || System.getenv("NACOS_SERVER_ADDR").isBlank()
            ? "localhost:8848"
            : System.getenv("NACOS_SERVER_ADDR");
    private static final String NACOS_GROUP = "DEFAULT_GROUP";
    private static final String DATA_ID = "diag-agent-model-config";

    private ConfigService configService;

    private String chatModelName = "qvq-max-2025-03-25";
    private String criticChatModelName = "qwen-plus";
    private String streamingChatModelName = "qvq-max-2025-03-25";
    private String embeddingModelName = "text-embedding-v4";
    private String rerankerModelName = "qwen3-rerank";

    private final ConcurrentHashMap<String, String> modelConfigs = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            Properties properties = new Properties();
            properties.put("serverAddr", NACOS_SERVER_ADDR);
            properties.put("username", "nacos");
            properties.put("password", "nacos");
            configService = NacosFactory.createConfigService(properties);

            loadConfigs();
            addListener();
            log.info("模型配置初始化完成，chatModel={}, criticModel={}, embeddingModel={}, rerankerModel={}",
                    chatModelName, criticChatModelName, embeddingModelName, rerankerModelName);
        } catch (NacosException e) {
            log.error("初始化 Nacos ConfigService 失败", e);
        }
    }

    private void loadConfigs() {
        try {
            String config = configService.getConfig(DATA_ID, NACOS_GROUP, 5000);
            if (config != null && !config.isBlank()) {
                parseAndApplyConfig(config);
                log.info("从 Nacos 加载模型配置成功");
            } else {
                log.warn("Nacos 中没有找到模型配置，使用默认配置");
                createDefaultConfigInNacos();
            }
        } catch (NacosException e) {
            log.error("从 Nacos 加载配置失败", e);
        }
    }

    private void parseAndApplyConfig(String config) {
        for (String line : config.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                String value = parts[1].trim();
                modelConfigs.put(key, value);

                switch (key) {
                    case "chatModelName" -> chatModelName = value;
                    case "criticChatModelName" -> criticChatModelName = value;
                    case "streamingChatModelName" -> streamingChatModelName = value;
                    case "embeddingModelName" -> embeddingModelName = value;
                    case "rerankerModelName" -> rerankerModelName = value;
                }
                log.info("应用模型配置: {} = {}", key, value);
            }
        }
    }

    private void createDefaultConfigInNacos() {
        try {
            String defaultConfig = "# DiagAgent 模型配置\n" +
                    "# 大模型配置\n" +
                    "chatModelName=qvq-max-2025-03-25\n" +
                    "criticChatModelName=qwen-plus\n" +
                    "streamingChatModelName=qvq-max-2025-03-25\n" +
                    "# 向量模型配置\n" +
                    "embeddingModelName=text-embedding-v4\n" +
                    "# 精排模型配置\n" +
                    "rerankerModelName=qwen3-rerank\n";

            boolean success = configService.publishConfig(DATA_ID, NACOS_GROUP, defaultConfig);
            if (success) {
                log.info("已创建默认模型配置到 Nacos");
            } else {
                log.warn("创建默认模型配置失败，可能已存在");
            }
        } catch (NacosException e) {
            log.error("创建默认配置失败", e);
        }
    }

    private void addListener() {
        try {
            configService.addListener(DATA_ID, NACOS_GROUP, new AbstractListener() {
                @Override
                public void receiveConfigInfo(String config) {
                    log.info("检测到模型配置变化，重新加载配置");
                    parseAndApplyConfig(config);
                }
            });
        } catch (NacosException e) {
            log.error("添加配置监听失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (configService != null) {
            try {
                configService.shutDown();
            } catch (NacosException e) {
                log.error("关闭 ConfigService 失败", e);
            }
        }
    }

    public String getChatModelName() {
        return chatModelName;
    }

    public String getStreamingChatModelName() {
        return streamingChatModelName;
    }

    public String getCriticChatModelName() {
        return criticChatModelName == null || criticChatModelName.isBlank() ? chatModelName : criticChatModelName;
    }

    public String getEmbeddingModelName() {
        return embeddingModelName;
    }

    public String getRerankerModelName() {
        return rerankerModelName;
    }
}

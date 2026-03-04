package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
@Slf4j
public class MetricQueryTool {

    private final Random random = new Random();

    @Tool("查询特定服务的指标（CPU、内存、延迟）。返回当前值。")
    public String queryMetrics(String serviceName, String metricName) {
        log.info("Agent 正在查询指标: {} - {}", serviceName, metricName);

        if ("inventory-db".equalsIgnoreCase(serviceName) && "cpu".equalsIgnoreCase(metricName)) {
            return "CPU Usage: 95% (Critical)";
        }

        if ("product-service".equalsIgnoreCase(serviceName) && "latency".equalsIgnoreCase(metricName)) {
            return "Latency: 2500ms (High)";
        }

        if ("payment-service".equalsIgnoreCase(serviceName) && "latency".equalsIgnoreCase(metricName)) {
            return "Latency: 3155ms (High)";
        }

        if ("cpu".equalsIgnoreCase(metricName)) {
            return "CPU Usage: " + (random.nextInt(30) + 10) + "% (Normal)";
        }

        if ("memory".equalsIgnoreCase(metricName) || "内存".equals(metricName)) {
             return "Memory Usage: " + (random.nextInt(40) + 20) + "% (Normal)";
        }

        if ("latency".equalsIgnoreCase(metricName) || "延迟".equals(metricName)) {
             return "Latency: " + (random.nextInt(50) + 10) + "ms (Normal)";
        }

        return "Metric not found.";
    }

    @Tool("获取指定服务的所有关键指标（CPU、内存、延迟、错误率）。")
    public String getMetrics(String serviceName) {
        log.info("Agent 正在获取服务指标: {}", serviceName);

        if ("inventory-db".equalsIgnoreCase(serviceName) || "inventorydb".equalsIgnoreCase(serviceName)) {
            return "inventory-db 指标:\n" +
                   "- CPU: 95% (Critical - 需要立即处理)\n" +
                   "- Memory: 78%\n" +
                   "- Latency: 2500ms (High)\n" +
                   "- Error Rate: 45%";
        }

        if ("product-service".equalsIgnoreCase(serviceName)) {
            return "product-service 指标:\n" +
                   "- CPU: 65%\n" +
                   "- Memory: 55%\n" +
                   "- Latency: 2500ms (High - 由于依赖数据库慢)\n" +
                   "- Error Rate: 15%";
        }

        if ("payment-service".equalsIgnoreCase(serviceName)) {
            return "payment-service 指标:\n" +
                   "- CPU: 45%\n" +
                   "- Memory: 40%\n" +
                   "- Latency: 3155ms (Warning)\n" +
                   "- Error Rate: 8%";
        }

        if ("external-payment-gateway".equalsIgnoreCase(serviceName) || "payment-gateway".equalsIgnoreCase(serviceName)) {
            return "external-payment-gateway 指标:\n" +
                   "- CPU: N/A (第三方服务)\n" +
                   "- Latency: 3000ms (Timeout)\n" +
                   "- Error Rate: 100% (当前不可用)";
        }

        return serviceName + " 指标:\n" +
               "- CPU: " + (random.nextInt(30) + 10) + "% (Normal)\n" +
               "- Memory: " + (random.nextInt(40) + 20) + "% (Normal)\n" +
               "- Latency: " + (random.nextInt(50) + 10) + "ms (Normal)\n" +
               "- Error Rate: 0%";
    }
}

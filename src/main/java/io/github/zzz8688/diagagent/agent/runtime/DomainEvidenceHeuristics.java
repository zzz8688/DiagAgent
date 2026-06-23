package io.github.zzz8688.diagagent.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class DomainEvidenceHeuristics {

    private static final Pattern TRACE_OR_REQUEST_ID_PATTERN =
            Pattern.compile("(?i)(traceId|requestId)\\s*[:=]?\\s*([A-Za-z0-9\\-]+)");
    private static final Pattern SERVICE_NAME_PATTERN =
            Pattern.compile("(?i)\\b([a-z0-9\\-]+-service)\\b");

    boolean shouldPreferMetrics(String userQuestion) {
        String normalized = safe(userQuestion).toLowerCase(Locale.ROOT);
        return normalized.contains("错误率")
                || normalized.contains("延迟")
                || normalized.contains("趋势")
                || normalized.contains("指标")
                || normalized.contains("健康");
    }

    boolean isExplicitKnowledgeIntent(String userQuestion) {
        String normalized = safe(userQuestion).toLowerCase(Locale.ROOT);
        return containsAny(
                normalized,
                "知识库",
                "文档",
                "手册",
                "规则",
                "案例",
                "经验",
                "怎么排查",
                "如何排查",
                "是什么意思",
                "说明",
                "原理",
                "告警",
                "排查思路"
        );
    }

    String defaultLogQuery(String userQuestion) {
        String normalized = safe(userQuestion);
        if (normalized.isBlank()) {
            return "最近错误日志";
        }
        if (containsAny(normalized, "错误", "异常", "失败", "超时", "timeout", "error", "failed", "504")) {
            return normalized + " 日志";
        }
        return normalized + " 错误 超时 失败 日志";
    }

    Map<String, String> defaultMetricArguments(String userQuestion) {
        String normalized = safe(userQuestion);
        if (normalized.isBlank()) {
            return Map.of("metricName", "service_error_rate", "timeRange", "15m");
        }
        return Map.of("metricName", normalizeMetricHint(normalized), "timeRange", "15m");
    }

    Map<String, String> metricArgumentsForCriticism(String criticObservation) {
        String normalized = safe(criticObservation).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Map.of("metricName", "service_error_rate", "timeRange", "15m");
        }
        return Map.of("metricName", normalizeMetricHint(normalized), "timeRange", "15m");
    }

    String buildTraceLogQuery(String userQuestion, String criticObservation) {
        String normalizedCriticism = safe(criticObservation);
        Matcher matcher = TRACE_OR_REQUEST_ID_PATTERN.matcher(normalizedCriticism);
        if (matcher.find()) {
            return matcher.group(1) + " " + matcher.group(2) + " timeout error";
        }
        if (normalizedCriticism.contains("traceId")
                || normalizedCriticism.contains("requestId")
                || normalizedCriticism.contains("链路")) {
            return defaultLogQuery(userQuestion) + " traceId requestId";
        }
        return null;
    }

    String extractTraceOrRequestId(String text) {
        Matcher matcher = TRACE_OR_REQUEST_ID_PATTERN.matcher(safe(text));
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }

    String buildChangeLogQuery(String userQuestion, String criticObservation) {
        String serviceName = extractServiceName(criticObservation);
        if (serviceName != null) {
            return serviceName + " 发布 配置变更 deployment release rollback";
        }
        String normalizedQuestion = safe(userQuestion);
        if (normalizedQuestion.contains("支付") || normalizedQuestion.contains("结账")) {
            return "payment-service 发布 配置变更 deployment release rollback";
        }
        return "发布 配置变更 deployment release rollback";
    }

    String extractServiceName(String text) {
        Matcher matcher = SERVICE_NAME_PATTERN.matcher(safe(text));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    boolean criticismRequiresMetrics(String criticObservation) {
        String normalized = safe(criticObservation).toLowerCase(Locale.ROOT);
        return normalized.contains("prometheus")
                || normalized.contains("错误率")
                || normalized.contains("延迟")
                || normalized.contains("依赖状态")
                || normalized.contains("健康")
                || normalized.contains("连接池")
                || normalized.contains("慢查询");
    }

    boolean criticismRequiresTraceLogs(String criticObservation) {
        String normalized = safe(criticObservation).toLowerCase(Locale.ROOT);
        return normalized.contains("traceid")
                || normalized.contains("requestid")
                || normalized.contains("链路");
    }

    boolean criticismRequiresChangeLogs(String criticObservation) {
        String normalized = safe(criticObservation).toLowerCase(Locale.ROOT);
        return normalized.contains("发布")
                || normalized.contains("配置变更")
                || normalized.contains("变更事件")
                || normalized.contains("deployment")
                || normalized.contains("release");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalizeMetricHint(String text) {
        String normalized = safe(text);
        if (normalized.isBlank()) {
            return "service_error_rate";
        }
        if (containsAny(normalized, "健康", "状态", "巡检", "health")) {
            return normalized;
        }
        if (containsAny(normalized, "慢", "延迟", "耗时", "latency", "duration", "p99")) {
            return normalized + " 延迟";
        }
        if (containsAny(normalized, "错误", "异常", "失败", "超时", "timeout", "error", "failed", "504")) {
            return normalized + " 错误";
        }
        return normalized + " 错误 延迟";
    }

    private boolean containsAny(String text, String... candidates) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()
                    && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

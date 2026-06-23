package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.parser.LogEntry;
import io.github.zzz8688.diagagent.parser.LogParserService;
import io.github.zzz8688.diagagent.store.LogVectorStoreService;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class LogQueryTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(LogQueryTool.class);

    private final LogVectorStoreService logVectorStoreService;
    private final LogParserService logParserService;

    @PlatformTool(description = "查询实时入索引的日志证据，支持语义检索、关键词检索与 service/level/traceId/timeRange 过滤")
    @Tool("查询实时入索引的日志证据，支持语义检索、关键词检索与 service/level/traceId/timeRange 过滤")
    public String queryLogs(String query,
                            String timeRange,
                            String service,
                            String level,
                            String traceId,
                            Integer topK) {
        String auditArgs = "query=%s|timeRange=%s|service=%s|level=%s|traceId=%s|topK=%s"
                .formatted(query, timeRange, service, level, traceId, topK);
        log.info("执行日志查询，参数: {}", auditArgs);
        beforeToolCall("queryLogs", auditArgs);
        try {
            checkCancelled();
            int limit = topK == null || topK <= 0 ? 8 : topK;
            List<LogVectorStoreService.LogSearchResult> matches = logVectorStoreService.search(
                    new LogVectorStoreService.LogSearchRequest(query, timeRange, service, level, traceId, limit)
            );
            checkCancelled();
            if (matches.isEmpty()) {
                return """
                        日志检索结果
                        query: %s
                        filters: timeRange=%s, service=%s, level=%s, traceId=%s, topK=%s
                        results:
                        - 未检索到匹配的日志证据
                        """.formatted(
                        safe(query),
                        safe(timeRange),
                        safe(service),
                        safe(level),
                        safe(traceId),
                        limit
                ).trim();
            }

            StringBuilder result = new StringBuilder();
            result.append("日志检索结果\n");
            result.append("query: ").append(safe(query)).append("\n");
            result.append("filters: timeRange=").append(safe(timeRange))
                    .append(", service=").append(safe(service))
                    .append(", level=").append(safe(level))
                    .append(", traceId=").append(safe(traceId))
                    .append(", topK=").append(limit)
                    .append("\n");
            result.append("results:\n");
            int index = 1;
            for (LogVectorStoreService.LogSearchResult match : matches) {
                checkCancelled();
                String messageId = metadata(match, "messageId");
                String timestamp = metadata(match, "timestamp");
                String resultLevel = metadata(match, "level");
                String resultService = metadata(match, "service");
                String resultTraceId = metadata(match, "traceId");
                String resultRequestId = metadata(match, "attr.requestId");
                String upstreamDependency = metadata(match, "attr.upstreamDependency");
                String template = metadata(match, "template");
                String summary = metadata(match, "summary");
                result.append(index++).append(". ")
                        .append("score=").append(format(match.finalScore()))
                        .append(", mode=").append(match.retrievalMode())
                        .append("\n");
                result.append("   messageId=").append(messageId).append("\n");
                result.append("   timestamp=").append(timestamp).append("\n");
                result.append("   service=").append(resultService).append("\n");
                result.append("   level=").append(resultLevel).append("\n");
                result.append("   traceId=").append(resultTraceId).append("\n");
                result.append("   requestId=").append(resultRequestId).append("\n");
                result.append("   upstreamDependency=").append(upstreamDependency).append("\n");
                result.append("   summary=").append(summary).append("\n");
                result.append("   template=").append(template).append("\n");
                result.append("   content=").append(match.segment().text()).append("\n");
            }
            return result.toString();
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询日志失败", e);
            throw new IllegalStateException("查询日志失败: " + e.getMessage(), e);
        }
    }

    @PlatformTool(description = "解析原始日志文本，提取结构化信息")
    @Tool("解析原始日志文本，提取结构化信息")
    public LogEntry parseLog(String rawLog) {
        log.info("解析日志: {}", rawLog);
        beforeToolCall("parseLog", rawLog);
        return logParserService.parse(rawLog);
    }

    @PlatformTool(description = "提取日志模板，识别日志中的变量")
    @Tool("提取日志模板，识别日志中的变量")
    public String extractTemplate(String logMessage) {
        log.info("提取日志模板: {}", logMessage);
        beforeToolCall("extractTemplate", logMessage);
        return logMessage.replaceAll("\\d+", "<NUM>").replaceAll("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", "<UUID>");
    }

    private String metadata(LogVectorStoreService.LogSearchResult match, String key) {
        if (match == null || match.segment() == null || match.segment().metadata() == null) {
            return "";
        }
        String value = match.segment().metadata().getString(key);
        return value == null ? "" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String format(double score) {
        return String.format(Locale.ROOT, "%.3f", score);
    }
}

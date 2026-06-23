package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.tools.KnowledgeQueryTool;
import io.github.zzz8688.diagagent.tools.LogQueryTool;
import io.github.zzz8688.diagagent.tools.MemoryQueryTool;
import io.github.zzz8688.diagagent.tools.TopologyQueryTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LocalToolActionAdapter implements ReactActionAdapter {

    private final LogQueryTool logQueryTool;
    private final TopologyQueryTool topologyQueryTool;
    private final KnowledgeQueryTool knowledgeQueryTool;
    private final MemoryQueryTool memoryQueryTool;

    @Override
    public ReactActionType actionType() {
        return ReactActionType.CALL_LOCAL_TOOL;
    }

    @Override
    public ReactActionExecutionResult execute(ReactAction action,
                                              String userQuestion,
                                              String sessionId,
                                              boolean readOnly) {
        String toolName = normalize(action.target());
        if (toolName == null) {
            return ReactActionExecutionResult.failed(action, null, "Local tool target is empty");
        }
        try {
            Object payload = switch (toolName.toLowerCase(Locale.ROOT)) {
                case "querylogs" -> logQueryTool.queryLogs(
                        arg(action.arguments(), "query", userQuestion),
                        arg(action.arguments(), "timeRange", "15m"),
                        arg(action.arguments(), "service", ""),
                        arg(action.arguments(), "level", "ERROR"),
                        arg(action.arguments(), "traceId", ""),
                        intArg(action.arguments(), "topK", 8)
                );
                case "querytopology" -> topologyQueryTool.queryTopology(
                        arg(action.arguments(), "serviceName", "unknown-service"),
                        arg(action.arguments(), "direction", "downstream")
                );
                case "getdependencies" -> topologyQueryTool.getDependencies(arg(action.arguments(), "serviceName", "unknown-service"));
                case "queryknowledgebase" -> knowledgeQueryTool.queryKnowledgeBase(arg(action.arguments(), "query", userQuestion));
                case "querymemoryfiles" -> memoryQueryTool.queryMemoryFiles(arg(action.arguments(), "query", userQuestion));
                default -> null;
            };
            if (payload == null) {
                return ReactActionExecutionResult.unsupported(action, "Unsupported local tool target: " + toolName);
            }
            return ReactActionExecutionResult.completed(action, payload, "local tool completed");
        } catch (Exception e) {
            return ReactActionExecutionResult.failed(action, null, "local tool failed: " + e.getMessage());
        }
    }

    private String arg(Map<String, String> arguments, String key, String fallback) {
        if (arguments == null) {
            return fallback;
        }
        String value = arguments.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Integer intArg(Map<String, String> arguments, String key, int fallback) {
        String value = arg(arguments, key, Integer.toString(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

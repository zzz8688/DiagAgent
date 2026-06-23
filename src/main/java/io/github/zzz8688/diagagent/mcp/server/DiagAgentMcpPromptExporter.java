package io.github.zzz8688.diagagent.mcp.server;

import io.github.zzz8688.diagagent.config.SystemPromptConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DiagAgentMcpPromptExporter {

    private final SystemPromptConfig systemPromptConfig;

    public List<DiagAgentMcpPrompt> listPrompts() {
        return List.of(
                prompt("main-agent", "主智能体提示词"),
                prompt("app-agent", "应用层诊断提示词"),
                prompt("db-agent", "数据库层诊断提示词"),
                prompt("network-agent", "网络层诊断提示词")
        );
    }

    public DiagAgentMcpPromptGetResult getPrompt(String name, Map<String, Object> arguments) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String suffix = renderSuffix(arguments);
        return switch (normalized) {
            case "main-agent" -> DiagAgentMcpPromptGetResult.success("主智能体提示词", systemPromptConfig.getMainAgentPrompt() + suffix);
            case "app-agent" -> DiagAgentMcpPromptGetResult.success("应用层诊断提示词", systemPromptConfig.getAppAgentPrompt() + suffix);
            case "db-agent" -> DiagAgentMcpPromptGetResult.success("数据库层诊断提示词", systemPromptConfig.getDbAgentPrompt() + suffix);
            case "network-agent" -> DiagAgentMcpPromptGetResult.success("网络层诊断提示词", systemPromptConfig.getNetworkAgentPrompt() + suffix);
            default -> DiagAgentMcpPromptGetResult.error("未找到 prompt: " + name);
        };
    }

    private DiagAgentMcpPrompt prompt(String name, String description) {
        return new DiagAgentMcpPrompt(
                name,
                description,
                List.of(Map.of(
                        "name", "context",
                        "description", "可选的额外上下文，会附加到 prompt 末尾",
                        "required", false
                )),
                Map.of("source", "diagagent")
        );
    }

    private String renderSuffix(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        Object context = arguments.get("context");
        if (context == null || String.valueOf(context).isBlank()) {
            return "";
        }
        return "\n\n附加上下文:\n" + context;
    }
}

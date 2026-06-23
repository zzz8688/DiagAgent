package io.github.zzz8688.diagagent.mcp.server;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.tools.BaseTool;

import java.lang.reflect.Method;

public record DiagAgentMcpInvocableTool(
        String name,
        String description,
        BaseTool bean,
        Method method
) {

    public static DiagAgentMcpInvocableTool from(BaseTool bean, Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        String description = annotation == null ? "" : String.join(" ", annotation.value());
        return new DiagAgentMcpInvocableTool(method.getName(), description, bean, method);
    }
}

package io.github.zzz8688.diagagent.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import io.github.zzz8688.diagagent.tools.BaseTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DiagAgentMcpToolExecutor {

    private final DiagAgentMcpToolExporter toolExporter;
    private final ObjectMapper objectMapper;
    private final SandboxExecutionService sandboxExecutionService;

    public DiagAgentMcpToolCallResult call(String toolName, Map<String, Object> arguments) {
        DiagAgentMcpInvocableTool tool = toolExporter.findInvocableTool(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("未找到工具: " + toolName);
        }

        try {
            SandboxProtectedActionEnvelope<Object> envelope = sandboxExecutionService.executeProtectedAction(
                    tool.name(),
                    SandboxTargetType.MCP_TOOL,
                    tool.name(),
                    arguments == null ? Map.of() : arguments,
                    null,
                    () -> invoke(tool, arguments == null ? Map.of() : arguments)
            );
            if (!envelope.success()) {
                return DiagAgentMcpToolCallResult.error("工具执行失败: " + defaultText(envelope.errorMessage(), "unknown error"));
            }
            Object result = envelope.result();
            return DiagAgentMcpToolCallResult.success(renderResult(result), toStructuredContent(result));
        } catch (BaseTool.ToolExecutionCancelledException e) {
            return DiagAgentMcpToolCallResult.error("工具执行被取消: " + e.getMessage());
        } catch (Exception e) {
            return DiagAgentMcpToolCallResult.error("工具执行失败: " + defaultText(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private Object invoke(DiagAgentMcpInvocableTool tool, Map<String, Object> arguments) throws InvocationTargetException, IllegalAccessException {
        Method method = tool.method();
        Parameter[] parameters = method.getParameters();
        Object[] resolvedArguments = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            String parameterName = parameter.isNamePresent() ? parameter.getName() : "arg" + index;
            Object value = arguments.containsKey(parameterName)
                    ? arguments.get(parameterName)
                    : (parameters.length == 1 && arguments.size() == 1 ? arguments.values().iterator().next() : null);
            if (value == null && parameter.getType().isPrimitive()) {
                throw new IllegalArgumentException("缺少必填参数: " + parameterName);
            }
            resolvedArguments[index] = convertValue(value, parameter.getType());
        }
        try {
            return method.invoke(tool.bean(), resolvedArguments);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof BaseTool.ToolExecutionCancelledException cancelledException) {
                throw cancelledException;
            }
            String message = target == null ? e.getMessage() : target.getMessage();
            throw new IllegalStateException(defaultText(message, target == null ? "unknown error" : target.getClass().getSimpleName()), target);
        }
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return objectMapper.convertValue(value, Integer.class);
        }
        if (targetType == long.class || targetType == Long.class) {
            return objectMapper.convertValue(value, Long.class);
        }
        if (targetType == double.class || targetType == Double.class) {
            return objectMapper.convertValue(value, Double.class);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return objectMapper.convertValue(value, Boolean.class);
        }
        return objectMapper.convertValue(value, targetType);
    }

    private String renderResult(Object result) throws JsonProcessingException {
        if (result == null) {
            return "";
        }
        if (result instanceof String text) {
            return text;
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    private Map<String, Object> toStructuredContent(Object result) {
        if (result == null
                || result instanceof String
                || result instanceof Number
                || result instanceof Boolean
                || result.getClass().isArray()
                || result instanceof java.util.List<?>) {
            return null;
        }
        if (result instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        try {
            return objectMapper.convertValue(result, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

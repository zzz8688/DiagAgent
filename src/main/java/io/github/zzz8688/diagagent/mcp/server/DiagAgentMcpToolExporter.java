package io.github.zzz8688.diagagent.mcp.server;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.tools.BaseTool;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DiagAgentMcpToolExporter {

    private final List<BaseTool> localToolBeans;

    @Value("${mcp.diagagent-server.exposed-tool-names:queryLogs,queryPrometheusMetrics,queryKnowledgeBase,queryMemoryFiles,runShellCommand,queryTopology,getDependencies}")
    private List<String> exposedToolNames;

    public List<DiagAgentMcpExportedTool> listExportableTools() {
        return listInvocableTools().stream()
                .map(this::toExportedTool)
                .sorted(Comparator.comparing(DiagAgentMcpExportedTool::name))
                .toList();
    }

    public DiagAgentMcpInvocableTool findInvocableTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return listInvocableTools().stream()
                .filter(tool -> tool.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    public List<DiagAgentMcpInvocableTool> listInvocableTools() {
        return localToolBeans.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(bean -> bean.getClass().getSimpleName()))
                .flatMap(this::extractTools)
                .filter(this::isExposedTool)
                .sorted(Comparator.comparing(DiagAgentMcpInvocableTool::name))
                .toList();
    }

    private Stream<DiagAgentMcpInvocableTool> extractTools(BaseTool bean) {
        return Stream.of(bean.getClass().getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> DiagAgentMcpInvocableTool.from(bean, method));
    }

    private boolean isExposedTool(DiagAgentMcpInvocableTool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            return false;
        }
        List<String> configuredNames = exposedToolNames == null
                ? List.of()
                : exposedToolNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList();
        if (configuredNames.isEmpty()) {
            return true;
        }
        return configuredNames.stream().anyMatch(name -> name.equalsIgnoreCase(tool.name()));
    }

    private DiagAgentMcpExportedTool toExportedTool(DiagAgentMcpInvocableTool tool) {
        return new DiagAgentMcpExportedTool(
                tool.name(),
                tool.description(),
                buildInputSchema(tool.method()),
                buildOutputSchema(tool.method()),
                buildAnnotations(tool.method()),
                buildMeta(tool)
        );
    }

    private Map<String, Object> buildInputSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            String parameterName = resolveParameterName(parameter, index);
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", mapJsonType(parameter.getType()));
            property.put("title", parameterName);
            property.put("description", buildParameterDescription(parameterName, parameter.getType()));
            properties.put(parameterName, property);
            required.add(parameterName);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", method.getName() + "Input");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> buildOutputSchema(Method method) {
        if (!supportsStructuredOutput(method.getReturnType())) {
            return null;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", method.getName() + "Output");
        schema.put("additionalProperties", true);
        schema.put("description", "Java return type: " + describeType(method.getGenericReturnType()));
        return schema;
    }

    private Map<String, Object> buildAnnotations(Method method) {
        boolean readOnly = isReadOnlyMethod(method.getName());
        return Map.of(
                "title", method.getName(),
                "readOnlyHint", readOnly,
                "destructiveHint", !readOnly,
                "idempotentHint", readOnly
        );
    }

    private Map<String, Object> buildMeta(DiagAgentMcpInvocableTool tool) {
        return Map.of(
                "sourceType", "local",
                "providerId", tool.bean().getClass().getSimpleName(),
                "transport", "in-process",
                "javaMethod", tool.method().getName(),
                "javaBean", tool.method().getDeclaringClass().getName()
        );
    }

    private String resolveParameterName(Parameter parameter, int index) {
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        return "arg" + index;
    }

    private String mapJsonType(Class<?> parameterType) {
        if (parameterType == null) {
            return "string";
        }
        if (Number.class.isAssignableFrom(parameterType)
                || parameterType == int.class
                || parameterType == long.class
                || parameterType == double.class
                || parameterType == float.class
                || parameterType == short.class) {
            return "number";
        }
        if (parameterType == boolean.class || parameterType == Boolean.class) {
            return "boolean";
        }
        if (parameterType == java.util.List.class || parameterType.isArray()) {
            return "array";
        }
        if (parameterType == java.util.Map.class) {
            return "object";
        }
        return "string";
    }

    private String buildParameterDescription(String parameterName, Class<?> parameterType) {
        return "Parameter `" + parameterName + "` of type `" + describeType(parameterType) + "`";
    }

    private boolean supportsStructuredOutput(Class<?> returnType) {
        if (returnType == null
                || returnType == void.class
                || returnType == Void.class
                || returnType == String.class
                || returnType.isPrimitive()
                || Number.class.isAssignableFrom(returnType)
                || returnType == Boolean.class
                || returnType.isArray()
                || List.class.isAssignableFrom(returnType)) {
            return false;
        }
        return true;
    }

    private String describeType(Type type) {
        return type == null ? "java.lang.String" : type.getTypeName();
    }

    private boolean isReadOnlyMethod(String methodName) {
        String normalized = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
        return normalized.startsWith("query")
                || normalized.startsWith("get")
                || normalized.startsWith("read")
                || normalized.startsWith("search")
                || normalized.startsWith("list")
                || normalized.startsWith("parse")
                || normalized.startsWith("extract");
    }
}

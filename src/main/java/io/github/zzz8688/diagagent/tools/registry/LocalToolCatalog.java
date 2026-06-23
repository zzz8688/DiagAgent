package io.github.zzz8688.diagagent.tools.registry;

import io.github.zzz8688.diagagent.tools.BaseTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class LocalToolCatalog {

    private final List<BaseTool> localToolBeans;

    public List<RegisteredTool> listLocalTools() {
        return listLocalToolDescriptors().stream()
                .map(RegisteredTool::fromDescriptor)
                .toList();
    }

    public List<ToolDescriptor> listLocalToolDescriptors() {
        return localToolBeans.stream()
                .sorted(Comparator.comparing(bean -> bean.getClass().getSimpleName()))
                .flatMap(this::extractTools)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public List<ToolDescriptor> resolveToolDescriptors(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        Map<String, ToolDescriptor> descriptorsByName = listLocalToolDescriptors().stream()
                .collect(Collectors.toMap(
                        descriptor -> descriptor.name().toLowerCase(Locale.ROOT),
                        descriptor -> descriptor,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return toolNames.stream()
                .map(name -> descriptorsByName.get(normalize(name)))
                .filter(descriptor -> descriptor != null)
                .toList();
    }

    public Object[] resolveFrameworkToolBeans(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new Object[0];
        }
        Map<String, BaseTool> beansByToolName = localToolBeans.stream()
                .sorted(Comparator.comparing(bean -> bean.getClass().getSimpleName()))
                .flatMap(bean -> extractToolBindings(bean).stream())
                .collect(Collectors.toMap(
                        binding -> binding.descriptor().name().toLowerCase(Locale.ROOT),
                        ToolBinding::bean,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return toolNames.stream()
                .map(this::normalize)
                .map(beansByToolName::get)
                .filter(bean -> bean != null)
                .distinct()
                .toArray();
    }

    private Stream<ToolDescriptor> extractTools(BaseTool bean) {
        return extractToolBindings(bean).stream().map(ToolBinding::descriptor);
    }

    private List<ToolBinding> extractToolBindings(BaseTool bean) {
        return Stream.of(bean.getClass().getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .filter(method -> method.isAnnotationPresent(PlatformTool.class))
                .map(method -> new ToolBinding(bean, toToolDescriptor(bean, method)))
                .toList();
    }

    private ToolDescriptor toToolDescriptor(BaseTool bean, Method method) {
        PlatformTool annotation = method.getAnnotation(PlatformTool.class);
        String toolName = normalizeName(annotation.logicalName(), method.getName());
        return new ToolDescriptor(
                toolName,
                annotation.description(),
                ToolSourceType.LOCAL,
                "local",
                bean.getClass().getSimpleName(),
                bean.getClass().getName(),
                buildSchema(method),
                ToolExecutionPolicy.compatibilityDefault(),
                toBackendStrategy(annotation, toolName),
                annotation.enabled(),
                annotation.userVisible(),
                annotation.transport(),
                annotation.frameworkBindingMode()
        );
    }

    private ToolBackendStrategy toBackendStrategy(PlatformTool annotation, String toolName) {
        String strategyType = normalize(annotation.strategyType());
        return switch (strategyType) {
            case "mcp-only" -> new ToolBackendStrategy(
                    toolName,
                    "mcp-only",
                    annotation.preferredBackend(),
                    "",
                    annotation.strategyNotes()
            );
            case "mcp-preferred-with-local-fallback" -> new ToolBackendStrategy(
                    toolName,
                    "mcp-preferred-with-local-fallback",
                    annotation.preferredBackend(),
                    annotation.fallbackBackend(),
                    annotation.strategyNotes()
            );
            default -> new ToolBackendStrategy(
                    toolName,
                    "local-only",
                    annotation.preferredBackend(),
                    annotation.fallbackBackend(),
                    annotation.strategyNotes()
            );
        };
    }

    private String normalizeName(String explicitName, String fallbackName) {
        return explicitName == null || explicitName.isBlank() ? fallbackName : explicitName.trim();
    }

    private ToolSchema buildSchema(Method method) {
        List<ToolSchemaField> fields = Stream.of(method.getParameters())
                .map(this::toSchemaField)
                .toList();
        return new ToolSchema(fields);
    }

    private ToolSchemaField toSchemaField(Parameter parameter) {
        return new ToolSchemaField(
                parameter.getName(),
                parameter.getType().getSimpleName(),
                true,
                ""
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ToolBinding(BaseTool bean, ToolDescriptor descriptor) {
    }
}

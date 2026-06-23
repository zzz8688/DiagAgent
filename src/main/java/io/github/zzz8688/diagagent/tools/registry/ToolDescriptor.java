package io.github.zzz8688.diagagent.tools.registry;

public record ToolDescriptor(
        String name,
        String description,
        ToolSourceType sourceType,
        String loadedFrom,
        String providerId,
        String beanClassName,
        ToolSchema schema,
        ToolExecutionPolicy executionPolicy,
        ToolBackendStrategy backendStrategy,
        boolean enabled,
        boolean userVisible,
        String transport,
        String frameworkBindingMode
) {

    public ToolDescriptor {
        schema = schema == null ? ToolSchema.empty() : schema;
        executionPolicy = executionPolicy == null ? ToolExecutionPolicy.compatibilityDefault() : executionPolicy;
        backendStrategy = backendStrategy == null ? ToolBackendStrategy.localOnly(name) : backendStrategy;
        frameworkBindingMode = frameworkBindingMode == null ? "" : frameworkBindingMode;
    }
}

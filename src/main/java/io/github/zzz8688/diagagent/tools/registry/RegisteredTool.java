package io.github.zzz8688.diagagent.tools.registry;

public record RegisteredTool(
        String name,
        String description,
        ToolSourceType sourceType,
        String loadedFrom,
        String providerId,
        String beanClassName,
        ToolBackendStrategy backendStrategy,
        boolean enabled,
        boolean userVisible,
        String transport,
        String frameworkBindingMode
) {
    public static RegisteredTool fromDescriptor(ToolDescriptor descriptor) {
        return new RegisteredTool(
                descriptor.name(),
                descriptor.description(),
                descriptor.sourceType(),
                descriptor.loadedFrom(),
                descriptor.providerId(),
                descriptor.beanClassName(),
                descriptor.backendStrategy(),
                descriptor.enabled(),
                descriptor.userVisible(),
                descriptor.transport(),
                descriptor.frameworkBindingMode()
        );
    }
}

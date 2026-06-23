package io.github.zzz8688.diagagent.tools.registry;

public record ToolExecutionPolicy(
        boolean readOnly,
        boolean destructive,
        boolean requiresApproval,
        boolean concurrentSafe,
        String policySource,
        String notes
) {

    public static ToolExecutionPolicy compatibilityDefault() {
        return new ToolExecutionPolicy(
                false,
                false,
                false,
                true,
                "framework-default",
                "Derived from current @Tool annotation defaults. Explicit platform execution policy is not declared yet."
        );
    }
}

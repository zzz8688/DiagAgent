package io.github.zzz8688.diagagent.sandbox.permission;

import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;

import java.util.Map;

public record SandboxPermissionRequest(
        String toolName,
        SandboxTargetType targetType,
        String targetName,
        Map<String, Object> arguments,
        String workingDirectory
) {
    public SandboxPermissionRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

package io.github.zzz8688.diagagent.sandbox.execution;

import io.github.zzz8688.diagagent.sandbox.isolation.SandboxProfile;

import java.time.Duration;
import java.util.Map;

public record SandboxExecutionRequest(
        String executionId,
        SandboxTargetType targetType,
        String targetName,
        Map<String, Object> arguments,
        String workingDirectory,
        Duration timeout,
        SandboxProfile profile
) {
    public SandboxExecutionRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        profile = profile == null ? SandboxProfile.defaultProfile() : profile;
        timeout = timeout == null ? profile.maxWallClockTime() : timeout;
    }
}

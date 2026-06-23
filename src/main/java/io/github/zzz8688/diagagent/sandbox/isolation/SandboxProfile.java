package io.github.zzz8688.diagagent.sandbox.isolation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record SandboxProfile(
        String profileName,
        boolean readOnlyFilesystem,
        List<String> allowReadPaths,
        List<String> allowWritePaths,
        List<String> denyReadPaths,
        List<String> denyWritePaths,
        boolean networkEnabled,
        List<String> allowedDomains,
        List<String> deniedDomains,
        boolean allowUnsandboxedFallback,
        Duration maxWallClockTime,
        long maxOutputBytes,
        Map<String, String> environmentAllowlist
) {
    public SandboxProfile {
        allowReadPaths = allowReadPaths == null ? List.of() : List.copyOf(allowReadPaths);
        allowWritePaths = allowWritePaths == null ? List.of() : List.copyOf(allowWritePaths);
        denyReadPaths = denyReadPaths == null ? List.of() : List.copyOf(denyReadPaths);
        denyWritePaths = denyWritePaths == null ? List.of() : List.copyOf(denyWritePaths);
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        deniedDomains = deniedDomains == null ? List.of() : List.copyOf(deniedDomains);
        environmentAllowlist = environmentAllowlist == null ? Map.of() : Map.copyOf(environmentAllowlist);
        maxWallClockTime = maxWallClockTime == null ? Duration.ofSeconds(30) : maxWallClockTime;
        maxOutputBytes = maxOutputBytes <= 0 ? 30_000 : maxOutputBytes;
    }

    public static SandboxProfile defaultProfile() {
        return new SandboxProfile(
                "default",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                List.of(),
                List.of(),
                false,
                Duration.ofSeconds(30),
                30_000,
                Map.of()
        );
    }
}

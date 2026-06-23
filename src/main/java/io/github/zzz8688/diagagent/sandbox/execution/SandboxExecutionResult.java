package io.github.zzz8688.diagagent.sandbox.execution;

public record SandboxExecutionResult(
        String executionId,
        boolean success,
        boolean timedOut,
        boolean cancelled,
        int exitCode,
        String stdout,
        String stderr,
        String outputDigest
) {
    public static SandboxExecutionResult failed(String executionId, String stderr) {
        return new SandboxExecutionResult(
                executionId,
                false,
                false,
                false,
                -1,
                "",
                stderr == null ? "" : stderr,
                ""
        );
    }
}

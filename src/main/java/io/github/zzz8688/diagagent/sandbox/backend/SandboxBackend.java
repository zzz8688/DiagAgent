package io.github.zzz8688.diagagent.sandbox.backend;

import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionRequest;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionResult;

public interface SandboxBackend {

    SandboxBackendType type();

    boolean available();

    SandboxExecutionResult execute(SandboxExecutionRequest request);
}

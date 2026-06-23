package io.github.zzz8688.diagagent.agent.runtime;

public interface SpecialistRuntime {

    String agentId();

    String bindingMode();

    String diagnose(String userQuestion);
}

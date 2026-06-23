package io.github.zzz8688.diagagent.agent.runtime;

public interface SpecialistCompatInvokerFactory {

    SpecialistCompatInvoker create(String agentId,
                                   String sessionId,
                                   String bindingMode,
                                   String systemPrompt,
                                   Object[] frameworkTools);
}

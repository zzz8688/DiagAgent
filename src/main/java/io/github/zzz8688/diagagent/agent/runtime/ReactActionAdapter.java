package io.github.zzz8688.diagagent.agent.runtime;

public interface ReactActionAdapter {

    ReactActionType actionType();

    ReactActionExecutionResult execute(ReactAction action,
                                       String userQuestion,
                                       String sessionId,
                                       boolean readOnly);
}

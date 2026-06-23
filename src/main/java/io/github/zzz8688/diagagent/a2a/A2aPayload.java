package io.github.zzz8688.diagagent.a2a;

public sealed interface A2aPayload permits A2aMessage, A2aArtifact, A2aTask, A2aTaskStatusUpdateEvent, A2aTaskArtifactUpdateEvent {

    String payloadType();

    default String taskId() {
        return null;
    }

    default String contextId() {
        return null;
    }

    default String taskState() {
        return null;
    }
}

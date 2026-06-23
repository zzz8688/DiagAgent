package io.github.zzz8688.diagagent.a2a;

public enum A2aTaskState {
    TASK_STATE_SUBMITTED(false, false),
    TASK_STATE_WORKING(false, false),
    TASK_STATE_INPUT_REQUIRED(false, true),
    TASK_STATE_AUTH_REQUIRED(false, true),
    TASK_STATE_COMPLETED(true, false),
    TASK_STATE_CANCELED(true, false),
    TASK_STATE_FAILED(true, false),
    TASK_STATE_REJECTED(true, false),
    UNRECOGNIZED(true, false);

    private final boolean finalState;
    private final boolean interrupted;

    A2aTaskState(boolean finalState, boolean interrupted) {
        this.finalState = finalState;
        this.interrupted = interrupted;
    }

    public boolean isFinal() {
        return finalState;
    }

    public boolean isInterrupted() {
        return interrupted;
    }
}

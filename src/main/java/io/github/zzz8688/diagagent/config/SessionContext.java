package io.github.zzz8688.diagagent.config;

public final class SessionContext {

    private static final ThreadLocal<String> SESSION_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> TASK_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> PROTECTED_EXECUTION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private SessionContext() {
    }

    public static void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            SESSION_ID_HOLDER.remove();
            return;
        }
        SESSION_ID_HOLDER.set(sessionId);
    }

    public static void setTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            TASK_ID_HOLDER.remove();
            return;
        }
        TASK_ID_HOLDER.set(taskId);
    }

    public static void setExecutionContext(String sessionId, String taskId) {
        setSessionId(sessionId);
        setTaskId(taskId);
    }

    public static String getSessionId() {
        return SESSION_ID_HOLDER.get();
    }

    public static String getTaskId() {
        return TASK_ID_HOLDER.get();
    }

    public static boolean inProtectedExecution() {
        return PROTECTED_EXECUTION_DEPTH.get() > 0;
    }

    public static void enterProtectedExecution() {
        PROTECTED_EXECUTION_DEPTH.set(PROTECTED_EXECUTION_DEPTH.get() + 1);
    }

    public static void exitProtectedExecution() {
        int depth = PROTECTED_EXECUTION_DEPTH.get();
        if (depth <= 1) {
            PROTECTED_EXECUTION_DEPTH.remove();
            return;
        }
        PROTECTED_EXECUTION_DEPTH.set(depth - 1);
    }

    public static void clear() {
        SESSION_ID_HOLDER.remove();
        TASK_ID_HOLDER.remove();
        PROTECTED_EXECUTION_DEPTH.remove();
    }
}

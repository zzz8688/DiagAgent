package io.github.zzz8688.diagagent.agent.runtime;

public record RuntimeRequest(
        String userQuestion,
        String sessionId,
        boolean readOnly,
        int maxTurns,
        int maxBudgetTokens
) {

    public RuntimeRequest {
        userQuestion = userQuestion == null ? "" : userQuestion;
        sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        maxTurns = Math.max(1, maxTurns);
        maxBudgetTokens = Math.max(1, maxBudgetTokens);
    }

    public static RuntimeRequest diagnostic(String userQuestion,
                                            String sessionId,
                                            boolean readOnly,
                                            int maxTurns,
                                            int maxBudgetTokens) {
        return new RuntimeRequest(userQuestion, sessionId, readOnly, maxTurns, maxBudgetTokens);
    }
}

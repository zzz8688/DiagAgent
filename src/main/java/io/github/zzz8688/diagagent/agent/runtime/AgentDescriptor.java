package io.github.zzz8688.diagagent.agent.runtime;

import java.util.List;

public record AgentDescriptor(
        String agentId,
        String displayName,
        boolean localAgent,
        String transport,
        String endpoint,
        List<String> skills
) {

    public static AgentDescriptor local(String agentId, String displayName, List<String> skills) {
        return new AgentDescriptor(
                agentId,
                displayName,
                true,
                "a2a-local",
                "local://" + agentId,
                List.copyOf(skills)
        );
    }
}

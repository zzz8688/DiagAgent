package io.github.zzz8688.diagagent.a2a;

import java.util.List;

public record A2aCliStateSnapshot(
        List<AgentState> agents
) {

    public record AgentState(
            String agentId,
            String name,
            boolean local,
            String status,
            String endpoint,
            String transport,
            String connectionState,
            int skillCount
    ) {
    }
}

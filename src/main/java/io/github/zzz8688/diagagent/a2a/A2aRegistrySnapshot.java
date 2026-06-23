package io.github.zzz8688.diagagent.a2a;

import java.util.List;

public record A2aRegistrySnapshot(
        List<A2aRemoteAgentRegistration> agents
) {
}

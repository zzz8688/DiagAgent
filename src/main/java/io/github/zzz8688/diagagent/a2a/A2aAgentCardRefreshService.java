package io.github.zzz8688.diagagent.a2a;

import io.github.zzz8688.diagagent.config.A2aProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class A2aAgentCardRefreshService {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentCardRefreshService.class);

    private final A2aProperties a2aProperties;
    private final A2aRegistryService a2aRegistryService;
    private final A2aClientAdapter a2aClientAdapter;

    @Scheduled(fixedDelayString = "${a2a.client.agent-card-refresh-interval-ms:60000}")
    public void refreshRemoteAgentCards() {
        if (!a2aProperties.isEnabled()) {
            return;
        }
        for (A2aRemoteAgentRegistration registration : a2aRegistryService.listRefreshableAgents()) {
            try {
                a2aClientAdapter.fetchAgentCard(registration.agentId());
            } catch (Exception e) {
                log.debug("刷新 A2A AgentCard 失败: agentId={}, error={}", registration.agentId(), e.getMessage());
            }
        }
    }
}

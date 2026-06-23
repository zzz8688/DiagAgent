package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SpecialistRoutingService {

    private static final Logger log = LoggerFactory.getLogger(SpecialistRoutingService.class);

    private final AgentRegistry agentRegistry;
    private final MainAgentRoutingService mainAgentRoutingService;
    private final SessionAssembler sessionAssembler;

    @Value("${diagagent.experimental-specialists-enabled:false}")
    private boolean experimentalSpecialistsEnabled;

    public List<AgentDescriptor> route(String userQuestion,
                                       String sessionId,
                                       boolean readOnly,
                                       ReactTurnState turnState) {
        if (!experimentalSpecialistsEnabled) {
            log.info("Specialist routing is disabled by default: sessionId={}", sessionId);
            return List.of();
        }
        log.info("MainAgent starts routing: sessionId={}, readOnly={}", sessionId, readOnly);
        try {
            SessionAssembler.PromptAssembly promptAssembly = sessionAssembler.buildPromptAssembly(
                    turnState,
                    readOnly,
                    null,
                    "routing",
                    userQuestion
            );
            MainAgentPromptAssembler.MainAgentPromptContext promptContext = promptAssembly.promptContext();
            List<String> routedAgentIds = mainAgentRoutingService.routeAgentIds(userQuestion, promptContext);
            if (routedAgentIds == null || routedAgentIds.isEmpty() || routedAgentIds.contains("all")) {
                return agentRegistry.listSpecialists();
            }

            List<String> validAgentIds = routedAgentIds.stream()
                    .map(String::toLowerCase)
                    .filter(agentRegistry.validSpecialistIds()::contains)
                    .distinct()
                    .toList();
            if (validAgentIds.isEmpty()) {
                return agentRegistry.listSpecialists();
            }
            return agentRegistry.resolve(validAgentIds);
        } catch (Exception e) {
            log.error("MainAgent routing failed, fallback to all local specialists", e);
            return agentRegistry.listSpecialists();
        }
    }
}

package io.github.zzz8688.diagagent.a2a;

import io.github.zzz8688.diagagent.config.A2aProperties;
import io.github.zzz8688.diagagent.protocol.HttpProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolAuthConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportFactory;
import io.github.zzz8688.diagagent.protocol.ProtocolType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class A2aBootstrapService {

    private final A2aProperties a2aProperties;
    private final A2aRegistryService a2aRegistryService;
    private final ProtocolTransportFactory protocolTransportFactory;

    @PostConstruct
    public void registerAgents() {
        if (!a2aProperties.isEnabled()) {
            return;
        }
        registerSelfAgent();
        registerRemoteAgents();
    }

    private void registerSelfAgent() {
        A2aProperties.SelfProperties self = a2aProperties.getSelf();
        if (self == null || !self.isEnabled()) {
            return;
        }
        ProtocolServerConfig config = new HttpProtocolServerConfig(
                self.getAgentId(),
                self.getName(),
                ProtocolType.A2A,
                self.getEndpoint(),
                true,
                ProtocolAuthConfig.none()
        );
        A2aAgentCard card = toAgentCard(
                self.getProtocolVersion(),
                self.getName(),
                self.getDescription(),
                self.getEndpoint(),
                self.getPreferredTransport(),
                self.getVersion(),
                self.isStreaming(),
                self.isPushNotifications(),
                self.getDefaultInputModes(),
                self.getDefaultOutputModes(),
                self.getDocumentationUrl(),
                self.getSkills(),
                Map.of("source", "local", "implements", List.of("agent-card", "registry", "protocol-cli"))
        );
        a2aRegistryService.registerAgent(new A2aRemoteAgentRegistration(
                self.getAgentId(),
                self.getName(),
                true,
                A2aRemoteAgentStatus.LOCAL,
                config,
                protocolTransportFactory.create(config),
                protocolTransportFactory.createConnection(config),
                card,
                null,
                Instant.now().toString()
        ));
    }

    private void registerRemoteAgents() {
        if (a2aProperties.getRemotes() == null) {
            return;
        }
        for (A2aProperties.RemoteAgentProperties remote : a2aProperties.getRemotes()) {
            if (remote == null || remote.getAgentId() == null || remote.getAgentId().isBlank()) {
                continue;
            }
            ProtocolServerConfig config = new HttpProtocolServerConfig(
                    remote.getAgentId(),
                    defaultText(remote.getName(), remote.getAgentId()),
                    ProtocolType.A2A,
                    remote.getEndpoint(),
                    remote.isEnabled(),
                    ProtocolAuthConfig.none()
            );
            A2aAgentCard card = toAgentCard(
                    remote.getProtocolVersion(),
                    defaultText(remote.getName(), remote.getAgentId()),
                    remote.getDescription(),
                    remote.getEndpoint(),
                    remote.getPreferredTransport(),
                    remote.getVersion(),
                    remote.isStreaming(),
                    remote.isPushNotifications(),
                    remote.getDefaultInputModes(),
                    remote.getDefaultOutputModes(),
                    remote.getDocumentationUrl(),
                    remote.getSkills(),
                    Map.of("source", "configured-remote")
            );
            a2aRegistryService.registerAgent(new A2aRemoteAgentRegistration(
                    remote.getAgentId(),
                    defaultText(remote.getName(), remote.getAgentId()),
                    false,
                    remote.isEnabled() ? A2aRemoteAgentStatus.REGISTERED : A2aRemoteAgentStatus.DISABLED,
                    config,
                    protocolTransportFactory.create(config),
                    protocolTransportFactory.createConnection(config),
                    card,
                    null,
                    Instant.now().toString()
            ));
        }
    }

    private A2aAgentCard toAgentCard(String protocolVersion,
                                     String name,
                                     String description,
                                     String endpoint,
                                     String preferredTransport,
                                     String version,
                                     boolean streaming,
                                     boolean pushNotifications,
                                     List<String> defaultInputModes,
                                     List<String> defaultOutputModes,
                                     String documentationUrl,
                                     List<A2aProperties.SkillProperties> skills,
                                     Map<String, Object> metadata) {
        List<A2aAgentCard.AgentSkill> agentSkills = skills == null ? List.of() : skills.stream()
                .filter(skill -> skill.getId() != null && !skill.getId().isBlank())
                .map(skill -> new A2aAgentCard.AgentSkill(
                        skill.getId(),
                        defaultText(skill.getName(), skill.getId()),
                        defaultText(skill.getDescription(), ""),
                        skill.getTags() == null ? List.of() : List.copyOf(skill.getTags()),
                        skill.getExamples() == null ? null : List.copyOf(skill.getExamples()),
                        null,
                        null,
                        null
                ))
                .toList();
        return new A2aAgentCard(
                name,
                defaultText(description, ""),
                null,
                defaultText(version, "1.0.0"),
                documentationUrl,
                new A2aAgentCard.AgentCapabilities(streaming, pushNotifications, false, null),
                defaultInputModes == null ? List.of("text") : List.copyOf(defaultInputModes),
                defaultOutputModes == null ? List.of("text") : List.copyOf(defaultOutputModes),
                agentSkills,
                null,
                null,
                null,
                List.of(new A2aAgentCard.AgentInterface(
                        normalizeProtocolBinding(preferredTransport),
                        endpoint,
                        null,
                        defaultText(protocolVersion, "1.0")
                )),
                null,
                endpoint,
                normalizeProtocolBinding(preferredTransport),
                List.of(new A2aAgentCard.LegacyAgentInterface(normalizeProtocolBinding(preferredTransport), endpoint))
        );
    }

    private String normalizeProtocolBinding(String preferredTransport) {
        String value = defaultText(preferredTransport, "JSONRPC").trim();
        return value.toUpperCase();
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

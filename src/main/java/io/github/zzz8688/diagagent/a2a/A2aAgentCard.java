package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aAgentCard(
        String name,
        String description,
        AgentProvider provider,
        String version,
        String documentationUrl,
        AgentCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<AgentSkill> skills,
        Map<String, Object> securitySchemes,
        List<SecurityRequirement> securityRequirements,
        String iconUrl,
        List<AgentInterface> supportedInterfaces,
        List<AgentCardSignature> signatures,
        String url,
        String preferredTransport,
        List<LegacyAgentInterface> additionalInterfaces
) {

    public record AgentProvider(
            String organization,
            String url
    ) {
    }

    public record AgentCapabilities(
            boolean streaming,
            boolean pushNotifications,
            boolean extendedAgentCard,
            List<AgentExtension> extensions
    ) {
    }

    public record AgentExtension(
            String uri,
            String description,
            boolean required
    ) {
    }

    public record AgentInterface(
            String protocolBinding,
            String url,
            String tenant,
            String protocolVersion
    ) {
    }

    public record AgentSkill(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes,
            List<SecurityRequirement> securityRequirements
    ) {
    }

    public record SecurityRequirement(
            Map<String, List<String>> schemes
    ) {
    }

    public record AgentCardSignature(
            String protectedHeader,
            String signature,
            String header
    ) {
    }

    public record LegacyAgentInterface(
            String transport,
            String url
    ) {
    }
}

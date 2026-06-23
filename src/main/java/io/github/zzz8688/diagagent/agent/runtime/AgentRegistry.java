package io.github.zzz8688.diagagent.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentRegistry {

    private final Map<String, AgentDescriptor> descriptors = new LinkedHashMap<>();

    public AgentRegistry() {
        register(AgentDescriptor.local("app", "App Specialist", List.of(
                "diagnosis-playbook-skill",
                "general-troubleshooting-skill",
                "service-health-check-skill"
        )));
        register(AgentDescriptor.local("db", "Database Specialist", List.of(
                "diagnosis-playbook-skill",
                "general-troubleshooting-skill",
                "service-health-check-skill"
        )));
        register(AgentDescriptor.local("network", "Network Specialist", List.of(
                "diagnosis-playbook-skill",
                "general-troubleshooting-skill",
                "service-health-check-skill"
        )));
    }

    public void register(AgentDescriptor descriptor) {
        descriptors.put(descriptor.agentId(), descriptor);
    }

    public Optional<AgentDescriptor> findById(String agentId) {
        return Optional.ofNullable(descriptors.get(agentId));
    }

    public List<AgentDescriptor> listSpecialists() {
        return List.copyOf(descriptors.values());
    }

    public List<String> validSpecialistIds() {
        return descriptors.keySet().stream().toList();
    }

    public List<AgentDescriptor> resolve(List<String> agentIds) {
        return agentIds.stream()
                .map(descriptors::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.a2a.A2aRemoteAgentRegistration;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.config.A2aProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CriticA2aAgentLocator {

    private final A2aProperties a2aProperties;
    private final A2aRegistryService a2aRegistryService;

    public Optional<A2aProperties.RemoteAgentProperties> configuredRemote() {
        List<A2aProperties.RemoteAgentProperties> remotes = a2aProperties.getRemotes();
        if (remotes == null || remotes.isEmpty()) {
            return Optional.empty();
        }
        return remotes.stream()
                .filter(this::isCriticRemote)
                .findFirst();
    }

    public Optional<String> configuredAgentId() {
        return configuredRemote()
                .map(A2aProperties.RemoteAgentProperties::getAgentId)
                .filter(value -> value != null && !value.isBlank());
    }

    public Optional<A2aRemoteAgentRegistration> registeredRemote() {
        return configuredAgentId().flatMap(a2aRegistryService::findAgent);
    }

    private boolean isCriticRemote(A2aProperties.RemoteAgentProperties remote) {
        if (remote == null) {
            return false;
        }
        if (contains(remote.getAgentId(), "critic")) {
            return true;
        }
        if (contains(remote.getName(), "critic") || contains(remote.getDescription(), "批评")) {
            return true;
        }
        List<A2aProperties.SkillProperties> skills = remote.getSkills();
        if (skills == null || skills.isEmpty()) {
            return false;
        }
        return skills.stream().anyMatch(skill ->
                contains(skill.getId(), "final-answer-review")
                        || contains(skill.getId(), "critic")
                        || contains(skill.getName(), "critic")
                        || contains(skill.getDescription(), "批评"));
    }

    private boolean contains(String value, String expected) {
        if (value == null || value.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }
}

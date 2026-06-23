package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CapabilitySnapshotLoader {

    private final CapabilityViewAssembler capabilityViewAssembler;

    public SessionAssembler.CapabilitySnapshot load(boolean readOnly) {
        CapabilityViewAssembler.CapabilityView capabilityView = capabilityViewAssembler.assemble(readOnly);
        CapabilityViewAssembler.CapabilityManifestOverview overview = capabilityViewAssembler.overview(readOnly);
        return new SessionAssembler.CapabilitySnapshot(
                capabilityView,
                capabilityViewAssembler.renderPromptSection(capabilityView),
                overview
        );
    }
}

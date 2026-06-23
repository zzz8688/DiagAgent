package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.agent.runtime.CapabilityViewAssembler;
import io.github.zzz8688.diagagent.mcp.McpCapabilitySnapshot;
import io.github.zzz8688.diagagent.mcp.McpCliStateSnapshot;
import io.github.zzz8688.diagagent.mcp.McpRegistrySnapshot;
import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import io.github.zzz8688.diagagent.mcp.McpServerRegistration;
import io.github.zzz8688.diagagent.mcp.server.DiagAgentMcpExportedTool;
import io.github.zzz8688.diagagent.mcp.server.DiagAgentMcpToolExporter;
import io.github.zzz8688.diagagent.a2a.A2aCliStateSnapshot;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.a2a.A2aRegistrySnapshot;
import io.github.zzz8688.diagagent.a2a.A2aRemoteAgentRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/protocol")
@RequiredArgsConstructor
public class ProtocolRegistryController {

    private final McpRegistryService mcpRegistryService;
    private final DiagAgentMcpToolExporter diagAgentMcpToolExporter;
    private final A2aRegistryService a2aRegistryService;
    private final CapabilityViewAssembler capabilityViewAssembler;

    @GetMapping("/capabilities/manifest")
    public ResponseEntity<CapabilityViewAssembler.CapabilityManifest> getCapabilityManifest() {
        return ResponseEntity.ok(capabilityViewAssembler.manifest(false));
    }

    @GetMapping("/capabilities/overview")
    public ResponseEntity<CapabilityViewAssembler.CapabilityManifestOverview> getCapabilityOverview() {
        return ResponseEntity.ok(capabilityViewAssembler.overview(false));
    }

    @GetMapping("/mcp")
    public ResponseEntity<McpRegistrySnapshot> getMcpRegistry() {
        return ResponseEntity.ok(mcpRegistryService.snapshot());
    }

    @GetMapping("/mcp/state")
    public ResponseEntity<McpCliStateSnapshot> getMcpCliState() {
        return ResponseEntity.ok(mcpRegistryService.cliState());
    }

    @GetMapping("/mcp/servers/{serverId}")
    public ResponseEntity<McpServerRegistration> getMcpServer(@PathVariable String serverId) {
        return ResponseEntity.of(mcpRegistryService.findServer(serverId));
    }

    @GetMapping("/mcp/capabilities/{serverId}")
    public ResponseEntity<McpCapabilitySnapshot> getMcpCapabilitySnapshot(@PathVariable String serverId) {
        return ResponseEntity.of(mcpRegistryService.capabilitySnapshot(serverId));
    }

    @GetMapping("/mcp/export/tools")
    public ResponseEntity<List<DiagAgentMcpExportedTool>> listExportableMcpTools() {
        return ResponseEntity.ok(diagAgentMcpToolExporter.listExportableTools());
    }

    @GetMapping("/a2a")
    public ResponseEntity<A2aRegistrySnapshot> getA2aRegistry() {
        return ResponseEntity.ok(a2aRegistryService.snapshot());
    }

    @GetMapping("/a2a/state")
    public ResponseEntity<A2aCliStateSnapshot> getA2aCliState() {
        return ResponseEntity.ok(a2aRegistryService.cliState());
    }

    @GetMapping("/a2a/agents/{agentId}")
    public ResponseEntity<A2aRemoteAgentRegistration> getA2aAgent(@PathVariable String agentId) {
        return ResponseEntity.of(a2aRegistryService.findAgent(agentId));
    }
}

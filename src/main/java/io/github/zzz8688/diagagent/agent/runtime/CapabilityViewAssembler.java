package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.mcp.McpCapabilitySnapshot;
import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import io.github.zzz8688.diagagent.mcp.McpToolRegistration;
import io.github.zzz8688.diagagent.skills.SkillDoc;
import io.github.zzz8688.diagagent.skills.SkillService;
import io.github.zzz8688.diagagent.tools.registry.LocalToolCatalog;
import io.github.zzz8688.diagagent.tools.registry.RegisteredTool;
import io.github.zzz8688.diagagent.tools.registry.ToolBackendStrategy;
import io.github.zzz8688.diagagent.tools.registry.ToolDescriptor;
import io.github.zzz8688.diagagent.tools.registry.ToolExecutionPolicy;
import io.github.zzz8688.diagagent.tools.registry.ToolSchema;
import io.github.zzz8688.diagagent.tools.registry.ToolSchemaField;
import io.github.zzz8688.diagagent.tools.registry.ToolRegistry;
import io.github.zzz8688.diagagent.tools.registry.ToolSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CapabilityViewAssembler {

    private static final String DEFAULT_SELF_MCP_SERVER_ID = "diagagent-mcp-server";

    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final LocalToolCatalog localToolCatalog;
    private final A2aRegistryService a2aRegistryService;
    private final McpRegistryService mcpRegistryService;
    private final SkillService skillService;
    private final McpPromptInjectionAssembler mcpPromptInjectionAssembler;

    @Value("${mcp.diagagent-server.visible-in-runtime-tool-pool:false}")
    private boolean selfMcpVisibleInRuntimeToolPool;

    @Value("${diagagent.experimental-specialists-enabled:false}")
    private boolean experimentalSpecialistsEnabled;

    public CapabilityView assemble(boolean readOnly) {
        List<ToolDescriptor> visibleLocalToolDescriptors = loadVisibleLocalToolDescriptors();
        List<McpToolRegistration> visibleMcpTools = loadVisibleMcpTools();

        List<CapabilityEntry> localSpecialists = experimentalSpecialistsEnabled
                ? agentRegistry.listSpecialists().stream()
                .map(this::toLocalSpecialistEntry)
                .toList()
                : List.of();

        List<CapabilityEntry> localTools = visibleLocalToolDescriptors.stream()
                .filter(this::isRuntimeLocalTool)
                .map(this::toDescriptorBackedToolEntry)
                .toList();

        List<CapabilityEntry> mcpTools = buildRuntimeMcpToolEntries(visibleLocalToolDescriptors, visibleMcpTools);

        List<CapabilityEntry> skills = skillService.listVisibleSkills().stream()
                .map(this::toSkillEntry)
                .toList();

        List<CapabilityEntry> remoteSpecialists = experimentalSpecialistsEnabled
                ? a2aRegistryService.snapshot().agents().stream()
                .filter(agent -> !agent.localAgent())
                .map(this::toRemoteSpecialistEntry)
                .toList()
                : List.of();

        return new CapabilityView(
                capabilityViewVersion(),
                readOnly,
                localSpecialists,
                localTools,
                mcpTools,
                skills,
                remoteSpecialists
        );
    }

    public CapabilityManifest manifest(boolean readOnly) {
        CapabilityView view = assemble(readOnly);
        List<CapabilityEntry> allEntries = new ArrayList<>();
        allEntries.addAll(view.localSpecialists());
        allEntries.addAll(view.localTools());
        allEntries.addAll(view.mcpTools());
        allEntries.addAll(view.skills());
        allEntries.addAll(view.remoteSpecialists());
        Map<String, Long> countsByKind = new LinkedHashMap<>();
        allEntries.stream()
                .map(CapabilityEntry::kind)
                .filter(kind -> kind != null && !kind.isBlank())
                .forEach(kind -> countsByKind.put(kind, countsByKind.getOrDefault(kind, 0L) + 1L));
        return new CapabilityManifest(
                view.viewVersion(),
                view.readOnly(),
                allEntries.size(),
                Map.copyOf(countsByKind),
                allEntries
        );
    }

    public CapabilityManifestOverview overview(boolean readOnly) {
        CapabilityManifest manifest = manifest(readOnly);
        Map<String, Long> countsBySource = new LinkedHashMap<>();
        Map<String, Long> countsByDisplayMode = new LinkedHashMap<>();
        Map<String, Long> countsByTag = new LinkedHashMap<>();
        int entriesWithSchema = 0;
        int schemaFieldCount = 0;
        int approvalRequiredCount = 0;
        int readOnlyCount = 0;
        int destructiveCount = 0;
        int concurrentSafeCount = 0;
        int entriesWithHelpText = 0;
        int entriesWithTags = 0;
        int entriesWithPolicyNotes = 0;
        for (CapabilityEntry entry : manifest.entries()) {
            if (entry == null) {
                continue;
            }
            incrementCount(countsBySource, normalizeKey(entry.source()));
            incrementCount(countsByDisplayMode, normalizeKey(entry.uiHint() == null ? "" : entry.uiHint().displayMode()));
            if (entry.schema() != null && entry.schema().fields() != null && !entry.schema().fields().isEmpty()) {
                entriesWithSchema++;
                schemaFieldCount += entry.schema().fields().size();
            }
            if (entry.executionPolicy() != null) {
                if (entry.executionPolicy().requiresApproval()) {
                    approvalRequiredCount++;
                }
                if (entry.executionPolicy().readOnly()) {
                    readOnlyCount++;
                }
                if (entry.executionPolicy().destructive()) {
                    destructiveCount++;
                }
                if (entry.executionPolicy().concurrentSafe()) {
                    concurrentSafeCount++;
                }
                if (!safeText(entry.executionPolicy().notes()).isBlank()) {
                    entriesWithPolicyNotes++;
                }
            }
            if (entry.uiHint() != null) {
                if (!safeText(entry.uiHint().helpText()).isBlank()) {
                    entriesWithHelpText++;
                }
                if (entry.uiHint().tags() != null && !entry.uiHint().tags().isEmpty()) {
                    entriesWithTags++;
                    entry.uiHint().tags().forEach(tag -> incrementCount(countsByTag, normalizeKey(tag)));
                }
            }
        }
        return new CapabilityManifestOverview(
                manifest.viewVersion(),
                manifest.readOnly(),
                manifest.totalEntryCount(),
                manifest.countsByKind(),
                Map.copyOf(countsBySource),
                Map.copyOf(countsByDisplayMode),
                Map.copyOf(countsByTag),
                entriesWithSchema,
                schemaFieldCount,
                approvalRequiredCount,
                readOnlyCount,
                destructiveCount,
                concurrentSafeCount,
                entriesWithHelpText,
                entriesWithTags,
                entriesWithPolicyNotes,
                buildRegistryAlignment(manifest.entries())
        );
    }

    public String renderPromptSection(boolean readOnly) {
        return renderPromptSection(assemble(readOnly));
    }

    public String renderPromptSection(CapabilityView capabilityView) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "Local Specialists", capabilityView.localSpecialists());
        appendSection(builder, "Local Tools", capabilityView.localTools());
        appendSection(builder, "MCP Tools", capabilityView.mcpTools());
        appendSection(builder, "Skills", capabilityView.skills());
        appendSection(builder, "Remote Specialists", capabilityView.remoteSpecialists());
        return builder.toString().trim();
    }

    public String capabilityViewVersion() {
        return mcpPromptInjectionAssembler.capabilityViewVersion();
    }

    public boolean hasLocalSpecialist(String target) {
        return experimentalSpecialistsEnabled && target != null && agentRegistry.findById(target).isPresent();
    }

    public boolean hasLocalTool(String target) {
        return target != null && loadVisibleLocalToolDescriptors().stream()
                .filter(this::isRuntimeLocalTool)
                .anyMatch(descriptor -> target.equalsIgnoreCase(descriptor.name()));
    }

    public boolean hasMcpTool(String serverId, String toolName) {
        return serverId != null
                && toolName != null
                && loadVisibleMcpTools().stream()
                .anyMatch(tool -> serverId.equalsIgnoreCase(tool.serverId())
                        && toolName.equalsIgnoreCase(tool.toolName()));
    }

    public boolean hasRemoteSpecialist(String target) {
        return experimentalSpecialistsEnabled && target != null && a2aRegistryService.findAgent(target)
                .filter(agent -> !agent.localAgent())
                .isPresent();
    }

    public AgentDescriptor fallbackLocalSpecialist() {
        if (!experimentalSpecialistsEnabled) {
            return null;
        }
        return agentRegistry.listSpecialists().stream().findFirst().orElse(null);
    }

    public String defaultMcpTarget() {
        return loadVisibleMcpTools().stream()
                .findFirst()
                .map(tool -> buildMcpTarget(tool.serverId(), tool.toolName()))
                .orElse("prometheus-mcp/queryPrometheusMetrics");
    }

    private void appendSection(StringBuilder builder, String title, List<CapabilityEntry> entries) {
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("## ").append(title).append("\n");
        if (entries == null || entries.isEmpty()) {
            builder.append("- none");
            return;
        }
        builder.append(entries.stream()
                .map(entry -> "- %s: %s".formatted(entry.id(), safeText(entry.description())))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- none"));
    }

    private CapabilityEntry toLocalSpecialistEntry(AgentDescriptor descriptor) {
        return new CapabilityEntry(
                descriptor.agentId(),
                descriptor.displayName(),
                "specialist",
                "local",
                "local specialist",
                ToolSchema.empty(),
                new ToolExecutionPolicy(true, false, false, true, "specialist-default", "Local specialist is routed by runtime and does not directly mutate platform state."),
                new UiHint("selector", "cpu", List.of("runtime", "specialist"), "Select local specialist by agentId"),
                Map.of("skills", descriptor.skills())
        );
    }

    private CapabilityEntry toDescriptorBackedToolEntry(ToolDescriptor descriptor) {
        ToolSourceType runtimeSourceType = resolveRuntimeToolSourceType(descriptor);
        String runtimeProviderId = resolveRuntimeProviderId(descriptor, runtimeSourceType);
        String runtimeTransport = resolveRuntimeTransport(descriptor, runtimeSourceType);
        String runtimeId = resolveRuntimeToolId(descriptor, runtimeSourceType, runtimeProviderId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerId", runtimeProviderId);
        metadata.put("transport", runtimeTransport);
        metadata.put("loadedFrom", safeText(descriptor.loadedFrom()));
        metadata.put("frameworkBindingMode", safeText(descriptor.frameworkBindingMode()));
        metadata.put("backendStrategyType", descriptor.backendStrategy() == null ? "" : safeText(descriptor.backendStrategy().strategyType()));
        metadata.put("preferredBackend", descriptor.backendStrategy() == null ? "" : safeText(descriptor.backendStrategy().preferredBackend()));
        metadata.put("fallbackBackend", descriptor.backendStrategy() == null ? "" : safeText(descriptor.backendStrategy().fallbackBackend()));
        if (runtimeSourceType == ToolSourceType.MCP) {
            metadata.put("logicalToolName", safeText(descriptor.name()));
            metadata.put("catalogProviderId", safeText(descriptor.providerId()));
        }
        return new CapabilityEntry(
                runtimeId,
                descriptor.name(),
                "tool",
                runtimeSourceType == ToolSourceType.MCP ? "mcp" : "local",
                renderToolContract(descriptor.name(), descriptor.description(), descriptor.schema()),
                descriptor.schema(),
                descriptor.executionPolicy(),
                buildToolUiHint(descriptor.name(), runtimeSourceType, runtimeTransport),
                Map.copyOf(metadata)
        );
    }

    private CapabilityEntry toMcpToolEntry(McpToolRegistration tool) {
        return new CapabilityEntry(
                buildMcpTarget(tool.serverId(), tool.toolName()),
                tool.toolName(),
                "tool",
                "mcp",
                safeText(tool.description()),
                toToolSchema(tool.inputSchema()),
                new ToolExecutionPolicy(false, false, true, false, "mcp-runtime-governed", "Remote MCP tool calls go through sandbox, approval, audit and runtime events."),
                buildToolUiHint(tool.toolName(), ToolSourceType.MCP, "mcp"),
                buildMcpToolMetadata(tool)
        );
    }

    private Map<String, Object> buildMcpToolMetadata(McpToolRegistration tool) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerId", safeText(tool.serverId()));
        metadata.put("transport", "mcp");
        if (tool.outputSchema() != null) {
            metadata.put("outputSchema", tool.outputSchema());
        }
        if (tool.annotations() != null) {
            metadata.put("annotations", tool.annotations());
        }
        return Map.copyOf(metadata);
    }

    private CapabilityEntry toSkillEntry(SkillDoc skill) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("allowedTools", skill.allowedTools());
        metadata.put("argumentNames", skill.argumentNames());
        metadata.put("executionMode", skill.executionSchema() == null ? "" : safeText(skill.executionSchema().executionMode()));
        metadata.put("executionContext", skill.executionSchema() == null ? "" : safeText(skill.executionSchema().executionContext()));
        metadata.put("targetAgent", skill.executionSchema() == null ? "" : safeText(skill.executionSchema().targetAgent()));
        metadata.put("scriptCount", skill.executionSchema() == null ? 0 : skill.executionSchema().scriptFiles().size());
        metadata.put("executionSchemaSource", skill.executionSchema() == null ? "" : safeText(skill.executionSchema().schemaSource()));
        metadata.put("version", safeText(skill.version()));
        metadata.put("source", safeText(skill.source()));
        return new CapabilityEntry(
                skill.id(),
                safeText(skill.title()),
                "skill",
                "registry",
                safeText(skill.description()),
                new ToolSchema(skill.argumentNames().stream()
                        .map(name -> new ToolSchemaField(name, "string", false, "Skill argument"))
                        .toList()),
                new ToolExecutionPolicy(true, false, false, true, "skill-plan", "Skill is discovered and expanded before runtime executes concrete tools."),
                new UiHint("drawer", "book", List.of("runtime", "skills"), safeText(skill.argumentHint())),
                Map.copyOf(metadata)
        );
    }

    private CapabilityEntry toRemoteSpecialistEntry(io.github.zzz8688.diagagent.a2a.A2aRemoteAgentRegistration agent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", agent.transportSpec() == null || agent.transportSpec().transportType() == null
                ? ""
                : agent.transportSpec().transportType().name());
        metadata.put("status", agent.status() == null ? "" : agent.status().name());
        metadata.put("endpoint", agent.serverConfig() == null ? "" : safeText(agent.serverConfig().endpoint()));
        if (agent.agentCard() != null) {
            metadata.put("skills", agent.agentCard().skills());
        }
        return new CapabilityEntry(
                agent.agentId(),
                agent.agentName(),
                "specialist",
                "remote",
                "remote specialist",
                ToolSchema.empty(),
                new ToolExecutionPolicy(false, false, false, false, "a2a-runtime-governed", "Remote specialist runs through runtime/A2A bridge instead of direct local execution."),
                new UiHint("selector", "globe", List.of("runtime", "a2a"), "Select remote specialist by agentId"),
                Map.copyOf(metadata)
        );
    }

    private List<McpToolRegistration> loadVisibleMcpTools() {
        return mcpRegistryService.snapshot().capabilitySnapshots().stream()
                .flatMap(snapshot -> snapshot.toolRegistrations().stream())
                .filter(McpToolRegistration::enabled)
                .filter(McpToolRegistration::userVisible)
                .filter(tool -> selfMcpVisibleInRuntimeToolPool || !isSelfManagedMcpTool(tool))
                .toList();
    }

    private List<ToolDescriptor> loadVisibleLocalToolDescriptors() {
        return localToolCatalog.listLocalToolDescriptors().stream()
                .filter(ToolDescriptor::userVisible)
                .filter(ToolDescriptor::enabled)
                .toList();
    }

    private List<CapabilityEntry> buildRuntimeMcpToolEntries(List<ToolDescriptor> visibleLocalToolDescriptors,
                                                             List<McpToolRegistration> visibleMcpTools) {
        List<CapabilityEntry> entries = new ArrayList<>(visibleMcpTools.stream()
                .map(this::toMcpToolEntry)
                .toList());
        visibleLocalToolDescriptors.stream()
                .filter(this::isRuntimeMcpTool)
                .filter(descriptor -> !isCoveredByRegisteredMcpTool(descriptor, visibleMcpTools))
                .map(this::toDescriptorBackedToolEntry)
                .forEach(entries::add);
        return List.copyOf(entries);
    }

    private boolean isRuntimeLocalTool(ToolDescriptor descriptor) {
        return resolveRuntimeToolSourceType(descriptor) == ToolSourceType.LOCAL
                && !isSkillLifecycleTool(descriptor);
    }

    private boolean isRuntimeMcpTool(ToolDescriptor descriptor) {
        return resolveRuntimeToolSourceType(descriptor) == ToolSourceType.MCP
                && !isSkillLifecycleTool(descriptor);
    }

    private ToolSourceType resolveRuntimeToolSourceType(ToolDescriptor descriptor) {
        if (descriptor == null) {
            return ToolSourceType.LOCAL;
        }
        ToolBackendStrategy backendStrategy = descriptor.backendStrategy();
        if (backendStrategy != null) {
            String strategyType = safeText(backendStrategy.strategyType()).trim().toLowerCase(Locale.ROOT);
            if ("mcp-only".equals(strategyType) || "mcp-preferred-with-local-fallback".equals(strategyType)) {
                return ToolSourceType.MCP;
            }
            if ("local-only".equals(strategyType)) {
                return ToolSourceType.LOCAL;
            }
            String preferredBackend = safeText(backendStrategy.preferredBackend()).trim().toLowerCase(Locale.ROOT);
            if (preferredBackend.startsWith("mcp:")) {
                return ToolSourceType.MCP;
            }
            if ("local".equals(preferredBackend)) {
                return ToolSourceType.LOCAL;
            }
        }
        return descriptor.sourceType() == ToolSourceType.MCP ? ToolSourceType.MCP : ToolSourceType.LOCAL;
    }

    private String resolveRuntimeProviderId(ToolDescriptor descriptor, ToolSourceType runtimeSourceType) {
        if (runtimeSourceType == ToolSourceType.MCP) {
            String preferredBackend = descriptor == null || descriptor.backendStrategy() == null
                    ? ""
                    : safeText(descriptor.backendStrategy().preferredBackend()).trim();
            if (preferredBackend.regionMatches(true, 0, "mcp:", 0, 4)) {
                return safeText(preferredBackend.substring(4).trim());
            }
        }
        return descriptor == null ? "" : safeText(descriptor.providerId());
    }

    private String resolveRuntimeTransport(ToolDescriptor descriptor, ToolSourceType runtimeSourceType) {
        if (runtimeSourceType == ToolSourceType.MCP) {
            return "mcp";
        }
        String transport = descriptor == null ? "" : safeText(descriptor.transport());
        return transport.isBlank() ? "in-process" : transport;
    }

    private String resolveRuntimeToolId(ToolDescriptor descriptor,
                                        ToolSourceType runtimeSourceType,
                                        String runtimeProviderId) {
        if (runtimeSourceType == ToolSourceType.MCP && runtimeProviderId != null && !runtimeProviderId.isBlank()) {
            return buildMcpTarget(runtimeProviderId, descriptor == null ? "" : descriptor.name());
        }
        return descriptor == null ? "" : descriptor.name();
    }

    private boolean isCoveredByRegisteredMcpTool(ToolDescriptor descriptor, List<McpToolRegistration> visibleMcpTools) {
        String runtimeProviderId = resolveRuntimeProviderId(descriptor, ToolSourceType.MCP);
        String runtimeTarget = resolveRuntimeToolId(descriptor, ToolSourceType.MCP, runtimeProviderId);
        return visibleMcpTools.stream()
                .anyMatch(tool -> runtimeTarget.equalsIgnoreCase(buildMcpTarget(tool.serverId(), tool.toolName())));
    }

    private boolean isSelfManagedMcpTool(McpToolRegistration tool) {
        return tool != null
                && tool.serverId() != null
                && DEFAULT_SELF_MCP_SERVER_ID.equalsIgnoreCase(tool.serverId());
    }

    private String renderToolContract(String name, String description, ToolSchema schema) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String signature = switch (normalized) {
            case "querylogs" -> "args={query}";
            case "querymetrics" -> "args={metricName,timeRange}";
            case "querytopology" -> "args={serviceName,direction}";
            case "getdependencies" -> "args={serviceName}";
            case "queryknowledgebase" -> "args={query}";
            case "querymemoryfiles" -> "args={query}";
            default -> schema == null || schema.fields().isEmpty()
                    ? "args={...}"
                    : "args={" + schema.fields().stream().map(ToolSchemaField::name).reduce((left, right) -> left + "," + right).orElse("...") + "}";
        };
        return "%s | %s".formatted(signature, safeText(description));
    }

    private boolean isSkillLifecycleTool(ToolDescriptor descriptor) {
        if (descriptor == null || descriptor.name() == null) {
            return false;
        }
        String normalized = descriptor.name().trim().toLowerCase(Locale.ROOT);
        return normalized.equals("queryskills")
                || normalized.equals("queryskillcontent")
                || normalized.equals("queryskillresource")
                || normalized.equals("runskill");
    }

    private String buildMcpTarget(String serverId, String toolName) {
        return safeText(serverId) + "/" + safeText(toolName);
    }

    private ToolSchema toToolSchema(Object inputSchema) {
        if (!(inputSchema instanceof Map<?, ?> schemaMap)) {
            return ToolSchema.empty();
        }
        Object propertiesValue = schemaMap.get("properties");
        Object requiredValue = schemaMap.get("required");
        List<String> requiredFields = requiredValue instanceof List<?> requiredList
                ? requiredList.stream().map(String::valueOf).toList()
                : List.of();
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return ToolSchema.empty();
        }
        List<ToolSchemaField> fields = properties.entrySet().stream()
                .map(entry -> toSchemaField(String.valueOf(entry.getKey()), entry.getValue(), requiredFields))
                .toList();
        return new ToolSchema(fields);
    }

    private ToolSchemaField toSchemaField(String name, Object propertyValue, List<String> requiredFields) {
        if (propertyValue instanceof Map<?, ?> propertyMap) {
            String type = safeText(stringValue(propertyMap.get("type")));
            String description = safeText(stringValue(propertyMap.get("description")));
            return new ToolSchemaField(
                    name,
                    type.isBlank() ? "object" : type,
                    requiredFields.contains(name),
                    description
            );
        }
        return new ToolSchemaField(name, "object", requiredFields.contains(name), "");
    }

    private UiHint buildToolUiHint(String toolName, ToolSourceType sourceType, String transport) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        String icon = switch (normalized) {
            case "querylogs" -> "file-search";
            case "querymetrics" -> "chart";
            case "querytopology", "getdependencies" -> "network";
            case "runskill" -> "book";
            default -> "tool";
        };
        String displayMode = sourceType == ToolSourceType.MCP ? "modal" : "inline";
        return new UiHint(
                displayMode,
                icon,
                List.of(safeText(sourceType == null ? "" : sourceType.name()).toLowerCase(Locale.ROOT), safeText(transport)),
                "Render arguments from schema before execution"
        );
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private RegistryAlignmentSummary buildRegistryAlignment(List<CapabilityEntry> entries) {
        List<ToolDescriptor> visibleLocalToolDescriptors = loadVisibleLocalToolDescriptors();
        List<McpToolRegistration> visibleMcpTools = loadVisibleMcpTools();
        return new RegistryAlignmentSummary(
                countEntries(entries, "specialist", "local"),
                agentRegistry.listSpecialists().size(),
                countEntries(entries, "tool", "local"),
                (int) visibleLocalToolDescriptors.stream()
                        .filter(this::isRuntimeLocalTool)
                        .count(),
                countEntries(entries, "tool", "mcp"),
                visibleMcpTools.size() + (int) visibleLocalToolDescriptors.stream()
                        .filter(this::isRuntimeMcpTool)
                        .filter(descriptor -> !isCoveredByRegisteredMcpTool(descriptor, visibleMcpTools))
                        .count(),
                countEntries(entries, "skill", "registry"),
                skillService.listVisibleSkills().size(),
                countEntries(entries, "specialist", "remote"),
                (int) a2aRegistryService.snapshot().agents().stream().filter(agent -> !agent.localAgent()).count()
        );
    }

    private int countEntries(List<CapabilityEntry> entries, String kind, String source) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        return (int) entries.stream()
                .filter(entry -> entry != null)
                .filter(entry -> normalizeKey(entry.kind()).equals(normalizeKey(kind)))
                .filter(entry -> normalizeKey(entry.source()).equals(normalizeKey(source)))
                .count();
    }

    private void incrementCount(Map<String, Long> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0L) + 1L);
    }

    private String normalizeKey(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record CapabilityView(
            String viewVersion,
            boolean readOnly,
            List<CapabilityEntry> localSpecialists,
            List<CapabilityEntry> localTools,
            List<CapabilityEntry> mcpTools,
            List<CapabilityEntry> skills,
            List<CapabilityEntry> remoteSpecialists
    ) {
        public boolean hasLocalSpecialist(String target) {
            return containsId(localSpecialists, target);
        }

        public boolean hasLocalTool(String target) {
            return containsId(localTools, target);
        }

        public boolean hasMcpTool(String serverId, String toolName) {
            return mcpTools != null && mcpTools.stream()
                    .anyMatch(entry -> entry != null
                            && matches(entry.id(), serverId + "/" + toolName));
        }

        public boolean hasRemoteSpecialist(String target) {
            return containsId(remoteSpecialists, target);
        }

        public String defaultMcpTarget() {
            return mcpTools == null || mcpTools.isEmpty()
                    ? "prometheus-mcp/queryPrometheusMetrics"
                    : mcpTools.get(0).id();
        }

        public CapabilityEntry fallbackLocalSpecialist() {
            return localSpecialists == null || localSpecialists.isEmpty()
                    ? null
                    : localSpecialists.get(0);
        }

        public int totalEntryCount() {
            return sizeOf(localSpecialists)
                    + sizeOf(localTools)
                    + sizeOf(mcpTools)
                    + sizeOf(skills)
                    + sizeOf(remoteSpecialists);
        }

        private boolean containsId(List<CapabilityEntry> entries, String target) {
            return entries != null && entries.stream()
                    .anyMatch(entry -> entry != null && matches(entry.id(), target));
        }

        private boolean matches(String left, String right) {
            return left != null && right != null && left.equalsIgnoreCase(right);
        }

        private int sizeOf(List<CapabilityEntry> entries) {
            return entries == null ? 0 : entries.size();
        }
    }

    public record CapabilityEntry(
            String id,
            String displayName,
            String kind,
            String source,
            String description,
            ToolSchema schema,
            ToolExecutionPolicy executionPolicy,
            UiHint uiHint,
            Map<String, Object> metadata
    ) {
        public CapabilityEntry {
            schema = schema == null ? ToolSchema.empty() : schema;
            executionPolicy = executionPolicy == null ? ToolExecutionPolicy.compatibilityDefault() : executionPolicy;
            uiHint = uiHint == null ? new UiHint("inline", "tool", List.of(), "") : uiHint;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record UiHint(
            String displayMode,
            String icon,
            List<String> tags,
            String helpText
    ) {
        public UiHint {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record CapabilityManifest(
            String viewVersion,
            boolean readOnly,
            int totalEntryCount,
            Map<String, Long> countsByKind,
            List<CapabilityEntry> entries
    ) {
    }

    public record CapabilityManifestOverview(
            String viewVersion,
            boolean readOnly,
            int totalEntryCount,
            Map<String, Long> countsByKind,
            Map<String, Long> countsBySource,
            Map<String, Long> countsByDisplayMode,
            Map<String, Long> countsByTag,
            int entriesWithSchema,
            int schemaFieldCount,
            int approvalRequiredCount,
            int readOnlyCount,
            int destructiveCount,
            int concurrentSafeCount,
            int entriesWithHelpText,
            int entriesWithTags,
            int entriesWithPolicyNotes,
            RegistryAlignmentSummary registryAlignment
    ) {
    }

    public record RegistryAlignmentSummary(
            int manifestLocalSpecialistCount,
            int runtimeLocalSpecialistCount,
            int manifestLocalToolCount,
            int runtimeLocalToolCount,
            int manifestMcpToolCount,
            int runtimeMcpToolCount,
            int manifestSkillCount,
            int runtimeSkillCount,
            int manifestRemoteSpecialistCount,
            int runtimeRemoteSpecialistCount
    ) {
    }
}

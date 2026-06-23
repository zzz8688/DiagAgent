package io.github.zzz8688.diagagent.mcp;

import io.github.zzz8688.diagagent.protocol.ProtocolTransportSpec;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.reflect.Array;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class McpRegistryService {

    private final Map<String, McpServerRegistration> servers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, McpToolRegistration>> toolsByServer = new ConcurrentHashMap<>();
    private final Map<String, Map<String, McpResourceRegistration>> resourcesByServer = new ConcurrentHashMap<>();
    private final Map<String, Map<String, McpPromptRegistration>> promptsByServer = new ConcurrentHashMap<>();

    public McpRegistrySnapshot snapshot() {
        List<McpServerRegistration> serverList = servers.values().stream()
                .sorted(Comparator.comparing(McpServerRegistration::serverId))
                .toList();
        List<McpToolRegistration> toolList = toolsByServer.values().stream()
                .flatMap(serverTools -> serverTools.values().stream())
                .sorted(Comparator.comparing(McpToolRegistration::serverId)
                        .thenComparing(McpToolRegistration::toolName))
                .toList();
        List<McpResourceRegistration> resourceList = resourcesByServer.values().stream()
                .flatMap(serverResources -> serverResources.values().stream())
                .sorted(Comparator.comparing(McpResourceRegistration::serverId)
                        .thenComparing(McpResourceRegistration::uri))
                .toList();
        List<McpPromptRegistration> promptList = promptsByServer.values().stream()
                .flatMap(serverPrompts -> serverPrompts.values().stream())
                .sorted(Comparator.comparing(McpPromptRegistration::serverId)
                        .thenComparing(McpPromptRegistration::promptName))
                .toList();
        List<McpCapabilitySnapshot> capabilitySnapshots = serverList.stream()
                .map(this::toCapabilitySnapshot)
                .toList();
        return new McpRegistrySnapshot(serverList, toolList, resourceList, promptList, capabilitySnapshots);
    }

    public Optional<McpCapabilitySnapshot> capabilitySnapshot(String serverId) {
        return Optional.ofNullable(servers.get(serverId))
                .map(this::toCapabilitySnapshot);
    }

    public Optional<McpServerRegistration> findServer(String serverId) {
        return Optional.ofNullable(servers.get(serverId));
    }

    public List<McpServerRegistration> listServers() {
        return servers.values().stream()
                .sorted(Comparator.comparing(McpServerRegistration::serverId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<McpToolRegistration> findTool(String serverId, String toolName) {
        if (serverId == null || serverId.isBlank() || toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolsByServer.getOrDefault(serverId, Map.of()).get(toolKey(toolName)));
    }

    public McpCliStateSnapshot cliState() {
        List<McpServerRegistration> serverList = servers.values().stream()
                .sorted(Comparator.comparing(McpServerRegistration::serverId))
                .toList();
        List<McpSerializedClient> clients = serverList.stream()
                .map(this::toSerializedClient)
                .toList();
        Map<String, McpServerConfigSnapshot> configs = serverList.stream()
                .collect(Collectors.toMap(
                        McpServerRegistration::serverId,
                        this::toServerConfigSnapshot,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        List<McpSerializedTool> serializedTools = toolsByServer.values().stream()
                .flatMap(serverTools -> serverTools.values().stream())
                .sorted(Comparator.comparing(McpToolRegistration::serverId)
                        .thenComparing(McpToolRegistration::toolName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toSerializedTool)
                .toList();
        Map<String, List<McpResourceRegistration>> resources = serverList.stream()
                .collect(Collectors.toMap(
                        McpServerRegistration::serverId,
                        server -> listServerResources(server.serverId()),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Map<String, String> normalizedNames = serializedTools.stream()
                .collect(Collectors.toMap(
                        tool -> normalizeToolName(tool.originalToolName()),
                        McpSerializedTool::originalToolName,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return new McpCliStateSnapshot(clients, configs, serializedTools, resources, normalizedNames);
    }

    public void registerServer(McpServerRegistration server) {
        servers.compute(server.serverId(), (serverId, existing) -> mergeServer(existing, server));
        if (hasRegisteredCapabilities(server.serverId())) {
            refreshCapabilitySnapshot(server.serverId());
        }
    }

    public void registerTool(McpToolRegistration tool) {
        toolsByServer.computeIfAbsent(tool.serverId(), ignored -> new ConcurrentHashMap<>())
                .put(toolKey(tool.toolName()), tool);
        refreshCapabilitySnapshot(tool.serverId());
    }

    public void replaceServerTools(String serverId, List<McpToolRegistration> tools) {
        Map<String, McpToolRegistration> serverTools = new ConcurrentHashMap<>();
        for (McpToolRegistration tool : tools) {
            serverTools.put(toolKey(tool.toolName()), tool);
        }
        toolsByServer.put(serverId, serverTools);
        refreshCapabilitySnapshot(serverId);
    }

    public void replaceServerResources(String serverId, List<McpResourceRegistration> resources) {
        Map<String, McpResourceRegistration> serverResources = new ConcurrentHashMap<>();
        for (McpResourceRegistration resource : resources) {
            serverResources.put(resourceKey(resource.uri()), resource);
        }
        resourcesByServer.put(serverId, serverResources);
        refreshCapabilitySnapshot(serverId);
    }

    public void replaceServerPrompts(String serverId, List<McpPromptRegistration> prompts) {
        Map<String, McpPromptRegistration> serverPrompts = new ConcurrentHashMap<>();
        for (McpPromptRegistration prompt : prompts) {
            serverPrompts.put(promptKey(prompt.promptName()), prompt);
        }
        promptsByServer.put(serverId, serverPrompts);
        refreshCapabilitySnapshot(serverId);
    }

    public void enableServer(String serverId) {
        updateServer(serverId, existing -> existing.withEnabled(true, McpServerStatus.REGISTERED, now(), null));
    }

    public void disableServer(String serverId) {
        updateServer(serverId, existing -> existing.withEnabled(false, McpServerStatus.DISABLED, now(), null));
    }

    public void markConnecting(String serverId) {
        markConnecting(serverId, null, null, null);
    }

    public void markConnecting(String serverId,
                               Integer reconnectAttempt,
                               Integer maxReconnectAttempts,
                               String nextRetryAt) {
        String timestamp = now();
        updateServer(serverId, existing -> existing
                .withReconnectState(reconnectAttempt, maxReconnectAttempts, nextRetryAt, timestamp)
                .withStatus(McpServerStatus.CONNECTING, existing.lastConnectedAt(), timestamp, null));
    }

    public void markReconnecting(String serverId,
                                 Integer reconnectAttempt,
                                 Integer maxReconnectAttempts,
                                 String nextRetryAt) {
        String timestamp = now();
        updateServer(serverId, existing -> existing
                .withReconnectState(reconnectAttempt, maxReconnectAttempts, nextRetryAt, timestamp)
                .withStatus(McpServerStatus.RECONNECTING, existing.lastConnectedAt(), timestamp, null));
    }

    public void markConnected(String serverId) {
        String timestamp = now();
        updateServer(serverId, existing -> existing
                .withReconnectState(null, null, null, timestamp)
                .withStatus(McpServerStatus.CONNECTED, timestamp, timestamp, null));
    }

    public void markDegraded(String serverId, String message) {
        updateServer(serverId, existing -> existing.withStatus(McpServerStatus.DEGRADED, existing.lastConnectedAt(), now(), normalizeMessage(message)));
    }

    public void markAuthRequired(String serverId, String message) {
        updateServer(serverId, existing -> existing.withStatus(McpServerStatus.AUTH_REQUIRED, existing.lastConnectedAt(), now(), normalizeMessage(message)));
    }

    public void markFailed(String serverId, String message) {
        markFailed(serverId, message, null, null, null);
    }

    public void markFailed(String serverId,
                           String message,
                           Integer reconnectAttempt,
                           Integer maxReconnectAttempts,
                           String nextRetryAt) {
        String timestamp = now();
        updateServer(serverId, existing -> existing
                .withReconnectState(reconnectAttempt, maxReconnectAttempts, nextRetryAt, timestamp)
                .withStatus(McpServerStatus.FAILED, existing.lastConnectedAt(), timestamp, normalizeMessage(message)));
    }

    public void markClosed(String serverId) {
        updateServer(serverId, existing -> existing.withStatus(McpServerStatus.CLOSED, existing.lastConnectedAt(), now(), existing.lastError()));
    }

    public void updateTransportSpec(String serverId, ProtocolTransportSpec transportSpec) {
        if (transportSpec == null) {
            return;
        }
        updateServer(serverId, existing -> existing.withTransportSpec(transportSpec, now()));
        refreshCapabilitySnapshot(serverId);
    }

    public void updateTransportConnection(String serverId, io.github.zzz8688.diagagent.protocol.ProtocolTransportConnection transportConnection) {
        if (transportConnection == null) {
            return;
        }
        updateServer(serverId, existing -> existing.withTransportConnection(transportConnection));
        refreshCapabilitySnapshot(serverId);
    }

    private McpServerRegistration mergeServer(McpServerRegistration existing, McpServerRegistration incoming) {
        if (existing == null) {
            return incoming;
        }
        ProtocolTransportSpec mergedTransportSpec = incoming.transportSpec() == null
                ? existing.transportSpec()
                : incoming.transportSpec();
        var mergedTransportConnection = incoming.transportConnection() == null
                ? existing.transportConnection()
                : incoming.transportConnection();
        return new McpServerRegistration(
                incoming.serverId(),
                incoming.serverName(),
                incoming.protocolType(),
                mergedTransportSpec == null ? incoming.transportType() : mergedTransportSpec.transportType(),
                incoming.authType(),
                mergedTransportSpec == null ? incoming.endpoint() : mergedTransportSpec.endpoint(),
                incoming.enabled(),
                existing.status(),
                existing.lastConnectedAt(),
                existing.lastUpdatedAt(),
                existing.lastError(),
                existing.discoveredToolCount(),
                existing.discoveredResourceCount(),
                existing.discoveredPromptCount(),
                existing.lastCapabilityRefreshAt(),
                existing.capabilityVersion(),
                existing.capabilityDigest(),
                existing.reconnectAttempt(),
                existing.maxReconnectAttempts(),
                existing.nextRetryAt(),
                mergedTransportSpec,
                mergedTransportConnection
        );
    }

    private void refreshCapabilitySnapshot(String serverId) {
        List<McpToolRegistration> tools = listServerTools(serverId);
        List<McpResourceRegistration> resources = listServerResources(serverId);
        List<McpPromptRegistration> prompts = listServerPrompts(serverId);
        int toolCount = tools.size();
        int resourceCount = resources.size();
        int promptCount = prompts.size();
        String digest = computeCapabilityDigest(serverId, tools, resources, prompts);
        String timestamp = now();
        updateServer(serverId, existing -> existing.withCapabilityCounts(
                toolCount,
                resourceCount,
                promptCount,
                timestamp,
                timestamp,
                nextCapabilityVersion(existing, digest),
                digest
        ));
    }

    private void updateServer(String serverId, java.util.function.UnaryOperator<McpServerRegistration> updater) {
        servers.computeIfPresent(serverId, (ignored, existing) -> updater.apply(existing));
    }

    private String toolKey(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase();
    }

    private String resourceKey(String uri) {
        return uri == null ? "" : uri.trim().toLowerCase();
    }

    private String promptKey(String promptName) {
        return promptName == null ? "" : promptName.trim().toLowerCase();
    }

    private McpCapabilitySnapshot toCapabilitySnapshot(McpServerRegistration server) {
        String serverId = server.serverId();
        List<McpToolRegistration> tools = listServerTools(serverId);
        List<McpResourceRegistration> resources = listServerResources(serverId);
        List<McpPromptRegistration> prompts = listServerPrompts(serverId);
        List<String> toolNames = tools.stream()
                .map(McpToolRegistration::toolName)
                .sorted(String::compareToIgnoreCase)
                .toList();
        List<String> resourceUris = resources.stream()
                .map(McpResourceRegistration::uri)
                .sorted(String::compareToIgnoreCase)
                .toList();
        List<String> promptNames = prompts.stream()
                .map(McpPromptRegistration::promptName)
                .sorted(String::compareToIgnoreCase)
                .toList();
        return new McpCapabilitySnapshot(
                serverId,
                server.serverName(),
                server.protocolType(),
                server.status(),
                server.enabled(),
                server.authType(),
                server.capabilityVersion(),
                server.capabilityDigest(),
                toolNames.size(),
                resourceUris.size(),
                promptNames.size(),
                (int) tools.stream().filter(McpToolRegistration::userVisible).count(),
                (int) resources.stream().filter(McpResourceRegistration::userVisible).count(),
                (int) prompts.stream().filter(McpPromptRegistration::userVisible).count(),
                toolNames,
                resourceUris,
                promptNames,
                tools,
                resources,
                prompts,
                server.transportSpec(),
                server.transportConnection(),
                server.lastCapabilityRefreshAt()
        );
    }

    private McpSerializedClient toSerializedClient(McpServerRegistration server) {
        McpCapabilitySnapshot capabilitySnapshot = toCapabilitySnapshot(server);
        return new McpSerializedClient(
                server.serverId(),
                server.serverName(),
                mapClientType(server.status()),
                server.status().name(),
                server.lastError(),
                server.reconnectAttempt(),
                server.maxReconnectAttempts(),
                capabilitySnapshot.toolCount() + capabilitySnapshot.resourceCount() + capabilitySnapshot.promptCount() == 0
                        ? Map.of()
                        : Map.of(
                        "tools", Map.of("count", capabilitySnapshot.toolCount()),
                        "resources", Map.of("count", capabilitySnapshot.resourceCount()),
                        "prompts", Map.of("count", capabilitySnapshot.promptCount())
                ),
                toServerConfigSnapshot(server),
                buildRuntimeState(server)
        );
    }

    private McpServerConfigSnapshot toServerConfigSnapshot(McpServerRegistration server) {
        return new McpServerConfigSnapshot(
                server.serverId(),
                server.serverName(),
                server.protocolType(),
                server.transportType(),
                server.endpoint(),
                server.enabled(),
                server.authType(),
                server.transportSpec(),
                server.transportConnection()
        );
    }

    private McpSerializedTool toSerializedTool(McpToolRegistration tool) {
        return new McpSerializedTool(
                normalizeToolName(tool.toolName()),
                tool.description(),
                tool.inputSchema(),
                true,
                tool.toolName(),
                tool.serverId(),
                tool.enabled(),
                tool.userVisible()
        );
    }

    private boolean hasRegisteredCapabilities(String serverId) {
        return !toolsByServer.getOrDefault(serverId, Map.of()).isEmpty()
                || !resourcesByServer.getOrDefault(serverId, Map.of()).isEmpty()
                || !promptsByServer.getOrDefault(serverId, Map.of()).isEmpty();
    }

    private List<McpToolRegistration> listServerTools(String serverId) {
        return toolsByServer.getOrDefault(serverId, Map.of()).values().stream()
                .sorted(Comparator.comparing(McpToolRegistration::toolName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<McpResourceRegistration> listServerResources(String serverId) {
        return resourcesByServer.getOrDefault(serverId, Map.of()).values().stream()
                .sorted(Comparator.comparing(McpResourceRegistration::uri, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<McpPromptRegistration> listServerPrompts(String serverId) {
        return promptsByServer.getOrDefault(serverId, Map.of()).values().stream()
                .sorted(Comparator.comparing(McpPromptRegistration::promptName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String computeCapabilityDigest(String serverId,
                                           List<McpToolRegistration> tools,
                                           List<McpResourceRegistration> resources,
                                           List<McpPromptRegistration> prompts) {
        McpServerRegistration server = servers.get(serverId);
        StringBuilder fingerprint = new StringBuilder();
        if (server != null) {
            fingerprint.append("server|")
                    .append(nullSafe(server.serverId())).append('|')
                    .append(nullSafe(server.protocolType())).append('|')
                    .append(nullSafe(server.authType())).append('|')
                    .append(nullSafe(server.endpoint())).append('\n');
            ProtocolTransportSpec transportSpec = server.transportSpec();
            if (transportSpec != null) {
                fingerprint.append("transport|")
                        .append(transportSpec.transportType()).append('|')
                        .append(nullSafe(transportSpec.command())).append('|')
                        .append(String.join(",", transportSpec.args())).append('|')
                        .append(transportSpec.authRequired()).append('|')
                        .append(nullSafe(transportSpec.endpoint())).append('|')
                        .append(stableValue(transportSpec.headers())).append('|')
                        .append(stableValue(transportSpec.environment())).append('|')
                        .append(stableValue(transportSpec.metadata())).append('\n');
            }
            if (server.transportConnection() != null) {
                fingerprint.append("connection|")
                        .append(nullSafe(server.transportConnection().connectionId())).append('|')
                        .append(server.transportConnection().state()).append('|')
                        .append(server.transportConnection().authRequired()).append('|')
                        .append(server.transportConnection().reconnectSupported()).append('|')
                        .append(server.transportConnection().cleanupSupported()).append('|')
                        .append(nullSafe(server.transportConnection().lastHandshakeAt())).append('|')
                        .append(stableValue(server.transportConnection().metadata())).append('\n');
            }
        }
        for (McpToolRegistration tool : tools) {
            fingerprint.append("tool|")
                    .append(nullSafe(tool.toolName())).append('|')
                    .append(nullSafe(tool.description())).append('|')
                    .append(tool.enabled()).append('|')
                    .append(tool.userVisible()).append('|')
                    .append(stableValue(tool.inputSchema())).append('|')
                    .append(stableValue(tool.outputSchema())).append('|')
                    .append(stableValue(tool.annotations())).append('|')
                    .append(stableValue(tool.metadata())).append('\n');
        }
        for (McpResourceRegistration resource : resources) {
            fingerprint.append("resource|")
                    .append(nullSafe(resource.uri())).append('|')
                    .append(nullSafe(resource.name())).append('|')
                    .append(nullSafe(resource.description())).append('|')
                    .append(nullSafe(resource.mimeType())).append('|')
                    .append(resource.userVisible()).append('|')
                    .append(stableValue(resource.metadata())).append('\n');
        }
        for (McpPromptRegistration prompt : prompts) {
            fingerprint.append("prompt|")
                    .append(nullSafe(prompt.promptName())).append('|')
                    .append(nullSafe(prompt.description())).append('|')
                    .append(prompt.userVisible()).append('|')
                    .append(stableValue(prompt.arguments())).append('|')
                    .append(stableValue(prompt.metadata())).append('\n');
        }
        return sha256Hex(fingerprint.toString());
    }

    private Map<String, Object> buildRuntimeState(McpServerRegistration server) {
        if (server.transportConnection() == null) {
            return Map.of();
        }
        return Map.of(
                "connectionId", nullSafe(server.transportConnection().connectionId()),
                "state", server.transportConnection().state().name(),
                "reconnectSupported", server.transportConnection().reconnectSupported(),
                "cleanupSupported", server.transportConnection().cleanupSupported(),
                "authRequired", server.transportConnection().authRequired(),
                "createdAt", nullSafe(server.transportConnection().createdAt()),
                "lastStateChangedAt", nullSafe(server.transportConnection().lastStateChangedAt()),
                "lastHandshakeAt", nullSafe(server.transportConnection().lastHandshakeAt()),
                "lastError", nullSafe(server.transportConnection().lastError())
        );
    }

    private String stableValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                    .map(entry -> stableValue(entry.getKey()) + ":" + stableValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::stableValue)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(stableValue(item));
                first = false;
            }
            return builder.append(']').toString();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(stableValue(Array.get(value, index)));
            }
            return builder.append(']').toString();
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        return String.valueOf(value);
    }

    private String nextCapabilityVersion(McpServerRegistration existing, String digest) {
        if (existing.capabilityVersion() != null && digest != null && digest.equals(existing.capabilityDigest())) {
            return existing.capabilityVersion();
        }
        long nextVersionNumber = parseCapabilityVersion(existing.capabilityVersion()) + 1;
        return "v" + nextVersionNumber;
    }

    private long parseCapabilityVersion(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        String normalized = value.trim().toLowerCase().startsWith("v")
                ? value.trim().substring(1)
                : value.trim();
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private String mapClientType(McpServerStatus status) {
        if (status == null) {
            return "pending";
        }
        return switch (status) {
            case CONNECTED -> "connected";
            case FAILED -> "failed";
            case AUTH_REQUIRED -> "needs-auth";
            case DISABLED -> "disabled";
            case REGISTERED, CONNECTING, RECONNECTING, DEGRADED, CLOSED -> "pending";
        };
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase();
    }

    private String normalizeMessage(String message) {
        return message == null || message.isBlank() ? null : message.trim();
    }

    private String now() {
        return Instant.now().toString();
    }
}

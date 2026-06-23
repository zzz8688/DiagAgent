package io.github.zzz8688.diagagent.protocol;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DefaultProtocolTransportFactory implements ProtocolTransportFactory {

    @Override
    public ProtocolTransportSpec create(ProtocolServerConfig config) {
        if (config instanceof StdioProtocolServerConfig stdio) {
            return new ProtocolTransportSpec(
                    stdio.transportType(),
                    stdio.endpoint(),
                    stdio.command(),
                    stdio.args() == null ? List.of() : stdio.args(),
                    Map.of(),
                    Map.of(),
                    stdio.authConfig().required(),
                    createBaseMetadata(stdio)
            );
        }
        if (config instanceof SseProtocolServerConfig sse) {
            return new ProtocolTransportSpec(
                    sse.transportType(),
                    sse.endpoint(),
                    null,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    sse.authConfig().required(),
                    createBaseMetadata(sse)
            );
        }
        if (config instanceof HttpProtocolServerConfig http) {
            return new ProtocolTransportSpec(
                    http.transportType(),
                    http.endpoint(),
                    null,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    http.authConfig().required(),
                    createBaseMetadata(http)
            );
        }
        if (config instanceof WebSocketProtocolServerConfig ws) {
            return new ProtocolTransportSpec(
                    ws.transportType(),
                    ws.endpoint(),
                    null,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    ws.authConfig().required(),
                    createBaseMetadata(ws)
            );
        }
        return new ProtocolTransportSpec(
                config.transportType(),
                config.endpoint(),
                null,
                List.of(),
                Map.of(),
                Map.of(),
                config.authConfig().required(),
                createBaseMetadata(config)
        );
    }

    @Override
    public ProtocolTransportConnection createConnection(ProtocolServerConfig config) {
        ProtocolTransportSpec spec = create(config);
        String timestamp = Instant.now().toString();
        Map<String, Object> metadata = new LinkedHashMap<>(spec.metadata());
        metadata.put("serverName", config.serverName());
        metadata.put("protocolType", config.protocolType().name());
        metadata.put("headers", spec.headers());
        metadata.put("environment", spec.environment());
        return new ProtocolTransportConnection(
                UUID.randomUUID().toString(),
                config.serverId(),
                spec.transportType(),
                spec.endpoint(),
                spec.command(),
                config.enabled() ? ProtocolTransportConnectionState.REGISTERED : ProtocolTransportConnectionState.DISABLED,
                spec.authRequired(),
                spec.transportType() != io.github.zzz8688.diagagent.mcp.McpTransportType.STDIO,
                spec.transportType() == io.github.zzz8688.diagagent.mcp.McpTransportType.STDIO,
                timestamp,
                timestamp,
                null,
                null,
                metadata
        );
    }

    private Map<String, Object> createBaseMetadata(ProtocolServerConfig config) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protocolType", config.protocolType().name());
        metadata.put("authType", config.authConfig().type());
        metadata.put("authRequired", config.authConfig().required());
        if (config.authConfig().headerName() != null && !config.authConfig().headerName().isBlank()) {
            metadata.put("authHeaderName", config.authConfig().headerName());
        }
        if (config.authConfig().secretName() != null && !config.authConfig().secretName().isBlank()) {
            metadata.put("authSecretName", config.authConfig().secretName());
        }
        metadata.put("transportType", config.transportType().name());
        metadata.put("serverId", config.serverId());
        metadata.put("serverName", config.serverName());
        return metadata;
    }
}

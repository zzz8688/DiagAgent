package io.github.zzz8688.diagagent.protocol;

import io.github.zzz8688.diagagent.mcp.McpTransportType;

import java.util.List;
import java.util.Map;

public record ProtocolTransportSpec(
        McpTransportType transportType,
        String endpoint,
        String command,
        List<String> args,
        Map<String, String> headers,
        Map<String, String> environment,
        boolean authRequired,
        Map<String, Object> metadata
) {

    public ProtocolTransportSpec {
        args = args == null ? List.of() : List.copyOf(args);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

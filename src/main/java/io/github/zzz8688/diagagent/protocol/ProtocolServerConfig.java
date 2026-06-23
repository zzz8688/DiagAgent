package io.github.zzz8688.diagagent.protocol;

import io.github.zzz8688.diagagent.mcp.McpTransportType;

public interface ProtocolServerConfig {

    String serverId();

    String serverName();

    ProtocolType protocolType();

    McpTransportType transportType();

    boolean enabled();

    ProtocolAuthConfig authConfig();

    String endpoint();
}

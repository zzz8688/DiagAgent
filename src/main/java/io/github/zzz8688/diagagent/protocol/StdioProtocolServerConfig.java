package io.github.zzz8688.diagagent.protocol;

import io.github.zzz8688.diagagent.mcp.McpTransportType;

import java.util.List;
import java.util.StringJoiner;

public record StdioProtocolServerConfig(
        String serverId,
        String serverName,
        ProtocolType protocolType,
        String command,
        List<String> args,
        boolean enabled,
        ProtocolAuthConfig authConfig
) implements ProtocolServerConfig {

    @Override
    public McpTransportType transportType() {
        return McpTransportType.STDIO;
    }

    @Override
    public String endpoint() {
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add(command == null ? "" : command.trim());
        if (args != null) {
            args.stream()
                    .filter(arg -> arg != null && !arg.isBlank())
                    .forEach(joiner::add);
        }
        return "stdio://" + joiner;
    }
}

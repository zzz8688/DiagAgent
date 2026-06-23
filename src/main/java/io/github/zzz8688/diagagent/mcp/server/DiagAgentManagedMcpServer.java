package io.github.zzz8688.diagagent.mcp.server;

import io.github.zzz8688.diagagent.protocol.HttpProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ManagedProtocolClient;
import io.github.zzz8688.diagagent.protocol.ProtocolPromptDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolResourceDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolAuthConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolLifecycleManager;
import io.github.zzz8688.diagagent.protocol.ProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolToolDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DiagAgentManagedMcpServer implements ManagedProtocolClient {

    private static final Logger log = LoggerFactory.getLogger(DiagAgentManagedMcpServer.class);

    private final ProtocolLifecycleManager protocolLifecycleManager;
    private final DiagAgentMcpToolExporter toolExporter;
    private final DiagAgentMcpResourceExporter resourceExporter;
    private final DiagAgentMcpPromptExporter promptExporter;

    @Value("${mcp.diagagent-server.enabled:true}")
    private boolean enabled;

    @Value("${server.port:8081}")
    private int serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Override
    public ProtocolServerConfig serverConfig() {
        return new HttpProtocolServerConfig(
                "diagagent-mcp-server",
                "DiagAgent MCP Server",
                ProtocolType.MCP,
                "http://localhost:" + serverPort + normalizeContextPath(contextPath) + "/api/mcp/server",
                enabled,
                ProtocolAuthConfig.none()
        );
    }

    @Override
    public List<ProtocolToolDescriptor> bootstrapTools() {
        return toolExporter.listExportableTools().stream()
                .map(tool -> new ProtocolToolDescriptor(
                        tool.name(),
                        tool.description(),
                        true,
                        true,
                        tool.inputSchema(),
                        tool.outputSchema(),
                        tool.annotations(),
                        copyMetadata(tool._meta())
                ))
                .toList();
    }

    @Override
    public List<ProtocolResourceDescriptor> bootstrapResources() {
        return resourceExporter.listResources().stream()
                .map(resource -> new ProtocolResourceDescriptor(
                        resource.uri(),
                        resource.name(),
                        resource.description(),
                        resource.mimeType(),
                        true,
                        Map.of(
                                "uriScheme", resolveUriScheme(resource.uri()),
                                "mimeType", resource.mimeType()
                        )
                ))
                .toList();
    }

    @Override
    public List<ProtocolPromptDescriptor> bootstrapPrompts() {
        return promptExporter.listPrompts().stream()
                .map(prompt -> new ProtocolPromptDescriptor(
                        prompt.name(),
                        prompt.description(),
                        true,
                        prompt.arguments(),
                        Map.of(
                                "sourceType", "system-prompt-config",
                                "argumentCount", prompt.arguments() == null ? 0 : prompt.arguments().size()
                        )
                ))
                .toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerSelfServer() {
        protocolLifecycleManager.register(this);
        if (!enabled) {
            protocolLifecycleManager.markDisabled(this);
            return;
        }
        try {
            protocolLifecycleManager.markConnected(this, bootstrapTools(), bootstrapResources(), bootstrapPrompts());
            log.info("DiagAgent MCP server 已纳入统一 registry，endpoint={}", serverConfig().endpoint());
        } catch (Exception e) {
            protocolLifecycleManager.markFailed(this, e.getMessage());
            log.warn("注册 DiagAgent 自身 MCP server 失败: {}", e.getMessage(), e);
        }
    }

    private String normalizeContextPath(String value) {
        if (value == null || value.isBlank() || "/".equals(value.trim())) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String resolveUriScheme(String uri) {
        if (uri == null || uri.isBlank() || !uri.contains("://")) {
            return "unknown";
        }
        return uri.substring(0, uri.indexOf("://")).toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyMetadata(Object metadata) {
        if (metadata instanceof Map<?, ?> map) {
            return Map.copyOf((Map<String, Object>) map);
        }
        return Map.of();
    }
}

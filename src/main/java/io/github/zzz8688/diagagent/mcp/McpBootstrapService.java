package io.github.zzz8688.diagagent.mcp;

import jakarta.annotation.PostConstruct;
import io.github.zzz8688.diagagent.config.DingtalkMcpProperties;
import io.github.zzz8688.diagagent.protocol.ProtocolAuthConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportFactory;
import io.github.zzz8688.diagagent.protocol.ProtocolType;
import io.github.zzz8688.diagagent.protocol.SseProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.StdioProtocolServerConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class McpBootstrapService {

    private final McpRegistryService mcpRegistryService;
    private final DingtalkMcpProperties dingtalkMcpProperties;
    private final ProtocolTransportFactory protocolTransportFactory;

    @Value("${mcp.prometheus.enabled:true}")
    private boolean prometheusEnabled;

    @Value("${mcp.prometheus.python-command:python}")
    private String pythonCommand;

    @Value("${mcp.prometheus.script-path:./mcp/prometheus-mcp-server.py}")
    private String scriptPath;

    @Value("${mcp.prometheus.base-url:http://localhost:9090}")
    private String prometheusBaseUrl;

    @PostConstruct
    public void registerPlaceholders() {
        StdioProtocolServerConfig prometheusConfig = new StdioProtocolServerConfig(
                "prometheus-mcp",
                "Prometheus MCP Server",
                ProtocolType.MCP,
                pythonCommand,
                List.of(scriptPath, "--prometheus-url", prometheusBaseUrl),
                prometheusEnabled,
                ProtocolAuthConfig.none()
        );
        mcpRegistryService.registerServer(McpServerRegistration.fromConfig(
                prometheusConfig,
                protocolTransportFactory.create(prometheusConfig),
                protocolTransportFactory.createConnection(prometheusConfig),
                prometheusEnabled ? McpServerStatus.REGISTERED : McpServerStatus.DISABLED
        ));

        mcpRegistryService.registerTool(new McpToolRegistration(
                "queryPrometheusMetrics",
                "通过外部 Prometheus MCP server 查询监控指标。",
                "prometheus-mcp",
                true,
                true
        ));

        SseProtocolServerConfig dingTalkConfig = new SseProtocolServerConfig(
                "dingtalk-mcp",
                "DingTalk MCP Server",
                ProtocolType.MCP,
                dingtalkMcpProperties.getSseEndpoint(),
                dingtalkMcpProperties.isEnabled(),
                ProtocolAuthConfig.bearerHeader(
                        dingtalkMcpProperties.getAuthorizationHeader() == null || dingtalkMcpProperties.getAuthorizationHeader().isBlank()
                                ? "Authorization"
                                : dingtalkMcpProperties.getAuthorizationHeader(),
                        "API_Key"
                )
        );
        mcpRegistryService.registerServer(McpServerRegistration.fromConfig(
                dingTalkConfig,
                protocolTransportFactory.create(dingTalkConfig),
                protocolTransportFactory.createConnection(dingTalkConfig),
                dingtalkMcpProperties.isEnabled() ? McpServerStatus.REGISTERED : McpServerStatus.DISABLED
        ));

        mcpRegistryService.registerTool(new McpToolRegistration(
                "sendNotice",
                "通过 DashScope dingtalk-mcp SSE 端点发送低置信度人工介入通知，运行时会动态发现远端工具并调用。",
                "dingtalk-mcp",
                true,
                true
        ));
    }
}

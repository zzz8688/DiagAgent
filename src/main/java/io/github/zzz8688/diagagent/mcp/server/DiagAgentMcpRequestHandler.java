package io.github.zzz8688.diagagent.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DiagAgentMcpRequestHandler {

    private final DiagAgentMcpToolExporter toolExporter;
    private final DiagAgentMcpToolExecutor toolExecutor;
    private final DiagAgentMcpResourceExporter resourceExporter;
    private final DiagAgentMcpPromptExporter promptExporter;
    private final DiagAgentMcpNotificationCenter notificationCenter;
    private final ObjectMapper objectMapper;

    public DiagAgentMcpRequestHandler(DiagAgentMcpToolExporter toolExporter,
                                      DiagAgentMcpToolExecutor toolExecutor,
                                      DiagAgentMcpResourceExporter resourceExporter,
                                      DiagAgentMcpPromptExporter promptExporter,
                                      DiagAgentMcpNotificationCenter notificationCenter,
                                      ObjectMapper objectMapper) {
        this.toolExporter = toolExporter;
        this.toolExecutor = toolExecutor;
        this.resourceExporter = resourceExporter;
        this.promptExporter = promptExporter;
        this.notificationCenter = notificationCenter;
        this.objectMapper = objectMapper;
    }

    public McpHandledResponse handle(McpProtocolDtos.Request request) {
        if (request == null) {
            return McpHandledResponse.json(error(null, -32600, "Invalid Request"));
        }
        Object id = request.id();
        String method = request.method() == null ? "" : request.method().trim();

        return switch (method) {
            case "initialize" -> McpHandledResponse.json(success(id, initialize(request)));
            case "notifications/initialized" -> McpHandledResponse.notification(notificationCenter.initializedNotifications());
            case "ping" -> McpHandledResponse.json(success(id, java.util.Map.of()));
            case "tools/list" -> McpHandledResponse.json(success(id, new McpProtocolDtos.ToolListResult(toolExporter.listExportableTools())));
            case "tools/call" -> handleToolCall(request);
            case "resources/list" -> McpHandledResponse.json(success(id, new McpProtocolDtos.ResourceListResult(resourceExporter.listResources())));
            case "resources/read" -> handleResourceRead(request);
            case "resources/subscribe" -> handleResourceSubscribe(request);
            case "resources/unsubscribe" -> handleResourceUnsubscribe(request);
            case "prompts/list" -> McpHandledResponse.json(success(id, new McpProtocolDtos.PromptListResult(promptExporter.listPrompts())));
            case "prompts/get" -> handlePromptGet(request);
            default -> McpHandledResponse.json(error(id, -32601, "Unsupported MCP method: " + method));
        };
    }

    private McpHandledResponse handleToolCall(McpProtocolDtos.Request request) {
        try {
            McpProtocolDtos.CallToolParams params = convert(request.params(), McpProtocolDtos.CallToolParams.class);
            if (params.name() == null || params.name().isBlank()) {
                return McpHandledResponse.json(error(request.id(), -32602, "tools/call requires params.name"));
            }
            DiagAgentMcpToolCallResult result = toolExecutor.call(params.name(), params.arguments());
            return McpHandledResponse.json(success(request.id(), new McpProtocolDtos.CallToolResult(
                    List.of(new McpProtocolDtos.TextContent(result.text())),
                    result.structuredContent(),
                    result.error()
            )));
        } catch (IllegalArgumentException e) {
            return McpHandledResponse.json(error(request.id(), -32602, e.getMessage()));
        }
    }

    private McpHandledResponse handleResourceRead(McpProtocolDtos.Request request) {
        McpProtocolDtos.ReadResourceParams params = convert(request.params(), McpProtocolDtos.ReadResourceParams.class);
        if (params.uri() == null || params.uri().isBlank()) {
            return McpHandledResponse.json(error(request.id(), -32602, "resources/read requires params.uri"));
        }
        DiagAgentMcpResourceReadResult result = resourceExporter.readResource(params.uri());
        if (result.error()) {
            return McpHandledResponse.json(error(request.id(), -32002, result.message()));
        }
        return McpHandledResponse.json(success(request.id(), new McpProtocolDtos.ReadResourceResult(
                List.of(new McpProtocolDtos.TextResourceContents(
                        result.uri(),
                        result.mimeType(),
                        result.text()
                ))
        )));
    }

    private McpHandledResponse handlePromptGet(McpProtocolDtos.Request request) {
        McpProtocolDtos.GetPromptParams params = convert(request.params(), McpProtocolDtos.GetPromptParams.class);
        if (params.name() == null || params.name().isBlank()) {
            return McpHandledResponse.json(error(request.id(), -32602, "prompts/get requires params.name"));
        }
        DiagAgentMcpPromptGetResult result = promptExporter.getPrompt(params.name(), java.util.Map.copyOf(params.arguments()));
        if (result.error()) {
            return McpHandledResponse.json(error(request.id(), -32003, result.message()));
        }
        return McpHandledResponse.json(success(request.id(), new McpProtocolDtos.GetPromptResult(
                result.description(),
                List.of(new McpProtocolDtos.PromptMessage(
                        "user",
                        new McpProtocolDtos.TextContent(result.text())
                ))
        )));
    }

    private McpHandledResponse handleResourceSubscribe(McpProtocolDtos.Request request) {
        McpProtocolDtos.SubscribeRequestParams params = convert(request.params(), McpProtocolDtos.SubscribeRequestParams.class);
        if (params.uri() == null || params.uri().isBlank()) {
            return McpHandledResponse.json(error(request.id(), -32602, "resources/subscribe requires params.uri"));
        }
        notificationCenter.subscribeResource(params.uri());
        return McpHandledResponse.json(
                success(request.id(), new McpProtocolDtos.EmptyResult()),
                notificationCenter.resourceSubscribedNotifications(params.uri())
        );
    }

    private McpHandledResponse handleResourceUnsubscribe(McpProtocolDtos.Request request) {
        McpProtocolDtos.UnsubscribeRequestParams params = convert(request.params(), McpProtocolDtos.UnsubscribeRequestParams.class);
        if (params.uri() == null || params.uri().isBlank()) {
            return McpHandledResponse.json(error(request.id(), -32602, "resources/unsubscribe requires params.uri"));
        }
        notificationCenter.unsubscribeResource(params.uri());
        return McpHandledResponse.json(success(request.id(), new McpProtocolDtos.EmptyResult()));
    }

    private McpProtocolDtos.InitializeResult initialize(McpProtocolDtos.Request request) {
        McpProtocolDtos.InitializeParams params = convert(request.params(), McpProtocolDtos.InitializeParams.class);
        String protocolVersion = params.protocolVersion() == null || params.protocolVersion().isBlank()
                ? McpProtocolDtos.PROTOCOL_VERSION_2024_11_05
                : params.protocolVersion();
        return new McpProtocolDtos.InitializeResult(
                protocolVersion,
                new McpProtocolDtos.ServerCapabilities(
                        new McpProtocolDtos.Capability(true),
                        new McpProtocolDtos.ResourceCapability(true, true),
                        new McpProtocolDtos.Capability(true)
                ),
                new McpProtocolDtos.Implementation("diagagent-mcp-server", "0.1.0"),
                "DiagAgent MCP server exposing diagnostics tools, skill resources, and prompt templates."
        );
    }

    private <T> T convert(Object source, Class<T> targetType) {
        return objectMapper.convertValue(source == null ? java.util.Map.of() : source, targetType);
    }

    private McpProtocolDtos.SuccessResponse success(Object id, Object result) {
        return new McpProtocolDtos.SuccessResponse(id, result);
    }

    private McpProtocolDtos.ErrorResponse error(Object id, int code, String message) {
        return new McpProtocolDtos.ErrorResponse(id, new McpProtocolDtos.Error(code, message));
    }

    public record McpHandledResponse(
            int statusCode,
            Object body,
            List<McpProtocolDtos.Notification> notifications
    ) {
        public static McpHandledResponse json(Object body) {
            return new McpHandledResponse(200, body, List.of());
        }

        public static McpHandledResponse json(Object body, List<McpProtocolDtos.Notification> notifications) {
            return new McpHandledResponse(200, body, notifications == null ? List.of() : List.copyOf(notifications));
        }

        public static McpHandledResponse notification(List<McpProtocolDtos.Notification> notifications) {
            return new McpHandledResponse(204, null, notifications == null ? List.of() : List.copyOf(notifications));
        }
    }
}

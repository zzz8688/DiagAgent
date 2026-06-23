package io.github.zzz8688.diagagent.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public final class McpProtocolDtos {

    public static final String JSON_RPC_VERSION = "2.0";
    public static final String PROTOCOL_VERSION_2024_11_05 = "2024-11-05";

    private McpProtocolDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            String jsonrpc,
            Object id,
            String method,
            Map<String, Object> params
    ) {
        public Request {
            jsonrpc = jsonrpc == null || jsonrpc.isBlank() ? JSON_RPC_VERSION : jsonrpc;
            params = params == null || params.isEmpty() ? Map.of() : Map.copyOf(params);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(
            String jsonrpc,
            String method,
            Map<String, Object> params
    ) {
        public Notification(String method, Map<String, Object> params) {
            this(JSON_RPC_VERSION, method, params == null || params.isEmpty() ? null : Map.copyOf(params));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SuccessResponse(
            String jsonrpc,
            Object id,
            Object result
    ) {
        public SuccessResponse(Object id, Object result) {
            this(JSON_RPC_VERSION, id, result);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String jsonrpc,
            Object id,
            Error error
    ) {
        public ErrorResponse(Object id, Error error) {
            this(JSON_RPC_VERSION, id, error);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(
            int code,
            String message
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InitializeParams(
            String protocolVersion,
            Map<String, Object> capabilities,
            Implementation clientInfo
    ) {
        public InitializeParams {
            capabilities = capabilities == null || capabilities.isEmpty() ? Map.of() : Map.copyOf(capabilities);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InitializeResult(
            String protocolVersion,
            ServerCapabilities capabilities,
            Implementation serverInfo,
            String instructions
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Implementation(
            String name,
            String version
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServerCapabilities(
            Capability tools,
            ResourceCapability resources,
            Capability prompts
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capability(
            Boolean listChanged
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceCapability(
            Boolean subscribe,
            Boolean listChanged
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolListResult(
            List<DiagAgentMcpExportedTool> tools
    ) {
        public ToolListResult {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallToolParams(
            String name,
            Map<String, Object> arguments
    ) {
        public CallToolParams {
            arguments = arguments == null || arguments.isEmpty() ? Map.of() : Map.copyOf(arguments);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallToolResult(
            List<TextContent> content,
            Map<String, Object> structuredContent,
            Boolean isError
    ) {
        public CallToolResult {
            content = content == null ? List.of() : List.copyOf(content);
            structuredContent = structuredContent == null || structuredContent.isEmpty() ? null : Map.copyOf(structuredContent);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextContent(
            String type,
            String text
    ) {
        public TextContent(String text) {
            this("text", text == null ? "" : text);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceListResult(
            List<DiagAgentMcpResource> resources
    ) {
        public ResourceListResult {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadResourceParams(
            String uri
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmptyResult() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubscribeRequestParams(
            String uri
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnsubscribeRequestParams(
            String uri
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadResourceResult(
            List<TextResourceContents> contents
    ) {
        public ReadResourceResult {
            contents = contents == null ? List.of() : List.copyOf(contents);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextResourceContents(
            String uri,
            String mimeType,
            String text
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceUpdatedNotificationParams(
            String uri
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptListResult(
            List<DiagAgentMcpPrompt> prompts
    ) {
        public PromptListResult {
            prompts = prompts == null ? List.of() : List.copyOf(prompts);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetPromptParams(
            String name,
            Map<String, String> arguments
    ) {
        public GetPromptParams {
            arguments = arguments == null || arguments.isEmpty() ? Map.of() : Map.copyOf(arguments);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetPromptResult(
            String description,
            List<PromptMessage> messages
    ) {
        public GetPromptResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptMessage(
            String role,
            TextContent content
    ) {
    }
}

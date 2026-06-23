package io.github.zzz8688.diagagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.client.DingtalkDepartmentUserClient;
import io.github.zzz8688.diagagent.config.DingtalkMcpProperties;
import io.github.zzz8688.diagagent.protocol.ProtocolAuthConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolLifecycleManager;
import io.github.zzz8688.diagagent.protocol.ProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolToolDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolType;
import io.github.zzz8688.diagagent.protocol.SseProtocolServerConfig;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class DingtalkMcpClient implements McpClientAdapter {

    private static final Logger log = LoggerFactory.getLogger(DingtalkMcpClient.class);

    private final DingtalkMcpProperties properties;
    private final DingtalkDepartmentUserClient departmentUserClient;
    private final ObjectMapper objectMapper;
    private final ProtocolLifecycleManager protocolLifecycleManager;
    private final SandboxExecutionService sandboxExecutionService;

    private static final List<String> SEND_PROGRESS_TOOL_NAMES = List.of("getSendProgress");
    private static final List<String> SEND_RESULT_TOOL_NAMES = List.of("getSendResult");

    public String sendWorkNotice(String title, String content, String level) {
        SandboxProtectedActionEnvelope<String> envelope = sandboxExecutionService.executeProtectedAction(
                "sendNotification",
                SandboxTargetType.MCP_TOOL,
                "dingtalk-mcp/sendNotice",
                Map.of(
                        "title", defaultText(title, ""),
                        "content", defaultText(content, ""),
                        "level", defaultText(level, "warning")
                ),
                null,
                () -> sendWorkNoticeDirect(title, content, level)
        );
        if (!envelope.success()) {
            protocolLifecycleManager.markFailed(this, envelope.errorMessage());
            log.warn("DingTalk MCP 通知被治理链拒绝或执行失败: {}", envelope.errorMessage());
            return "DingTalk MCP 调用失败: " + defaultText(envelope.errorMessage(), "sandbox protected action failed");
        }
        return envelope.result();
    }

    private String sendWorkNoticeDirect(String title, String content, String level) {
        protocolLifecycleManager.register(this);
        if (!properties.isEnabled()) {
            protocolLifecycleManager.markDisabled(this);
            log.warn("DingTalk MCP 通知未启用，跳过发送");
            return "DingTalk MCP 通知未启用";
        }

        String apiKey = defaultText(properties.getApiKey(), "").trim();
        if (apiKey.isBlank()) {
            protocolLifecycleManager.markAuthRequired(this, "DingTalk MCP 未配置 API_Key 环境变量");
            log.error("DingTalk MCP 鉴权失败，未配置 API_Key 环境变量");
            return "DingTalk MCP 未配置 API_Key 环境变量";
        }

        URI endpoint = URI.create(properties.getSseEndpoint().trim());
        String serverBaseUrl = endpoint.getScheme() + "://" + endpoint.getAuthority();
        String ssePath = endpoint.getRawPath()
                + (endpoint.getRawQuery() == null || endpoint.getRawQuery().isBlank() ? "" : "?" + endpoint.getRawQuery());

        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(serverBaseUrl)
                .sseEndpoint(ssePath)
                .connectTimeout(Duration.ofSeconds(10))
                .customizeRequest(request -> applyAuthHeader(request, apiKey))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(Math.max(properties.getRequestTimeoutMs(), 1000L)))
                .initializationTimeout(Duration.ofSeconds(10))
                .build()) {

            protocolLifecycleManager.markConnecting(this);
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            List<McpSchema.Tool> tools = toolsResult == null ? List.of() : defaultTools(toolsResult.tools());
            if (tools.isEmpty()) {
                protocolLifecycleManager.markDegraded(this, "DingTalk MCP 未返回任何工具");
                log.error("DingTalk MCP 未返回任何工具");
                return "DingTalk MCP 未返回任何工具";
            }

            List<McpSchema.Tool> exposedTools = filterExposedTools(tools);
            if (exposedTools.isEmpty()) {
                protocolLifecycleManager.markDegraded(this, "DingTalk MCP 未返回符合暴露条件的工具");
            } else {
                protocolLifecycleManager.markConnected(this, toDiscoveredTools(exposedTools));
            }

            McpSchema.Tool selectedTool = selectTool(tools);
            if (selectedTool == null) {
                protocolLifecycleManager.markDegraded(this, "DingTalk MCP 未找到可用通知工具");
                log.error("DingTalk MCP 未找到可用通知工具，availableTools={}", tools.stream().map(McpSchema.Tool::name).toList());
                return "DingTalk MCP 未找到可用通知工具";
            }

            Map<String, Object> arguments = buildArguments(selectedTool, title, content, level);
            McpSchema.JsonSchema inputSchema = selectedTool.inputSchema();
            log.info("DingTalk MCP 工具 schema: name={}, properties={}, required={}",
                    selectedTool.name(),
                    inputSchema == null ? null : inputSchema.properties(),
                    inputSchema == null ? null : inputSchema.required());
            try {
                log.info("DingTalk MCP 工具 arguments.json: {}", objectMapper.writeValueAsString(arguments));
            } catch (Exception serializationException) {
                log.warn("DingTalk MCP 工具 arguments JSON 序列化失败", serializationException);
            }
            log.info("调用 DingTalk MCP 工具: {}, arguments={}", selectedTool.name(), arguments);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(selectedTool.name(), arguments));
            log.info("DingTalk MCP 工具原始返回: isError={}, structuredContent={}, content={}",
                    result == null ? null : result.isError(),
                    result == null ? null : result.structuredContent(),
                    result == null ? null : result.content());
            ToolCallSummary sendSummary = summarizeToolCallResult(selectedTool.name(), result);
            if (!sendSummary.success()) {
                return sendSummary.message();
            }
            String taskId = extractTaskId(sendSummary.rawPayload());
            if (taskId.isBlank()) {
                return "DingTalk MCP 工作通知任务创建成功: " + sendSummary.rawPayload();
            }
            log.info("DingTalk 工作通知任务已创建: taskId={}, tool={}", taskId, selectedTool.name());
            DeliveryStatusSummary deliveryStatus = queryDeliveryStatus(client, tools, taskId);
            return renderSendNoticeOutcome(taskId, sendSummary.rawPayload(), deliveryStatus);
        } catch (Exception e) {
            if (looksLikeAuthError(e)) {
                protocolLifecycleManager.markAuthRequired(this, e.getMessage());
            } else {
                protocolLifecycleManager.markFailed(this, e.getMessage());
            }
            log.error("调用 DingTalk MCP 失败", e);
            return "DingTalk MCP 调用失败: " + e.getMessage();
        }
    }

    @Override
    public ProtocolServerConfig serverConfig() {
        return new SseProtocolServerConfig(
                "dingtalk-mcp",
                "DingTalk MCP Server",
                ProtocolType.MCP,
                defaultText(properties.getSseEndpoint(), "").trim(),
                properties.isEnabled(),
                ProtocolAuthConfig.bearerHeader(defaultText(properties.getAuthorizationHeader(), "Authorization"), "API_Key")
        );
    }

    @Override
    public List<ProtocolToolDescriptor> bootstrapTools() {
        return List.of(new ProtocolToolDescriptor(
                "sendNotice",
                "通过 DashScope dingtalk-mcp SSE 端点发送低置信度人工介入通知，运行时会动态发现远端工具并调用。",
                true,
                true
        ));
    }

    @Override
    public List<ProtocolToolDescriptor> discoverTools() throws Exception {
        return withClientSession((client, tools) -> toDiscoveredTools(filterExposedTools(tools)));
    }

    @Override
    public Object invokeTool(String toolName, Map<String, Object> arguments) throws Exception {
        return withClientSession((client, tools) -> {
            McpSchema.Tool selectedTool = selectTool(toolName, tools);
            if (selectedTool == null) {
                throw new IllegalStateException("DingTalk MCP 未找到可用工具: " + toolName);
            }
            Map<String, Object> actualArguments = buildArguments(
                    selectedTool,
                    defaultText(arguments == null ? null : (String) arguments.get("title"), ""),
                    defaultText(arguments == null ? null : (String) arguments.get("content"), ""),
                    defaultText(arguments == null ? null : (String) arguments.get("level"), "warning")
            );
            return client.callTool(new McpSchema.CallToolRequest(selectedTool.name(), actualArguments));
        });
    }

    @Override
    public void refreshCapabilities(ProtocolLifecycleManager protocolLifecycleManager) {
        protocolLifecycleManager.register(this);
        if (!properties.isEnabled()) {
            protocolLifecycleManager.markDisabled(this);
            return;
        }
        String apiKey = defaultText(properties.getApiKey(), "").trim();
        if (apiKey.isBlank()) {
            protocolLifecycleManager.markAuthRequired(this, "DingTalk MCP 未配置 API_Key 环境变量");
            throw new IllegalStateException("DingTalk MCP 未配置 API_Key 环境变量");
        }
        try {
            protocolLifecycleManager.markConnecting(this);
            List<ProtocolToolDescriptor> tools = discoverTools();
            if (tools.isEmpty()) {
                protocolLifecycleManager.markDegraded(this, "DingTalk MCP 未返回符合暴露条件的工具");
                return;
            }
            protocolLifecycleManager.markConnected(this, tools, discoverResources(), discoverPrompts());
        } catch (Exception e) {
            if (looksLikeAuthError(e)) {
                protocolLifecycleManager.markAuthRequired(this, e.getMessage());
            } else {
                protocolLifecycleManager.markFailed(this, e.getMessage());
            }
            throw new IllegalStateException("刷新 DingTalk MCP capability 失败", e);
        }
    }

    private void applyAuthHeader(HttpRequest.Builder request, String apiKey) {
        String headerName = defaultText(properties.getAuthorizationHeader(), "Authorization");
        request.header(headerName, "Bearer " + apiKey);
    }

    private <T> T withClientSession(DingtalkClientCallback<T> callback) throws Exception {
        String apiKey = defaultText(properties.getApiKey(), "").trim();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("DingTalk MCP 未配置 API_Key 环境变量");
        }

        URI endpoint = URI.create(properties.getSseEndpoint().trim());
        String serverBaseUrl = endpoint.getScheme() + "://" + endpoint.getAuthority();
        String ssePath = endpoint.getRawPath()
                + (endpoint.getRawQuery() == null || endpoint.getRawQuery().isBlank() ? "" : "?" + endpoint.getRawQuery());

        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(serverBaseUrl)
                .sseEndpoint(ssePath)
                .connectTimeout(Duration.ofSeconds(10))
                .customizeRequest(request -> applyAuthHeader(request, apiKey))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(Math.max(properties.getRequestTimeoutMs(), 1000L)))
                .initializationTimeout(Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            List<McpSchema.Tool> tools = toolsResult == null ? List.of() : defaultTools(toolsResult.tools());
            return callback.execute(client, tools);
        }
    }

    private List<McpSchema.Tool> defaultTools(List<McpSchema.Tool> tools) {
        return tools == null ? List.of() : tools.stream().filter(Objects::nonNull).toList();
    }

    private List<McpSchema.Tool> filterExposedTools(List<McpSchema.Tool> tools) {
        List<McpSchema.Tool> safeTools = defaultTools(tools);
        if (safeTools.isEmpty()) {
            return safeTools;
        }
        List<String> preferredNames = properties.getPreferredToolNames() == null
                ? List.of()
                : properties.getPreferredToolNames().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList();
        if (preferredNames.isEmpty()) {
            return safeTools;
        }
        List<McpSchema.Tool> filtered = safeTools.stream()
                .filter(tool -> matchesPreferredTool(tool, preferredNames))
                .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        log.warn("DingTalk MCP 未命中 preferredToolNames={}, fallback 到默认通知工具选择逻辑", preferredNames);
        McpSchema.Tool selectedTool = selectTool(safeTools);
        return selectedTool == null ? List.of() : List.of(selectedTool);
    }

    private List<ProtocolToolDescriptor> toDiscoveredTools(List<McpSchema.Tool> tools) {
        return tools.stream()
                .map(tool -> new ProtocolToolDescriptor(
                        defaultText(tool.name(), "unknown"),
                        defaultText(tool.description(), "Discovered from DingTalk MCP server"),
                        true,
                        true
                ))
                .toList();
    }

    private boolean matchesPreferredTool(McpSchema.Tool tool, List<String> preferredNames) {
        if (tool == null || tool.name() == null) {
            return false;
        }
        return preferredNames.stream().anyMatch(preferred -> preferred.equalsIgnoreCase(tool.name()));
    }

    private McpSchema.Tool selectTool(List<McpSchema.Tool> tools) {
        for (String preferredToolName : properties.getPreferredToolNames()) {
            if (preferredToolName == null || preferredToolName.isBlank()) {
                continue;
            }
            for (McpSchema.Tool tool : tools) {
                if (preferredToolName.equalsIgnoreCase(tool.name())) {
                    return tool;
                }
            }
        }

        for (McpSchema.Tool tool : tools) {
            String haystack = (defaultText(tool.name(), "") + " " + defaultText(tool.description(), "")).toLowerCase(Locale.ROOT);
            if (haystack.contains("dingtalk")
                    || haystack.contains("ding")
                    || haystack.contains("send")
                    || haystack.contains("message")
                    || haystack.contains("notice")
                    || haystack.contains("robot")) {
                return tool;
            }
        }

        return tools.size() == 1 ? tools.get(0) : null;
    }

    private McpSchema.Tool selectTool(String toolName, List<McpSchema.Tool> tools) {
        if (toolName != null && !toolName.isBlank()) {
            for (McpSchema.Tool tool : tools) {
                if (toolName.equalsIgnoreCase(tool.name())) {
                    return tool;
                }
            }
        }
        return selectTool(tools);
    }

    private Map<String, Object> buildArguments(McpSchema.Tool tool, String title, String content, String level) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        McpSchema.JsonSchema inputSchema = tool.inputSchema();
        Map<String, Object> propertiesMap = inputSchema == null || inputSchema.properties() == null
                ? Map.of()
                : inputSchema.properties();

        if (propertiesMap.isEmpty()) {
            arguments.put("title", title);
            arguments.put("content", content);
            arguments.put("level", level);
            return arguments;
        }

        Map<String, String> normalizedKeys = new LinkedHashMap<>();
        for (String key : propertiesMap.keySet()) {
            normalizedKeys.put(key.toLowerCase(Locale.ROOT), key);
        }

        if ("sendnotice".equalsIgnoreCase(tool.name()) || normalizedKeys.containsKey("agentid")) {
            return buildSendNoticeArguments(normalizedKeys, title, content, level);
        }

        putIfPresent(arguments, normalizedKeys, List.of("title", "subject", "summary"), title);
        putIfPresent(arguments, normalizedKeys, List.of("level", "severity", "priority"), level);
        putIfPresent(arguments, normalizedKeys, List.of("type", "msgtype", "messageType", "noticeType"), "markdown");

        String markdown = "# " + defaultText(title, "系统通知") + "\n\n" + defaultText(content, "");
        putIfPresent(arguments, normalizedKeys, List.of("content", "message", "text", "body", "msg", "notice"), content);
        putIfPresent(arguments, normalizedKeys, List.of("markdown", "markdown_text"), markdown);

        if (arguments.isEmpty()) {
            String firstKey = propertiesMap.keySet().iterator().next();
            arguments.put(firstKey, markdown);
        }

        return arguments;
    }

    private Map<String, Object> buildSendNoticeArguments(Map<String, String> normalizedKeys,
                                                         String title,
                                                         String content,
                                                         String level) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Long agentId = properties.getAgentId();
        if (agentId == null || agentId <= 0) {
            throw new IllegalStateException("DingTalk MCP 缺少 agentId 配置，请设置环境变量 AgentId 或配置 mcp.dingtalk.agent-id");
        }

        boolean toAllUser = properties.isToAllUser();
        List<String> deptIdList = cleanList(properties.getDeptIdList());
        List<String> userIdList = cleanList(properties.getUserIdList());
        if (!toAllUser && userIdList.isEmpty() && !deptIdList.isEmpty()) {
            userIdList = departmentUserClient.resolveUserIds(deptIdList);
            if (!userIdList.isEmpty()) {
                log.info("已根据部门自动解析出 {} 个钉钉用户", userIdList.size());
            }
        }
        if (!toAllUser && userIdList.isEmpty() && deptIdList.isEmpty()) {
            throw new IllegalStateException("DingTalk MCP 在 toAllUser=false 时必须配置 userIdList 或 deptIdList；如需按部门自动解析用户，请额外配置 Ding_Client_ID、Ding_Client_Secret 和 deptIdList");
        }

        String actualAgentIdKey = firstPresent(normalizedKeys, List.of("agent_id", "agentid"));
        String actualToAllUserKey = firstPresent(normalizedKeys, List.of("to_all_user", "toalluser"));
        String actualUseridListKey = firstPresent(normalizedKeys, List.of("userid_list", "useridlist", "userids", "userlist"));
        String actualDeptidListKey = firstPresent(normalizedKeys, List.of("dept_id_list", "deptidlist", "deptids", "deptlist"));
        arguments.put(actualAgentIdKey != null ? actualAgentIdKey : "agentId", agentId);
        arguments.put(actualToAllUserKey != null ? actualToAllUserKey : "toAllUser", toAllUser);
        if (!userIdList.isEmpty()) {
            arguments.put(actualUseridListKey != null ? actualUseridListKey : "useridList", joinAsCsv(userIdList));
        }
        if (!deptIdList.isEmpty()) {
            arguments.put(actualDeptidListKey != null ? actualDeptidListKey : "deptIdList", joinAsCsv(deptIdList));
        }

        arguments.putAll(buildSendNoticeMessageArguments(normalizedKeys, title, content, level));
        return arguments;
    }

    private Map<String, Object> buildSendNoticeMessageArguments(Map<String, String> normalizedKeys,
                                                                String title,
                                                                String content,
                                                                String level) {
        String configuredType = defaultText(properties.getMessageType(), "markdown").trim().toLowerCase(Locale.ROOT);
        String normalizedType = switch (configuredType) {
            case "text", "link", "markdown" -> configuredType;
            default -> "markdown";
        };

        String safeTitle = defaultText(title, "系统通知");
        String safeContent = defaultText(content, "");
        String textContent = "[" + defaultText(level, "warning") + "] " + safeTitle + " - " + safeContent;
        String markdownText = "# " + safeTitle + "\n\n" + safeContent + "\n\n> level: " + defaultText(level, "warning");
        Map<String, Object> markdownPayload = Map.of(
                "title", safeTitle,
                "text", markdownText
        );

        Map<String, Object> arguments = new LinkedHashMap<>();
        String actualMsgTypeKey = firstPresent(normalizedKeys, List.of("msg.msgtype", "msgtype", "messageType", "type", "noticeType"));
        arguments.put(actualMsgTypeKey != null ? actualMsgTypeKey : "msgtype", normalizedType);

        switch (normalizedType) {
            case "text" -> {
                String actualTextKey = firstPresent(normalizedKeys, List.of("msg.text.content", "text", "content", "message", "body"));
                arguments.put(actualTextKey != null ? actualTextKey : "text", textContent);
                putIfPresent(arguments, normalizedKeys, List.of("title"), safeTitle);
            }
            case "link" -> {
                putIfPresent(arguments, normalizedKeys, List.of("title"), safeTitle);
                putIfPresent(arguments, normalizedKeys, List.of("text", "content", "message", "body"), safeContent);
                putIfPresent(arguments, normalizedKeys, List.of("messageurl", "messageUrl", "url", "linkurl"),
                        defaultText(properties.getLinkMessageUrl(), "http://localhost:5173/diagnosis"));
                if (!defaultText(properties.getLinkPicUrl(), "").isBlank()) {
                    putIfPresent(arguments, normalizedKeys, List.of("picurl", "picUrl", "image", "imageUrl"),
                            properties.getLinkPicUrl().trim());
                }
            }
            default -> {
                String actualMarkdownTitleKey = firstPresent(normalizedKeys, List.of("msg.markdown.title", "markdown.title", "title"));
                String actualMarkdownTextKey = firstPresent(normalizedKeys, List.of("msg.markdown.text", "markdown.text", "text", "content", "message", "body"));
                arguments.put(actualMarkdownTitleKey != null ? actualMarkdownTitleKey : "msg.markdown.title", safeTitle);
                arguments.put(actualMarkdownTextKey != null ? actualMarkdownTextKey : "msg.markdown.text", markdownText);
            }
        }

        return arguments;
    }

    private void putIfPresent(Map<String, Object> target,
                              Map<String, String> normalizedKeys,
                              List<String> candidates,
                              String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String candidate : candidates) {
            String actualKey = normalizedKeys.get(candidate.toLowerCase(Locale.ROOT));
            if (actualKey != null && !target.containsKey(actualKey)) {
                target.put(actualKey, value);
                return;
            }
        }
    }

    private String firstPresent(Map<String, String> normalizedKeys, List<String> candidates) {
        for (String candidate : candidates) {
            String actualKey = normalizedKeys.get(candidate.toLowerCase(Locale.ROOT));
            if (actualKey != null) {
                return actualKey;
            }
        }
        return null;
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String joinAsCsv(List<String> values) {
        return String.join(",", cleanList(values));
    }

    private ToolCallSummary summarizeToolCallResult(String toolName, McpSchema.CallToolResult result) {
        if (result == null) {
            return ToolCallSummary.failure("DingTalk MCP 返回空结果", "");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = firstNonBlank(extractContentText(result), stringifyStructuredContent(result));
            return ToolCallSummary.failure("DingTalk MCP 工具调用失败: " + errorText, errorText);
        }

        String payload = firstNonBlank(extractContentText(result), stringifyStructuredContent(result), toolName);
        if (containsBusinessError(payload)) {
            return ToolCallSummary.failure("DingTalk MCP 工具调用失败: " + payload, payload);
        }
        return ToolCallSummary.success("DingTalk MCP 工具调用成功: " + payload, payload);
    }

    private String stringifyStructuredContent(McpSchema.CallToolResult result) {
        return result == null || result.structuredContent() == null ? "" : String.valueOf(result.structuredContent());
    }

    private String extractTaskId(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode taskIdNode = firstPresent(root, "task_id", "taskId", "id");
            return taskIdNode == null || taskIdNode.isNull() ? "" : taskIdNode.asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private DeliveryStatusSummary queryDeliveryStatus(McpSyncClient client,
                                                      List<McpSchema.Tool> tools,
                                                      String taskId) {
        ToolFollowupSummary progress = invokeFollowupTool(client, tools, SEND_PROGRESS_TOOL_NAMES, taskId);
        ToolFollowupSummary result = invokeFollowupTool(client, tools, SEND_RESULT_TOOL_NAMES, taskId);
        return new DeliveryStatusSummary(progress, result);
    }

    private ToolFollowupSummary invokeFollowupTool(McpSyncClient client,
                                                   List<McpSchema.Tool> tools,
                                                   List<String> candidateToolNames,
                                                   String taskId) {
        McpSchema.Tool tool = selectFollowupTool(tools, candidateToolNames);
        if (tool == null) {
            return ToolFollowupSummary.unavailable(candidateToolNames.get(0) + " unavailable");
        }
        try {
            Map<String, Object> arguments = buildTaskQueryArguments(tool, taskId);
            log.info("调用 DingTalk MCP 跟进工具: {}, arguments={}", tool.name(), arguments);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool.name(), arguments));
            log.info("DingTalk MCP 跟进工具原始返回: tool={}, isError={}, structuredContent={}, content={}",
                    tool.name(),
                    result == null ? null : result.isError(),
                    result == null ? null : result.structuredContent(),
                    result == null ? null : result.content());
            ToolCallSummary summary = summarizeToolCallResult(tool.name(), result);
            return summary.success()
                    ? ToolFollowupSummary.success(tool.name(), summary.rawPayload())
                    : ToolFollowupSummary.failure(tool.name(), summary.message());
        } catch (Exception e) {
            log.warn("调用 DingTalk MCP 跟进工具失败: tool={}, taskId={}", tool.name(), taskId, e);
            return ToolFollowupSummary.failure(tool.name(), e.getMessage());
        }
    }

    private McpSchema.Tool selectFollowupTool(List<McpSchema.Tool> tools, List<String> candidateToolNames) {
        for (String candidate : candidateToolNames) {
            McpSchema.Tool exact = selectTool(candidate, tools);
            if (exact != null) {
                return exact;
            }
        }
        for (McpSchema.Tool tool : defaultTools(tools)) {
            String normalizedName = defaultText(tool.name(), "").toLowerCase(Locale.ROOT);
            for (String candidate : candidateToolNames) {
                if (normalizedName.contains(candidate.toLowerCase(Locale.ROOT))) {
                    return tool;
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildTaskQueryArguments(McpSchema.Tool tool, String taskId) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, Object> propertiesMap = tool.inputSchema() == null || tool.inputSchema().properties() == null
                ? Map.of()
                : tool.inputSchema().properties();
        if (propertiesMap.isEmpty()) {
            arguments.put("task_id", taskId);
            return arguments;
        }
        Map<String, String> normalizedKeys = new LinkedHashMap<>();
        for (String key : propertiesMap.keySet()) {
            normalizedKeys.put(key.toLowerCase(Locale.ROOT), key);
        }
        String actualTaskIdKey = firstPresent(normalizedKeys, List.of("task_id", "taskid", "id"));
        arguments.put(actualTaskIdKey != null ? actualTaskIdKey : "task_id", taskId);
        String actualAgentIdKey = firstPresent(normalizedKeys, List.of("agent_id", "agentid"));
        if (actualAgentIdKey != null && properties.getAgentId() != null && properties.getAgentId() > 0) {
            arguments.put(actualAgentIdKey, properties.getAgentId());
        }
        return arguments;
    }

    private String renderSendNoticeOutcome(String taskId,
                                           String sendPayload,
                                           DeliveryStatusSummary deliveryStatus) {
        StringBuilder builder = new StringBuilder();
        builder.append("DingTalk MCP 工作通知任务创建成功: taskId=").append(taskId);
        if (!defaultText(sendPayload, "").isBlank()) {
            builder.append(", sendNotice=").append(sendPayload);
        }
        if (deliveryStatus.progress().available()) {
            builder.append(", sendProgress=");
            builder.append(deliveryStatus.progress().success()
                    ? deliveryStatus.progress().payload()
                    : "failed(" + deliveryStatus.progress().payload() + ")");
        }
        if (deliveryStatus.result().available()) {
            builder.append(", sendResult=");
            builder.append(deliveryStatus.result().success()
                    ? deliveryStatus.result().payload()
                    : "failed(" + deliveryStatus.result().payload() + ")");
        }
        if (!deliveryStatus.progress().available() && !deliveryStatus.result().available()) {
            builder.append(", deliveryStatus=follow-up tools unavailable");
        }
        return builder.toString();
    }

    private String extractContentText(McpSchema.CallToolResult result) {
        List<String> messages = new ArrayList<>();
        List<McpSchema.Content> contentList = result.content();
        if (contentList == null) {
            return "";
        }
        for (McpSchema.Content item : contentList) {
            if (item instanceof McpSchema.TextContent textContent) {
                messages.add(defaultText(textContent.text(), ""));
            } else if (item instanceof McpSchema.EmbeddedResource embeddedResource) {
                messages.add(String.valueOf(embeddedResource.resource()));
            } else if (item != null) {
                messages.add(String.valueOf(item));
            }
        }
        return String.join("\n", messages).trim();
    }

    private boolean containsBusinessError(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(text);
            if (root.has("errcode")) {
                return root.path("errcode").asInt(0) != 0;
            }
            if (root.has("success")) {
                return !root.path("success").asBoolean(true);
            }
            if (root.has("code")) {
                String code = root.path("code").asText("");
                if (!code.isBlank() && !"0".equals(code) && !"success".equalsIgnoreCase(code)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("\"errcode\"") && !normalized.contains("\"errcode\": 0")) {
                return true;
            }
            if (normalized.contains("errmsg")) {
                return true;
            }
        }

        return false;
    }

    private JsonNode firstPresent(JsonNode root, String... fields) {
        if (root == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            JsonNode node = root.path(field);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean looksLikeAuthError(Exception exception) {
        String message = exception == null ? "" : defaultText(exception.getMessage(), "").toLowerCase(Locale.ROOT);
        return message.contains("401")
                || message.contains("403")
                || message.contains("unauthorized")
                || message.contains("forbidden")
                || message.contains("auth")
                || message.contains("api_key");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @FunctionalInterface
    private interface DingtalkClientCallback<T> {
        T execute(McpSyncClient client, List<McpSchema.Tool> tools) throws Exception;
    }

    private record ToolCallSummary(boolean success, String message, String rawPayload) {
        private static ToolCallSummary success(String message, String rawPayload) {
            return new ToolCallSummary(true, message, rawPayload);
        }

        private static ToolCallSummary failure(String message, String rawPayload) {
            return new ToolCallSummary(false, message, rawPayload);
        }
    }

    private record ToolFollowupSummary(boolean available, boolean success, String toolName, String payload) {
        private static ToolFollowupSummary unavailable(String payload) {
            return new ToolFollowupSummary(false, false, "", payload);
        }

        private static ToolFollowupSummary success(String toolName, String payload) {
            return new ToolFollowupSummary(true, true, toolName, payload);
        }

        private static ToolFollowupSummary failure(String toolName, String payload) {
            return new ToolFollowupSummary(true, false, toolName, payload);
        }
    }

    private record DeliveryStatusSummary(ToolFollowupSummary progress, ToolFollowupSummary result) {
    }
}

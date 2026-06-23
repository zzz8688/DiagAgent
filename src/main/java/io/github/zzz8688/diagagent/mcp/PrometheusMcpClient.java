package io.github.zzz8688.diagagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.protocol.ProtocolAuthConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolLifecycleManager;
import io.github.zzz8688.diagagent.protocol.ProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolToolDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolType;
import io.github.zzz8688.diagagent.protocol.StdioProtocolServerConfig;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class PrometheusMcpClient implements McpClientAdapter {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMcpClient.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final ProtocolLifecycleManager protocolLifecycleManager;
    private final SandboxExecutionService sandboxExecutionService;

    @Value("${mcp.prometheus.enabled:true}")
    private boolean enabled;

    @Value("${mcp.prometheus.python-command:python}")
    private String pythonCommand;

    @Value("${mcp.prometheus.script-path:./mcp/prometheus-mcp-server.py}")
    private String scriptPath;

    @Value("${mcp.prometheus.base-url:http://localhost:9090}")
    private String prometheusBaseUrl;

    @Value("${mcp.prometheus.call-timeout-ms:10000}")
    private long callTimeoutMs;

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> listTools() {
        try {
            refreshCapabilities(protocolLifecycleManager);
            return discoverTools().stream()
                    .map(ProtocolToolDescriptor::name)
                    .toList();
        } catch (Exception e) {
            log.warn("列出 Prometheus MCP tools 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public String queryPrometheusMetrics(String metricName, String timeRange) {
        if (!enabled) {
            protocolLifecycleManager.register(this);
            protocolLifecycleManager.markDisabled(this);
            throw new IllegalStateException("Prometheus MCP 未启用");
        }
        String query = normalizePrometheusQuery(metricName);
        try {
            SandboxProtectedActionEnvelope<String> envelope = sandboxExecutionService.executeProtectedAction(
                    "queryPrometheusMetrics",
                    SandboxTargetType.MCP_TOOL,
                    "prometheus-mcp/queryPrometheusMetrics",
                    Map.of(
                            "metricName", metricName == null ? "" : metricName,
                            "timeRange", timeRange == null ? "" : timeRange,
                            "query", query
                    ),
                    null,
                    () -> queryPrometheusMetricsDirect(metricName, timeRange, query)
            );
            if (!envelope.success()) {
                protocolLifecycleManager.markFailed(this, envelope.errorMessage());
                log.warn("Prometheus MCP 查询被治理链拒绝或执行失败: {}", envelope.errorMessage());
                throw new IllegalStateException("Prometheus MCP 查询失败: " + envelope.errorMessage());
            }
            return envelope.result();
        } catch (Exception e) {
            protocolLifecycleManager.markFailed(this, e.getMessage());
            log.warn("Prometheus MCP 查询失败，metricName={}, timeRange={}, error={}",
                    metricName, timeRange, e.getMessage());
            throw new IllegalStateException("Prometheus MCP 查询失败: " + e.getMessage(), e);
        }
    }

    private String queryPrometheusMetricsDirect(String metricName, String timeRange, String query) {
        try {
            refreshCapabilities(protocolLifecycleManager);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("query", query);
            applyRangeArguments(arguments, timeRange);
            Map<String, Object> response = castResponse(invokeTool("queryPrometheusMetrics", arguments));
            if (response.containsKey("error")) {
                protocolLifecycleManager.markFailed(this, String.valueOf(response.get("error")));
                log.warn("Prometheus MCP 返回错误响应: {}", response.get("error"));
                throw new IllegalStateException("Prometheus MCP 返回错误响应: " + response.get("error"));
            }

            Map<String, Object> result = asMap(response.get("result"));
            List<Map<String, Object>> content = castContent(result.get("content"));
            if (content.isEmpty()) {
                protocolLifecycleManager.markDegraded(this, "Prometheus MCP 返回内容为空");
                throw new IllegalStateException("Prometheus MCP 返回内容为空");
            }

            Object text = content.get(0).get("text");
            if (text == null) {
                protocolLifecycleManager.markDegraded(this, "Prometheus MCP 返回 text 为空");
                throw new IllegalStateException("Prometheus MCP 返回 text 为空");
            }

            return "MCP 指标查询成功\n"
                    + "metricName: " + metricName + "\n"
                    + "timeRange: " + timeRange + "\n"
                    + "prometheusQuery: " + query + "\n"
                    + text;
        } catch (Exception e) {
            protocolLifecycleManager.markFailed(this, e.getMessage());
            log.warn("Prometheus MCP 查询失败，metricName={}, timeRange={}, error={}",
                    metricName, timeRange, e.getMessage());
            throw new IllegalStateException("Prometheus MCP 查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ProtocolServerConfig serverConfig() {
        return new StdioProtocolServerConfig(
                "prometheus-mcp",
                "Prometheus MCP Server",
                ProtocolType.MCP,
                pythonCommand,
                List.of(scriptPath, "--prometheus-url", prometheusBaseUrl),
                enabled,
                ProtocolAuthConfig.none()
        );
    }

    @Override
    public List<ProtocolToolDescriptor> bootstrapTools() {
        return List.of(new ProtocolToolDescriptor(
                "queryPrometheusMetrics",
                "通过外部 Prometheus MCP server 查询监控指标。",
                true,
                true
        ));
    }

    @Override
    public List<ProtocolToolDescriptor> discoverTools() throws IOException {
        if (!enabled) {
            return List.of();
        }
        List<String> toolNames = invoke(server -> {
            initialize(server);
            Map<String, Object> payload = jsonRpcRequest(2, "tools/list", Map.of());
            writeMessage(server.output(), payload);
            Map<String, Object> result = awaitResponse(server.input(), callTimeoutMs);
            return extractToolNames(result);
        });
        return toDiscoveredTools(toolNames);
    }

    @Override
    public Object invokeTool(String toolName, Map<String, Object> arguments) throws IOException {
        if (!enabled) {
            throw new IOException("Prometheus MCP 未启用");
        }
        try {
            return invoke(server -> {
                initialize(server);

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("name", toolName);
                params.put("arguments", arguments == null ? Map.of() : arguments);

                Map<String, Object> payload = jsonRpcRequest(2, "tools/call", params);
                writeMessage(server.output(), payload);
                return awaitResponse(server.input(), callTimeoutMs);
            });
        } catch (IOException e) {
            log.warn("Prometheus MCP invokeTool 失败: toolName={}, error={}", toolName, e.getMessage());
            throw e;
        }
    }

    private <T> T invoke(ServerCallback<T> callback) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildCommand());
        builder.directory(Path.of(".").toAbsolutePath().normalize().toFile());
        Process process = builder.start();

        try (BufferedInputStream input = new BufferedInputStream(process.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(process.getOutputStream())) {
            ServerSession server = new ServerSession(input, output);
            return callback.execute(server);
        } finally {
            destroyProcess(process);
        }
    }

    private void initialize(ServerSession server) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "diagagent-java-client", "version", "0.1.0"));

        writeMessage(server.output(), jsonRpcRequest(1, "initialize", params));
        awaitResponse(server.input(), callTimeoutMs);
        writeMessage(server.output(), jsonRpcNotification("notifications/initialized", Map.of()));
    }

    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        command.add(scriptPath);
        command.add("--prometheus-url");
        command.add(prometheusBaseUrl);
        return command;
    }

    private Map<String, Object> castResponse(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> response = new LinkedHashMap<>();
            map.forEach((key, value) -> response.put(String.valueOf(key), value));
            return response;
        }
        throw new IllegalStateException("Prometheus MCP 返回了无法识别的响应结构");
    }

    private Map<String, Object> jsonRpcRequest(int id, String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        return payload;
    }

    private Map<String, Object> jsonRpcNotification(String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", params);
        return payload;
    }

    private void writeMessage(OutputStream output, Map<String, Object> payload) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(payload);
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private Map<String, Object> awaitResponse(InputStream input, long timeoutMs) throws IOException {
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return readMessage(input);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 MCP 响应被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException state && state.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("读取 MCP 响应失败", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("等待 MCP 响应超时");
        }
    }

    private Map<String, Object> readMessage(InputStream input) throws IOException {
        int contentLength = 0;
        while (true) {
            String line = readAsciiLine(input);
            if (line == null) {
                throw new IOException("MCP 服务端未返回完整响应");
            }
            if (line.isEmpty()) {
                break;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }

        if (contentLength <= 0) {
            throw new IOException("MCP 响应缺少有效的 Content-Length");
        }

        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("MCP 响应体不完整");
        }

        return objectMapper.readValue(body, MAP_TYPE);
    }

    private String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (buffer.size() == 0) {
                    return null;
                }
                break;
            }
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                buffer.write(next);
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private List<String> extractToolNames(Map<String, Object> response) {
        Map<String, Object> result = asMap(response.get("result"));
        List<Map<String, Object>> tools = castContent(result.get("tools"));
        List<String> names = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Object name = tool.get("name");
            if (name != null) {
                names.add(String.valueOf(name));
            }
        }
        return names;
    }

    private List<ProtocolToolDescriptor> toDiscoveredTools(List<String> toolNames) {
        return toolNames.stream()
                .map(name -> new ProtocolToolDescriptor(name, "Discovered from Prometheus MCP server", true, true))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castContent(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                content.add((Map<String, Object>) map);
            }
        }
        return content;
    }

    private String normalizePrometheusQuery(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return "process_cpu_usage";
        }

        String normalized = metricName.trim().toLowerCase(Locale.ROOT);
        if ((normalized.contains("checkout") || normalized.contains("结账") || normalized.contains("下单"))
                && (normalized.contains("pending") || normalized.contains("等待")
                || normalized.contains("转圈") || normalized.contains("回执"))) {
            return "payment_result_pending_requests{service=\"payment-service\"}";
        }
        if ((normalized.contains("checkout") || normalized.contains("结账") || normalized.contains("下单"))
                && (normalized.contains("error") || normalized.contains("错误")
                || normalized.contains("失败") || normalized.contains("timeout")
                || normalized.contains("超时") || normalized.contains("504"))) {
            return "payment_gateway_retry_exhausted_total{service=\"payment-service\"}";
        }
        if ((normalized.contains("payment") || normalized.contains("支付"))
                && (normalized.contains("gateway") || normalized.contains("网关"))
                && (normalized.contains("health") || normalized.contains("状态")
                || normalized.contains("依赖") || normalized.contains("upstream"))) {
            return "upstream_dependency_health{service=\"payment-service\",dependency=\"payment-gateway\"}";
        }
        if ((normalized.contains("payment") || normalized.contains("支付"))
                && (normalized.contains("gateway") || normalized.contains("网关"))
                && (normalized.contains("latency") || normalized.contains("duration")
                || normalized.contains("延迟") || normalized.contains("耗时"))) {
            return "upstream_request_duration_seconds{service=\"payment-service\",dependency=\"payment-gateway\"}";
        }
        if ((normalized.contains("payment") || normalized.contains("支付"))
                && (normalized.contains("gateway") || normalized.contains("网关"))
                && (normalized.contains("error") || normalized.contains("异常") || normalized.contains("错误"))) {
            return "upstream_request_error_rate{service=\"payment-service\",dependency=\"payment-gateway\"}";
        }
        if ((normalized.contains("payment") || normalized.contains("支付"))
                && (normalized.contains("retry") || normalized.contains("重试"))) {
            return "payment_gateway_retry_exhausted_total{service=\"payment-service\"}";
        }
        if ((normalized.contains("payment") || normalized.contains("支付"))
                && (normalized.contains("pending") || normalized.contains("转圈")
                || normalized.contains("回执") || normalized.contains("等待"))) {
            return "payment_result_pending_requests{service=\"payment-service\"}";
        }
        if ((normalized.contains("payment-service") || normalized.contains("payment service") || normalized.contains("支付服务"))
                && (normalized.contains("pool") || normalized.contains("连接池") || normalized.contains("connection"))) {
            return "payment_gateway_retry_exhausted_total{service=\"payment-service\"}";
        }
        if ((normalized.contains("payment-service") || normalized.contains("payment service") || normalized.contains("支付服务"))
                && (normalized.contains("slow") || normalized.contains("慢查询") || normalized.contains("query"))) {
            return "upstream_request_duration_seconds{service=\"payment-service\",dependency=\"payment-gateway\"}";
        }
        if ((normalized.contains("payment-service") || normalized.contains("payment service") || normalized.contains("支付服务"))
                && (normalized.contains("latency") || normalized.contains("duration")
                || normalized.contains("延迟") || normalized.contains("耗时"))) {
            return "http_request_duration_seconds{service=\"payment-service\",endpoint=\"/api/payments\"}";
        }
        if ((normalized.contains("payment-service") || normalized.contains("payment service") || normalized.contains("支付服务"))
                && (normalized.contains("error") || normalized.contains("异常") || normalized.contains("错误"))) {
            return "service_error_rate{service=\"payment-service\"}";
        }
        if ((normalized.contains("order-service") || normalized.contains("order service") || normalized.contains("订单服务"))
                && (normalized.contains("latency") || normalized.contains("duration")
                || normalized.contains("延迟") || normalized.contains("耗时"))) {
            return "http_request_duration_seconds{service=\"order-service\",endpoint=\"/api/orders/checkout\"}";
        }
        if ((normalized.contains("order-service") || normalized.contains("order service") || normalized.contains("订单服务"))
                && (normalized.contains("error") || normalized.contains("异常") || normalized.contains("错误"))) {
            return "service_error_rate{service=\"order-service\"}";
        }
        if ((normalized.contains("order-service") || normalized.contains("order service") || normalized.contains("订单服务"))
                && (normalized.contains("health") || normalized.contains("状态")
                || normalized.contains("依赖") || normalized.contains("upstream"))) {
            return "upstream_dependency_health{service=\"order-service\",dependency=\"payment-service\"}";
        }
        if ((normalized.contains("product") || normalized.contains("商品"))
                && (normalized.contains("首屏") || normalized.contains("first screen")
                || normalized.contains("页面") || normalized.contains("加载慢"))) {
            return "frontend_first_screen_p95{page=\"/products\"}";
        }
        if ((normalized.contains("product") || normalized.contains("商品"))
                && (normalized.contains("bundle") || normalized.contains("静态资源")
                || normalized.contains("脚本"))) {
            return "static_bundle_download_p95{page=\"/products\"}";
        }
        if ((normalized.contains("product") || normalized.contains("商品"))
                && (normalized.contains("cache") || normalized.contains("缓存"))) {
            return "product_detail_cache_hit_ratio{service=\"product-service\"}";
        }
        if ((normalized.contains("product") || normalized.contains("商品"))
                && (normalized.contains("recommendation") || normalized.contains("推荐")
                || normalized.contains("依赖"))) {
            return "recommendation_request_duration_p95{service=\"recommendation-service\"}";
        }
        if ((normalized.contains("系统") || normalized.contains("巡检") || normalized.contains("健康"))
                && (normalized.contains("实例") || normalized.contains("健康") || normalized.contains("异常"))) {
            return "service_health_score{service=\"gateway-service\"}";
        }
        if ((normalized.contains("auth") || normalized.contains("鉴权") || normalized.contains("token")
                || normalized.contains("会话"))) {
            return "token_validation_latency_p99{service=\"auth-service\"}";
        }
        if (normalized.contains("cpu")) {
            return "process_cpu_usage{service=\"mock-app-service\"}";
        }
        if (normalized.contains("memory") || normalized.contains("mem") || normalized.contains("内存")) {
            return "jvm_memory_used_bytes{service=\"mock-app-service\",area=\"heap\"}";
        }
        if (normalized.contains("latency") || normalized.contains("duration")
                || normalized.contains("响应") || normalized.contains("耗时")) {
            return "http_request_duration_seconds{service=\"mock-app-service\",endpoint=\"/api/orders\"}";
        }
        if (normalized.contains("error") || normalized.contains("异常") || normalized.contains("错误")) {
            return "service_error_rate{service=\"mock-app-service\"}";
        }
        if (normalized.contains("db") || normalized.contains("pool") || normalized.contains("连接")) {
            return "db_connection_pool_active{service=\"mock-db-service\",pool=\"primary\"}";
        }
        return metricName.trim();
    }

    private void applyRangeArguments(Map<String, Object> arguments, String timeRange) {
        TimeRangeWindow rangeWindow = resolveTimeRangeWindow(timeRange);
        if (arguments == null || rangeWindow == null) {
            return;
        }
        arguments.put("start", Long.toString(rangeWindow.startEpochSeconds()));
        arguments.put("end", Long.toString(rangeWindow.endEpochSeconds()));
        arguments.put("step", rangeWindow.step());
    }

    private TimeRangeWindow resolveTimeRangeWindow(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return null;
        }
        String normalized = timeRange.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            long amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
            char unit = normalized.charAt(normalized.length() - 1);
            long seconds = switch (unit) {
                case 's' -> amount;
                case 'm' -> amount * 60;
                case 'h' -> amount * 3600;
                case 'd' -> amount * 86400;
                default -> -1;
            };
            if (seconds <= 0) {
                return null;
            }
            Instant end = Instant.now();
            Instant start = end.minusSeconds(seconds);
            return new TimeRangeWindow(
                    start.getEpochSecond(),
                    end.getEpochSecond(),
                    chooseStep(seconds)
            );
        } catch (NumberFormatException ex) {
            log.debug("无法解析 timeRange，降级为 instant query: {}", timeRange);
            return null;
        }
    }

    private String chooseStep(long seconds) {
        if (seconds <= 30 * 60) {
            return "30s";
        }
        if (seconds <= 6 * 3600) {
            return "1m";
        }
        if (seconds <= 24 * 3600) {
            return "5m";
        }
        return "15m";
    }

    private void destroyProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record ServerSession(BufferedInputStream input, BufferedOutputStream output) {
    }

    private record TimeRangeWindow(
            long startEpochSeconds,
            long endEpochSeconds,
            String step
    ) {
    }

    @FunctionalInterface
    private interface ServerCallback<T> {
        T execute(ServerSession server) throws IOException;
    }
}

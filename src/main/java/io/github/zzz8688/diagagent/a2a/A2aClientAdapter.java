package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.config.A2aProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
@Slf4j
@RequiredArgsConstructor
public class A2aClientAdapter {

    private final A2aProperties a2aProperties;
    private final A2aRegistryService a2aRegistryService;
    private final A2aTaskEventBridge a2aTaskEventBridge;
    private final A2aRemoteSessionBridge a2aRemoteSessionBridge;
    private final ObjectMapper objectMapper;

    public A2aAgentCard fetchAgentCard(String agentId) {
        A2aRemoteAgentRegistration agent = requireAgent(agentId);
        URI cardUri = resolveAgentCardUri(agent);
        try {
            a2aRegistryService.markDiscoveryStarted(agentId);
            HttpRequest request = HttpRequest.newBuilder(cardUri)
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent())
                    .timeout(requestTimeout())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertHttpSuccess(response.statusCode(), "拉取 Agent Card", cardUri.toString());
            A2aAgentCard card = objectMapper.readValue(defaultBody(response.body()), A2aAgentCard.class);
            a2aRegistryService.markDiscoverySucceeded(agentId, card);
            return card;
        } catch (Exception e) {
            a2aRegistryService.markDiscoveryFailed(agentId, e.getMessage());
            throw new IllegalStateException("拉取 A2A Agent Card 失败: agentId=" + agentId + ", url=" + cardUri, e);
        }
    }

    public A2aClientInvocationResult sendMessage(String agentId, A2aSendMessageParams params) {
        return invoke(agentId, "SendMessage", params);
    }

    public Flux<A2aTaskEvent> sendStreamingMessage(String agentId, A2aSendMessageParams params) {
        return invokeStreaming(agentId, "SendStreamingMessage", params);
    }

    public A2aClientInvocationResult getTask(String agentId, A2aTaskQueryParams params) {
        return invoke(agentId, "GetTask", params);
    }

    public A2aClientInvocationResult cancelTask(String agentId, A2aTaskIdParams params) {
        return invoke(agentId, "CancelTask", params);
    }

    private <T> A2aClientInvocationResult invoke(String agentId, String method, T params) {
        A2aRemoteAgentRegistration agent = requireAgent(agentId);
        A2aJsonRpcRequest<T> requestPayload = A2aJsonRpcRequest.create(method, params);
        Instant startedAt = Instant.now();
        try {
            String requestBody = objectMapper.writeValueAsString(requestPayload);
            log.info("A2A invoke start: agentId={}, method={}, requestId={}, endpoint={}",
                    agentId, method, requestPayload.id(), agent.serverConfig().endpoint());
            HttpRequest request = HttpRequest.newBuilder(URI.create(agent.serverConfig().endpoint()))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("A2A-Version", protocolVersion(agent))
                    .header("User-Agent", userAgent())
                    .timeout(requestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertHttpSuccess(response.statusCode(), method, agent.serverConfig().endpoint());
            A2aJsonRpcResponse rpcResponse = objectMapper.readValue(defaultBody(response.body()), A2aJsonRpcResponse.class);
            JsonNode rawResult = rpcResponse.requireResult();
            A2aPayload payload = parsePayload(rawResult);
            A2aClientInvocationResult invocationResult = new A2aClientInvocationResult(
                    agentId,
                    agent.serverConfig().endpoint(),
                    requestPayload.id(),
                    method,
                    payload.payloadType(),
                    payload.taskId(),
                    payload.contextId(),
                    payload.taskState(),
                    payload,
                    Instant.now().toString()
            );
            log.info("A2A invoke success: agentId={}, method={}, requestId={}, payloadType={}, taskId={}, taskState={}, durationMs={}",
                    agentId,
                    method,
                    requestPayload.id(),
                    invocationResult.resultType(),
                    defaultBody(invocationResult.taskId()),
                    defaultBody(invocationResult.taskState()),
                    Duration.between(startedAt, Instant.now()).toMillis());
            a2aTaskEventBridge.publishInvocation(invocationResult);
            a2aRegistryService.markInvocationSucceeded(agentId);
            return invocationResult;
        } catch (Exception e) {
            log.warn("A2A invoke failed: agentId={}, method={}, requestId={}, durationMs={}",
                    agentId,
                    method,
                    requestPayload.id(),
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    e);
            a2aRegistryService.markInvocationFailed(agentId, e.getMessage());
            throw new IllegalStateException("A2A 调用失败: agentId=" + agentId + ", method=" + method, e);
        }
    }

    private <T> Flux<A2aTaskEvent> invokeStreaming(String agentId, String method, T params) {
        A2aRemoteAgentRegistration agent = requireAgent(agentId);
        assertStreamingCapable(agent);
        A2aJsonRpcRequest<T> requestPayload = A2aJsonRpcRequest.create(method, params);
        return Flux.<A2aTaskEvent>create(sink -> {
            try {
                a2aRegistryService.markStreamingStarted(agentId);
                String requestBody = objectMapper.writeValueAsString(requestPayload);
                HttpRequest request = HttpRequest.newBuilder(URI.create(agent.serverConfig().endpoint()))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("Accept", "text/event-stream, application/json")
                        .header("A2A-Version", protocolVersion(agent))
                        .header("User-Agent", userAgent())
                        .timeout(streamingRequestTimeout())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertHttpSuccess(response.statusCode(), method, agent.serverConfig().endpoint());
                try (InputStream responseBody = response.body()) {
                    String contentType = response.headers().firstValue("Content-Type").orElse("");
                    if (contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                        consumeEventStream(agentId, method, responseBody, sink);
                    } else {
                        emitJsonStreamingResult(agentId, method, responseBody, sink);
                    }
                }
                a2aRegistryService.markStreamingSucceeded(agentId);
                sink.complete();
            } catch (Exception e) {
                a2aRegistryService.markStreamingFailed(agentId, e.getMessage());
                String taskId = params instanceof A2aSendMessageParams sendMessageParams && sendMessageParams.message() != null
                        ? sendMessageParams.message().taskId()
                        : null;
                if (taskId != null && !taskId.isBlank()) {
                    a2aRemoteSessionBridge.markReconnect(taskId);
                }
                sink.error(new IllegalStateException("A2A streaming 调用失败: agentId=" + agentId + ", method=" + method, e));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void consumeEventStream(String agentId,
                                    String sourceMethod,
                                    InputStream responseBody,
                                    FluxSink<A2aTaskEvent> sink) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
            StringBuilder currentData = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    emitBufferedEvent(agentId, sourceMethod, currentData, sink);
                    currentData.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (currentData.length() > 0) {
                        currentData.append('\n');
                    }
                    currentData.append(line.substring("data:".length()).trim());
                }
            }
            emitBufferedEvent(agentId, sourceMethod, currentData, sink);
        }
    }

    private void emitBufferedEvent(String agentId,
                                   String sourceMethod,
                                   StringBuilder currentData,
                                   FluxSink<A2aTaskEvent> sink) {
        if (currentData == null || currentData.length() == 0) {
            return;
        }
        JsonNode rawPayload = parseStreamingPayload(currentData.toString());
        A2aPayload payload = parsePayload(rawPayload);
        A2aTaskEvent event = a2aTaskEventBridge.publishStreamingEvent(
                agentId,
                sourceMethod,
                payload
        );
        a2aRemoteSessionBridge.openSession(agentId, event.taskId(), event.contextId());
        a2aRemoteSessionBridge.acknowledgeDelivered(event.taskId(), event.sequence());
        sink.next(event);
    }

    private void emitJsonStreamingResult(String agentId,
                                         String sourceMethod,
                                         InputStream responseBody,
                                         FluxSink<A2aTaskEvent> sink) throws IOException {
        String body = new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
        A2aJsonRpcResponse rpcResponse = objectMapper.readValue(defaultBody(body), A2aJsonRpcResponse.class);
        JsonNode rawResult = rpcResponse.requireResult();
        A2aPayload payload = parsePayload(rawResult);
        A2aTaskEvent event = a2aTaskEventBridge.publishStreamingEvent(
                agentId,
                sourceMethod,
                payload
        );
        a2aRemoteSessionBridge.openSession(agentId, event.taskId(), event.contextId());
        a2aRemoteSessionBridge.acknowledgeDelivered(event.taskId(), event.sequence());
        sink.next(event);
    }

    private JsonNode parseStreamingPayload(String value) {
        try {
            return objectMapper.readTree(defaultBody(value));
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("text", value);
        }
    }

    private void assertStreamingCapable(A2aRemoteAgentRegistration agent) {
        if (agent.agentCard() == null || agent.agentCard().capabilities() == null) {
            return;
        }
        if (!agent.agentCard().capabilities().streaming()) {
            throw new IllegalStateException("A2A agent 不支持 streaming: " + agent.agentId());
        }
    }

    private A2aRemoteAgentRegistration requireAgent(String agentId) {
        return a2aRegistryService.findAgent(agentId)
                .orElseThrow(() -> new IllegalArgumentException("未找到 A2A agent: " + agentId));
    }

    private URI resolveAgentCardUri(A2aRemoteAgentRegistration agent) {
        String endpoint = agent.serverConfig() == null ? "" : agent.serverConfig().endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("A2A agent 未配置 endpoint: " + agent.agentId());
        }
        URI endpointUri = URI.create(endpoint);
        String scheme = endpointUri.getScheme() == null ? "http" : endpointUri.getScheme().toLowerCase(Locale.ROOT);
        String authority = endpointUri.getAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("A2A endpoint 非法，缺少主机信息: " + endpoint);
        }
        return URI.create(scheme + "://" + authority + "/.well-known/agent-card.json");
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout())
                .build();
    }

    private Duration connectTimeout() {
        long timeoutMs = Math.max(1000L, a2aProperties.getClient().getConnectTimeoutMs());
        return Duration.ofMillis(timeoutMs);
    }

    private Duration requestTimeout() {
        long timeoutMs = Math.max(1000L, a2aProperties.getClient().getRequestTimeoutMs());
        return Duration.ofMillis(timeoutMs);
    }

    private Duration streamingRequestTimeout() {
        long timeoutMs = Math.max(1000L, a2aProperties.getClient().getStreamingRequestTimeoutMs());
        return Duration.ofMillis(timeoutMs);
    }

    private String protocolVersion(A2aRemoteAgentRegistration agent) {
        if (agent.agentCard() != null
                && agent.agentCard().supportedInterfaces() != null
                && !agent.agentCard().supportedInterfaces().isEmpty()) {
            String version = agent.agentCard().supportedInterfaces().get(0).protocolVersion();
            if (version != null && !version.isBlank()) {
                return version;
            }
        }
        return a2aProperties.getSelf() == null ? "1.0" : defaultText(a2aProperties.getSelf().getProtocolVersion(), "1.0");
    }

    private String userAgent() {
        return defaultText(a2aProperties.getClient().getUserAgent(), "DiagAgent-A2A-Client/1.0");
    }

    private A2aPayload parsePayload(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalStateException("A2A result payload is empty");
        }
        if (looksLikeTask(node)) {
            return convert(node, A2aTask.class, "task");
        }
        if (looksLikeMessage(node)) {
            return convert(node, A2aMessage.class, "message");
        }
        if (looksLikeArtifact(node)) {
            return convert(node, A2aArtifact.class, "artifact");
        }
        if (looksLikeStatusUpdate(node)) {
            return convert(node, A2aTaskStatusUpdateEvent.class, "statusUpdate");
        }
        if (looksLikeArtifactUpdate(node)) {
            return convert(node, A2aTaskArtifactUpdateEvent.class, "artifactUpdate");
        }
        throw new IllegalStateException("Unsupported A2A payload shape: " + node);
    }

    private <T extends A2aPayload> T convert(JsonNode node, Class<T> targetType, String label) {
        try {
            return objectMapper.treeToValue(node, targetType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse A2A " + label + " payload", e);
        }
    }

    private void assertHttpSuccess(int statusCode, String action, String endpoint) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        throw new IllegalStateException(action + "失败: httpStatus=" + statusCode + ", endpoint=" + endpoint);
    }

    private String defaultBody(String body) {
        return body == null || body.isBlank() ? "{}" : body;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean looksLikeTask(JsonNode node) {
        return node != null
                && node.hasNonNull("id")
                && node.hasNonNull("contextId")
                && node.hasNonNull("status");
    }

    private boolean looksLikeMessage(JsonNode node) {
        return node != null
                && node.hasNonNull("messageId")
                && node.hasNonNull("role")
                && node.hasNonNull("parts");
    }

    private boolean looksLikeArtifact(JsonNode node) {
        return node != null
                && node.hasNonNull("artifactId")
                && node.hasNonNull("parts");
    }

    private boolean looksLikeStatusUpdate(JsonNode node) {
        return node != null
                && node.hasNonNull("taskId")
                && node.hasNonNull("contextId")
                && node.hasNonNull("status");
    }

    private boolean looksLikeArtifactUpdate(JsonNode node) {
        return node != null
                && node.hasNonNull("taskId")
                && node.hasNonNull("contextId")
                && node.hasNonNull("artifact");
    }
}

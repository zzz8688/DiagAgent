package io.github.zzz8688.diagagent.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.zzz8688.diagagent.a2a.A2aAgentCard;
import io.github.zzz8688.diagagent.a2a.A2aJsonRpcResponse;
import io.github.zzz8688.diagagent.a2a.A2aSendMessageParams;
import io.github.zzz8688.diagagent.config.A2aProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CriticA2aServer implements SmartLifecycle {

    private static final String WELL_KNOWN_CARD_PATH = "/.well-known/agent-card.json";

    private final CriticA2aAgentLocator criticA2aAgentLocator;
    private final FinalAnswerCriticEngine finalAnswerCriticEngine;
    private final ObjectMapper objectMapper;

    private volatile HttpServer httpServer;
    private volatile ExecutorService executorService;
    private volatile boolean running;

    @Override
    public void start() {
        if (running || !enabled()) {
            return;
        }
        URI endpoint = resolveEndpoint();
        if (endpoint == null) {
            return;
        }
        try {
            InetSocketAddress address = new InetSocketAddress(endpoint.getHost(), endpoint.getPort());
            HttpServer server = HttpServer.create(address, 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.createContext(WELL_KNOWN_CARD_PATH, new AgentCardHandler());
            server.createContext(normalizePath(endpoint.getPath()), new JsonRpcHandler());
            server.setExecutor(executor);
            server.start();
            this.httpServer = server;
            this.executorService = executor;
            this.running = true;
            log.info("Started critic A2A server at {}", endpoint);
        } catch (IOException ex) {
            throw new IllegalStateException("启动 critic A2A server 失败", ex);
        }
    }

    @Override
    public void stop() {
        HttpServer server = this.httpServer;
        ExecutorService executor = this.executorService;
        this.httpServer = null;
        this.executorService = null;
        this.running = false;
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private boolean enabled() {
        return criticA2aAgentLocator.configuredRemote()
                .filter(A2aProperties.RemoteAgentProperties::isEnabled)
                .isPresent();
    }

    private URI resolveEndpoint() {
        return criticA2aAgentLocator.configuredRemote()
                .map(A2aProperties.RemoteAgentProperties::getEndpoint)
                .filter(value -> value != null && !value.isBlank())
                .map(URI::create)
                .orElse(null);
    }

    private Optional<A2aAgentCard> criticAgentCard() {
        return criticA2aAgentLocator.registeredRemote().map(registration -> registration.agentCard());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.trim();
    }

    private final class AgentCardHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            Optional<A2aAgentCard> card = criticAgentCard();
            if (card.isEmpty()) {
                writeStatus(exchange, 404, "critic agent card not found");
                return;
            }
            writeJson(exchange, 200, card.get());
        }
    }

    private final class JsonRpcHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeStatus(exchange, 405, "Method Not Allowed");
                return;
            }
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String id = request.path("id").asText(UUID.randomUUID().toString());
            String method = request.path("method").asText("");
            JsonNode paramsNode = request.path("params");
            try {
                JsonNode result = switch (method) {
                    case "SendMessage" -> handleSendMessage(id, paramsNode);
                    case "GetTask", "CancelTask" -> throw unsupportedTaskMethod(method);
                    default -> throw new IllegalArgumentException("unsupported A2A method: " + method);
                };
                writeJson(exchange, 200, new A2aJsonRpcResponse("2.0", id, result, null));
            } catch (IllegalArgumentException ex) {
                writeJson(exchange, 200, new A2aJsonRpcResponse(
                        "2.0",
                        id,
                        null,
                        new A2aJsonRpcResponse.JsonRpcError(-32601, ex.getMessage(), null)
                ));
            } catch (Exception ex) {
                log.warn("critic A2A request handling failed", ex);
                writeJson(exchange, 200, new A2aJsonRpcResponse(
                        "2.0",
                        id,
                        null,
                        new A2aJsonRpcResponse.JsonRpcError(-32000, ex.getMessage(), null)
                ));
            }
        }

        private JsonNode handleSendMessage(String requestId, JsonNode paramsNode) throws Exception {
            A2aSendMessageParams params = objectMapper.treeToValue(paramsNode, A2aSendMessageParams.class);
            CriticReviewRequest reviewRequest = extractReviewRequest(params);
            log.info("critic A2A request received: requestId={}, sessionId={}, taskId={}, question={}",
                    requestId,
                    reviewRequest.promptContext() == null ? "" : safe(reviewRequest.promptContext().sessionId()),
                    reviewRequest.promptContext() == null ? "" : safe(reviewRequest.promptContext().taskId()),
                    abbreviate(reviewRequest.userQuestion()));
            FinalAnswerCriticService.CriticResult result = finalAnswerCriticEngine.critique(reviewRequest);
            log.info("critic A2A response ready: requestId={}, decision={}, grounded={}, missingEvidence={}, blockingMissing={}",
                    requestId,
                    safe(result.decision()),
                    result.grounded(),
                    result.missingEvidence() == null ? 0 : result.missingEvidence().size(),
                    result.blockingMissingEvidence() == null ? 0 : result.blockingMissingEvidence().size());
            String resultJson = objectMapper.writeValueAsString(result);
            A2aSendMessageParams.Message responseMessage = new A2aSendMessageParams.Message(
                    UUID.randomUUID().toString(),
                    params != null && params.message() != null ? params.message().contextId() : null,
                    null,
                    A2aSendMessageParams.Role.ROLE_AGENT,
                    List.of(new A2aSendMessageParams.TextPart(
                            resultJson,
                            Map.of("schema", "critic-review-result")
                    )),
                    Map.of("agentId", criticA2aAgentLocator.configuredAgentId().orElse("critic-a2a")),
                    null,
                    List.of()
            );
            return objectMapper.valueToTree(responseMessage);
        }

        private CriticReviewRequest extractReviewRequest(A2aSendMessageParams params) throws Exception {
            if (params == null || params.message() == null || params.message().parts().isEmpty()) {
                throw new IllegalArgumentException("critic A2A request missing message parts");
            }
            A2aSendMessageParams.Part part = params.message().parts().get(0);
            if (!(part instanceof A2aSendMessageParams.TextPart textPart)
                    || textPart.text() == null
                    || textPart.text().isBlank()) {
                throw new IllegalArgumentException("critic A2A request part text is blank");
            }
            return objectMapper.readValue(textPart.text(), CriticReviewRequest.class);
        }

        private IllegalArgumentException unsupportedTaskMethod(String method) {
            return new IllegalArgumentException("critic agent is message-only; unsupported method: " + method);
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private void writeStatus(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value) {
        String normalized = safe(value);
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }
}

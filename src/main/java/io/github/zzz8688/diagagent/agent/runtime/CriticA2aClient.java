package io.github.zzz8688.diagagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.a2a.A2aClientAdapter;
import io.github.zzz8688.diagagent.a2a.A2aClientInvocationResult;
import io.github.zzz8688.diagagent.a2a.A2aMessage;
import io.github.zzz8688.diagagent.a2a.A2aPayload;
import io.github.zzz8688.diagagent.a2a.A2aSendMessageParams;
import io.github.zzz8688.diagagent.config.A2aProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class CriticA2aClient {

    private final A2aClientAdapter a2aClientAdapter;
    private final CriticA2aAgentLocator criticA2aAgentLocator;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return criticA2aAgentLocator.configuredRemote()
                .filter(A2aProperties.RemoteAgentProperties::isEnabled)
                .isPresent();
    }

    public FinalAnswerCriticService.CriticResult critique(CriticReviewRequest request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("critic review request must not be null");
        }
        String agentId = criticA2aAgentLocator.configuredAgentId()
                .orElseThrow(() -> new IllegalStateException("未配置 critic A2A agentId"));
        String sessionId = request.promptContext() == null ? "" : safe(request.promptContext().sessionId());
        String taskId = request.promptContext() == null ? "" : safe(request.promptContext().taskId());
        log.info("critic A2A review start: agentId={}, sessionId={}, taskId={}, question={}",
                agentId, sessionId, taskId, abbreviate(request.userQuestion()));
        String payload = objectMapper.writeValueAsString(request);
        A2aSendMessageParams.Message message = new A2aSendMessageParams.Message(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                null,
                A2aSendMessageParams.Role.ROLE_USER,
                List.of(new A2aSendMessageParams.TextPart(payload, Map.of("schema", "critic-review-request"))),
                Map.of("source", "final-answer-critic"),
                null,
                List.of()
        );
        A2aClientInvocationResult result = a2aClientAdapter.sendMessage(
                agentId,
                new A2aSendMessageParams(
                        message,
                        new A2aSendMessageParams.Configuration(List.of("text"), 0, null, false),
                        Map.of("source", "final-answer-critic")
                )
        );
        String responseBody = extractResponseBody(result.payload());
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("critic A2A server 返回空结果");
        }
        FinalAnswerCriticService.CriticResult criticResult =
                objectMapper.readValue(responseBody, FinalAnswerCriticService.CriticResult.class);
        log.info("critic A2A review success: agentId={}, sessionId={}, taskId={}, decision={}, grounded={}, missingEvidence={}, blockingMissing={}",
                agentId,
                sessionId,
                taskId,
                safe(criticResult.decision()),
                criticResult.grounded(),
                criticResult.missingEvidence() == null ? 0 : criticResult.missingEvidence().size(),
                criticResult.blockingMissingEvidence() == null ? 0 : criticResult.blockingMissingEvidence().size());
        return criticResult;
    }

    private String extractResponseBody(A2aPayload payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof A2aMessage message) {
            return message.parts().stream()
                    .filter(A2aSendMessageParams.TextPart.class::isInstance)
                    .map(A2aSendMessageParams.TextPart.class::cast)
                    .map(A2aSendMessageParams.TextPart::text)
                    .findFirst()
                    .orElse("");
        }
        return payload.toString();
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

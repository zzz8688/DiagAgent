package io.github.zzz8688.diagagent.agent.runtime.compat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.zzz8688.diagagent.agent.runtime.TranscriptMessage;
import io.github.zzz8688.diagagent.agent.runtime.TranscriptMessageRole;
import io.github.zzz8688.diagagent.agent.runtime.TranscriptToolCall;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class Langchain4jTranscriptMessageMapper {

    public List<TranscriptMessage> toTranscriptMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(this::toTranscriptMessage)
                .toList();
    }

    public List<ChatMessage> toFrameworkMessages(List<TranscriptMessage> transcriptMessages) {
        if (transcriptMessages == null || transcriptMessages.isEmpty()) {
            return List.of();
        }
        return transcriptMessages.stream()
                .map(this::toFrameworkMessage)
                .toList();
    }

    private TranscriptMessage toTranscriptMessage(ChatMessage message) {
        if (message == null) {
            return emptyTranscript();
        }
        if (message instanceof SystemMessage systemMessage) {
            return new TranscriptMessage(
                    TranscriptMessageRole.SYSTEM,
                    safeText(systemMessage.text()),
                    message.getClass().getSimpleName(),
                    "",
                    "",
                    List.of(),
                    "",
                    Map.of()
            );
        }
        if (message instanceof UserMessage userMessage) {
            return new TranscriptMessage(
                    TranscriptMessageRole.USER,
                    safeText(userMessage.singleText()),
                    message.getClass().getSimpleName(),
                    "",
                    "",
                    List.of(),
                    "",
                    Map.of()
            );
        }
        if (message instanceof AiMessage aiMessage) {
            List<ToolExecutionRequest> toolExecutionRequests = aiMessage.toolExecutionRequests();
            return new TranscriptMessage(
                    TranscriptMessageRole.ASSISTANT,
                    safeText(aiMessage.text()),
                    message.getClass().getSimpleName(),
                    "",
                    "",
                    toTranscriptToolCalls(toolExecutionRequests),
                    "",
                    buildAssistantMetadata(toolExecutionRequests)
            );
        }
        if (message instanceof ToolExecutionResultMessage toolMessage) {
            return new TranscriptMessage(
                    TranscriptMessageRole.TOOL,
                    safeText(toolMessage.text()),
                    message.getClass().getSimpleName(),
                    safeText(toolMessage.toolName()),
                    safeText(toolMessage.id()),
                    List.of(),
                    "",
                    Map.of()
            );
        }
        return new TranscriptMessage(
                TranscriptMessageRole.UNKNOWN,
                safeText(message.toString()),
                message.getClass().getSimpleName(),
                "",
                "",
                List.of(),
                "",
                Map.of()
        );
    }

    private ChatMessage toFrameworkMessage(TranscriptMessage transcriptMessage) {
        if (transcriptMessage == null) {
            return UserMessage.from("");
        }
        if (!transcriptMessage.frameworkMessageJson().isBlank()) {
            return ChatMessageDeserializer.messageFromJson(transcriptMessage.frameworkMessageJson());
        }
        return switch (transcriptMessage.role()) {
            case SYSTEM -> SystemMessage.from(transcriptMessage.content());
            case USER -> UserMessage.from(transcriptMessage.content());
            case ASSISTANT -> toAssistantMessage(transcriptMessage);
            case TOOL -> new ToolExecutionResultMessage(
                    transcriptMessage.toolCallId(),
                    transcriptMessage.toolName(),
                    transcriptMessage.content()
            );
            case UNKNOWN -> UserMessage.from(transcriptMessage.content());
        };
    }

    private AiMessage toAssistantMessage(TranscriptMessage transcriptMessage) {
        List<ToolExecutionRequest> toolRequests = transcriptMessage.toolCalls().stream()
                .map(this::toToolExecutionRequest)
                .toList();
        if (toolRequests.isEmpty()) {
            return AiMessage.from(transcriptMessage.content());
        }
        return AiMessage.from(transcriptMessage.content(), toolRequests);
    }

    private List<TranscriptToolCall> toTranscriptToolCalls(List<ToolExecutionRequest> toolExecutionRequests) {
        if (toolExecutionRequests == null || toolExecutionRequests.isEmpty()) {
            return List.of();
        }
        return toolExecutionRequests.stream()
                .map(request -> new TranscriptToolCall(
                        safeText(request.id()),
                        safeText(request.name()),
                        safeText(request.arguments())
                ))
                .toList();
    }

    private ToolExecutionRequest toToolExecutionRequest(TranscriptToolCall toolCall) {
        return ToolExecutionRequest.builder()
                .id(toolCall == null ? "" : safeText(toolCall.id()))
                .name(toolCall == null ? "" : safeText(toolCall.name()))
                .arguments(toolCall == null ? "" : safeText(toolCall.arguments()))
                .build();
    }

    private Map<String, String> buildAssistantMetadata(List<ToolExecutionRequest> toolExecutionRequests) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("hasToolExecutionRequests", Boolean.toString(toolExecutionRequests != null && !toolExecutionRequests.isEmpty()));
        return metadata;
    }

    private TranscriptMessage emptyTranscript() {
        return new TranscriptMessage(
                TranscriptMessageRole.UNKNOWN,
                "",
                "",
                "",
                "",
                List.of(),
                "",
                Map.of()
        );
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class Langchain4jTranscriptCodec implements TranscriptCodec {

    private static final String CODEC_ID = "platform-transcript-v1";

    private final ObjectMapper objectMapper;

    @Override
    public String codecId() {
        return CODEC_ID;
    }

    @Override
    public String encode(List<TranscriptMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages == null ? List.<TranscriptMessage>of() : messages);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode transcript with codec " + CODEC_ID, e);
        }
    }

    @Override
    public List<TranscriptMessage> decode(String transcriptJson) {
        if (transcriptJson == null || transcriptJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    transcriptJson,
                    new TypeReference<List<TranscriptMessage>>() {
                    }
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode transcript with codec " + CODEC_ID, e);
        }
    }
}

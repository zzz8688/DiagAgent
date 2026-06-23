package io.github.zzz8688.diagagent.agent.runtime;

import java.util.List;

public interface TranscriptCodec {

    String codecId();

    String encode(List<TranscriptMessage> messages);

    List<TranscriptMessage> decode(String transcriptJson);
}

package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTaskStatus(
        A2aTaskState state,
        A2aMessage message,
        OffsetDateTime timestamp
) {

    public A2aTaskStatus {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        timestamp = timestamp == null ? OffsetDateTime.now(ZoneOffset.UTC) : timestamp;
    }

    public A2aTaskStatus(A2aTaskState state) {
        this(state, null, OffsetDateTime.now(ZoneOffset.UTC));
    }
}

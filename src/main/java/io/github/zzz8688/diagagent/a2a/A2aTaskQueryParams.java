package io.github.zzz8688.diagagent.a2a;

public record A2aTaskQueryParams(
        String id,
        Integer historyLength,
        String tenant
) {

    public A2aTaskQueryParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (historyLength != null && historyLength < 0) {
            throw new IllegalArgumentException("historyLength must be >= 0");
        }
    }

    public A2aTaskQueryParams(String id, Integer historyLength) {
        this(id, historyLength, null);
    }
}

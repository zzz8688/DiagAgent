package io.github.zzz8688.diagagent.a2a;

public record A2aTaskIdParams(
        String id,
        String tenant
) {

    public A2aTaskIdParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public A2aTaskIdParams(String id) {
        this(id, null);
    }
}

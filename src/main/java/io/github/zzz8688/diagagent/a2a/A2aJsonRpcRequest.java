package io.github.zzz8688.diagagent.a2a;

import java.util.UUID;

public record A2aJsonRpcRequest<T>(
        String jsonrpc,
        String id,
        String method,
        T params
) {

    public static <T> A2aJsonRpcRequest<T> create(String method, T params) {
        return new A2aJsonRpcRequest<>("2.0", UUID.randomUUID().toString(), method, params);
    }
}

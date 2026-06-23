package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.databind.JsonNode;

public record A2aJsonRpcResponse(
        String jsonrpc,
        String id,
        JsonNode result,
        JsonRpcError error
) {

    public boolean success() {
        return error == null;
    }

    public JsonNode requireResult() {
        if (!success()) {
            throw new IllegalStateException("A2A JSON-RPC 调用失败: code="
                    + error.code() + ", message=" + error.message());
        }
        return result;
    }

    public record JsonRpcError(
            int code,
            String message,
            JsonNode data
    ) {
    }
}

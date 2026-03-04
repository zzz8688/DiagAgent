package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TraceQueryTool {

    @Tool("查询特定 Trace ID 的分布式链路追踪，查看每个跨度的时间线和错误。")
    public String queryTrace(String traceId) {
        log.info("Agent 正在查询链路: {}", traceId);

        if (traceId.contains("fc98c619ccaf4266")) {
            return """
                   Trace ID: %s (Internal Dependency Error)
                   --------------------------------------------------
                   1. frontend       | START | 0ms
                   2. order-service  | START | 50ms (Called by frontend)
                   3. product-service| START | 100ms (Called by order-service)
                   4. inventory-db   | ERROR | 2100ms (Connection timeout)
                   5. product-service| ERROR | 2100ms (Failed to connect to inventory-db)
                   6. order-service  | ERROR | 2105ms (Internal dependency error from product-service)
                   7. frontend       | ERROR | 2110ms (Order creation failed)
                   --------------------------------------------------
                   Root Cause: Connection timeout between product-service and inventory-db.
                   """.formatted(traceId);
        } else if (traceId.contains("712edc730d4b40fd")) {
            return """
                   Trace ID: %s (Payment Error)
                   --------------------------------------------------
                   1. frontend       | START | 0ms
                   2. order-service  | START | 50ms (Called by frontend)
                   3. payment-service| START | 150ms (Called by order-service)
                   4. payment-gateway| ERROR | 3150ms (Gateway timeout)
                   5. payment-service| ERROR | 3155ms (Payment provider error)
                   6. order-service  | ERROR | 3160ms (Payment failed)
                   7. frontend       | ERROR | 3165ms (Checkout failed)
                   --------------------------------------------------
                   Root Cause: Timeout from the external payment-gateway.
                   """.formatted(traceId);
        } else {
            return """
                   Trace ID: %s (Success)
                   --------------------------------------------------
                   1. frontend       | START | 0ms
                   2. order-service  | START | 50ms
                   3. product-service| START | 100ms
                   4. payment-service| START | 150ms
                   5. payment-gateway| SUCCESS | 550ms
                   6. order-service  | SUCCESS | 600ms
                   7. frontend       | SUCCESS | 650ms
                   --------------------------------------------------
                   Status: Success
                   """.formatted(traceId);
        }
    }
}

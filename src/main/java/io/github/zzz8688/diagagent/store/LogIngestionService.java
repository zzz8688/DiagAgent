package io.github.zzz8688.diagagent.store;

import io.github.zzz8688.diagagent.parser.LogEntry;
import io.github.zzz8688.diagagent.parser.LogParserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class LogIngestionService {

    private final LogParserService logParserService;
    private final LogVectorStoreService logVectorStoreService;

    @PostConstruct
    public void init() {
        ingestMockLogs();
    }

    private void ingestMockLogs() {
        log.info("========== [日志写入] 开始写入Mock日志到向量存储 ==========");

        List<String> mockLogs = List.of(
            "2026-02-20 10:00:01.001 INFO [frontend,a1b2c3d4e5f6g7h8] Received request from user_123 for /checkout",
            "2026-02-20 10:00:02.002 ERROR [order-service,a1b2c3d4e5f6g7h8] Checkout failed - Payment declined",

            "2026-02-20 10:00:03.003 INFO [frontend,i9j0k1l2m3n4o5p6] Received request from user_456 for /checkout",
            "2026-02-20 10:00:04.004 ERROR [payment-service,i9j0k1l2m3n4o5p6] Payment gateway 503 Service Unavailable",
            "2026-02-20 10:00:05.005 ERROR [order-service,i9j0k1l2m3n4o5p6] Checkout failed - Payment service unavailable",

            "2026-02-20 10:00:06.006 INFO [frontend,q7r8s9t0u1v2w3x4] Received request from user_789 for /checkout",
            "2026-02-20 10:00:07.007 ERROR [payment-service,q7r8s9t0u1v2w3x4] Payment process failed - Gateway timeout",
            "2026-02-20 10:00:08.008 ERROR [order-service,q7r8s9t0u1v2w3x4] Checkout failed - Payment timeout",

            "2026-02-20 14:00:01.001 INFO [frontend,y5z6a7b8c9d0e1f2] Received request from user_111 for /product/detail",
            "2026-02-20 14:00:02.002 ERROR [product-service,y5z6a7b8c9d0e1f2] Failed to query inventory - Connection timeout",
            "2026-02-20 14:00:03.003 ERROR [product-service,y5z6a7b8c9d0e1f2] Product service error - database connection failed",

            "2026-02-20 14:00:04.004 INFO [frontend,g3h4i5j6k7l8m9n0] Received request from user_222 for /product/list",
            "2026-02-20 14:00:05.005 ERROR [product-service,g3h4i5j6k7l8m9n0] Database query timeout - inventory-db not responding",
            "2026-02-20 14:00:06.006 WARN [product-service,g3h4i5j6k7l8m9n0] Slow query detected - product search took 5s"
        );

        int successCount = 0;
        int failCount = 0;
        
        for (int i = 0; i < mockLogs.size(); i++) {
            String line = mockLogs.get(i);
            try {
                log.info("[日志写入] 处理第 {}/{} 条日志...", i + 1, mockLogs.size());
                LogEntry entry = logParserService.parse(line);
                if (entry != null) {
                    log.info("[日志写入] 解析成功: service={}, template={}", 
                             entry.getServiceName(), 
                             entry.getTemplate() != null ? entry.getTemplate().substring(0, Math.min(40, entry.getTemplate().length())) : "null");
                    logVectorStoreService.ingest(entry);
                    successCount++;
                } else {
                    log.warn("[日志写入] 解析返回null: {}", line);
                    failCount++;
                }
            } catch (Exception e) {
                log.error("[日志写入] 处理失败: {}, 错误: {}", line, e.getMessage());
                failCount++;
            }
        }
        log.info("========== [日志写入] 完成! 成功: {}, 失败: {}, 总计: {} ==========", successCount, failCount, mockLogs.size());
    }
}

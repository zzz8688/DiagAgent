package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.mcp.DingtalkMcpClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DingtalkMcpClient dingtalkMcpClient;
    private final Set<String> sentNotificationKeys = ConcurrentHashMap.newKeySet();

    public String sendNotification(String message) {
        return sendNotification("智能体诊断置信度低 - 需要人工介入", message, "warning");
    }

    public String sendNotification(String title, String message, String level) {
        log.info("发送通知: {} - {}", title, message);
        System.out.println("【通知】" + message);

        try {
            String response = dingtalkMcpClient.sendWorkNotice(title, message, level);
            if (isSuccessfulDelivery(response)) {
                log.info("通知发送结果: {}", response);
            } else {
                log.warn("通知发送结果: {}", response);
            }
            return response;
        } catch (Exception e) {
            log.error("DingTalk MCP 通知发送异常", e);
            return "DingTalk MCP 通知发送异常: " + e.getMessage();
        }
    }

    public String sendNotificationOnce(String dedupeKey, String title, String message, String level) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return sendNotification(title, message, level);
        }
        if (!sentNotificationKeys.add(dedupeKey)) {
            log.info("重复通知已跳过: key={}", dedupeKey);
            return "notification skipped: duplicate key";
        }
        String response = sendNotification(title, message, level);
        if (!isSuccessfulDelivery(response)) {
            sentNotificationKeys.remove(dedupeKey);
        }
        return response;
    }

    private boolean isSuccessfulDelivery(String response) {
        return response != null
                && (response.contains("工作通知任务创建成功") || response.contains("通知发送成功"));
    }
}

package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;

    @PostMapping("/send")
    public Map<String, Object> sendNotification(@RequestBody NotificationRequest request) {
        log.info("收到发送通知请求: {}", request);
        try {
            String response = notificationService.sendNotification(
                request.getTitle(), 
                request.getMessage(), 
                request.getLevel()
            );
            return Map.of(
                "success", true,
                "message", "通知已发送",
                "response", response
            );
        } catch (Exception e) {
            log.error("发送通知失败", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }

    public static class NotificationRequest {
        private String title;
        private String message;
        private String level;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }
    }
}

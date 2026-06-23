package io.github.zzz8688.diagagent.mcp.server;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DiagAgentMcpNotificationCenter {

    private final Set<String> subscribedResources = ConcurrentHashMap.newKeySet();

    public void subscribeResource(String uri) {
        if (uri != null && !uri.isBlank()) {
            subscribedResources.add(uri.trim());
        }
    }

    public void unsubscribeResource(String uri) {
        if (uri != null && !uri.isBlank()) {
            subscribedResources.remove(uri.trim());
        }
    }

    public List<McpProtocolDtos.Notification> initializedNotifications() {
        return List.of(
                new McpProtocolDtos.Notification("notifications/tools/list_changed", null),
                new McpProtocolDtos.Notification("notifications/resources/list_changed", null),
                new McpProtocolDtos.Notification("notifications/prompts/list_changed", null)
        );
    }

    public List<McpProtocolDtos.Notification> resourceSubscribedNotifications(String uri) {
        if (uri == null || uri.isBlank()) {
            return List.of();
        }
        return List.of(new McpProtocolDtos.Notification(
                "notifications/resources/updated",
                java.util.Map.of("uri", uri.trim())
        ));
    }

    public boolean isSubscribed(String uri) {
        return uri != null && subscribedResources.contains(uri.trim());
    }
}

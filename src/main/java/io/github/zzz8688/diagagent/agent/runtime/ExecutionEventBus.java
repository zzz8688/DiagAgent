package io.github.zzz8688.diagagent.agent.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ExecutionEventBus {

    private final AtomicLong cursorSequence = new AtomicLong(0);
    private final ConcurrentMap<String, CopyOnWriteArrayList<ExecutionEvent>> eventsByTaskId = new ConcurrentHashMap<>();

    public ExecutionEvent publish(ExecutionEventType eventType,
                                  String taskId,
                                  String sessionId,
                                  String source,
                                  Map<String, String> payload) {
        String safeTaskId = safe(taskId);
        ExecutionEvent event = new ExecutionEvent(
                cursorSequence.incrementAndGet(),
                UUID.randomUUID().toString(),
                eventType,
                safeTaskId,
                safe(sessionId),
                safe(source),
                payload,
                Instant.now()
        );
        eventsByTaskId.computeIfAbsent(safeTaskId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        return event;
    }

    public List<ExecutionEvent> replay(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        return List.copyOf(eventsByTaskId.getOrDefault(taskId, new CopyOnWriteArrayList<>()));
    }

    public List<ExecutionEvent> replayFromCursor(String taskId, long cursor) {
        List<ExecutionEvent> events = replay(taskId);
        if (events.isEmpty()) {
            return List.of();
        }
        List<ExecutionEvent> filtered = new ArrayList<>();
        for (ExecutionEvent event : events) {
            if (event.cursor() > cursor) {
                filtered.add(event);
            }
        }
        return List.copyOf(filtered);
    }

    public long latestCursor(String taskId) {
        List<ExecutionEvent> events = replay(taskId);
        return events.isEmpty() ? 0 : events.get(events.size() - 1).cursor();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

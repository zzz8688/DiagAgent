package io.github.zzz8688.diagagent.a2a;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class A2aTaskEventBridge {

    private static final int MAX_EVENTS_PER_TASK = 200;
    private static final int MAX_RECENT_EVENTS = 500;

    private final AtomicLong sequence = new AtomicLong(0L);
    private final Map<String, CopyOnWriteArrayList<A2aTaskEvent>> eventsByTask = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<A2aTaskEvent> recentEvents = new CopyOnWriteArrayList<>();
    private final Map<String, Sinks.Many<A2aTaskEvent>> sinksByTask = new ConcurrentHashMap<>();

    public A2aTaskEvent publishInvocation(A2aClientInvocationResult result) {
        if (result == null) {
            return null;
        }
        return publish(
                result.agentId(),
                result.method(),
                result.payload()
        );
    }

    public A2aTaskEvent publishStreamingEvent(String agentId,
                                              String sourceMethod,
                                              A2aPayload event) {
        return publish(agentId, sourceMethod, event);
    }

    public List<A2aTaskEvent> listTaskEvents(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        return List.copyOf(eventsByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>()));
    }

    public List<A2aTaskEvent> listTaskEventsAfter(String taskId, long sequenceExclusive) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        return eventsByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>()).stream()
                .filter(event -> event.sequence() > sequenceExclusive)
                .toList();
    }

    public List<A2aTaskEvent> recentEvents(int limit) {
        int safeLimit = Math.max(1, limit);
        int size = recentEvents.size();
        int fromIndex = Math.max(0, size - safeLimit);
        return List.copyOf(recentEvents.subList(fromIndex, size));
    }

    public Flux<A2aTaskEvent> streamTaskEvents(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Flux.empty();
        }
        Flux<A2aTaskEvent> history = Flux.fromIterable(listTaskEvents(taskId));
        Sinks.Many<A2aTaskEvent> sink = sinksByTask.computeIfAbsent(taskId, ignored -> Sinks.many().multicast().onBackpressureBuffer());
        return history.concatWith(sink.asFlux());
    }

    public Flux<A2aTaskEvent> liveTaskEvents(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Flux.empty();
        }
        Sinks.Many<A2aTaskEvent> sink = sinksByTask.computeIfAbsent(taskId, ignored -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    private A2aTaskEvent publish(String agentId,
                                 String sourceMethod,
                                 A2aPayload event) {
        A2aTaskEvent taskEvent = new A2aTaskEvent(
                sequence.incrementAndGet(),
                emptyToNull(agentId),
                emptyToNull(sourceMethod),
                event,
                Instant.now().toString()
        );
        if (taskEvent.taskId() != null) {
            CopyOnWriteArrayList<A2aTaskEvent> events = eventsByTask.computeIfAbsent(taskEvent.taskId(), ignored -> new CopyOnWriteArrayList<>());
            events.add(taskEvent);
            trimTaskEvents(events);
            sinksByTask.computeIfAbsent(taskEvent.taskId(), ignored -> Sinks.many().multicast().onBackpressureBuffer())
                    .tryEmitNext(taskEvent);
        }
        recentEvents.add(taskEvent);
        trimRecentEvents();
        return taskEvent;
    }

    private void trimTaskEvents(CopyOnWriteArrayList<A2aTaskEvent> events) {
        while (events.size() > MAX_EVENTS_PER_TASK) {
            events.remove(0);
        }
    }

    private void trimRecentEvents() {
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.remove(0);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

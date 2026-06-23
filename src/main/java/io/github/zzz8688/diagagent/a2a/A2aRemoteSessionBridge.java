package io.github.zzz8688.diagagent.a2a;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class A2aRemoteSessionBridge {

    private final A2aTaskEventBridge a2aTaskEventBridge;
    private final Map<String, BridgeState> bridgeStates = new ConcurrentHashMap<>();

    public A2aRemoteSessionBridge(A2aTaskEventBridge a2aTaskEventBridge) {
        this.a2aTaskEventBridge = a2aTaskEventBridge;
    }

    public RemoteSessionSnapshot openSession(String agentId, String taskId, String contextId) {
        BridgeState state = bridgeStates.compute(taskId, (ignored, existing) -> {
            BridgeState next = existing == null ? new BridgeState(taskId) : existing;
            next.agentId = normalize(agentId);
            next.contextId = normalize(contextId);
            next.bridgeStatus = "streaming";
            next.reconnectSnapshotAt = Instant.now().toString();
            return next;
        });
        return snapshotOf(state);
    }

    public RemoteSessionSnapshot markInterrupted(String taskId, String interruptReason) {
        BridgeState state = requireState(taskId);
        state.pendingInterrupt = true;
        state.interruptReason = normalize(interruptReason);
        state.bridgeStatus = "interrupt-requested";
        state.reconnectSnapshotAt = Instant.now().toString();
        return snapshotOf(state);
    }

    public RemoteSessionSnapshot queueUserEvent(String taskId, Map<String, Object> eventPayload) {
        BridgeState state = requireState(taskId);
        state.queuedUserEvents.add(eventPayload == null ? Map.of() : Map.copyOf(eventPayload));
        state.bridgeStatus = "queued";
        return snapshotOf(state);
    }

    public RemoteSessionSnapshot acknowledgeDelivered(String taskId, long deliveredSequence) {
        BridgeState state = requireState(taskId);
        state.lastDeliveredSequence = Math.max(state.lastDeliveredSequence, deliveredSequence);
        state.bridgeStatus = "streaming";
        return snapshotOf(state);
    }

    public RemoteSessionSnapshot markReconnect(String taskId) {
        BridgeState state = requireState(taskId);
        state.bridgeStatus = "reconnecting";
        state.reconnectSnapshotAt = Instant.now().toString();
        return snapshotOf(state);
    }

    public List<A2aTaskEvent> replayHistory(String taskId) {
        BridgeState state = requireState(taskId);
        return a2aTaskEventBridge.listTaskEventsAfter(taskId, state.lastDeliveredSequence);
    }

    public Flux<A2aTaskEvent> streamWithHistory(String taskId) {
        BridgeState state = requireState(taskId);
        List<A2aTaskEvent> history = a2aTaskEventBridge.listTaskEventsAfter(taskId, state.lastDeliveredSequence);
        Flux<A2aTaskEvent> live = a2aTaskEventBridge.liveTaskEvents(taskId)
                .filter(event -> event.sequence() > state.lastDeliveredSequence);
        return Flux.fromIterable(history)
                .concatWith(live)
                .doOnNext(event -> acknowledgeDelivered(taskId, event.sequence()));
    }

    public RemoteSessionSnapshot snapshot(String taskId) {
        return snapshotOf(requireState(taskId));
    }

    private BridgeState requireState(String taskId) {
        String safeTaskId = normalize(taskId);
        if (safeTaskId == null) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        return bridgeStates.computeIfAbsent(safeTaskId, BridgeState::new);
    }

    private RemoteSessionSnapshot snapshotOf(BridgeState state) {
        return new RemoteSessionSnapshot(
                state.agentId,
                state.taskId,
                state.contextId,
                state.lastDeliveredSequence,
                state.pendingInterrupt,
                state.interruptReason,
                state.queuedUserEvents.size(),
                state.bridgeStatus,
                state.reconnectSnapshotAt
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class BridgeState {
        private final String taskId;
        private volatile String agentId;
        private volatile String contextId;
        private volatile long lastDeliveredSequence;
        private volatile boolean pendingInterrupt;
        private volatile String interruptReason;
        private final Queue<Map<String, Object>> queuedUserEvents = new ConcurrentLinkedQueue<>();
        private volatile String bridgeStatus = "idle";
        private volatile String reconnectSnapshotAt;

        private BridgeState(String taskId) {
            this.taskId = taskId;
        }
    }

    public record RemoteSessionSnapshot(
            String agentId,
            String taskId,
            String contextId,
            long lastDeliveredSequence,
            boolean pendingInterrupt,
            String interruptReason,
            int queuedUserEventCount,
            String bridgeStatus,
            String reconnectSnapshotAt
    ) {
    }
}

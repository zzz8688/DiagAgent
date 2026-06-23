package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.service.RedisRuntimeCoordinationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final ConcurrentMap<String, RuntimeTask> tasksById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestTaskIdBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionLaneState> laneStateBySession = new ConcurrentHashMap<>();
    private final ExecutionEventBus executionEventBus;
    private final RedisRuntimeCoordinationService redisRuntimeCoordinationService;

    public TaskManager(ExecutionEventBus executionEventBus,
                       RedisRuntimeCoordinationService redisRuntimeCoordinationService) {
        this.executionEventBus = executionEventBus;
        this.redisRuntimeCoordinationService = redisRuntimeCoordinationService;
    }

    public RuntimeTask startTask(RuntimeRequest request, String requestMode) {
        Instant now = Instant.now();
        String sessionId = normalize(request.sessionId());
        if (sessionId == null) {
            sessionId = "runtime-session-" + System.currentTimeMillis();
        }
        RuntimeTask task = new RuntimeTask(
                UUID.randomUUID().toString(),
                sessionId,
                RuntimeTaskStatus.RUNNING,
                buildDescription(request.userQuestion()),
                normalize(requestMode),
                "query-runtime",
                "langchain4j",
                null,
                null,
                now,
                now,
                null
        );
        String finalSessionId = sessionId;
        tasksById.put(task.taskId(), task);
        latestTaskIdBySession.put(sessionId, task.taskId());
        laneStateBySession.computeIfAbsent(finalSessionId, ignored -> SessionLaneState.empty(finalSessionId));
        executionEventBus.publish(
                ExecutionEventType.TASK_STARTED,
                task.taskId(),
                task.sessionId(),
                "task-manager",
                Map.of(
                        "requestMode", safe(task.requestMode()),
                        "description", safe(task.description())
                )
        );
        return task;
    }

    public RuntimeTask markCompleted(String taskId, String outputSummary) {
        return updateLifecycle(taskId, RuntimeTaskStatus.COMPLETED, "completed", null, outputSummary, ExecutionEventType.TASK_COMPLETED);
    }

    public RuntimeTask markFailed(String taskId, String errorMessage) {
        return updateLifecycle(taskId, RuntimeTaskStatus.FAILED, "failed", errorMessage, null, ExecutionEventType.TASK_FAILED);
    }

    public RuntimeTask markCancelled(String taskId, String summary) {
        return updateLifecycle(taskId, RuntimeTaskStatus.CANCELLED, "cancelled", null, summary, ExecutionEventType.TASK_CANCELLED);
    }

    public Optional<RuntimeTask> findTask(String taskId) {
        return Optional.ofNullable(tasksById.get(taskId));
    }

    public Optional<RuntimeTask> findLatestBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String taskId = latestTaskIdBySession.get(sessionId);
        if (taskId == null) {
            return Optional.empty();
        }
        return findTask(taskId);
    }

    public Optional<RuntimeTask> cancelLatestBySessionId(String sessionId, String summary) {
        return findLatestBySessionId(sessionId)
                .map(task -> markCancelled(task.taskId(), summary));
    }

    public List<RuntimeTask> listRecentTasks(int limit) {
        int safeLimit = limit <= 0 ? 20 : limit;
        return tasksById.values().stream()
                .sorted(Comparator
                        .comparing(RuntimeTask::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RuntimeTask::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();
    }

    public Map<String, Long> countTasksByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(RuntimeTaskStatus.RUNNING.name(), 0L);
        counts.put(RuntimeTaskStatus.COMPLETED.name(), 0L);
        counts.put(RuntimeTaskStatus.FAILED.name(), 0L);
        counts.put(RuntimeTaskStatus.CANCELLED.name(), 0L);
        counts.put(RuntimeTaskStatus.PENDING.name(), 0L);
        for (RuntimeTask task : tasksById.values()) {
            if (task == null || task.status() == null) {
                continue;
            }
            String status = task.status().name();
            counts.put(status, counts.getOrDefault(status, 0L) + 1);
        }
        return Map.copyOf(counts);
    }

    public Map<String, Long> countLaneEntries() {
        long followups = 0L;
        long steers = 0L;
        long interrupts = 0L;
        for (SessionLaneState laneState : laneStateBySession.values()) {
            if (laneState == null) {
                continue;
            }
            followups += laneState.followups().size();
            steers += laneState.steers().size();
            interrupts += laneState.interrupts().size();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("FOLLOWUP", followups);
        counts.put("STEER", steers);
        counts.put("INTERRUPT", interrupts);
        return Map.copyOf(counts);
    }

    public List<ExecutionEvent> replay(String taskId) {
        return executionEventBus.replay(taskId);
    }

    public List<ExecutionEvent> replayFromCursor(String taskId, long cursor) {
        return executionEventBus.replayFromCursor(taskId, cursor);
    }

    public long latestCursor(String taskId) {
        return executionEventBus.latestCursor(taskId);
    }

    public List<ExecutionEvent> replayWindow(String taskId, long afterCursor, int maxEvents) {
        List<ExecutionEvent> events = afterCursor > 0 ? replayFromCursor(taskId, afterCursor) : replay(taskId);
        if (events.isEmpty()) {
            return List.of();
        }
        int limit = maxEvents <= 0 ? events.size() : maxEvents;
        if (events.size() <= limit) {
            return events;
        }
        return List.copyOf(new ArrayList<>(events.subList(events.size() - limit, events.size())));
    }

    public SessionLaneState enqueueFollowup(String sessionId, String taskId, String payloadSummary) {
        return enqueueLaneEntry(sessionId, taskId, SessionLaneType.FOLLOWUP, payloadSummary);
    }

    public SessionLaneState enqueueSteer(String sessionId, String taskId, String payloadSummary) {
        return enqueueLaneEntry(sessionId, taskId, SessionLaneType.STEER, payloadSummary);
    }

    public SessionLaneState requestInterrupt(String sessionId, String taskId, String payloadSummary) {
        return enqueueLaneEntry(sessionId, taskId, SessionLaneType.INTERRUPT, payloadSummary);
    }

    public Optional<SessionLaneState> findLaneState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(laneStateBySession.get(sessionId));
    }

    public Optional<SessionLaneState> clearLaneEntry(String sessionId, String laneEntryId) {
        if (sessionId == null || sessionId.isBlank() || laneEntryId == null || laneEntryId.isBlank()) {
            return Optional.empty();
        }
        SessionLaneState updated = laneStateBySession.computeIfPresent(sessionId, (ignored, current) -> current.remove(laneEntryId));
        if (updated != null) {
            Optional<RuntimeTask> latestTask = findLatestBySessionId(sessionId);
            executionEventBus.publish(
                    ExecutionEventType.SESSION_LANE_CLEARED,
                    latestTask.map(RuntimeTask::taskId).orElse(""),
                    sessionId,
                    "task-manager",
                    Map.of(
                            "laneEntryId", safe(laneEntryId),
                            "message", "lane entry cleared"
                    )
            );
        }
        return Optional.ofNullable(updated);
    }

    public List<SessionLaneEntry> consumeLaneEntries(String sessionId, SessionLaneType laneType) {
        String normalizedSessionId = normalize(sessionId);
        if (normalizedSessionId == null || laneType == null) {
            return List.of();
        }
        List<SessionLaneEntry> consumedEntries = new ArrayList<>();
        laneStateBySession.computeIfPresent(normalizedSessionId, (ignored, current) -> {
            LaneConsumption laneConsumption = current.consume(laneType);
            consumedEntries.addAll(laneConsumption.consumedEntries());
            return laneConsumption.updatedState();
        });
        if (!consumedEntries.isEmpty()) {
            String taskId = latestTaskIdBySession.getOrDefault(normalizedSessionId, "");
            for (SessionLaneEntry entry : consumedEntries) {
                executionEventBus.publish(
                        ExecutionEventType.SESSION_LANE_CONSUMED,
                        entry == null || entry.taskId() == null || entry.taskId().isBlank() ? taskId : entry.taskId(),
                        normalizedSessionId,
                        "task-manager",
                        Map.of(
                                "laneEntryId", safe(entry == null ? null : entry.entryId()),
                                "laneType", entry == null || entry.laneType() == null ? "" : entry.laneType().name(),
                                "taskId", safe(entry == null ? null : entry.taskId()),
                                "payloadSummary", safe(entry == null ? null : entry.payloadSummary())
                        )
                );
            }
        }
        return List.copyOf(consumedEntries);
    }

    private SessionLaneState enqueueLaneEntry(String sessionId,
                                              String taskId,
                                              SessionLaneType laneType,
                                              String payloadSummary) {
        String normalizedSessionId = normalize(sessionId);
        if (normalizedSessionId == null) {
            return null;
        }
        SessionLaneEntry entry = new SessionLaneEntry(
                UUID.randomUUID().toString(),
                laneType == null ? SessionLaneType.FOLLOWUP : laneType,
                normalize(taskId),
                normalize(payloadSummary),
                Instant.now()
        );
        SessionLaneState updated = laneStateBySession.compute(normalizedSessionId, (ignored, current) ->
                (current == null ? SessionLaneState.empty(normalizedSessionId) : current).append(entry));
        executionEventBus.publish(
                ExecutionEventType.SESSION_LANE_ENQUEUED,
                normalize(taskId) == null ? latestTaskIdBySession.getOrDefault(normalizedSessionId, "") : normalize(taskId),
                normalizedSessionId,
                "task-manager",
                Map.of(
                        "laneEntryId", entry.entryId(),
                        "laneType", entry.laneType().name(),
                        "taskId", safe(entry.taskId()),
                        "payloadSummary", safe(entry.payloadSummary())
                )
        );
        return updated;
    }

    private RuntimeTask updateLifecycle(String taskId,
                                        RuntimeTaskStatus status,
                                        String currentNode,
                                        String errorMessage,
                                        String outputSummary,
                                        ExecutionEventType eventType) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        String lockToken = redisRuntimeCoordinationService.tryAcquireTaskLifecycleLock(taskId);
        if (lockToken == null) {
            log.warn("任务生命周期更新被并发锁拒绝: taskId={}, targetStatus={}", taskId, status);
            return tasksById.get(taskId);
        }
        try {
            RuntimeTask updated = tasksById.computeIfPresent(taskId, (ignored, current) ->
                    current.withLifecycle(status, currentNode, errorMessage, outputSummary, Instant.now()));
            if (updated != null) {
                executionEventBus.publish(
                        eventType,
                        updated.taskId(),
                        updated.sessionId(),
                        "task-manager",
                        Map.of(
                                "status", updated.status().name(),
                                "currentNode", safe(updated.currentNode()),
                                "lastError", safe(updated.lastError()),
                                "outputSummary", safe(updated.outputSummary())
                        )
                );
            }
            return updated;
        } finally {
            if (lockToken != null) {
                redisRuntimeCoordinationService.releaseTaskLifecycleLock(taskId, lockToken);
            }
        }
    }

    private String buildDescription(String userQuestion) {
        String text = normalize(userQuestion);
        if (text == null) {
            return "diag runtime task";
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public enum SessionLaneType {
        FOLLOWUP,
        STEER,
        INTERRUPT
    }

    public record SessionLaneEntry(
            String entryId,
            SessionLaneType laneType,
            String taskId,
            String payloadSummary,
            Instant createdAt
    ) {
    }

    public record SessionLaneState(
            String sessionId,
            List<SessionLaneEntry> followups,
            List<SessionLaneEntry> steers,
            List<SessionLaneEntry> interrupts
    ) {
        public static SessionLaneState empty(String sessionId) {
            return new SessionLaneState(sessionId, List.of(), List.of(), List.of());
        }

        public SessionLaneState append(SessionLaneEntry entry) {
            if (entry == null || entry.laneType() == null) {
                return this;
            }
            return switch (entry.laneType()) {
                case FOLLOWUP -> new SessionLaneState(sessionId, appendTo(followups, entry), steers, interrupts);
                case STEER -> new SessionLaneState(sessionId, followups, appendTo(steers, entry), interrupts);
                case INTERRUPT -> new SessionLaneState(sessionId, followups, steers, appendTo(interrupts, entry));
            };
        }

        public SessionLaneState remove(String entryId) {
            return new SessionLaneState(
                    sessionId,
                    removeFrom(followups, entryId),
                    removeFrom(steers, entryId),
                    removeFrom(interrupts, entryId)
            );
        }

        public LaneConsumption consume(SessionLaneType laneType) {
            if (laneType == null) {
                return new LaneConsumption(this, List.of());
            }
            return switch (laneType) {
                case FOLLOWUP -> new LaneConsumption(
                        new SessionLaneState(sessionId, List.of(), steers, interrupts),
                        followups == null ? List.of() : List.copyOf(followups)
                );
                case STEER -> new LaneConsumption(
                        new SessionLaneState(sessionId, followups, List.of(), interrupts),
                        steers == null ? List.of() : List.copyOf(steers)
                );
                case INTERRUPT -> new LaneConsumption(
                        new SessionLaneState(sessionId, followups, steers, List.of()),
                        interrupts == null ? List.of() : List.copyOf(interrupts)
                );
            };
        }

        private static List<SessionLaneEntry> appendTo(List<SessionLaneEntry> entries, SessionLaneEntry entry) {
            List<SessionLaneEntry> updated = new ArrayList<>(entries == null ? List.of() : entries);
            updated.add(entry);
            updated.sort(Comparator.comparing(SessionLaneEntry::createdAt));
            return List.copyOf(updated);
        }

        private static List<SessionLaneEntry> removeFrom(List<SessionLaneEntry> entries, String entryId) {
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            return entries.stream()
                    .filter(entry -> entry != null && !safeEquals(entry.entryId(), entryId))
                    .toList();
        }

        private static boolean safeEquals(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }
    }

    public record LaneConsumption(
            SessionLaneState updatedState,
            List<SessionLaneEntry> consumedEntries
    ) {
    }
}

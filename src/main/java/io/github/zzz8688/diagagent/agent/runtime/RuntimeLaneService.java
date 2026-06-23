package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuntimeLaneService {

    private final TaskManager taskManager;
    private final RuntimeFacade runtimeFacade;

    public TaskManager.SessionLaneState enqueue(String sessionId,
                                                String taskId,
                                                TaskManager.SessionLaneType laneType,
                                                String payloadSummary) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        TaskManager.SessionLaneType safeType = laneType == null ? TaskManager.SessionLaneType.FOLLOWUP : laneType;
        return switch (safeType) {
            case FOLLOWUP -> taskManager.enqueueFollowup(sessionId, taskId, payloadSummary);
            case STEER -> taskManager.enqueueSteer(sessionId, taskId, payloadSummary);
            case INTERRUPT -> {
                TaskManager.SessionLaneState state = taskManager.requestInterrupt(sessionId, taskId, payloadSummary);
                runtimeFacade.cancel(sessionId);
                yield state;
            }
        };
    }

    public TaskManager.SessionLaneState clear(String sessionId, String laneEntryId) {
        return taskManager.clearLaneEntry(sessionId, laneEntryId).orElse(null);
    }
}

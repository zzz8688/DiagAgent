package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ReplayPromptProjectionLoader {

    private static final int DEFAULT_REPLAY_WINDOW_SIZE = 40;

    private final TaskManager taskManager;
    private final EventReplayProjector eventReplayProjector;

    public SessionAssembler.ReplayPromptProjection load(ReactTurnState turnState) {
        if (turnState == null || turnState.taskId() == null || turnState.taskId().isBlank()) {
            return emptyProjection();
        }
        List<ExecutionEvent> events = taskManager.replayWindow(
                turnState.taskId(),
                0,
                DEFAULT_REPLAY_WINDOW_SIZE
        );
        if (events.isEmpty()) {
            return emptyProjection();
        }
        EventReplayProjector.EventReplayProjection projection = eventReplayProjector.project(events);
        return new SessionAssembler.ReplayPromptProjection(
                safe(projection.renderRuntimePromptSection()),
                buildEventReplaySummary(projection),
                safe(projection.renderApprovalPromptSection())
        );
    }

    private SessionAssembler.ReplayPromptProjection emptyProjection() {
        return new SessionAssembler.ReplayPromptProjection("", "", "");
    }

    private String buildEventReplaySummary(EventReplayProjector.EventReplayProjection projection) {
        if (projection == null) {
            return "";
        }
        String eventSection = safe(projection.renderEventPromptSection());
        String logSection = safe(projection.renderLogPromptSection());
        if (eventSection.isBlank()) {
            return logSection;
        }
        if (logSection.isBlank()) {
            return eventSection;
        }
        return """
                <action_event_replay>
                %s
                </action_event_replay>

                <log_event_replay>
                %s
                </log_event_replay>
                """.formatted(eventSection, logSection).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

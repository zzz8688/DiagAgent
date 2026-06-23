package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.agent.runtime.RuntimeLaneService;
import io.github.zzz8688.diagagent.agent.runtime.RuntimeReplayService;
import io.github.zzz8688.diagagent.agent.runtime.TaskManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/runtime/replay")
@RequiredArgsConstructor
public class RuntimeReplayController {

    private final RuntimeReplayService runtimeReplayService;
    private final RuntimeLaneService runtimeLaneService;

    @GetMapping("/overview")
    public ResponseEntity<RuntimeReplayService.RuntimeReplayOverview> getOverview(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(runtimeReplayService.buildOverview(limit));
    }

    @GetMapping("/metrics")
    public ResponseEntity<RuntimeReplayService.RuntimeMetricsView> getMetrics(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(runtimeReplayService.buildMetrics(limit));
    }

    @GetMapping("/timeline/{sessionId}")
    public ResponseEntity<RuntimeReplayService.ReplayTimelineView> getTimeline(@PathVariable String sessionId,
                                                                               @RequestParam(defaultValue = "120") int limit) {
        return ResponseEntity.ok(runtimeReplayService.buildTimelineBySession(sessionId, limit));
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<RuntimeReplayService.ReplayTimelineView> getTimelineByTask(@PathVariable String taskId,
                                                                                     @RequestParam(defaultValue = "120") int limit) {
        return ResponseEntity.ok(runtimeReplayService.buildTimelineByTask(taskId, limit));
    }

    @GetMapping("/audit/session/{sessionId}")
    public ResponseEntity<RuntimeReplayService.RuntimeAuditExport> getAuditBySession(@PathVariable String sessionId,
                                                                                     @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(runtimeReplayService.buildAuditExportBySession(sessionId, limit));
    }

    @GetMapping("/audit/task/{taskId}")
    public ResponseEntity<RuntimeReplayService.RuntimeAuditExport> getAuditByTask(@PathVariable String taskId,
                                                                                  @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(runtimeReplayService.buildAuditExportByTask(taskId, limit));
    }

    @PostMapping("/lane")
    public ResponseEntity<LaneMutationResponse> enqueueLane(@RequestBody LaneMutationRequest request) {
        String sessionId = normalize(request.sessionId());
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 不能为空");
        }
        TaskManager.SessionLaneType laneType = parseLaneType(request.laneType());
        String payloadSummary = normalize(request.payloadSummary());
        if (payloadSummary == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payloadSummary 不能为空");
        }
        TaskManager.SessionLaneState laneState = runtimeLaneService.enqueue(
                sessionId,
                normalize(request.taskId()),
                laneType,
                payloadSummary
        );
        return ResponseEntity.ok(new LaneMutationResponse(
                sessionId,
                laneType.name(),
                "lane entry enqueued",
                laneState == null ? TaskManager.SessionLaneState.empty(sessionId) : laneState
        ));
    }

    @DeleteMapping("/lane/{sessionId}/{laneEntryId}")
    public ResponseEntity<LaneMutationResponse> clearLane(@PathVariable String sessionId,
                                                          @PathVariable String laneEntryId) {
        String normalizedSessionId = normalize(sessionId);
        String normalizedEntryId = normalize(laneEntryId);
        if (normalizedSessionId == null || normalizedEntryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 和 laneEntryId 不能为空");
        }
        TaskManager.SessionLaneState laneState = runtimeLaneService.clear(normalizedSessionId, normalizedEntryId);
        return ResponseEntity.ok(new LaneMutationResponse(
                normalizedSessionId,
                "",
                "lane entry cleared",
                laneState == null ? TaskManager.SessionLaneState.empty(normalizedSessionId) : laneState
        ));
    }

    private TaskManager.SessionLaneType parseLaneType(String laneType) {
        String normalized = normalize(laneType);
        if (normalized == null) {
            return TaskManager.SessionLaneType.FOLLOWUP;
        }
        try {
            return TaskManager.SessionLaneType.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的 laneType: " + laneType);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record LaneMutationRequest(
            String sessionId,
            String taskId,
            String laneType,
            String payloadSummary
    ) {
    }

    public record LaneMutationResponse(
            String sessionId,
            String laneType,
            String message,
            TaskManager.SessionLaneState laneState
    ) {
    }
}

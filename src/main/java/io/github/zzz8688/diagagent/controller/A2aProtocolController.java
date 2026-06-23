package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.a2a.A2aAgentCard;
import io.github.zzz8688.diagagent.a2a.A2aClientAdapter;
import io.github.zzz8688.diagagent.a2a.A2aClientInvocationResult;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.a2a.A2aSendMessageParams;
import io.github.zzz8688.diagagent.a2a.A2aTaskEvent;
import io.github.zzz8688.diagagent.a2a.A2aTaskEventBridge;
import io.github.zzz8688.diagagent.a2a.A2aTaskIdParams;
import io.github.zzz8688.diagagent.a2a.A2aTaskQueryParams;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/a2a")
@RequiredArgsConstructor
public class A2aProtocolController {

    private final A2aClientAdapter a2aClientAdapter;
    private final A2aRegistryService a2aRegistryService;
    private final A2aTaskEventBridge a2aTaskEventBridge;

    @GetMapping("/agents/{agentId}/card/refresh")
    public ResponseEntity<A2aAgentCard> refreshAgentCard(@PathVariable String agentId) {
        return ResponseEntity.ok(a2aClientAdapter.fetchAgentCard(agentId));
    }

    @GetMapping("/agents/{agentId}/card")
    public ResponseEntity<A2aAgentCard> getCachedAgentCard(@PathVariable String agentId) {
        return ResponseEntity.of(a2aRegistryService.findAgent(agentId).map(registration -> registration.agentCard()));
    }

    @PostMapping("/agents/{agentId}/messages")
    public ResponseEntity<A2aClientInvocationResult> sendMessage(@PathVariable String agentId,
                                                                 @RequestBody A2aSendMessageParams params) {
        return ResponseEntity.ok(a2aClientAdapter.sendMessage(agentId, params));
    }

    @PostMapping(value = "/agents/{agentId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<A2aTaskEvent> sendStreamingMessage(@PathVariable String agentId,
                                                   @RequestBody A2aSendMessageParams params) {
        return a2aClientAdapter.sendStreamingMessage(agentId, params);
    }

    @GetMapping("/agents/{agentId}/tasks/{taskId}")
    public ResponseEntity<A2aClientInvocationResult> getTask(@PathVariable String agentId,
                                                             @PathVariable String taskId,
                                                             @RequestParam(name = "historyLength", required = false) Integer historyLength) {
        return ResponseEntity.ok(a2aClientAdapter.getTask(
                agentId,
                new A2aTaskQueryParams(taskId, historyLength, null)
        ));
    }

    @PostMapping("/agents/{agentId}/tasks/{taskId}/cancel")
    public ResponseEntity<A2aClientInvocationResult> cancelTask(@PathVariable String agentId,
                                                                @PathVariable String taskId,
                                                                @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(a2aClientAdapter.cancelTask(
                agentId,
                new A2aTaskIdParams(taskId, null)
        ));
    }

    @GetMapping("/tasks/{taskId}/events")
    public ResponseEntity<List<A2aTaskEvent>> listTaskEventsDebug(@PathVariable String taskId) {
        return ResponseEntity.ok(a2aTaskEventBridge.listTaskEvents(taskId));
    }

    @GetMapping(value = "/tasks/{taskId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<A2aTaskEvent> streamTaskEventsDebug(@PathVariable String taskId) {
        return a2aTaskEventBridge.streamTaskEvents(taskId);
    }

    @GetMapping("/events/recent")
    public ResponseEntity<List<A2aTaskEvent>> recentEvents(@RequestParam(name = "limit", defaultValue = "50") Integer limit) {
        return ResponseEntity.ok(a2aTaskEventBridge.recentEvents(limit == null ? 50 : limit));
    }
}

package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.a2a.A2aAgentCard;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class A2aAgentCardController {

    private final A2aRegistryService a2aRegistryService;

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<A2aAgentCard> getWellKnownAgentCard() {
        return ResponseEntity.of(a2aRegistryService.selfAgentCard());
    }

    @GetMapping("/api/a2a/card/self")
    public ResponseEntity<A2aAgentCard> getSelfAgentCard() {
        return ResponseEntity.of(a2aRegistryService.selfAgentCard());
    }
}

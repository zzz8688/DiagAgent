package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.service.StopGenerateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class StopGenerateStopHook implements StopHook {

    private final StopGenerateService stopGenerateService;

    @Override
    public StopHookResult evaluate(ReactTurnState turnState) {
        if (turnState == null || turnState.sessionId() == null) {
            return StopHookResult.continueLoop();
        }
        if (stopGenerateService.shouldContinue(turnState.sessionId())) {
            return StopHookResult.continueLoop();
        }
        return StopHookResult.stop(
                StopReason.USER_CANCELLED,
                Map.of(
                        "hook", name(),
                        "sessionId", turnState.sessionId()
                )
        );
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class MultiAgentReActRuntime {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentReActRuntime.class);

    private static final int DEFAULT_MAX_TURNS = 12;
    private static final int DEFAULT_MAX_BUDGET_TOKENS = 4000;

    private final RuntimeFacade runtimeFacade;

    public DiagnosisConclusion executeDiagnosis(String userQuestion, String sessionId) {
        return executeDiagnosis(userQuestion, sessionId, false);
    }

    public DiagnosisConclusion executeDiagnosis(String userQuestion, String sessionId, boolean readOnly) {
        log.info("开始执行多Agent ReAct诊断，会话ID: {}, 只读模式: {}", sessionId, readOnly);
        return runtimeFacade.execute(RuntimeRequest.diagnostic(
                userQuestion,
                sessionId,
                readOnly,
                DEFAULT_MAX_TURNS,
                DEFAULT_MAX_BUDGET_TOKENS
        ));
    }

    public Flux<String> executeDiagnosisStream(String userQuestion, String sessionId) {
        return executeDiagnosisStream(userQuestion, sessionId, false);
    }

    public Flux<String> executeDiagnosisStream(String userQuestion, String sessionId, boolean readOnly) {
        log.info("开始执行多Agent流式 ReAct诊断，会话ID: {}, 只读模式: {}", sessionId, readOnly);
        return runtimeFacade.stream(RuntimeRequest.diagnostic(
                userQuestion,
                sessionId,
                readOnly,
                DEFAULT_MAX_TURNS,
                DEFAULT_MAX_BUDGET_TOKENS
        ));
    }

    public DiagnosisConclusion diagnose(String userQuestion) {
        return executeDiagnosis(userQuestion, "default-session-" + System.currentTimeMillis());
    }

    public Flux<String> diagnoseStream(String userQuestion) {
        return executeDiagnosisStream(userQuestion, "default-session-" + System.currentTimeMillis());
    }
}

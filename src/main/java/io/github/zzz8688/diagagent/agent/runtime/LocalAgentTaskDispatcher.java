package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.Gson;
import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;
import io.github.zzz8688.diagagent.config.MultiAgentFactory;
import io.github.zzz8688.diagagent.config.SessionContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalAgentTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentTaskDispatcher.class);

    private final MultiAgentFactory multiAgentFactory;
    private final Gson gson = new Gson();

    public AgentTaskResult dispatch(AgentTaskRequest request, AgentDescriptor descriptor) {
        try {
            SessionContext.setExecutionContext(request.sessionId(), request.taskId());
            String rawResponse = invokeLocalAgent(request, descriptor);
            String trimmedResponse = multiAgentFactory.trimResult(rawResponse);
            AgentDiagnosisResponse diagnosisResponse = parseResponse(descriptor.agentId(), rawResponse, trimmedResponse);
            return AgentTaskResult.completed(request.taskId(), descriptor.agentId(), diagnosisResponse);
        } catch (Exception e) {
            log.error("Local specialist task dispatch failed: agentId={}, taskId={}",
                    descriptor.agentId(), request.taskId(), e);
            return AgentTaskResult.failed(request.taskId(), descriptor.agentId(), e.getMessage());
        } finally {
            SessionContext.clear();
        }
    }

    private String invokeLocalAgent(AgentTaskRequest request, AgentDescriptor descriptor) {
        SpecialistRuntime specialistRuntime = multiAgentFactory.createSpecialistRuntime(
                descriptor.agentId(),
                request.sessionId()
        );
        log.debug("Dispatching local specialist via platform runtime: agentId={}, bindingMode={}",
                specialistRuntime.agentId(), specialistRuntime.bindingMode());
        return specialistRuntime.diagnose(request.userQuestion());
    }

    private AgentDiagnosisResponse parseResponse(String agentId, String rawResponse, String trimmedResponse) {
        try {
            AgentDiagnosisResponse parsed = gson.fromJson(trimmedResponse, AgentDiagnosisResponse.class);
            if (parsed == null) {
                throw new IllegalStateException("Parsed response is null");
            }
            parsed.setAgent(agentId);
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to parse specialist response as JSON, fallback to raw response: agentId={}, error={}",
                    agentId, e.getMessage());
            AgentDiagnosisResponse fallback = new AgentDiagnosisResponse();
            fallback.setAgent(agentId);
            fallback.setAnalysis("通过本地 specialist 取证后得到结果，但结构化解析失败");
            fallback.setConfidence(0.6);
            fallback.setConclusion(rawResponse);
            return fallback;
        }
    }
}

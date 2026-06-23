package io.github.zzz8688.diagagent.agent.runtime;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MainAgentRoutingService {

    private static final Logger log = LoggerFactory.getLogger(MainAgentRoutingService.class);
    private static final Type ROUTING_RESULT_TYPE = new TypeToken<Map<String, List<String>>>() { }.getType();

    private final MainAgentPromptAssembler mainAgentPromptAssembler;
    private final MainAgentModelGateway mainAgentModelGateway;
    private final Gson gson = new Gson();

    public List<String> routeAgentIds(String userQuestion,
                                      MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        MainAgentPromptAssembler.RenderedPrompt renderedPrompt = mainAgentPromptAssembler.buildRoutingPrompt(
                userQuestion,
                promptContext
        );
        String rawResponse = mainAgentModelGateway.complete(renderedPrompt.toPromptEnvelope());
        String jsonContent = extractJson(rawResponse);
        Map<String, List<String>> resultMap = gson.fromJson(jsonContent, ROUTING_RESULT_TYPE);
        if (resultMap == null || resultMap.get("routedAgents") == null) {
            throw new IllegalStateException("主智能体未返回 routedAgents 字段: " + rawResponse);
        }
        return resultMap.get("routedAgents");
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        log.warn("主智能体路由结果不是标准 JSON，返回原文: {}", trimmed);
        return trimmed;
    }
}

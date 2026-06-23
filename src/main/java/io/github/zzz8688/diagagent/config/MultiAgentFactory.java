package io.github.zzz8688.diagagent.config;

import io.github.zzz8688.diagagent.agent.*;
import io.github.zzz8688.diagagent.agent.runtime.SpecialistCompatInvoker;
import io.github.zzz8688.diagagent.agent.runtime.SpecialistCompatInvokerFactory;
import io.github.zzz8688.diagagent.agent.runtime.SpecialistRuntime;
import io.github.zzz8688.diagagent.tools.registry.LocalToolCatalog;
import io.github.zzz8688.diagagent.tools.registry.ToolDescriptor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class MultiAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentFactory.class);
    private static final String SPECIALIST_BINDING_MODE = "specialist-runtime-binding";
    private static final List<String> DEFAULT_SPECIALIST_TOOL_NAMES = List.of(
            "queryLogs",
            "queryKnowledgeBase",
            "queryMemoryFiles"
    );
    private static final List<String> NETWORK_SPECIALIST_TOOL_NAMES = List.of(
            "queryLogs",
            "queryTopology",
            "queryKnowledgeBase",
            "queryMemoryFiles"
    );

    private final SpecialistCompatInvokerFactory specialistCompatInvokerFactory;
    private final LocalToolCatalog localToolCatalog;
    private final SystemPromptConfig systemPromptConfig;

    private static final int MAX_RESULT_LENGTH = 2000;
    private static final String SNIP_SUFFIX = "... (内容过长已裁剪)";

    public SpecialistRuntime createSpecialistRuntime(String agentId, String sessionId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        return buildSpecialistRuntime(resolveSpecialistProfile(normalizedAgentId), sessionId);
    }

    private String normalizeAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        return agentId.trim().toLowerCase(Locale.ROOT);
    }

    private SpecialistProfile resolveSpecialistProfile(String agentId) {
        return switch (agentId) {
            case "app" -> new SpecialistProfile(
                    agentId,
                    systemPromptConfig.getAppAgentPrompt(),
                    DEFAULT_SPECIALIST_TOOL_NAMES
            );
            case "db" -> new SpecialistProfile(
                    agentId,
                    systemPromptConfig.getDbAgentPrompt(),
                    DEFAULT_SPECIALIST_TOOL_NAMES
            );
            case "network" -> new SpecialistProfile(
                    agentId,
                    systemPromptConfig.getNetworkAgentPrompt(),
                    NETWORK_SPECIALIST_TOOL_NAMES
            );
            default -> throw new IllegalArgumentException("Unknown local specialist: " + agentId);
        };
    }

    private SpecialistRuntime buildSpecialistRuntime(SpecialistProfile profile, String sessionId) {
        SpecialistToolBinding toolBinding = buildSpecialistToolBinding(profile.agentId(), profile.toolNames());
        String systemPrompt = withPlatformToolBoundary(profile.basePrompt(), toolBinding);
        log.info("Using system prompt for {} specialist", profile.agentId());
        SpecialistCompatInvoker compatInvoker = specialistCompatInvokerFactory.create(
                profile.agentId(),
                sessionId,
                SPECIALIST_BINDING_MODE,
                systemPrompt,
                toolBinding.frameworkTools()
        );
        return new PlatformSpecialistRuntime(profile.agentId(), compatInvoker.bindingMode(), compatInvoker::invoke);
    }

    private String withPlatformToolBoundary(String basePrompt, SpecialistToolBinding toolBinding) {
        StringBuilder builder = new StringBuilder(basePrompt == null ? "" : basePrompt);
        if (!toolBinding.toolNames().isBlank()) {
            builder.append("\n\n# Platform Tool Boundary\n")
                    .append("当前 specialist 允许使用的平台工具描述符: ")
                    .append(toolBinding.toolNames())
                    .append("\n这些工具当前通过 specialist runtime 绑定暴露，平台标准以 ToolDescriptor 为准。");
            builder.append("\n当前 specialist binding mode: ").append(SPECIALIST_BINDING_MODE);
        }
        return builder.toString();
    }

    private SpecialistToolBinding buildSpecialistToolBinding(String agentName, List<String> toolNames) {
        List<ToolDescriptor> descriptors = localToolCatalog.resolveToolDescriptors(toolNames);
        Object[] frameworkTools = localToolCatalog.resolveFrameworkToolBeans(toolNames);
        String descriptorNames = descriptors.stream()
                .map(ToolDescriptor::name)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        log.info("Building {} specialist with platform tool descriptors: {}, bindingMode={}", agentName, descriptorNames, SPECIALIST_BINDING_MODE);
        return new SpecialistToolBinding(frameworkTools, descriptorNames);
    }

    public String trimResult(String result) {
        if (result == null) {
            return result;
        }
        
        // 尝试提取 JSON 内容
        String jsonContent = extractJson(result);
        if (jsonContent != null) {
            result = jsonContent;
        }
        
        if (result.length() <= MAX_RESULT_LENGTH) {
            return result;
        }
        log.warn("Agent 结果过长（{} 字符），已裁剪至 {} 字符", result.length(), MAX_RESULT_LENGTH);
        return result.substring(0, MAX_RESULT_LENGTH - SNIP_SUFFIX.length()) + SNIP_SUFFIX;
    }
    
    private String extractJson(String content) {
        if (content == null) {
            return null;
        }
        
        content = content.trim();
        
        // 尝试提取 Markdown 代码块中的 JSON
        int codeBlockStart = content.indexOf("```json");
        if (codeBlockStart != -1) {
            int contentStart = content.indexOf('\n', codeBlockStart);
            if (contentStart != -1) {
                int codeBlockEnd = content.indexOf("```", contentStart + 1);
                if (codeBlockEnd != -1) {
                    return cleanAndFixJson(content.substring(contentStart + 1, codeBlockEnd).trim());
                }
            }
        }
        
        // 尝试提取 ``` 代码块（不带json标记）
        codeBlockStart = content.indexOf("```");
        if (codeBlockStart != -1) {
            int contentStart = content.indexOf('\n', codeBlockStart);
            if (contentStart != -1) {
                int codeBlockEnd = content.indexOf("```", contentStart + 1);
                if (codeBlockEnd != -1) {
                    return cleanAndFixJson(content.substring(contentStart + 1, codeBlockEnd).trim());
                }
            }
        }
        
        // 首先尝试直接查找完整的 JSON 对象
        int firstBrace = content.indexOf('{');
        int lastBrace = content.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return cleanAndFixJson(content.substring(firstBrace, lastBrace + 1));
        }
        
        return null;
    }
    
    /**
     * 清理和修复可能有问题的JSON字符串
     */
    private String cleanAndFixJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        
        // 移除多余的空白字符
        json = json.trim();
        
        // 尝试修复常见的JSON错误
        // 1. 替换未转义的双引号（在字符串值内部的）
        // 2. 移除JSON前后的非JSON字符
        // 3. 确保键名被正确引用
        
        return json;
    }

    private record SpecialistToolBinding(Object[] frameworkTools, String toolNames) {
    }

    private record SpecialistProfile(String agentId,
                                     String basePrompt,
                                     List<String> toolNames) {
    }

    private record PlatformSpecialistRuntime(String agentId,
                                             String bindingMode,
                                             Function<String, String> delegate) implements SpecialistRuntime {

        @Override
        public String diagnose(String userQuestion) {
            return delegate.apply(userQuestion);
        }
    }
}

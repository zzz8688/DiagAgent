package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.skills.SkillSearchResult;
import io.github.zzz8688.diagagent.skills.SkillService;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SkillQueryTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(SkillQueryTool.class);

    private final SkillService skillService;

    @PlatformTool(
            description = "搜索本地诊断技能模板、路由规则和排障流程。优先用于查找该怎么诊断，而不是查找案例事实。",
            userVisible = false
    )
    @Tool("搜索本地诊断技能模板、路由规则和排障流程。优先用于查找该怎么诊断，而不是查找案例事实。")
    public String querySkills(String query) {
        log.info("Agent 正在搜索技能模板: {}", query);
        beforeToolCall("querySkills", query);
        try {
            checkCancelled();
            List<SkillSearchResult> matches = skillService.search(query, 5);
            checkCancelled();
            if (matches.isEmpty()) {
                return "未找到相关技能模板。";
            }

            return matches.stream()
                    .map(match -> formatSkill(match))
                    .collect(Collectors.joining("\n\n"));
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询技能模板失败", e);
            return "查询技能模板失败: " + e.getMessage();
        }
    }

    @PlatformTool(
            description = "显式加载某个技能包的 SKILL.md 正文。输入 skillId，例如 diagnosis-playbook-skill。这个工具用于第二阶段加载技能正文，而不是首轮发现。",
            userVisible = false
    )
    @Tool("显式加载某个技能包的 SKILL.md 正文。输入 skillId，例如 diagnosis-playbook-skill。这个工具用于第二阶段加载技能正文，而不是首轮发现。")
    public String querySkillContent(String skillId) {
        log.info("Agent 正在加载技能正文: {}", skillId);
        beforeToolCall("querySkillContent", skillId);
        try {
            checkCancelled();
            String normalizedSkillId = skillId == null ? "" : skillId.trim();
            if (normalizedSkillId.isBlank()) {
                return "技能正文读取格式错误，请直接提供 skillId。";
            }

            String content = skillService.readSkillBody(normalizedSkillId);
            checkCancelled();
            if (content == null || content.isBlank()) {
                return "未找到对应的技能正文。";
            }

            String resourceHint = buildResourceHint(normalizedSkillId);
            return String.format(
                    "### %s\n%s%s",
                    normalizedSkillId,
                    content.trim(),
                    resourceHint
            );
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取技能正文失败", e);
            return "读取技能正文失败: " + e.getMessage();
        }
    }

    @PlatformTool(
            description = "按需读取某个技能包里的静态资源。输入格式为 skillId:resourcePath，例如 diagnosis-playbook-skill:resources/evidence-checklist.md。只读取 skill 包内除 SKILL.md 和 scripts 之外的静态资源。",
            userVisible = false
    )
    @Tool("按需读取某个技能包里的静态资源。输入格式为 skillId:resourcePath，例如 diagnosis-playbook-skill:resources/evidence-checklist.md。只读取 skill 包内除 SKILL.md 和 scripts 之外的静态资源。")
    public String querySkillResource(String request) {
        log.info("Agent 正在读取技能资源: {}", request);
        beforeToolCall("querySkillResource", request);
        try {
            checkCancelled();
            SkillResourceRef ref = parseResourceRequest(request);
            if (ref == null) {
                return "技能资源读取格式错误，请使用 skillId:resourcePath。";
            }

            String content = skillService.readResource(ref.skillId(), ref.resourcePath());
            checkCancelled();
            if (content == null || content.isBlank()) {
                return "未找到对应的技能静态资源。";
            }

            return String.format(
                    "### %s / %s\n%s",
                    ref.skillId(),
                    ref.resourcePath(),
                    content.trim()
            );
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取技能资源失败", e);
            return "读取技能资源失败: " + e.getMessage();
        }
    }

    private String formatSkill(SkillSearchResult result) {
        checkCancelled();
        return String.format(
                "### %s (score=%.2f)\nname: %s\ndescription: %s\nwhenToUse: %s\nallowedTools: %s",
                result.skill().id(),
                result.score(),
                result.skill().id(),
                emptyAsFallback(result.skill().description(), "未填写描述"),
                emptyAsFallback(result.skill().whenToUse(), "未填写"),
                result.skill().allowedTools().isEmpty() ? "[]" : result.skill().allowedTools()
        );
    }

    private String emptyAsFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String buildResourceHint(String skillId) {
        var skill = skillService.findSkill(skillId);
        if (skill == null || skill.resourceFiles().isEmpty()) {
            return "\n\n可继续读取的静态资源: 无";
        }
        return "\n\n可继续读取的静态资源:\n- " + String.join("\n- ", skill.resourceFiles());
    }

    private SkillResourceRef parseResourceRequest(String request) {
        if (request == null || request.isBlank()) {
            return null;
        }
        int separator = request.indexOf(':');
        if (separator <= 0 || separator >= request.length() - 1) {
            return null;
        }
        String skillId = request.substring(0, separator).trim();
        String resourcePath = request.substring(separator + 1).trim();
        if (skillId.isBlank() || resourcePath.isBlank()) {
            return null;
        }
        return new SkillResourceRef(skillId, resourcePath);
    }

    private record SkillResourceRef(String skillId, String resourcePath) {
    }
}

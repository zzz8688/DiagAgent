package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.skills.SkillDoc;
import io.github.zzz8688.diagagent.skills.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SkillStepActionAdapter implements ReactActionAdapter {

    private final SkillService skillService;

    @Override
    public ReactActionType actionType() {
        return ReactActionType.RUN_SKILL_STEP;
    }

    @Override
    public ReactActionExecutionResult execute(ReactAction action,
                                              String userQuestion,
                                              String sessionId,
                                              boolean readOnly) {
        try {
            String skillId = firstNonBlank(
                    action == null ? null : action.target(),
                    arg(action == null ? null : action.arguments(), "skillId")
            );
            if (skillId == null) {
                throw new IllegalArgumentException("skill target is empty");
            }
            SkillDoc skill = skillService.findSkill(skillId);
            if (skill == null) {
                return ReactActionExecutionResult.failed(action, null, "skill step failed: skill not found");
            }
            String payload = renderSkillContext(skill);
            return ReactActionExecutionResult.completed(action, payload, "skill context loaded");
        } catch (Exception e) {
            return ReactActionExecutionResult.failed(action, null, "skill step failed: " + e.getMessage());
        }
    }

    private String renderSkillContext(SkillDoc skill) {
        String resources = skill.resourceFiles().isEmpty()
                ? "无"
                : skill.resourceFiles().stream().collect(Collectors.joining(", "));
        return """
                ## Skill Context
                skillId: %s
                title: %s
                description: %s

                以下内容是 skill 注入的操作方法资产，不是事实证据，也不是工具调用结果。
                你应结合它来决定后续如何使用真实工具取证、如何组织输出，以及什么时候需要再看知识或依赖关系。

                %s

                可按需扩展的内部静态资源: %s
                """.formatted(
                safe(skill.id()),
                safe(skill.title()),
                safe(skill.description()),
                safe(skill.body()),
                resources
        ).trim();
    }

    private String arg(Map<String, String> arguments, String key) {
        if (arguments == null || key == null) {
            return "";
        }
        String value = arguments.get(key);
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

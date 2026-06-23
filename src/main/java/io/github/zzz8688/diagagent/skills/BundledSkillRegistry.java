package io.github.zzz8688.diagagent.skills;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BundledSkillRegistry {

    public List<SkillDoc> listBundledSkills() {
        return List.of(
                hostFinalAnswerSkill(),
                incidentHandoffSkill()
        );
    }

    private SkillDoc hostFinalAnswerSkill() {
        Map<String, String> resources = Map.of(
                "resources/final-answer-checklist.md", """
                        # Host Final Answer Checklist

                        主智能体输出最终答案前，至少确认：

                        - 已读取并比较各 specialist 返回结果
                        - 已区分事实、推断、建议、待确认
                        - 已明确是否需要人工介入
                        - 未把子智能体结果原样拼接成最终答案
                        """
        );

        return new SkillDoc(
                "host-final-answer-skill",
                "Host Final Answer",
                "内置主智能体最终回答技能，适用于 Router/HostCoordinator 在读取各 specialist 返回结果后，继续生成最终诊断答案。",
                "当主智能体需要综合多个 specialist 结果并输出最终答案时使用。",
                """
                        # Host Final Answer

                        目标：让主智能体在读取各 specialist 返回结果后，继续按 ReAct 范式生成最终答案。

                        执行步骤：
                        1. 先比较各 specialist 的事实证据和结论差异
                        2. 区分哪些是直接证据，哪些只是候选推断
                        3. 必要时补充 skills 或 knowledge 中的规则、案例、手册
                        4. 由主智能体自己生成最终答案，不原样拼接子智能体输出
                        5. 如果置信度不足，明确标记人工介入或交接建议

                        输出要求：
                        - 使用 Markdown
                        - 包含 `事实 / 推断 / 建议 / 待确认`
                        - 保持结论可追溯

                        如需最终回答检查项，请读取 `resources/final-answer-checklist.md`。
                        """,
                "bundled:host-final-answer-skill/SKILL.md",
                "",
                List.copyOf(resources.keySet()),
                List.of("queryKnowledgeBase"),
                "可选，补充主智能体看到的 specialist 结果摘要",
                List.of("specialistSummary"),
                "1.0.0",
                false,
                "bundled",
                "bundled",
                false,
                resources,
                Map.of(),
                new SkillExecutionSchema(
                        "inline-prompt-plan",
                        "inline",
                        "main-agent",
                        "",
                        Map.of(),
                        List.of(),
                        "bundled-registry"
                ),
                Map.of("kind", "bundled-runtime-skill")
        );
    }

    private SkillDoc incidentHandoffSkill() {
        Map<String, String> resources = Map.of(
                "resources/handoff-checklist.md", """
                        # Handoff Checklist

                        交接时至少包含以下信息：

                        - 故障现象和影响范围
                        - 已验证的日志、指标、链路证据
                        - 已排除的假设
                        - 下一步建议动作
                        - 是否需要人工升级
                        """
        );

        return new SkillDoc(
                "incident-handoff-skill",
                "Incident Handoff",
                "内置故障交接技能，适用于根因暂未锁定或需要人工升级时，帮助模型整理证据与待办。",
                "当证据不足、权限不足或需要人工升级时使用。",
                """
                        # Incident Handoff

                        目标：在证据不足、权限不足、或需要跨团队协作时，形成标准交接输出。

                        执行步骤：
                        1. 汇总已确认现象、时间范围和影响面
                        2. 列出已经收集到的关键证据
                        3. 标明尚未确认的假设
                        4. 给出下一步最小行动建议
                        5. 明确是否需要升级到人工或远程专科 agent

                        输出要求：
                        - 结论简洁
                        - 证据可追溯
                        - 待办明确

                        如需交接清单，请读取 `resources/handoff-checklist.md`。
                        """,
                "bundled:incident-handoff-skill/SKILL.md",
                "",
                List.copyOf(resources.keySet()),
                List.of("queryLogs", "prometheus-mcp/queryPrometheusMetrics"),
                "可选，补充当前故障摘要",
                List.of("incidentSummary"),
                "1.0.0",
                true,
                "bundled",
                "bundled",
                true,
                resources,
                Map.of(),
                new SkillExecutionSchema(
                        "inline-prompt-plan",
                        "inline",
                        "main-agent",
                        "",
                        Map.of(),
                        List.of(),
                        "bundled-registry"
                ),
                Map.of("kind", "bundled-runtime-skill")
        );
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.config.SystemPromptConfig;
import io.github.zzz8688.diagagent.skills.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Component
@RequiredArgsConstructor
public class MainAgentPromptAssembler {

    public static final String REACT_PROMPT_DYNAMIC_BOUNDARY = "=== REACT_PROMPT_DYNAMIC_BOUNDARY ===";

    private final SystemPromptConfig systemPromptConfig;
    private final SkillService skillService;
    private final SkillIntentAdvisor skillIntentAdvisor;
    private final McpPromptInjectionAssembler mcpPromptInjectionAssembler;

    public PromptBundle buildActionSelectionBundle(String userQuestion,
                                                   MainAgentPromptContext context) {
        return buildActionSelectionPrompt(
                userQuestion,
                context
        ).toPromptBundle();
    }

    public RenderedPrompt buildActionSelectionPrompt(String userQuestion,
                                                     MainAgentPromptContext context) {
        return buildRenderedPrompt(
                context,
                renderActionSelectionStaticInstructions(),
                renderActionSelectionUserPrompt(userQuestion, context)
        );
    }

    public PromptBundle buildRoutingBundle(String userQuestion, MainAgentPromptContext context) {
        return buildRoutingPrompt(userQuestion, context).toPromptBundle();
    }

    public RenderedPrompt buildRoutingPrompt(String userQuestion, MainAgentPromptContext context) {
        return buildRenderedPrompt(
                context,
                renderRoutingStaticInstructions(),
                renderRoutingUserPrompt(userQuestion, context)
        );
    }

    public PromptBundle buildFinalAnswerBundle(String userQuestion,
                                               String specialistResults,
                                               MainAgentPromptContext context) {
        return buildFinalAnswerPrompt(userQuestion, specialistResults, context).toPromptBundle();
    }

    public RenderedPrompt buildFinalAnswerPrompt(String userQuestion,
                                                 String specialistResults,
                                                 MainAgentPromptContext context) {
        return buildRenderedPrompt(
                context,
                renderFinalAnswerStaticInstructions(),
                renderFinalAnswerUserPrompt(userQuestion, specialistResults, context)
        );
    }

    private RenderedPrompt buildRenderedPrompt(MainAgentPromptContext context,
                                               String modeStaticInstructions,
                                               String userPrompt) {
        String stablePrefix = renderStablePrefix(modeStaticInstructions);
        String dynamicPromptSection = renderDynamicPromptSection(context);
        String userContextXml = extractUserContextXml(userPrompt);
        String responseContract = extractResponseContract(userPrompt);
        return new RenderedPrompt(
                stablePrefix,
                dynamicPromptSection,
                userContextXml,
                responseContract,
                context.promptMode()
        );
    }

    private String renderStablePrefix(String modeStaticInstructions) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是 DiagAgent 的 MainAgent。

                你的职责不是机械路由或机械汇总，而是通过 ReAct loop 基于真实环境反馈逐步收敛诊断结论。
                你工作的核心是 Thought -> Action -> Observation -> Final Answer。

                你必须遵循下面这些稳定规则：
                1. 先形成当前轮 Thought，判断还缺哪些关键证据
                2. 如果还缺证据，只能选择一个最有价值的 Action
                3. 输出 Action 后必须立即停止，等待真实 Observation
                4. 收到 Observation 后，必须更新判断，再决定是否需要下一轮 Action
                5. 只有在必要动作都已返回结果且关键证据充分时，才允许输出 Final Answer

                核心约束：
                - Observation 只能来自真实执行返回
                - 不得伪造 Observation
                - 不得一次输出多个 Action
                - tool、skill、MCP、A2A 的返回都只是 Observation；specialist 若后续作为实验能力启用，也同样只提供 Observation
                - 不得把未验证的猜测写成 root cause
                - 如果还有 pending approval、pending remote event、pending reconnect，就不能结束
                - 最终答案必须明确区分事实、推断、建议、待确认
                - 当前 prompt 中边界之上的内容视为稳定前缀，边界之下的运行时信息每轮重建
                - 动态上下文中的 capability、memory、MCP 注入、budget、observation 都是运行时事实，不要忽略它们
                - 当前默认正式主线只保留 main agent + critic，不要把 specialist 当成默认必经层
                - 如果当前模式要求只输出 JSON，就不要输出 Markdown、解释文字或额外包裹
                """);
        String configuredBasePrompt = safeText(systemPromptConfig.getMainAgentPrompt());
        if (!configuredBasePrompt.isBlank()) {
            builder.append("\n\n<domain_instructions><![CDATA[\n")
                    .append(configuredBasePrompt.trim())
                    .append("\n]]></domain_instructions>");
        }
        builder.append("\n\n<mode_instructions><![CDATA[\n")
                .append(modeStaticInstructions.trim())
                .append("\n]]></mode_instructions>");
        return builder.toString().trim();
    }

    private String renderRoutingStaticInstructions() {
        return """
                当前任务是判断这一轮最应该调度哪些专科参与诊断。

                routing 规则：
                - 只根据当前用户问题、上一轮 observation 和动态上下文做判断
                - 路由 contract 由当前主流程 prompt 直接定义，不依赖 router skill 正文兜底
                - 服务不可用、接口报错、前端显示异常、Token 过期、认证失败、高响应时间这类服务行为异常，优先路由到 app
                - 数据库慢查询、数据库连接超时、锁等待、连接池耗尽、数据库连接不上这类已明确落到数据层的异常，优先路由到 db
                - 网络延迟、DNS 失败、连接被拒绝、网关超时、防火墙阻断这类网络连通性异常，优先路由到 network
                - 如果问题同时涉及多个领域、当前描述仍然模糊、或现有 observation 无法把领域单点收窄，输出 all
                - 如果证据不足以缩小范围，可以输出 all
                - routedAgents 只能填写 capability view 中存在的本地 specialist，当前默认只有 app、db、network、all
                - 商品页慢、首屏阻塞、缓存命中下降、鉴权转圈、订单/结账失败这类业务现象，在缺少数据库直接证据前优先按 app 或 all 处理
                - 网关超时、DNS、连接拒绝等词优先按 network 处理，不要被通用“服务报错”覆盖
                - 不要输出解释文字
                - 只输出 JSON

                输出格式固定为：
                {"routedAgents":["app"]}

                合法样例：
                - {"routedAgents":["db"]}
                - {"routedAgents":["app","network"]}
                - {"routedAgents":["all"]}

                非法样例：
                - {"routedAgents":["db"]} 之后再追加解释文字
                - {"routedAgents":["unknown"]} 使用 capability view 中不存在的 specialist
                - {"routedAgents":"db"} routedAgents 不是数组
                """;
    }

    private String renderActionSelectionStaticInstructions() {
        return """
                当前任务不是机械继续 ReAct，而是为 MainAgent 选择“最有助于本轮诊断收敛”的唯一下一步动作。

                当前子任务 contract：
                - 只允许输出一个 JSON 对象，不要输出解释文字
                - 必须从当前 capability view 中选择一个最有价值的动作
                - 目标是缩小关键证据缺口、消解主要冲突、推动最终收口，而不是为了继续循环而继续循环
                - 当用户只描述故障现象时，不要把“接下来怎么排查”反问给用户；应优先自主选择最有价值的动作
                - 如果当前还缺 factual evidence，或 recent observation 主要是 planning/control observation，优先选择事实收集动作
                - recommended_skills 代表基于 skill metadata 检索出来的高相关候选
                - 当前主链仍以真实取证工具为主：tool 负责真实取证，skill 负责模板、清单、专题方法和固定流程资产
                - 当前默认优先顺序固定为：当前事实证据 > 当前知识库 / RAG > 历史经验
                - 常规诊断默认顺序是：先 skill 或事实工具起步，再用知识库补规则和案例，最后才参考历史经验
                - 对于具体故障现象，优先顺序通常是 queryLogs / queryPrometheusMetrics / trace 或依赖关系，再决定是否看 queryKnowledgeBase
                - RUN_SKILL_STEP 表示让 runtime 注入某个 skill 的正文上下文，不等于调用本地工具，也不等于已经完成事实取证
                - 如果当前请求与某个 skill 的使用场景高度匹配，且 recommended_skills 非空，应优先用 RUN_SKILL_STEP 加载最相关 skill，再继续动作规划
                - 当前默认正式主线不启用 specialist；只有 capability view 明确暴露 specialist 时，才允许把它当成实验动作面
                - 没有部分当前事实前，不要把 queryKnowledgeBase 当成默认第一步
                - queryMemoryFiles 只用于搜索 historical experience，不是主链默认事实工具
                - 只有在证据不足、还没形成结论、或需要备选排查方向时，才允许选择 queryMemoryFiles
                - 如果当前轮刚拿到新的 factual evidence，优先继续补当前事实链，不要立刻转向 queryMemoryFiles
                - 如果证据已经充分、主要不确定性已被压缩、且不存在 pending 阻塞项，可以输出 RETURN_FINAL
                - 如果已经收到 critic_observation 且现有证据无法回应，优先继续补证据，不要急于再次 RETURN_FINAL

                关键禁令：
                - target 必须严格使用 capability view 中给出的精确标识
                - arguments 只填当前动作真正需要的参数
                - 输出 Action JSON 后立即停止，不要继续生成 thought、假设 Observation 或补充说明
                - 不要把执行 skill 当成已经完成事实取证
                - 不要把 queryMemoryFiles 返回的历史经验当成当前问题已经确认的事实证据
                - dingtalk-mcp/sendNotice 不是事实收集工具，不能用于补证据或替代最终结论
                - 除非受到 approval、budget、capability 或真实证据边界阻塞，否则不要把“还需要用户补充什么”当成默认退出方式

                输出格式固定为：
                {
                  "actionType":"CALL_LOCAL_TOOL",
                  "actionName":"queryLogs",
                  "target":"queryLogs",
                  "arguments":{"query":"支付超时 ERROR 日志"},
                  "reason":"先获取日志证据",
                  "requiresApproval":false
                }

                target 对照表：
                - CALL_LOCAL_TOOL -> target=toolName，例如 queryLogs
                - CALL_MCP_TOOL -> target=serverId/toolName，例如 prometheus-mcp/queryPrometheusMetrics
                - QUERY_KNOWLEDGE -> target=知识相关能力标识，例如 queryKnowledgeBase
                - RUN_SKILL_STEP -> target=skillId，actionName 固定填 loadSkillContext
                - WAIT_APPROVAL -> target 固定填 approval
                - RETURN_FINAL -> target 固定填 final-answer，actionName 固定填 return_final

                合法样例：
                {
                  "actionType":"CALL_LOCAL_TOOL",
                  "actionName":"queryLogs",
                  "target":"queryLogs",
                  "arguments":{"query":"支付超时 ERROR 日志"},
                  "reason":"先获取日志证据",
                  "requiresApproval":false
                }
                {
                  "actionType":"RUN_SKILL_STEP",
                  "actionName":"loadSkillContext",
                  "target":"service-health-check-skill",
                  "arguments":{"skillId":"service-health-check-skill"},
                  "reason":"用户明确要求健康巡检，先加载对应 skill 的检查顺序和输出模板",
                  "requiresApproval":false
                }
                {
                  "actionType":"CALL_MCP_TOOL",
                  "actionName":"queryPrometheusMetrics",
                  "target":"prometheus-mcp/queryPrometheusMetrics",
                  "arguments":{"metricName":"http_server_errors_total","timeRange":"15m"},
                  "reason":"先确认错误率是否抬升",
                  "requiresApproval":false
                }
                {
                  "actionType":"CALL_MCP_TOOL",
                  "actionName":"queryPrometheusMetrics",
                  "target":"prometheus-mcp/queryPrometheusMetrics",
                  "arguments":{"metricName":"payment_service_error_rate","timeRange":"15m"},
                  "reason":"日志已显示支付失败，需要补 payment-service 指标证据",
                  "requiresApproval":false
                }
                {
                  "actionType":"RETURN_FINAL",
                  "actionName":"return_final",
                  "target":"final-answer",
                  "arguments":{},
                  "reason":"必要 observation 已闭环",
                  "requiresApproval":false
                }

                非法样例：
                - {"actionType":"CALL_MCP_TOOL","actionName":"queryPrometheusMetrics","target":"queryPrometheusMetrics","arguments":{},"reason":"缺少 serverId","requiresApproval":false}
                - {"actionType":"CALL_LOCAL_TOOL","actionName":"querySkillContent","target":"diagnosis-playbook-skill","arguments":{},"reason":"skill 加载不应再伪装成本地工具调用","requiresApproval":false}
                - {"actionType":"RUN_SKILL_STEP","actionName":"querySkillContent","target":"diagnosis-playbook-skill","arguments":{},"reason":"RUN_SKILL_STEP 应表达 skill 上下文注入，而不是工具式正文读取","requiresApproval":false}
                - {"actionType":"RETURN_FINAL","actionName":"queryLogs","target":"queryLogs","arguments":{},"reason":"字段冲突","requiresApproval":false}
                - 输出两个 JSON 对象或在 JSON 后补解释文字
                """;
    }

    private String renderFinalAnswerStaticInstructions() {
        return """
                当前任务不是把已有 observation 机械总结成答案，而是基于已经返回的 observations 判断诊断循环是否真的可以安全收口。

                final-answer 规则：
                - specialist 返回的是 observation，不是最终业务答案
                - 不能机械拼接 specialist 输出
                - 你的目标是输出 grounded final answer，或者在无法安全收敛时明确 handoff；不要为了“结束循环”而强行给高置信度结论
                - 如果关键证据仍不充分，要明确指出缺口，不要编造根因
                - 只有在必要动作都已经返回结果、信息面足够完整、主要冲突已被解释、且 critic 批评已被回应时，才允许输出最终答案
                - 只允许输出一个 JSON 对象，不要输出 Markdown 或解释文字
                - evidence 中每一项都必须显式引用真实来源
                - evidence.sourceType 只允许使用 EVENT_REPLAY、APPROVAL_REPLAY、SPECIALIST_OBSERVATION
                - evidence.sourceId 必须填真实 actionId 或 specialist agentId
                - 如果 prompt 中已经提供 critic_observation，要把它当成最新 observation 处理
                - 不要机械重复上一版 final_answer_draft；必须明确回应 critic 提出的批评
                - summary、evidence、nextActions、confidence、needsHandoff 都必须存在
                - 如果关键证据不足、证据互相冲突、或当前证据无法再显著提升结论质量，则 needsHandoff 必须为 true，nextActions 必须给出下一步动作
                - confidence 必须反映当前证据强度，而不是反映你“想结束循环”的意愿
                - 如果你只能给出弱结论，就明确保持低 confidence，并通过 needsHandoff 承认当前 loop 尚未完全收敛
                - 如果日志与指标只在短时间窗口内零散异常、traceId 不完整、且不同服务指向不同候选根因，这属于低置信度场景；不要强行选一个根因
                - needsHandoff=true 的结论会在 runtime 收口阶段触发人工介入通知，因此你要把 handoff 作为真实控制信号，而不是礼貌措辞
                - summary 必须直接回答用户当前故障现象对应的最终结论，不要把“先查什么、再查什么、推荐哪个 specialist/skill”写成对用户的主输出
                - rootCause 只写当前能够成立的根因判断；如果尚未锁定，就留空，不要用教程式话术替代结论
                - 如果 prompt 中出现 historical_experience_matches 或 queryMemoryFiles 返回结果，只能把它们写成历史经验提示或候选方向，不能把它们放进已确认事实
                - 最终面向用户的表达要明确区分已确认事实、历史经验提示、待验证假设
                """;
    }

    private String renderDynamicPromptSection(MainAgentPromptContext context) {
        StringBuilder builder = new StringBuilder();
        RuntimePromptState runtimeState = context.runtimeState();
        LoopPromptState loopState = context.loopState();
        BudgetPromptState budgetState = context.budgetState();
        KnowledgePromptState knowledgeState = context.knowledgeState();
        ReplayPromptState replayState = context.replayState();
        appendXmlBlock(builder, "runtime_state", orderedMap(
                "task_id", runtimeState.taskId(),
                "session_id", runtimeState.sessionId(),
                "prompt_mode", runtimeState.promptMode(),
                "turn_index", Integer.toString(runtimeState.turnIndex()),
                "max_turns", Integer.toString(runtimeState.maxTurns()),
                "capability_view_version", runtimeState.capabilityViewVersion(),
                "read_only", Boolean.toString(runtimeState.readOnly())
        ));
        appendXmlBlock(builder, "loop_state", orderedMap(
                "last_action", loopState.lastAction(),
                "last_observation_summary", loopState.lastObservationSummary(),
                "lane_guidance_summary", loopState.laneGuidanceSummary(),
                "pending_approval", Boolean.toString(loopState.pendingApproval()),
                "pending_remote_event_cursor", loopState.pendingRemoteEventCursor(),
                "minimal_context_mode", Boolean.toString(loopState.minimalContextMode()),
                "pending_action_count", Integer.toString(loopState.pendingActionCount()),
                "evidence_sufficient", Boolean.toString(loopState.evidenceSufficient()),
                "final_answer_draft", loopState.finalAnswerDraft(),
                "critic_observation", loopState.finalAnswerCriticism()
        ));
        appendXmlBlock(builder, "budget_state", orderedMap(
                "used_budget_tokens", Integer.toString(budgetState.usedBudgetTokens()),
                "max_budget_tokens", Integer.toString(budgetState.maxBudgetTokens()),
                "budget_status", budgetState.budgetStatus(),
                "compaction_level", budgetState.compactionLevel()
        ));
        appendCdataBlock(builder, "memory_snapshot", knowledgeState.memorySummary());
        appendCdataBlock(builder, "rag_evidence", knowledgeState.ragEvidenceSummary());
        appendCdataBlock(builder, "runtime_replay", replayState.runtimeReplaySummary());
        appendCdataBlock(builder, "event_replay", replayState.eventReplaySummary());
        appendCdataBlock(builder, "approval_result_replay", replayState.approvalReplaySummary());
        appendCdataBlock(builder, "critic_observation", loopState.finalAnswerCriticism());
        appendCdataBlock(builder, "skill_catalog", skillService.buildSkillCatalogPrompt("main-agent"));
        appendCdataBlock(builder, "capability_view", context.capabilitySnapshotSummary());
        appendCdataBlock(builder, "mcp_prompt_injections", mcpPromptInjectionAssembler.renderPromptSection());

        StringJoiner outputRules = new StringJoiner("\n");
        if (context.readOnly()) {
            outputRules.add("- 当前会话处于只读模式，不要选择任何需要写入或破坏性执行的动作。");
        }
        if (context.evidenceSufficient()) {
            outputRules.add("- 当前 evidence coverage 已达到可输出最终结论的阈值。");
        } else {
            outputRules.add("- 当前 evidence coverage 仍未闭环，如果还有必要动作缺口，不要过早结束。");
        }
        if (context.minimalContextMode()) {
            outputRules.add("- 当前已进入最小上下文模式，优先读取最近 observation 与关键证据，不要展开无关长篇复述。");
        }
        appendCdataBlock(builder, "output_guardrails", outputRules.toString());
        return builder.toString().trim();
    }

    private String renderRoutingUserPrompt(String userQuestion, MainAgentPromptContext context) {
        return renderUserPrompt(
                renderCommonUserContextXml(userQuestion, context, null),
                """
                        请只输出 JSON，格式为：{"routedAgents":["app"]}。
                        如果不确定，请输出 {"routedAgents":["all"]}。
                        """
        );
    }

    private String renderActionSelectionUserPrompt(String userQuestion, MainAgentPromptContext context) {
        return renderUserPrompt(
                renderCommonUserContextXml(userQuestion, context, null),
                """
                        请只输出一个 ReactAction JSON，不要输出 Markdown，不要输出解释文字。
                        如果需要更多证据，只能选择一个动作。
                        如果当前请求与某个 skill 的使用场景高度匹配，且 recommended_skills 非空，应优先考虑 RUN_SKILL_STEP，并把 actionName 写成 loadSkillContext。
                        当前默认正式主线不启用 specialist；如果 capability view 没有暴露 specialist，就不要生成 CALL_LOCAL_SPECIALIST / CALL_REMOTE_SPECIALIST。
                        如果考虑 queryMemoryFiles，先确认当前证据仍不足，且它只用于补充历史经验提示，不直接作为最终事实依据。
                        输出 Action JSON 后立即停止，不要继续生成。
                        如果证据已经充分，输出 {"actionType":"RETURN_FINAL","actionName":"return_final","target":"final-answer","arguments":{},"reason":"evidence sufficient","requiresApproval":false}。
                        """
        );
    }

    private String renderFinalAnswerUserPrompt(String userQuestion,
                                               String specialistResults,
                                               MainAgentPromptContext context) {
        return renderUserPrompt(
                renderCommonUserContextXml(userQuestion, context, specialistResults),
                """
                        请你作为主智能体继续推理，并且只输出一个 FinalAnswer JSON。
                        如果当前必要动作都已返回结果且关键证据充分，则输出 grounded final answer。
                        如果关键证据仍不充分，则 needsHandoff 必须为 true，rootCause 可以留空，但 nextActions 不能为空。
                        如果已经提供 final_answer_draft 或 critic_observation，先吸收批评，再重新生成新的 FinalAnswer JSON。
                        如果 critic 指出证据不足、引用错误或结论过强，不要原样重复旧 draft。
                        summary 面向用户展示，必须直接给出最终结论，不要写“建议先排查”“推荐调用”“需要结合方法论”等过程性表述，也不要向用户追问下一步怎么做。
                        rootCause 面向用户展示时只承载根因判断；如果当前只能确认到故障层级而无法锁定根因，rootCause 留空即可。
                        evidence 中每一项都必须包含 sourceType、sourceId、summary 三个字段。
                        输出格式固定为：
                        {
                          "summary":"当前结账失败更接近支付网关超时与回执等待，主要异常集中在 payment-gateway 链路。",
                          "evidence":[
                            {
                              "sourceType":"EVENT_REPLAY",
                              "sourceId":"action-1",
                              "summary":"queryLogs 返回 payment-gateway 504、重试耗尽和回执未到的日志"
                            },
                            {
                              "sourceType":"EVENT_REPLAY",
                              "sourceId":"action-2",
                              "summary":"queryPrometheusMetrics 显示 payment gateway 重试耗尽和 pending request 同时升高"
                            }
                          ],
                          "rootCause":"支付网关抖动导致 payment-service 重试耗尽，前端持续等待支付回执。",
                          "nextActions":["核查 payment-gateway 上游状态与错误码","观察重试耗尽和 pending request 是否回落","确认是否需要临时降级支付结果轮询"],
                          "confidence":0.82,
                          "needsHandoff":false
                        }

                        合法样例：
                        {
                          "summary":"当前只能确认结账失败发生在支付链路，根因仍需更多网关和回执证据。",
                          "evidence":[
                            {
                              "sourceType":"EVENT_REPLAY",
                              "sourceId":"action-2",
                              "summary":"queryLogs 只显示零散 504 和 timeout，traceId 关联还不完整"
                            }
                          ],
                          "rootCause":"",
                          "nextActions":["补查 payment-gateway 重试耗尽与 pending request","确认是否存在外部回执延迟","补查同一 traceId 的支付链路日志"],
                          "confidence":0.46,
                          "needsHandoff":true
                        }
                        {
                          "summary":"凌晨窗口出现零散下单失败，但日志与指标只形成短时尖峰，且 payment/product 两条链路都留下了不完整证据，当前无法高置信度锁定单一根因。",
                          "evidence":[
                            {
                              "sourceType":"EVENT_REPLAY",
                              "sourceId":"action-5",
                              "summary":"queryLogs 只返回零散 timeout 与慢查询样本，traceId 不完整"
                            },
                            {
                              "sourceType":"EVENT_REPLAY",
                              "sourceId":"action-6",
                              "summary":"queryPrometheusMetrics 显示错误率与延迟仅短时尖峰后回落"
                            }
                          ],
                          "rootCause":"",
                          "nextActions":["通知值班同学复核凌晨窗口变更","补查完整 trace 与发布记录","确认 payment 和 product 两条链路哪条先异常"],
                          "confidence":0.38,
                          "needsHandoff":true
                        }

                        非法样例：
                        - 证据不足却输出 {"summary":"已确认根因","evidence":[],"rootCause":"支付网关故障","nextActions":[],"confidence":0.90,"needsHandoff":false}
                        - evidence 中缺少 sourceType/sourceId/summary 任一字段
                        - JSON 后再附加解释文字、Markdown 标题或额外代码块
                        """
        );
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private String renderCommonUserContextXml(String userQuestion,
                                              MainAgentPromptContext context,
                                              String specialistResults) {
        StringBuilder builder = new StringBuilder();
        LoopPromptState loopState = context.loopState();
        BudgetPromptState budgetState = context.budgetState();
        builder.append("<user_context>\n");
        appendXmlBlock(builder, "task_context", orderedMap(
                "user_question", userQuestion,
                "evidence_summary", loopState.evidenceSummary(),
                "pending_action_count", Integer.toString(loopState.pendingActionCount()),
                "evidence_sufficient", Boolean.toString(loopState.evidenceSufficient())
        ), 1);
        appendCdataBlock(builder, "recommended_skills", buildRecommendedSkills(userQuestion, loopState), 1);
        appendXmlBlock(builder, "turn_state", orderedMap(
                "last_action", loopState.lastAction(),
                "last_observation_summary", loopState.lastObservationSummary(),
                "lane_guidance_summary", loopState.laneGuidanceSummary(),
                "pending_approval", Boolean.toString(loopState.pendingApproval()),
                "pending_remote_event_cursor", loopState.pendingRemoteEventCursor(),
                "minimal_context_mode", Boolean.toString(loopState.minimalContextMode()),
                "budget_status", budgetState.budgetStatus(),
                "compaction_level", budgetState.compactionLevel()
        ), 1);
        if (specialistResults != null && !specialistResults.isBlank()) {
            appendCdataBlock(builder, "specialist_observations", specialistResults, 1);
        }
        if (loopState.finalAnswerDraft() != null && !loopState.finalAnswerDraft().isBlank()) {
            appendCdataBlock(builder, "final_answer_draft", loopState.finalAnswerDraft(), 1);
        }
        if (loopState.finalAnswerCriticism() != null && !loopState.finalAnswerCriticism().isBlank()) {
            appendCdataBlock(builder, "critic_observation", loopState.finalAnswerCriticism(), 1);
        }
        builder.append("</user_context>");
        return builder.toString();
    }

    private String renderUserPrompt(String userContextXml, String outputContract) {
        return userContextXml.trim()
                + "\n\n<response_contract><![CDATA[\n"
                + safeText(outputContract).trim()
                + "\n]]></response_contract>";
    }

    private String buildRecommendedSkills(String userQuestion, LoopPromptState loopState) {
        if (loopState != null && shouldSuppressSkillRecommendation(loopState.evidenceSummary())) {
            return "";
        }
        String evidenceSummary = loopState == null ? "" : loopState.evidenceSummary();
        List<String> recommendedIds = skillIntentAdvisor.recommendSkillIds(userQuestion, evidenceSummary);
        if (recommendedIds.isEmpty()) {
            return "";
        }
        return recommendedIds.stream()
                .map(skillService::findSkill)
                .filter(java.util.Objects::nonNull)
                .map(skill -> "- %s | description=%s".formatted(
                        skill.id(),
                        safeText(skill.description())
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private boolean shouldSuppressSkillRecommendation(String evidenceSummary) {
        String normalized = safeText(evidenceSummary);
        if (normalized.isBlank()) {
            return false;
        }
        int successfulFactualObservations = extractSummaryCount(normalized, "successfulFactualObservations=");
        int planningObservations = extractSummaryCount(normalized, "planningObservations=");
        return successfulFactualObservations == 0 && planningObservations > 0;
    }

    private int extractSummaryCount(String summary, String key) {
        String normalized = safeText(summary);
        int start = normalized.indexOf(key);
        if (start < 0) {
            return 0;
        }
        int valueStart = start + key.length();
        int valueEnd = valueStart;
        while (valueEnd < normalized.length() && Character.isDigit(normalized.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd <= valueStart) {
            return 0;
        }
        try {
            return Integer.parseInt(normalized.substring(valueStart, valueEnd));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String extractUserContextXml(String renderedUserPrompt) {
        String prompt = safeText(renderedUserPrompt).trim();
        int responseContractStart = prompt.indexOf("\n\n<response_contract>");
        if (responseContractStart == -1) {
            return prompt;
        }
        return prompt.substring(0, responseContractStart).trim();
    }

    private String extractResponseContract(String renderedUserPrompt) {
        String prompt = safeText(renderedUserPrompt).trim();
        int cdataStart = prompt.indexOf("<response_contract><![CDATA[");
        int cdataEnd = prompt.indexOf("]]></response_contract>");
        if (cdataStart == -1 || cdataEnd == -1 || cdataEnd <= cdataStart) {
            return "";
        }
        String prefix = "<response_contract><![CDATA[";
        return prompt.substring(cdataStart + prefix.length(), cdataEnd).trim();
    }

    private void appendXmlBlock(StringBuilder builder, String blockName, Map<String, String> fields) {
        appendXmlBlock(builder, blockName, fields, 0);
    }

    private void appendXmlBlock(StringBuilder builder, String blockName, Map<String, String> fields, int indentLevel) {
        String indent = indent(indentLevel);
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(indent).append("<").append(blockName).append(">\n");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            builder.append(indent(indentLevel + 1))
                    .append("<").append(entry.getKey()).append(">")
                    .append(xmlEscape(entry.getValue()))
                    .append("</").append(entry.getKey()).append(">\n");
        }
        builder.append(indent).append("</").append(blockName).append(">");
    }

    private void appendCdataBlock(StringBuilder builder, String blockName, String content) {
        appendCdataBlock(builder, blockName, content, 0);
    }

    private void appendCdataBlock(StringBuilder builder, String blockName, String content, int indentLevel) {
        String normalizedContent = safeText(content).trim();
        if (normalizedContent.isBlank()) {
            return;
        }
        String indent = indent(indentLevel);
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(indent)
                .append("<").append(blockName).append("><![CDATA[\n")
                .append(normalizedContent.replace("]]>", "]]]]><![CDATA[>"))
                .append("\n]]></").append(blockName).append(">");
    }

    private Map<String, String> orderedMap(String... entries) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            fields.put(entries[i], safeText(entries[i + 1]));
        }
        return fields;
    }

    private String indent(int level) {
        return "  ".repeat(Math.max(0, level));
    }

    private String xmlEscape(String value) {
        return safeText(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public record PromptBundle(
            String systemPrompt,
            String userPrompt,
            String stablePrefix,
            String dynamicPromptSection,
            String promptMode
    ) {
        public PromptEnvelope toPromptEnvelope() {
            return PromptEnvelope.systemAndUser(systemPrompt, userPrompt, promptMode);
        }
    }

    public record RenderedPrompt(
            String stableSystemPrompt,
            String dynamicSystemSection,
            String userContextXml,
            String responseContract,
            String promptMode
    ) {
        public String systemPrompt() {
            return safeConcat(stableSystemPrompt, REACT_PROMPT_DYNAMIC_BOUNDARY, dynamicSystemSection);
        }

        public String userPrompt() {
            String userContext = normalize(userContextXml);
            String contract = normalize(responseContract);
            if (contract == null) {
                return userContext == null ? "" : userContext;
            }
            if (userContext == null) {
                return "<response_contract><![CDATA[\n" + contract + "\n]]></response_contract>";
            }
            return userContext + "\n\n<response_contract><![CDATA[\n" + contract + "\n]]></response_contract>";
        }

        public PromptEnvelope toPromptEnvelope() {
            return PromptEnvelope.systemAndUser(systemPrompt(), userPrompt(), promptMode);
        }

        public PromptBundle toPromptBundle() {
            return new PromptBundle(systemPrompt(), userPrompt(), stableSystemPrompt, dynamicSystemSection, promptMode);
        }

        private static String safeConcat(String stablePrefix, String boundary, String dynamicSection) {
            String stable = normalize(stablePrefix);
            String dynamic = normalize(dynamicSection);
            if (stable == null) {
                return dynamic == null ? "" : dynamic;
            }
            if (dynamic == null) {
                return stable;
            }
            return stable + "\n\n" + boundary + "\n\n" + dynamic;
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public record RuntimePromptState(
            String taskId,
            String sessionId,
            int turnIndex,
            int maxTurns,
            String capabilityViewVersion,
            String promptMode,
            boolean readOnly
    ) {
    }

    public record LoopPromptState(
            String lastAction,
            String lastObservationSummary,
            String laneGuidanceSummary,
            boolean pendingApproval,
            String pendingRemoteEventCursor,
            boolean minimalContextMode,
            boolean evidenceSufficient,
            int pendingActionCount,
            String evidenceSummary,
            String finalAnswerDraft,
            String finalAnswerCriticism
    ) {
    }

    public record BudgetPromptState(
            int maxBudgetTokens,
            int usedBudgetTokens,
            String budgetStatus,
            String compactionLevel
    ) {
    }

    public record KnowledgePromptState(
            String memorySummary,
            String ragEvidenceSummary
    ) {
    }

    public record ReplayPromptState(
            String runtimeReplaySummary,
            String eventReplaySummary,
            String approvalReplaySummary
    ) {
    }

    public record CapabilityPromptState(
            String capabilitySnapshotSummary
    ) {
    }

    public record MainAgentPromptContext(
            RuntimePromptState runtimeState,
            LoopPromptState loopState,
            BudgetPromptState budgetState,
            KnowledgePromptState knowledgeState,
            ReplayPromptState replayState,
            CapabilityPromptState capabilityState
    ) {
        public static MainAgentPromptContext fromTurnState(ReactTurnState turnState,
                                                           boolean readOnly,
                                                           String evidenceSummary,
                                                           boolean evidenceSufficient,
                                                           int pendingActionCount,
                                                           String promptMode) {
            if (turnState == null) {
                return new MainAgentPromptContext(
                        new RuntimePromptState(null, null, 0, 0, null, promptMode, readOnly),
                        new LoopPromptState(
                                null,
                                null,
                                null,
                                false,
                                null,
                                false,
                                evidenceSufficient,
                                pendingActionCount,
                                evidenceSummary,
                                null,
                                null
                        ),
                        new BudgetPromptState(0, 0, TokenBudgetManager.BudgetStatus.NORMAL.name(), "none"),
                        new KnowledgePromptState(null, null),
                        new ReplayPromptState(null, null, null),
                        new CapabilityPromptState(null)
                );
            }
            return new MainAgentPromptContext(
                    new RuntimePromptState(
                            turnState.taskId(),
                            turnState.sessionId(),
                            turnState.turnIndex(),
                            turnState.maxTurns(),
                            turnState.capabilityViewVersion(),
                            promptMode,
                            readOnly
                    ),
                    new LoopPromptState(
                            turnState.lastAction(),
                            turnState.lastObservationSummary(),
                            turnState.laneGuidanceSummary(),
                            turnState.pendingApproval(),
                            turnState.pendingRemoteEventCursor(),
                            turnState.minimalContextMode(),
                            evidenceSufficient,
                            pendingActionCount,
                            evidenceSummary,
                            turnState.finalAnswerDraft(),
                            turnState.finalAnswerCriticism()
                    ),
                    new BudgetPromptState(
                            turnState.maxBudgetTokens(),
                            turnState.usedBudgetTokens(),
                            turnState.budgetStatus().name(),
                            turnState.compactionLevel()
                    ),
                    new KnowledgePromptState(null, null),
                    new ReplayPromptState(null, null, null),
                    new CapabilityPromptState(null)
            );
        }

        public static MainAgentPromptContext fromAssemblySources(SessionAssembler.TurnStatePromptProjection turnStateProjection,
                                                                 SessionAssembler.KnowledgePromptProjection knowledgeProjection,
                                                                 SessionAssembler.ReplayPromptProjection replayProjection,
                                                                 SessionAssembler.CapabilitySnapshot capabilitySnapshot) {
            SessionAssembler.TurnStatePromptProjection turnState = turnStateProjection == null
                    ? SessionAssembler.TurnStatePromptProjection.fromTurnState(null, false, "", false, 0, null)
                    : turnStateProjection;
            SessionAssembler.KnowledgePromptProjection knowledge = knowledgeProjection == null
                    ? new SessionAssembler.KnowledgePromptProjection(null, null)
                    : knowledgeProjection;
            SessionAssembler.ReplayPromptProjection replay = replayProjection == null
                    ? new SessionAssembler.ReplayPromptProjection(null, null, null)
                    : replayProjection;
            SessionAssembler.CapabilitySnapshot capability = capabilitySnapshot == null
                    ? new SessionAssembler.CapabilitySnapshot(null, null, null)
                    : capabilitySnapshot;
            return new MainAgentPromptContext(
                    new RuntimePromptState(
                            turnState.taskId(),
                            turnState.sessionId(),
                            turnState.turnIndex(),
                            turnState.maxTurns(),
                            turnState.capabilityViewVersion(),
                            turnState.promptMode(),
                            turnState.readOnly()
                    ),
                    new LoopPromptState(
                            turnState.lastAction(),
                            turnState.lastObservationSummary(),
                            turnState.laneGuidanceSummary(),
                            turnState.pendingApproval(),
                            turnState.pendingRemoteEventCursor(),
                            turnState.minimalContextMode(),
                            turnState.evidenceSufficient(),
                            turnState.pendingActionCount(),
                            turnState.evidenceSummary(),
                            turnState.finalAnswerDraft(),
                            turnState.finalAnswerCriticism()
                    ),
                    new BudgetPromptState(
                            turnState.maxBudgetTokens(),
                            turnState.usedBudgetTokens(),
                            turnState.budgetStatus(),
                            turnState.compactionLevel()
                    ),
                    new KnowledgePromptState(
                            knowledge.memorySummary(),
                            knowledge.ragEvidenceSummary()
                    ),
                    new ReplayPromptState(
                            replay.runtimeReplaySummary(),
                            replay.eventReplaySummary(),
                            replay.approvalReplaySummary()
                    ),
                    new CapabilityPromptState(
                            capability.capabilitySnapshotSummary()
                    )
            );
        }

        public MainAgentPromptContext withFinalAnswerReview(String finalAnswerDraft,
                                                            String finalAnswerCriticism,
                                                            String promptMode) {
            return new MainAgentPromptContext(
                    new RuntimePromptState(
                            runtimeState.taskId(),
                            runtimeState.sessionId(),
                            runtimeState.turnIndex(),
                            runtimeState.maxTurns(),
                            runtimeState.capabilityViewVersion(),
                            promptMode,
                            runtimeState.readOnly()
                    ),
                    new LoopPromptState(
                            loopState.lastAction(),
                            loopState.lastObservationSummary(),
                            loopState.laneGuidanceSummary(),
                            loopState.pendingApproval(),
                            loopState.pendingRemoteEventCursor(),
                            loopState.minimalContextMode(),
                            loopState.evidenceSufficient(),
                            loopState.pendingActionCount(),
                            loopState.evidenceSummary(),
                            finalAnswerDraft,
                            finalAnswerCriticism
                    ),
                    budgetState,
                    knowledgeState,
                    replayState,
                    capabilityState
            );
        }

        public String taskId() { return runtimeState.taskId(); }
        public String sessionId() { return runtimeState.sessionId(); }
        public int turnIndex() { return runtimeState.turnIndex(); }
        public int maxTurns() { return runtimeState.maxTurns(); }
        public String capabilityViewVersion() { return runtimeState.capabilityViewVersion(); }
        public String promptMode() { return runtimeState.promptMode(); }
        public boolean readOnly() { return runtimeState.readOnly(); }
        public String lastAction() { return loopState.lastAction(); }
        public String lastObservationSummary() { return loopState.lastObservationSummary(); }
        public String laneGuidanceSummary() { return loopState.laneGuidanceSummary(); }
        public boolean pendingApproval() { return loopState.pendingApproval(); }
        public String pendingRemoteEventCursor() { return loopState.pendingRemoteEventCursor(); }
        public boolean minimalContextMode() { return loopState.minimalContextMode(); }
        public boolean evidenceSufficient() { return loopState.evidenceSufficient(); }
        public int pendingActionCount() { return loopState.pendingActionCount(); }
        public String evidenceSummary() { return loopState.evidenceSummary(); }
        public String finalAnswerDraft() { return loopState.finalAnswerDraft(); }
        public String finalAnswerCriticism() { return loopState.finalAnswerCriticism(); }
        public int maxBudgetTokens() { return budgetState.maxBudgetTokens(); }
        public int usedBudgetTokens() { return budgetState.usedBudgetTokens(); }
        public String budgetStatus() { return budgetState.budgetStatus(); }
        public String compactionLevel() { return budgetState.compactionLevel(); }
        public String memorySummary() { return knowledgeState.memorySummary(); }
        public String ragEvidenceSummary() { return knowledgeState.ragEvidenceSummary(); }
        public String runtimeReplaySummary() { return replayState.runtimeReplaySummary(); }
        public String eventReplaySummary() { return replayState.eventReplaySummary(); }
        public String approvalReplaySummary() { return replayState.approvalReplaySummary(); }
        public String capabilitySnapshotSummary() { return capabilityState.capabilitySnapshotSummary(); }
    }
}

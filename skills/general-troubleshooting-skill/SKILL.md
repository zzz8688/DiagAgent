---
name: general-troubleshooting-skill
description: 面向常见故障模式的通用排障技能。用户问题表现为超时、连接失败、依赖报错、转圈、卡住或性能退化，且需要先做模式匹配或排障方法归类时应优先使用；适合帮助模型先判断更像哪类故障，再决定日志、指标和知识的收集路径。
---
# 通用排障方法论技能

## 目标

在常见故障模式下，给主智能体补充模式匹配方法、关键词和证据面提示，再决定是否需要查询案例知识，不把方法论继续放到知识库里。
本 skill 不负责默认 specialist 分诊；当前正式主线默认由主智能体自行完成模式判断与取证推进。

## 执行步骤

1. 先根据用户问题和初步日志现象判断最可能的故障模式。
   - `Connection timeout` + 数据库关键词 -> 数据库连接超时模式
   - `Gateway timeout` + `payment` -> 支付网关超时模式
   - `503` + `payment` -> 支付服务不可用或外部依赖失败模式
   - `internal dependency error` / `Failed to call` -> 内部依赖错误模式
2. 再通过 `queryLogs` 确认模式是否成立，并决定是否补充 traceId/requestId 线索或 `prometheus-mcp/queryPrometheusMetrics`。
3. 只有需要案例、规则或处理手册时，才调用 `queryKnowledgeBase` 查询对应知识。
4. 当需要整理候选模式时，再读取 `resources/pattern-matrix.md`，把它当作方法样例而不是事实来源。

## 输出要求

- 区分事实、推断和待确认项。
- 如果证据不足，只给出候选根因，不要写成确定事实。

## 渐进式披露

- 默认只把本技能正文当作主智能体的补充方法资产，不要把它当成主流程固定工作流。
- 如果要补充模式映射示例，再读取 `resources/pattern-matrix.md`。
- `scripts/match-incident-pattern.ps1` 是运行时资产，不直接展开给模型。

## 知识使用原则

- 故障排查方法论本身由本 skill 提供，不通过知识库再次查询
- 只有需要案例、规则或手册时，才通过 `queryKnowledgeBase` 按主题检索相关知识
- 不在 skill 中写死具体 `KB-xxx` 文件依赖
- 本 skill 只负责给主智能体补充“该补哪类关键词、哪类证据面”的方法提示

## 依赖关系提示

- `frontend -> order-service -> product-service -> inventory-db`
- `order-service -> payment-service -> external-payment-gateway`
- 如果上游报内部依赖错误，优先顺着调用链向下游继续追证

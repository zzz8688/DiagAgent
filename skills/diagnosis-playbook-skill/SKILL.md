---
name: diagnosis-playbook-skill
description: 面向通用故障诊断的辅助剧本。用户明确要求按模板、检查清单、固定格式或“怎么排查”来推进时应优先使用；也适用于已经拿到部分运行证据、需要补充关键词、证据清单和输出结构建议的场景。不负责接管 MainAgent 的主循环编排，也不替代事实取证。
---
# 通用故障诊断剧本技能

## 目标

在根因未知时，提供场景化排查提示，帮助模型补充关键词、参数、证据清单和输出结构。
本 skill 不是主循环编排器，不负责决定是否先查日志、先查指标、何时 RETURN_FINAL、何时 handoff，也不覆盖 runtime 的证据收口规则。
当前默认正式主线只保留 `main agent + critic`；本 skill 面向主智能体提供辅助方法资产，不面向默认 specialist 流水线。

## 适用时机

- 用户明确要求“按固定诊断模板/检查清单来排查”
- 当前已经拿到基础 factual evidence，需要补充下一步关键词、参数或证据清单
- 需要统一整理 `事实 / 推断 / 建议 / 待确认` 的输出结构

## 不适用时机

- 当前还没有任何 factual evidence，或 recent observation 主要是 planning / control 内容
- 只是想让模型“先固定流程”，但实际上更需要直接去调用日志、指标、trace、replay 等事实工具
- 想用本 skill 替代 runtime 的收口判定、critic 响应或最终答案治理

## 可提供的辅助内容

1. 根据用户问题补充更聚焦的检索关键词或参数建议。
   - 结账失败 / 订单失败 -> 可补充 `checkout payment failed`
   - 支付卡顿 / 支付超时 / 支付失败 -> 可补充 `payment gateway timeout`
   - 商品加载慢 / 商品无法加载 -> 可补充 `product database timeout slow query`
   - 数据库连接不上 -> 可补充 `database connection timeout`
   - 服务不可用 -> 可补充 `service unavailable error`
   - 系统健康检查 -> 优先结合 `service-health-check-skill`
2. 提醒当前问题还缺哪些证据面，例如日志、`prometheus-mcp/queryPrometheusMetrics`、traceId/requestId、外部依赖健康、知识规则。
3. 在已经有基础证据后，帮助整理更清晰的输出结构。
4. 当需要补充结构化清单时，再读取 `resources/evidence-checklist.md`。

## 日志与证据判读

- 日志出现 `Payment declined` -> 说明支付被拒绝，需继续核对支付链路和业务规则
- 日志出现 `503` -> 优先怀疑服务不可用或下游依赖异常
- 日志出现 `Gateway timeout` -> 优先怀疑网关或外部依赖超时
- 日志出现 `Connection timeout` 且包含 `inventory` / `db` -> 优先怀疑数据库或连接池问题
- 日志出现 `Slow query` -> 优先怀疑数据库慢查询或索引缺失

## 失败分支

- 如果知识库命中为空，保留基于运行证据的判断，但不要把案例经验写成确定事实
- 如果日志为空或没有 Trace ID，说明证据不足，应继续扩大时间范围、调整关键词或转向其他事实证据源
- 即使脚本资产已经接入 runtime，也不要把“脚本已执行”直接当成事实证据；仍需要继续核对日志、指标、trace 或其他 observation。

## 渐进式披露

- 先阅读本文件，不要默认展开 `resources/` 下的所有静态文档。
- 只有在需要补充输出结构时，才调用 `querySkillResource` 读取 `resources/evidence-checklist.md`。
- `resources/final-answer-runtime-templates.md` 是 runtime 消费的最终答案渲染模板资产，不是默认展开给模型的正文。
- `scripts/collect-runtime-evidence.ps1` 属于执行资产，不作为提示词正文给模型展开。

## 知识使用原则

- 主循环编排由 MainAgent 的 system prompt 和 runtime contract 决定，本 skill 只提供辅助模板
- 只有需要案例、规则或手册时，才通过 `queryKnowledgeBase` 按主题检索相关知识
- 不在 skill 中写死具体 `KB-xxx` 文件依赖
- 本 skill 只负责提供辅助模板，不重复保存知识库正文内容

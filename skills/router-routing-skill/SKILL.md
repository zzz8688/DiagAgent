---
name: router-routing-skill
description: 面向实验性路由场景的分类技能。用户明确要求做 app、db、network、all 路由分类，或需要补充领域边界样例、解释为什么某类问题应归到某个 lane 时应优先使用；用于补充 routing examples 和表达方式，而不是承载默认主线 contract。
---
# 路由分类技能

## 目标

根据用户问题描述，帮助模型理解路由样例与边界表达方式。正式路由 contract 由主流程 prompt / router policy 持有，不由本 skill 兜底；当前 specialist 默认关闭，本 skill 也不再服务默认主线分诊。

## 执行要求

1. 先把用户问题概括成 1 句领域判断问题。
2. 先依赖主流程 prompt 已经注入的正式路由规则完成判断。
3. 如果边界仍然模糊，再读取 `resources/routing-examples.md` 查看样例表达方式。
4. 本 skill 只用于补充 examples 和表达方式，不重复承载主流程默认一定生效的路由 contract。

## 渐进式披露

- 本文件不再保存正式路由规则，只保留 routing examples 的入口说明。
- 只有在路由边界模糊时，才读取 `resources/routing-examples.md` 里的例子。
- `scripts/classify-route.ps1` 是后续运行时执行资产，不直接作为模型上下文。

## 使用边界

- 正式 `app / db / network / all` 路由规则已经迁回主流程 prompt / router policy。
- 本 skill 现在只保留：
  - routing examples
  - 局部补充说明
  - 按需展开的资源
- 如果主流程没有显式请求，不要把本 skill 当成默认路由 contract。

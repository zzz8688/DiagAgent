# DiagAgent 微服务诊断智能体平台

`DiagAgent` 是一个面向微服务故障定位场景的 Java / Spring 智能体诊断平台，实现重点是把故障诊断过程收敛为一条可调度、可回放、可审计、可治理的运行主链。

## 当前定位

- 一个自带 `runtime` 控制面的诊断代理平台，而不是单纯的对话应用
- 一个同时接入本地工具、`MCP` 工具、最小 `A2A` 协作能力的工作台式系统
- 一个把 `RAG`、长期记忆、审批/沙箱、审计、回放、Redis 运行态协调纳入统一主链的工程化平台
- 一个仍然保留 `langchain4j` 作为 provider boundary 的项目，但平台主架构不再由框架反向定义

## 当前能力概览


- **统一运行时主链**：`RuntimeFacade -> QueryRuntime -> ReactLoopExecutor -> ReactActionExecutor -> RuntimeReplayService`
- **动作顺序显式约束**：系统提示词已固定“先事实证据，再知识库，最后历史经验”的默认顺序；`skill` 只提供方法约束，不替代事实取证
- **上下文形成链**：`RAG + rolling summary + durable memory recall + capability snapshot` 会统一进入 prompt 装配
- **长期记忆治理**：已形成 `transcript -> rolling summary -> memory candidates -> managed memory files` 主链，并提供候选整理和文件治理接口
- **检索评测与重建**：已提供 RAG 参数快照、评测接口、全量重建接口；最新实现已把知识索引全量重建升级为 collection 级硬清空 + 重建后校验
- **统一治理主链**：高风险执行通过沙箱、审批和审计统一收口，并提供配置与审计查询接口
- **协议接入**：已提供 `MCP` registry / capability manifest / capability overview，以及最小 `A2A` card、invoke、task event 调试接口
- **运行态协调**：Redis 不只做缓存，还承担 session/task 锁、限流、生成中状态、热点预热和运行态面板
- **前端工作台**：诊断、知识库、长期记忆、技能库、工具页、Redis、MCP、A2A、RAG、沙箱、系统状态等页面已接到真实后端接口

## 当前架构

### 1. Runtime 控制面

当前主链由以下组件承接：

- `RuntimeFacade`：统一同步执行、流式执行、取消与任务读取入口
- `QueryRuntime`：创建 task、初始化 `ReactTurnState`、发布 turn 级事件
- `ReactLoopExecutor`：推进 `Thought -> Action -> Observation -> Final Answer` 循环
- `ReactActionExecutor`：执行本地工具、MCP 工具、skill 注入、审批等待等动作
- `RuntimeReplayService`：对外输出 timeline、audit export 和结构化回放结果

这条链的目标是把诊断过程中的控制信号、执行动作、停止原因和回放事实收进同一个 runtime contract。

### 2. 证据与上下文

当前主链默认遵循下面的顺序：

1. 当前事实证据：日志、指标、trace、replay、approval、sandbox 等 observation
2. 当前知识库 / RAG：补规则、案例和排查思路
3. 历史经验：只在证据不足时参考 durable memory

对应到实现上：

- `KnowledgeBaseService` 负责混合检索、重排、参数快照和搜索报告
- `KnowledgePromptProjectionLoader` 负责把知识命中与历史经验命中装配回 prompt
- `SummaryChatMemory` 负责 rolling summary
- `MemoryMaintenanceAgent` 负责把候选整理为可治理的长期记忆文件

### 3. 治理与观测

当前平台已经把“能执行”和“能审计”放在同一条链上：

- `SandboxController` 提供配置与审计查询接口
- `SystemController` 提供系统健康、拓扑、Redis overview 与预热接口
- 运行时事件通过 `ExecutionEventBus` 发布，并由回放与工作台统一消费

### 4. 协议层

当前协议层按两条线分开：

- `MCP`：接外部 `tool / resource / prompt` 能力，统一进入 registry、capability snapshot 和 workbench
- `A2A`：接远程 agent card、最小 invoke、task event 调试与状态桥接

当前可见接口包括：

- `GET /api/protocol/capabilities/manifest`
- `GET /api/protocol/capabilities/overview`
- `GET /api/protocol/mcp`
- `GET /api/protocol/a2a`
- `POST /api/a2a/agents/{agentId}/messages`
- `POST /api/mcp/server`

## 工作台页面

前端工作台当前包含：

- `/diagnosis`：智能诊断
- `/history`：收藏夹
- `/knowledge`：知识库
- `/memory`：长期记忆
- `/skills`：技能库
- `/tools`：工具页
- `/redis`：Redis 运行态
- `/protocol`：MCP 协议
- `/a2a`：A2A 协议
- `/rag`：RAG 评测
- `/sandbox`：沙箱与审计
- `/system`：系统状态

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- Python 3.7+
- Docker Desktop
- DashScope `API_Key`

### 启动基础设施

```powershell
docker-compose up -d mysql mongodb redis etcd minio milvus nacos zookeeper kafka
```

如果要联调指标侧 `MCP`：

```powershell
docker-compose up -d mock-metrics-service prometheus
```

### 初始化 Nacos 提示词配置

```powershell
Start-Sleep -Seconds 45
python init/init_nacos_prompts.py
```

### 启动模拟日志流

```powershell
python init/mock_log_kafka_producer.py --bootstrap-servers 127.0.0.1:9092 --topic log-ingest
```

### 启动后端

```powershell
$env:API_Key = "your DashScope key"
.\mvnw.cmd spring-boot:run
```

### 启动前端

```powershell
cd diag-agent-ui
npm install
npm run dev
```

默认入口：

- 前端登录页：`http://localhost:5173/login`
- 诊断工作台：`http://localhost:5173/diagnosis`
- Nacos 控制台：`http://localhost:8848/nacos`

## 技术点



- **Runtime 工程化**：不是直接让模型“一次性回答”，而是让模型在受控循环里做动作选择、取证、回放和收口
- **协议接入能力**：`MCP` 与 `A2A` 已分线，且都能在工作台和接口层看到真实状态
- **RAG 工程治理**：检索参数、评测结果、重建接口和重建校验都已接入
- **长期记忆治理**：长期记忆已经从“摘要功能”推进到“候选 -> 治理 -> 文件”的闭环
- **治理与审计**：沙箱、审批、审计和运行时事件已进入统一可观测面
- **Redis 运行态协调**：缓存、锁、限流、热点和面板不再只是概念，已接到真实接口

## 技术栈

- **后端**：Spring Boot 3.2、Java 17
- **推理接入**：LangChain4j 1.0.0-beta3（当前作为 provider boundary）
- **对话模型**：DashScope / Qwen 系列
- **向量模型**：`text-embedding-v4`
- **向量存储**：Milvus
- **关系与文档存储**：MySQL、MongoDB
- **缓存与协调**：Redis
- **消息**：Kafka
- **配置中心**：Nacos
- **前端**：React 19、TypeScript、Vite、React Router

## 目录概览

```text
DiagAgent/
├── src/main/java/io/github/zzz8688/diagagent/
│   ├── agent/          # runtime、ReAct loop、prompt assembly、replay
│   ├── controller/     # memory、sandbox、system、protocol、knowledge 等接口
│   ├── mcp/            # MCP client、registry、snapshot、server export
│   ├── a2a/            # A2A card、registry、invoke、task event bridge
│   ├── sandbox/        # permission、approval、execution、audit
│   ├── service/        # Redis、memory、notification 等服务
│   ├── store/          # knowledge、memory、embedding store 相关实现
│   └── tools/          # 本地工具，如日志、知识库、拓扑、memory 查询
├── diag-agent-ui/      # React 工作台
├── knowledge/          # 知识库文档
├── memory/             # durable memory 文件
├── skills/             # 外置 skill 包
├── init/               # 初始化脚本与模拟数据脚本
├── docker-compose.yml
└── pom.xml
```

## 当前边界说明

- `skill` 是方法资产，不是事实证据
- `knowledge` 用于规则、案例、手册与专题知识，不替代当前日志和指标
- `memory` 是历史经验层，不直接等价于当前事实
- `MCP` 用于外部能力接入，`A2A` 用于远程 agent 协作，两条线已经分开维护
- `langchain4j` 当前保留在 provider boundary，不负责定义平台 runtime、protocol 或治理边界



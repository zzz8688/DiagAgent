# DiagAgent 智能故障诊断系统
本项目是一个由人工智能驱动的诊断智能体，旨在自动分析并定位分布式系统的故障根源。它利用大语言模型（LLM）来理解日志、指标、链路和拓扑数据，以提供全面的根本原因分析（RCA）。

## 技术栈
- **后端**: Spring Boot 3.2 + LangChain4j 1.0.0-beta3
- **LLM**: 通义千问 (对话推理)
- **Embedding**: 通义千问 text-embedding-v1 (向量检索)
- **向量存储**: Milvus
- **数据库**: MongoDB (对话记忆) + MySQL (诊断记录和会话消息)
- **前端**: Vue3 + Element Plus + Pinia

## 功能特性
### 智能诊断
- 实时流式诊断结果展示
- Markdown 格式渲染
- 常见问题快捷提问
- 一键复制诊断结果
- 保存诊断记录

### 会话管理
- 会话列表
- 会话消息独立存储（不包含系统消息、工具消息）
- 会话删除时同时清理关系型数据库和MongoDB数据

### 诊断历史
- 历史诊断记录列表
- 诊断详情查看
- 记录搜索和筛选
- 删除历史记录

### 知识库
- 知识库文档列表
- 文档内容预览
- 支持 MD 和 PDF 格式

## 项目结构

```
DiagAgent/
├── src/main/java/io/github/zzz8688/diagagent/
│   ├── agent/
│   ├── config/
│   ├── controller/
│   ├── entity/
│   ├── mapper/
│   ├── service/
│   ├── store/
│   └── tools/
├── src/main/resources/
│   ├── knowledge/
│   ├── application.properties
│   └── schema.sql
│   └── sre-agent-prompt.txt
│   └── static/
├── diag-agent-ui/
└── pom.xml
```

## 快速开始
### 环境要求
- JDK 17+
- Maven 3.8+
- Node.js 18+
- Docker (用于 MongoDB、MySQL、Milvus)

### 启动依赖服务
```bash
# MongoDB
docker run -d -p 27017:27017 --name mongodb ^
  -e MONGO_INITDB_ROOT_USERNAME=root ^
  -e MONGO_INITDB_ROOT_PASSWORD=1234 ^
  mongo:7.0

# MySQL
docker run -d -p 3306:3306 --name mysql ^
  -e MYSQL_ROOT_PASSWORD=1234 ^
  -e MYSQL_DATABASE=diagagent ^
  mysql:8.0

# Milvus
docker run -d -p 19530:19530 --name milvus ^
  milvusdb/milvus:latest
```

### 配置 API Key
```powershell
$env:API_Key = "您的DashScope密钥"
```

### 启动后端服务
```bash
mvn spring-boot:run
```

### 启动前端服务
```bash
cd diag-agent-ui
npm install
npm run dev
```

访问：http://localhost:5173




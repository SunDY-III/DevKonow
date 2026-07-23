<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot" alt="Spring Boot 3.2.5">
  <img src="https://img.shields.io/badge/JDK-21+LTS-important" alt="JDK 21">
  <img src="https://img.shields.io/badge/Virtual_Threads-enabled-success" alt="Virtual Threads">
  <img src="https://img.shields.io/badge/Qdrant-1.15.0-blueviolet" alt="Qdrant 1.15.0">
  <img src="https://img.shields.io/badge/Neo4j-Embedded-008CC1" alt="Neo4j Embedded">
  <img src="https://img.shields.io/badge/LangChain4j-0.36.2-purple" alt="LangChain4j 0.36.2">
  <img src="https://img.shields.io/badge/Tauri-2.x-FFC131?logo=tauri" alt="Tauri 2.x">
  <img src="https://img.shields.io/badge/Micrometer-Prometheus-orange" alt="Micrometer">
  <img src="https://img.shields.io/badge/Testcontainers-blue" alt="Testcontainers">
  <img src="https://img.shields.io/badge/Flyway-red" alt="Flyway">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License MIT">
</p>

<div align="center">
  <h1>DevKnow</h1>
  <p><strong>开发者的第二大脑 — 代码理解 · 文档 RAG · Agent 分析</strong></p>
  <p>面向个人开发者与中小团队的智能知识平台</p>
</div>

---

## 项目简介

DevKnow 是一个**生产级 Java RAG + Agent 平台**。上传代码仓库或文档，系统自动建立索引，支持自然语言问答、代码分析、变更影响分析。

功能覆盖 RAG 全链路（稠密向量+稀疏权重+关键词三路融合）、ReAct Agent 迭代检索、三层记忆系统、知识图谱增强，以及完整的企业级治理设施。

---

## 核心能力

### 🔍 RAG 检索管道

| 能力 | 实现 |
|------|------|
| **三路检索融合** | Qdrant 稠密向量 + LLM 驱动稀疏权重 + MySQL ngram 关键词 → RRF 融合 |
| **层级感知（L1~L5）** | LLM 自动分类问题所属知识层级，定向检索 |
| **角色感知** | 7 种角色（架构师/高级开发/一线/QA/运维/PM）自动调整搜索权重 |
| **HyDE** | LLM 生成假设文档再嵌入，缩小语义鸿沟 |
| **MMR 多样性去重** | 图感知 MMR，λ 值按层级自适应 |
| **Cross-encoder 重排** | LLM 评分 + 融合权重 |
| **图谱扩展** | Neo4j Embedded 多跳关联扩展 |
| **CRAG 纠错** | 检索质量评估 + 自动补搜 |
| **语义分块** | Markdown 结构解析 + Contextual Retrieval 描述生成 |
| **动态路由** | 每查询 fastModel 预测最优参数（TopK/HyDE/MMR 等） |

### 🤖 ReAct Agent 迭代检索

基于 LangChain4j `AiServices` + `@Tool` 的 Agent 系统：

- **自适应搜索**：多轮 Thought→Action→Observation 循环，信息不足时自动补搜
- **三个工具**：`search_code` / `search_doc` / `search_graph`
- **分级检索**：探索轮轻量（向量4+关键词4），确认轮全量（9步管道）
- **Observation 压缩**：工具返回结果自动压缩（ReNAct 模式），Token 减少 ~90%
- **经验缓存**：同类查询 SHA256 签名 → 跳过检索循环直接复用结果
- **三层记忆**：短期原文 + 中期结构化摘要 + 长期事实性记忆（Redis 持久化）

### 🛡️ 企业级治理

- **语义缓存**：余弦阈值 0.92，按来源文档联动失效
- **Token 审计**：异步落库 + Prometheus 指标 + 每日日报
- **敏感词过滤**：DFA Trie 树 O(n) 多模式匹配
- **熔断降级**：Resilience4j CircuitBreaker
- **双维度限流**：用户级 + 接口级独立配额
- **幻觉三关**：Chunk 过滤 + 事实验证 + 逐字引用追溯

### 📊 可观测性

- **Micrometer + Prometheus**：JVM / HTTP / RAG 管道 / Token 消耗 / 缓存命中率
- **Grafana 看板**：预构建全局仪表板
- **结构化日志**：JSON 格式 + correlationId 全链路追踪
- **依赖安全扫描**：Dependabot 自动更新 + OWASP Dependency-Check

---

## 快速开始

### 前置条件

- **JDK 21+**（虚拟线程）
- **Docker & Docker Compose**（中间件）
- Maven 3.8+ / Node.js 18+

### 启动（开发模式）

```bash
# 1. 配置 LLM（.env 文件）
cp .env.dev.example .env.dev

# 2. 启动中间件（开发模式低内存 ~3GB）
docker compose --env-file .env.dev up -d

# 3. 启动后端（Flyway 自动执行数据库迁移）
cd backend
mvn spring-boot:run

# 4. 启动前端
cd frontend
npm install && npm run dev
# 浏览器访问 http://localhost:5173
```

> 生产环境使用 `.env.prod.example` 调整资源限制。

---

## 技术栈

| 层次 | 选型 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 3.2.5 |
| 语言 | Java | **21 LTS** |
| 并发模型 | **虚拟线程（Project Loom）** | 内置 |
| 数据库 | MySQL 8.0 + JPA / Flyway | — |
| 向量数据库 | Qdrant（gRPC） | 1.15.0 |
| 图数据库 | **Neo4j Embedded**（JVM 内嵌） | 5.26.27 |
| 缓存 | Redis 7 | — |
| 消息队列 | RabbitMQ | 3.13 |
| 对象存储 | MinIO | — |
| AI 编排 | LangChain4j | 0.36.2 |
| 文档解析 | Apache Tika | 2.9.2 |
| 熔断降级 | Resilience4j | 2.2.0 |
| 代码解析 | Tree-sitter | 0.24.4 |
| 鉴权 | JWT（jjwt） | 0.11.5 |
| 前端 | Vue 3 + Vite | 6.x |
| 桌面壳 | Tauri | 2.x |
| 可观测性 | Micrometer + Prometheus + Grafana | — |
| 测试 | JUnit 5 + Testcontainers + ArchUnit | — |

---

## 项目结构

```
DevKnow/
├── deploy/               # 部署配置（nginx 等）
├── docs/
│   ├── rag-sota-todo.md          # RAG 功能路线图
│   ├── backend-sota-todo.md       # 后端工程路线图
│   └── culture/                   # 安全审查报告
├── backend/
│   ├── src/main/java/com/devknow/
│   │   ├── agent/        # ReAct Agent（AiServices + @Tool）
│   │   ├── auth/         # JWT + Spring Security
│   │   ├── cache/        # 语义缓存 + Agent 经验缓存
│   │   ├── chat/         # SSE 对话 + 三层记忆（短期/中期/长期）
│   │   ├── codeindex/    # 代码索引（Tree-sitter / SCIP）
│   │   ├── codereview/   # 代码审查 Agent
│   │   ├── config/       # LLM/MinIO/Neo4j/Qdrant + Mertics
│   │   ├── controller/   # REST + Eval API
│   │   ├── governance/   # 限流 / 熔断 / Token 审计
│   │   ├── knowledge/    # 文档管理 + 语义分块 + 图谱
│   │   ├── rag/          # 9步检索管道 + 动态路由 + 稀疏检索
│   │   │   ├── eval/     # RAG 评估框架
│   │   │   ├── router/   # 动态路由
│   │   │   └── sparse/   # 稀疏检索
│   │   ├── project/      # 项目管理 + 变更影响分析
│   │   └── vector/       # Qdrant 向量存储
│   ├── src/main/resources/
│   │   └── db/migration/ # Flyway 版本化迁移（V1~V7）
│   ├── Dockerfile        # 多阶段 + 分层 JAR
│   └── pom.xml
├── frontend/             # Vue 3 前端
├── grafana/              # Prometheus + Grafana 配置
├── prometheus.yml
├── docker-compose.yml    # 中间件编排
└── docker-compose.sonar.yml  # SonarQube（按需）
```

---

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/*` | 注册/登录 |
| POST | `/api/chat/stream` | SSE 流式对话（含 ReAct Agent） |
| POST | `/api/doc/upload` | 上传文档 |
| GET | `/api/project/*` | 项目管理 |
| POST | `/api/eval/run` | RAG 质量评估 |
| POST | `/api/eval/optimize` | 网格搜索最优参数 |
| GET | `/actuator/prometheus` | Prometheus 指标 |

---

## 路线图

- [RAG 功能路线图](docs/rag-sota-todo.md)
- [后端工程路线图](docs/backend-sota-todo.md)

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

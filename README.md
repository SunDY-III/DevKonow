<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot" alt="Spring Boot 3.2.5">
  <img src="https://img.shields.io/badge/Tree--sitter-0.24.4-important" alt="Tree-sitter 0.24.4">
  <img src="https://img.shields.io/badge/SCIP-Protocol-blue" alt="SCIP Protocol">
  <img src="https://img.shields.io/badge/Qdrant-1.15.0-blueviolet" alt="Qdrant 1.15.0">
  <img src="https://img.shields.io/badge/Neo4j-Embedded-008CC1" alt="Neo4j Embedded">
  <img src="https://img.shields.io/badge/LangChain4j-0.36.2-purple" alt="LangChain4j 0.36.2">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License MIT">
</p>

<div align="center">
  <h1>DevKnow</h1>
  <p><strong>面向开发团队的双通道智能知识平台</strong></p>
  <p>代码理解（Tree-sitter / SCIP） + 文档 RAG（层级感知 + 角色感知）+ Neo4j 知识图谱 + Git 变更影响分析</p>
</div>

---

## DevKnow 不是什么

在介绍 DevKnow 是什么之前，先说明它**不是什么**，这能更快理解它的定位：

| 它不是 | 因为 |
|--------|------|
| ❌ 不是 Confluence/语雀之类的文档管理平台 | DevKnow **不解决文档协作编辑**，它解决的是"已存在的代码和文档怎么被搜索和理解" |
| ❌ 不是 Sourcegraph 的替代品 | DevKnow 的定位更轻量，聚焦于**问答式理解**而非代码导航 IDE 插件 |
| ❌ 不是 ChatGPT 通用问答 | DevKnow 的 LLM 回答**严格基于检索到的代码和文档上下文**，不产生与项目无关的幻觉内容 |
| ❌ 不是传统知识库（FQA/帮助中心） | DevKnow 的知识入库**不需要人工整理问答对**，贴 Git 地址或上传文件即可自动索引 |

---

## 场景与价值（STAR 法）

### S — 背景

开发团队每天面临的核心问题不是"有没有文档"，而是**信息在哪**和**代码为什么这么写**：

- 新成员接手项目，不知道某个方法的调用链路，挨个文件翻
- 架构师做了一个 ADR 决策，几个月后没人记得当时为什么这么选
- 线上故障时，告警到了但查不到对应的变更记录和设计文档
- 代码里混了拼音命名、无注释的函数，靠人逐行猜逻辑
- Git 提交了一堆变更，reviewer 看不出来影响范围有多大

### T — 任务

构建一个平台，让开发者能用自然语言提问，直接获得**精确到文件行号的代码解释**、**关联的设计文档**、**变更影响范围分析**，并且不依赖于团队是否勤快写文档、是否规范命名。

### A — 方案

DevKnow 通过五层能力解决上述问题：

**① 代码理解层**：贴一个 Git 地址，系统自动拉取代码，用 Tree-sitter 或 SCIP 解析方法级别的结构，存入 Qdrant 向量库和 MySQL。开发者问"createOrder 方法在哪"，直接定位到文件行号。

**② 文档理解层**：上传 PDF/Word/Markdown，Tika 解析 + TextSplitter 分块 + Embedding 向量化，与代码通道做 RRF 融合。搜代码也能搜到关联的设计文档。

**③ 层级感知 + 角色感知**：知道一个问题是 L2 架构问题还是 L4 实现问题，知道提问者是架构师还是一线开发，自动调整搜索策略。

**④ 知识图谱**：文档上传后自动分析关联关系（ADR → 编码规范 → 配置示例），检索时多跳扩展上下文。

**⑤ 变更影响分析**：输入一个 commit hash，自动分析变更文件、影响的方法、调用方链路，并输出 LLM 合成的影响报告。

### R — 成果

| 场景 | 之前 | 之后 |
|------|------|------|
| 新成员接手项目 | 逐文件翻代码，花 3 天理解模块结构 | 直接问"订单流程怎么走的"，10 秒得到答案+引用 |
| 架构决策溯源 | 翻 ADR 文档目录，找不到或者忘了 | 搜"为什么限流"，直接命中 ADR 文档 |
| 线上故障排查 | 看告警 → 翻日志 → 猜代码 → 查 Git 历史 | 看告警 → 查询 commit 影响范围 → 定位根因 |
| 不规范代码理解 | 读 `zhifuJine` 猜含义 | 搜"支付金额"，同义词扩展自动匹配拼音命名 |
| Review 变更影响 | Reviewer 逐文件评估改动风险 | 提交 commit → 系统自动输出影响报告 |

---

## 核心能力一览

### 🔍 双模式代码索引

| 模式 | 原理 | 精度 | 依赖 | 适用场景 |
|------|------|------|------|---------|
| **Tree-sitter**（默认） | AST 语法树解析 | 方法名级别 | 无（纯 Java） | 快速上手、语言杂的项目 |
| **SCIP** | 符号协议 protobuf 索引 | 类.方法级别 + 类型签名 | 需外部 indexer | 追求精度的生产项目 |

运行时可一键切换，切换 SCIP 时前端阻塞弹窗实时显示索引生成进度。

### 📄 文档 RAG（层级感知 + 角色感知）

- **双通道检索**：Qdrant 向量（语义） + MySQL ngram（关键词），RRF 融合
- **层级感知**：LLM 自动分类问题层级（L1 原则/L2 架构/L3 规范/L4 实现/L5 经验），A 路定向检索 + B 路降权补刀
- **角色感知**：7 种知识角色（架构师/高级开发/一线开发/QA/运维/PM），自动调整搜索层级权重
- **全格式支持**：PDF / Word / Markdown / TXT，Apache Tika 统一解析

### 🕸️ Neo4j 知识图谱

- **零外部容器**：Neo4j 嵌入 JVM 进程运行
- **自动建关系**：文档上传后 LLM 自动分析摘要，推断 REFERENCES / DEPENDS_ON / EXTENDS / SEQUEL_TO
- **多跳扩展**：检索命中后 Cypher 查询 N 跳关联，追加关联文档 chunk

### ⚡ Git 变更影响分析

- **输入 commit hash → 自动输出影响报告**：
  - 变更文件列表（git diff）
  - 变更方法 + ripple 调用方追踪
  - LLM 合成风险等级和建议
- **SSE 实时推送**分析进度

### 🛠️ 增量波及重建

- `git pull` → 对比 commit → `git diff` → ripple 反向链查询 → 只重索引波及文件

### 🛡️ 治理体系

- 语义缓存（余弦阈值 0.95，按来源文档联动失效）
- 双维度限流（Redis + Lua 滑动窗口，用户级 + 接口级独立配额）
- 敏感词过滤（DFA Trie 树）
- Token 审计（异步落库 + 日报聚合）
- 熔断降级（Resilience4j CircuitBreaker）

---

## 快速开始

### 前置条件

- JDK 17+
- Docker & Docker Compose
- Maven 3.8+
- LLM API Key（兼容 OpenAI 协议）

### 1. 启动中间件

```bash
docker compose up -d
# MySQL:3306, Redis:6379, RabbitMQ:5672, MinIO:9000, Qdrant:6334
# Neo4j 嵌入式运行在应用进程内，无需额外容器
```

初始化数据库：

```bash
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema-v2.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/migration-v3-level.sql
```

### 2. 配置模型

```bash
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-xxx
export LLM_CHAT_MODEL=gpt-4o-mini
export EMBEDDING_MODEL=text-embedding-3-small
```

### 3. 启动应用

```bash
cd backend
mvn spring-boot:run
```

启动时自动完成：Qdrant 集合初始化、Neo4j Embedded 初始化、存量文档 LLM 层级分类迁移。

### 4. 使用

```bash
# 导入项目
curl -X POST "http://localhost:8080/api/project/import?repoUrl=https://github.com/user/repo.git"

# 设置知识角色（可选）
curl -X PUT "http://localhost:8080/api/auth/knowledge-role" \
  -H "Content-Type: application/json" \
  -d '{"knowledgeRole":"ARCHITECT"}'

# 切换代码索引模式（可选）
curl -X PUT "http://localhost:8080/api/codeindex/mode" \
  -H "Content-Type: application/json" \
  -d '{"mode": "scip", "projectDir": "/path/to/project"}'

# 变更影响分析
curl -N "http://localhost:8080/api/project/1/impact/abc123def"

# 打开前端
open http://localhost:5173
```

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户入口                                  │
│  对话 (层级感知问答) | 导入 (SSE进度) | 模式切换 (阻塞弹窗)    │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                     ┌──────┴──────┐
                     │ LLM 路由决策 │  code/doc/both/unknown
                     └──────┬──────┘
                            │
               ┌────────────┴────────────┐
               ▼                         ▼
    ┌──────────────────┐   ┌──────────────────────────┐
    │  代码通道          │   │  文档通道 (RAG)            │
    │  Tree-sitter /    │   │  层级感知 + 角色感知        │
    │  SCIP             │   │  Qdrant + MySQL ngram     │
    │  ↓ ripple 索引    │   │  Neo4j 图谱扩展            │
    └──────────────────┘   └──────────────────────────┘
               └──────────┬──────────────┘
                          ▼
               ┌──────────────────────┐
               │  Git 变更影响分析      │
               │  git diff → ripple    │
               │  → LLM 影响报告       │
               └──────────────────────┘

中间件层:  MySQL | Qdrant | Redis | RabbitMQ | MinIO | Neo4j Embedded
AI 层:     LLM (OpenAI) | Embedding | LangChain4j | LevelClassifier
治理层:    语义缓存 | 限流 | 熔断 | 敏感词 | Token 审计
```

---

## 技术栈

| 层次 | 选型 | 版本 | 说明 |
|---|---|---|---|
| **框架** | Spring Boot | 3.2.5 | |
| **语言** | Java | 17 | |
| **数据层** | MySQL | 8.0 | 持久化 + ngram 全文索引 |
| **向量数据库** | Qdrant | 1.15.0 | Rust 实现轻量级 ANN |
| **图数据库** | Neo4j Embedded | 5.26.27 | 嵌入 JVM，无需独立容器 |
| **缓存** | Redis | 7 | 记忆/缓存/限流/ripple |
| **消息队列** | RabbitMQ | 3.13 | 文档异步解析 |
| **对象存储** | MinIO | — | 文档原文存档 |
| **代码解析（轻量）** | Tree-sitter | 0.24.4 | Java/Go/JS/TS |
| **代码解析（性能）** | SCIP Protocol | — | 精确符号解析 |
| **AI 编排** | LangChain4j | 0.36.2 | RAG/Agent |
| **文档解析** | Apache Tika | 2.9.2 | PDF/Word/Markdown |
| **熔断降级** | Resilience4j | 2.2.0 | CircuitBreaker |
| **前端** | Vue 3 + Vite | 6.4 | SPA |

---

## 项目结构

```
zhishu-ai-agent/
├── docker-compose.yml
├── backend/
│   ├── pom.xml
│   ├── sql/                    # 数据库 DDL
│   └── src/main/
│       ├── proto/scip.proto    # SCIP 协议定义
│       └── java/com/devknow/
│           ├── auth/           # JWT 认证 + 知识角色
│           ├── chat/           # SSE 流式对话 + LLM 路由
│           ├── codeindex/      # ★ 代码索引（Tree-sitter / SCIP 双模式）
│           │   ├── scip/       # SCIP 解析器 + 索引生成器
│           │   └── tree/       # Tree-sitter AST 解析器
│           ├── codereview/     # 代码审查 Agent
│           ├── knowledge/      # 文档 RAG + Neo4j 知识图谱
│           ├── rag/            # RAG 检索链路（MMR/RRF/重排序）
│           ├── vector/         # Qdrant 向量存储
│           ├── project/        # 项目导入 + 变更影响分析
│           ├── governance/     # 限流/熔断/审计/敏感词
│           ├── config/         # 配置装配
│           └── controller/     # REST API 控制器
└── frontend/
    └── src/
        ├── App.vue             # 顶栏布局 + 模式切换
        ├── components/         # ScipModal 阻塞弹窗
        └── views/              # Chat / Import / Projects
```

---

## 典型问答对比

```
普通知识库（如 Confluence）：
  Q: "为什么支付网关要限流？"
  A: 搜索"限流" → 返回所有标题包含"限流"的文档列表
     （你需要自己从 15 篇文档里翻哪篇是你要的）

DevKnow（层级感知 + 角色感知）：
  Q: "为什么支付网关要限流？"
  A: ① LLM 分类 → 这是 L2 架构问题
     ② 架构师角色 → 主层级 [L1,L2]，置信度 +15%
     ③ Qdrant filter(level IN [1,2]) → 直接命中《ADR：支付网关防雪崩设计》
     ④ Neo4j 扩展 → 关联 L1《共识：简单可依赖》
     ⑤ LLM 回答 + 引用行号
```

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

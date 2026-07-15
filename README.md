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
  <p><strong>开发者双通道知识平台</strong></p>
  <p>代码索引（Tree-sitter / SCIP 双模式）| 文档 RAG（层级感知 + 角色感知）| Neo4j 知识图谱 | 波及重建</p>
</div>

---

## 项目简介

**DevKnow** 是一个面向开发团队的智能知识平台。贴一个 Git 地址，系统自动拉取代码并建立索引，开发者用自然语言提问即可检索代码方法、追溯调用链、查询开发文档。

### 核心能力

| 维度 | 能力 |
|------|------|
| **代码索引** | 双模式互斥：Tree-sitter（轻量，零依赖）或 SCIP（性能级，精确符号），运行时一键切换 |
| **文档检索** | Qdrant 向量 + MySQL ngram 双通道 RRF 融合，支持 PDF/Word/Markdown |
| **层级感知** | LLM 自动分类问题所属知识层级（L1 原则/L2 架构/L3 规范/L4 实现/L5 经验），定向检索 |
| **角色感知** | 7 种知识角色（架构师/高级开发/一线开发/QA/运维/PM），自动调整搜索层级权重 |
| **知识图谱** | Neo4j Embedded 存储文档关联关系，LLM 自动建关系，多跳扩展上下文 |
| **增量索引** | Git diff → riple 反向链查询 → 只重索引波及文件，不走全量 |
| **治理体系** | 语义缓存、双维度限流、敏感词过滤、Token 审计、熔断降级 |

---

## 目录

- [快速开始](#快速开始)
- [系统架构](#系统架构)
- [双模式代码索引](#双模式代码索引)
- [核心链路](#核心链路)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [API 概览](#api-概览)
- [许可证](#许可证)

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
# 启动：MySQL:3306, Redis:6379, RabbitMQ:5672, MinIO:9000, Qdrant:6334
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

启动时自动完成：
- Qdrant 集合初始化（COSINE + HNSW）
- Neo4j Embedded 初始化（持久化到 `data/neo4j/`）
- 存量文档 LLM 层级分类迁移

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

# 打开前端
open http://localhost:5173
```

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────────┐
│                        用户入口                                   │
│  对话 (层级感知问答) | 项目导入 (SSE 进度) | 模式切换 (阻塞弹窗) │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │ LLM 路由决策 │ ← classifyQuestion(): code/doc/both/unknown
                    └──────┬──────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
   ┌──────────────────┐   ┌──────────────────────────┐
   │  代码通道         │   │  文档通道 (RAG)           │
   │  Tree-sitter SCIP│   │  层级感知 + 角色感知       │
   │  ↓ ripple 索引    │   │  ↓ Qdrant + MySQL ngram  │
   └────────┬─────────┘   └───────────┬──────────────┘
            └──────────┬──────────────┘
                       ▼
            ┌──────────────────────┐
            │ RRF 融合 + 重排序    │
            │ Neo4j 知识图谱扩展   │
            └──────────┬───────────┘
                       ▼
            ┌──────────────────────┐
            │ LLM 生成回答         │
            └──────────────────────┘

基础设施层: MySQL | Qdrant | Redis | RabbitMQ | MinIO | Neo4j Embedded
AI 层:      LLM (OpenAI) | Embedding | LangChain4j | LevelClassifier
治理层:     语义缓存 | 限流 | 熔断 | 敏感词 | Token 审计
```

---

## 双模式代码索引

系统支持两种代码索引模式，**互斥，运行时一键切换**。

| 维度 | Tree-sitter 模式（默认） | SCIP 模式 |
|------|------------------------|-----------|
| **原理** | AST 语法树解析 | SCIP 协议 protobuf 索引 |
| **精度** | 方法名级别 | 类.方法级别 + 类型签名 |
| **调用链** | 方法名匹配（语法层） | 精确符号引用（语义层，跨文件） |
| **外部依赖** | 无（纯 Java） | 需要安装 scip-java/scip-go 等 indexer CLI |
| **首次索引** | 即时，导入时自动完成 | 需运行 indexer 生成 index.scip |
| **切换耗时** | 立即生效 | 可能需要等待索引生成（SSE 弹窗展示进度） |
| **适用场景** | 快速上手、语言杂的项目 | 追求精度的生产项目 |

**切换流程**：

```
用户点击切换到 SCIP
  → PUT /api/codeindex/mode
  → 检测 index.scip 是否存在
    ├─ 存在 → 立即切换
    └─ 不存在 → 异步调用外部 indexer → SSE 推送进度
       → 前端阻塞弹窗（实时日志 + 脉冲动画）
       → 完成后自动关闭，模式已切换
```

---

## 核心链路

### 项目导入链路

```
Git clone → StructureScanner 扫描（语言/框架/模块）
  → Tree-sitter 模式：逐文件 AST → CodeUnit → 三写
  → SCIP 模式：读取 index.scip → 提取符号 → CodeUnit → 三写
  → 三写一致：MySQL code_unit + Qdrant 向量 + Redis riple
  → 保存 HEAD commit hash → 完成（SSE 推进度）
```

### 波及重建链路（增量索引）

```
git pull → 对比 lastIndexedCommit → git diff
  → 提取变更方法名 → 查 riple 反向索引定位调用方
  → 合并（变更文件 + 调用方文件）
  → 只重索引这组文件，不走全量
```

### 层级感知检索链路

```
用户提问 + userId
  → 查用户知识角色（knowledge_role）
  → LLM 分类层级（L1~L5 + 置信度）
  → RoleLevelMapper 融合角色 + 层级
    ├─ 角色主层级命中 → 置信度 +15%
    ├─ 副层级命中 → 置信度 -15%
    └─ 不命中 → 主层级 A 路 + 原结果 B 路降权补刀
  → A 路：Qdrant searchByLevels（payload filter on level）
  → B 路（可选）：全量补刀降权 0.6
  → MySQL keywordSearchByLevel（JOIN d.level）
  → RRF 融合 → 规则重排序
  → Neo4j 知识图谱多跳扩展（关联文档 chunk）
  → LLM 生成回答 + 层级标签
```

### 文档写入链路

```
上传文件 → MD5 秒传（防重复） → MinIO 存原文
  → RabbitMQ 异步 → Tika 提取文本（PDF/Word/MD/TXT）
  → TextSplitter 语义分块（Markdown 标题感知 + overlap 滑动）
  → 逐块 Embedding 向量化
  → MySQL 落 chunk + Qdrant 落向量（payload 含 level）
  → Neo4j 创建文档节点 + LLM 自动建关系
```

---

## 技术栈

| 层次 | 选型 | 版本 | 说明 |
|---|---|---|---|
| **框架** | Spring Boot | 3.2.5 | 基础运行时 |
| **语言** | Java | 17 | |
| **数据层** | MySQL | 8.0 | 持久化 + ngram 全文索引 |
| **向量数据库** | Qdrant | 1.15.0 | Rust 实现，轻量级 ANN 搜索 |
| **图数据库** | Neo4j Embedded | 5.26.27 | 知识图谱，嵌入 JVM 无独立容器 |
| **缓存** | Redis | 7 | 聊天记忆、语义缓存、riple 反向索引、限流 |
| **消息队列** | RabbitMQ | 3.13 | 文档异步解析 |
| **对象存储** | MinIO | latest | 文档原文存档 |
| **代码解析（轻量）** | Tree-sitter | 0.24.4 | Java/Go/JS/TS AST 语法解析 |
| **代码解析（性能）** | SCIP Protocol | — | protobuf 索引，精确符号解析 |
| **AI 编排** | LangChain4j | 0.36.2 | 模型调用、RAG Pipeline、Agent |
| **模型协议** | OpenAI 协议 | — | 兼容 GPT / DeepSeek / 通义 / 智谱 |
| **文档解析** | Apache Tika | 2.9.2 | PDF/Word/Markdown/TXT |
| **熔断降级** | Resilience4j | 2.2.0 | @CircuitBreaker + fallback |
| **鉴权** | JWT (jjwt) | 0.11.5 | Header + SSE Query 双传参 |
| **前端** | Vue 3 + Vite | 6.4 | SPA |

---

## 项目结构

```
zhishu-ai-agent/
├── docker-compose.yml                # 5 个中间件容器
├── backend/
│   ├── pom.xml                       # Spring Boot 3.2.5 + 全量依赖
│   ├── sql/
│   │   ├── schema.sql                # 基础表结构
│   │   ├── schema-v2.sql             # v2 工单/审计表
│   │   └── migration-v3-level.sql    # v3 层级字段 DDL
│   └── src/main/
│       ├── proto/scip.proto          # SCIP 协议定义
│       ├── resources/
│       │   ├── application.yml       # 主配置
│       │   ├── sensitive-words.txt   # 敏感词库
│       │   ├── synonyms.yml          # 同义词
│       │   └── lua/                  # Redis Lua 脚本
│       └── java/com/devknow/         # 86 个 Java 文件
│           ├── DevKnowApplication.java
│           ├── auth/                 # 认证 + 知识角色
│           ├── chat/                 # SSE 流式对话
│           ├── codeindex/            # ★ 代码索引核心
│           │   ├── tree/             # Tree-sitter 解析器
│           │   ├── scip/             # SCIP 索引解析器
│           │   ├── CodeParser.java   # 双模式门面
│           │   ├── CodeIndexService.java  # 全量 + 波及重建
│           │   ├── CodeIndexModeService.java # 运行时模式切换
│           │   └── ...
│           ├── codereview/           # 代码审查 Agent
│           ├── knowledge/            # 文档 RAG
│           │   └── graph/            # Neo4j 知识图谱
│           ├── rag/                  # RAG 检索链路
│           ├── vector/               # Qdrant 向量存储
│           ├── project/              # 项目导入管理
│           ├── ticket/               # 工单 Agent
│           ├── config/               # 配置装配
│           │   └── rerank/           # 层级分类 + 角色映射
│           ├── controller/           # REST API
│           ├── governance/           # 治理层
│           ├── migration/            # 数据迁移
│           └── common/               # 公共组件
└── frontend/
    ├── package.json                  # Vue 3 + Vue Router + Vite
    ├── vite.config.js
    └── src/
        ├── App.vue                   # 侧边栏 + 模式切换
        ├── components/ScipModal.vue  # SCIP 阻塞弹窗
        ├── views/
        │   ├── ChatView.vue          # 对话
        │   ├── ImportView.vue        # 项目导入
        │   └── ProjectsView.vue      # 项目列表
        ├── api/index.js              # API 封装
        └── router/index.js           # 路由
```

---

## API 概览

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/profile` | 用户信息 |
| PUT | `/api/auth/knowledge-role` | 设置知识角色 |

### 代码索引

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/codeindex/mode` | 当前模式 |
| PUT | `/api/codeindex/mode` | 切换模式 |
| GET | `/api/codeindex/mode/progress` | SSE 切换进度 |

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式提问 |

### 项目

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/project/list` | 项目列表 |
| POST | `/api/project/import` | 导入项目（SSE） |
| DELETE | `/api/project/{id}` | 删除项目 |
| POST | `/api/project/{id}/reindex` | 重建索引 |

### 文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/doc/upload` | 上传文档 |
| GET | `/api/doc/list` | 文档列表 |
| DELETE | `/api/doc/{id}` | 删除文档 |

### 知识图谱

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge/graph/{docId}` | 文档关联图谱 |
| GET | `/api/knowledge/graph/stats` | 图谱统计 |
| POST | `/api/knowledge/graph/relation` | 添加关系 |
| POST | `/api/knowledge/graph/auto-build` | 全量自动建关系 |
| POST | `/api/knowledge/graph/sync-nodes` | 同步节点 |

### 工单

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ticket/list` | 工单列表 |
| POST | `/api/ticket` | 创建工单 |
| PUT | `/api/ticket/{id}/assign` | 分配工单 |

---

## 层级感知问答示例

```
用户（架构师）："为什么支付网关要限流？"

  ① LLM 层级分类 → L2（架构层），置信度 0.85
  ② 角色匹配 → ARCHITECT 主层级 [L1, L2] → 置信度 +15% → 0.98
  ③ Qdrant filter(level IN [1,2]) → 命中《ADR：支付网关防雪崩设计》
  ④ Neo4j 图谱扩展 → 关联 L1 原则文档
  ⑤ LLM 回答 + 层级标签 [L2 架构层]

用户（开发者）："限流怎么配？"

  ① LLM 层级分类 → L4（实现层），置信度 0.82
  ② 角色匹配 → DEVELOPER 主层级 [L3, L4, L5] → 置信度 +15% → 0.94
  ③ Qdrant filter(level IN [3,4,5]) → 命中《限流配置指南》
  ④ 回答含配置示例
```

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

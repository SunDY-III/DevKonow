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
  <p>代码理解（Tree-sitter / SCIP）· 文档 RAG（层级感知 + 角色感知）· Neo4j 知识图谱 · Git 变更影响分析</p>
</div>

---

## 项目简介

DevKnow 是一个面向开发团队的智能知识平台。贴一个 Git 地址，系统自动拉取代码并建立索引；上传一份文档，自动分块向量化并入库。开发者用自然语言提问即可检索代码方法、追溯调用链、查询设计文档、分析变更影响范围。

项目已通过编译验证和核心流程测试，可直接部署使用。

---

## 目录

- [快速开始](#快速开始)
- [核心能力](#核心能力)
- [系统架构](#系统架构)
- [API 参考](#api-参考)
- [配置说明](#配置说明)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [常见场景](#常见场景)
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
```

启动后包含 5 个服务：

| 服务 | 端口 | 用途 |
|------|------|------|
| MySQL 8.0 | 3306 | 业务数据 + ngram 全文索引 |
| Redis 7 | 6379 | 缓存 / 聊天记忆 / 限流 |
| RabbitMQ 3.13 | 5672 | 文档异步解析 |
| MinIO | 9000 | 文档文件存储 |
| Qdrant | 6334 | 向量近似搜索（ANN） |

Neo4j 以嵌入模式运行在应用进程内，无需独立容器。

### 2. 初始化数据库

```bash
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema-v2.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/migration-v3-level.sql
```

### 3. 配置模型

```bash
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-xxx
export LLM_CHAT_MODEL=gpt-4o-mini
export EMBEDDING_MODEL=text-embedding-3-small
```

支持任意 OpenAI 协议兼容服务（DeepSeek / 通义 / 智谱等）。

### 4. 编译 & 启动

```bash
cd backend
mvn clean package -DskipTests
java -jar target/devknow-1.0.0.jar
```

启动时自动完成：
- Qdrant 集合初始化（COSINE + HNSW 索引）
- Neo4j Embedded 初始化（持久化到 `data/neo4j/`）
- 存量文档自动 LLM 层级分类迁移

### 5. 前端

```bash
cd frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

---

## 核心能力

### 🔍 双模式代码索引

两种代码索引模式互斥，运行时一键切换。

| 维度 | Tree-sitter 模式（默认） | SCIP 模式 |
|------|------------------------|-----------|
| 原理 | AST 语法树解析 | SCIP 协议 protobuf 索引 |
| 精度 | 方法名级别 | 类.方法级别 + 类型签名 |
| 调用链 | 方法名匹配（语法层） | 精确符号引用（语义层，跨文件） |
| 外部依赖 | 无（纯 Java 实现） | 需要 scip-java / scip-go 等 indexer |
| 适用场景 | 快速上手、语言杂的项目 | 追求精度的生产项目 |

切换 SCIP 模式时，系统自动检测并调用外部 indexer 生成索引文件，前端阻塞弹窗实时显示进度。

### 📄 文档 RAG（层级感知 + 角色感知）

- **双通道检索**：Qdrant 向量（语义） + MySQL ngram（关键词）RRF 融合
- **层级感知（L1~L5）**：LLM 自动分类问题所属层级，A 路定向检索 + B 路低置信度降权补刀
- **角色感知**：7 种角色（架构师/高级开发/一线开发/QA/运维/PM）自动调整搜索层级权重
- **多因子重排序**：覆盖率 + 位置加权，MMR 多样性去重
- **同义词扩展**：15 组中英文 + 拼音（`zhifu` → `支付` / `pay`）
- **全格式文档**：PDF / Word / Markdown / TXT（Apache Tika 统一解析）

### 🕸️ Neo4j 知识图谱

- Neo4j Embedded，嵌入 JVM 运行，无需独立容器
- 文档上传后 LLM 自动分析摘要，推断 REFERENCE / DEPENDS_ON / EXTENDS / SEQUEL_TO 关系
- Cypher 多跳遍历，检索命中后自动扩展关联上下文
- REST API 全套增删查改 + 最短路径 + 统计

### ⚡ Git 变更影响分析

输入 commit hash，自动输出影响报告（SSE 实时推送进度）：

```
GET /api/project/{id}/impact/{commitHash} (SSE)
  ├─ 步骤 1: git diff 提取变更文件
  ├─ 步骤 2: ripple 反向索引追踪调用方
  └─ 步骤 3: LLM 合成影响报告（风险等级 + 影响范围 + 回滚命令）
```

### 🔄 增量波及重建

git pull → 对比 commit hash → git diff → ripple 查调用方 → 只重索引波及文件，不走全量。

### 🛡️ 企业级治理

- **语义缓存**：余弦相似度阈值 0.95，按来源文档联动失效
- **双维度限流**：Redis + Lua 滑动窗口，用户级 + 接口级独立配额
- **敏感词过滤**：DFA Trie 树，O(n) 多模式匹配
- **Token 审计**：异步落库 + 每日日报聚合
- **熔断降级**：Resilience4j CircuitBreaker，模型故障时回退纯检索结果

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户入口                                  │
│  对话（层级感知问答）· 导入（SSE 进度）· 模式切换（阻塞弹窗）   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                     ┌──────┴──────┐
                     │ LLM 路由决策 │  code / doc / both / unknown
                     └──────┬──────┘
                            │
               ┌────────────┴────────────┐
               ▼                         ▼
    ┌──────────────────┐   ┌──────────────────────────┐
    │  代码通道          │   │  文档通道 (RAG)            │
    │  Tree-sitter /    │   │  层级感知 + 角色感知        │
    │  SCIP → ripple    │   │  Qdrant + MySQL ngram    │
    │  ↓ Qdrant 向量    │   │  → RRF 融合 → MMR 去重   │
    └──────────────────┘   │  → 重排序 → 图谱扩展       │
               └──────────┬┴────────┬─────────────────┘
                          ▼         ▼
               ┌─────────────────────────────┐
               │  Git 变更影响分析              │
               │  git diff → ripple → LLM     │
               └─────────────────────────────┘

中间件层:  MySQL · Qdrant · Redis · RabbitMQ · MinIO · Neo4j Embedded
AI 层:     LLM · Embedding · LangChain4j · LevelClassifier · CodeReviewAgent
治理层:    语义缓存 · 限流 · 熔断 · 敏感词 · Token 审计
```

---

## API 参考

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录，返回 JWT |

### 代码索引

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/codeindex/mode` | 获取当前模式 |
| PUT | `/api/codeindex/mode` | 切换索引模式（body: `{"mode":"scip","projectDir":"..."}`） |
| GET | `/api/codeindex/mode/progress` | SSE 订阅模式切换进度 |

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式对话（参数: question, conversationId, token） |

### 项目

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/project/list` | 项目列表 |
| POST | `/api/project/import` | SSE 导入项目 |
| POST | `/api/project/{id}/reindex` | 重建索引 |
| GET | `/api/project/{id}/impact/{commit}` | SSE 变更影响分析 |
| GET | `/api/project/{id}/impact/{commit}/json` | 影响分析 JSON 结果 |

### 文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/doc/upload` | 上传文档（form-data: file） |
| DELETE | `/api/doc/{id}` | 删除文档 |

### 知识图谱

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge/graph/{docId}` | 文档关联图谱 |
| POST | `/api/knowledge/graph/relation` | 添加关系 |

### 用户

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/auth/profile` | 用户信息 |
| PUT | `/api/auth/knowledge-role` | 设置知识角色 |

---

## 配置说明

### 应用配置（`application.yml`）

```yaml
# RAG 检索参数
app.rag.vector-top-k: 8       # 向量召回数
app.rag.keyword-top-k: 8      # 关键词召回数
app.rag.rerank-top-n: 4       # 最终入 prompt 片段数
app.rag.confidence-threshold: 0.45  # 置信度阈值

# 代码索引模式
app.codeindex.mode: tree-sitter  # tree-sitter / scip

# Neo4j 数据目录
app.neo4j.data-dir: data/neo4j
```

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LLM_BASE_URL` | `https://api.openai.com/v1` | LLM 接口地址 |
| `LLM_API_KEY` | — | API 密钥 |
| `LLM_CHAT_MODEL` | `gpt-4o-mini` | 对话模型 |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding 模型 |
| `LLM_MAX_RETRIES` | 2 | 失败重试次数 |

---

## 技术栈

| 层次 | 选型 | 版本 |
|---|---|---|
| 框架 | Spring Boot | 3.2.5 |
| 语言 | Java | 17 |
| 数据库 | MySQL 8.0 + Spring Data JPA | — |
| 向量数据库 | Qdrant（gRPC） | 1.15.0 |
| 图数据库 | Neo4j Embedded | 5.26.27 |
| 缓存 | Redis 7 + StringRedisTemplate | — |
| 消息队列 | RabbitMQ | 3.13 |
| 对象存储 | MinIO | — |
| 代码解析（轻量） | Tree-sitter | 0.24.4 |
| 代码解析（性能） | SCIP Protocol + protobuf | — |
| AI 编排 | LangChain4j | 0.36.2 |
| 文档解析 | Apache Tika | 2.9.2 |
| 熔断降级 | Resilience4j | 2.2.0 |
| 鉴权 | JWT (jjwt) | 0.11.5 |
| 前端 | Vue 3 + Vite | 6.4 |

---

## 项目结构

```
zhishu-ai-agent/
├── docker-compose.yml            # 5 个中间件容器编排
├── backend/
│   ├── pom.xml                   # Maven 依赖管理
│   ├── sql/                      # 数据库 DDL（3 个迁移脚本）
│   └── src/main/
│       ├── proto/scip.proto      # SCIP 协议定义
│       └── java/com/devknow/
│           ├── DevKnowApplication.java
│           ├── auth/             # JWT 认证 + 知识角色
│           ├── chat/             # SSE 流式对话 + LLM 路由
│           ├── codeindex/        # ★ 代码索引核心
│           │   ├── tree/         # Tree-sitter AST 解析器
│           │   ├── scip/         # SCIP 解析 + 索引生成
│           │   ├── CodeIndexService.java  # 全量 + 增量
│           │   └── CodeParser.java        # 双模式门面
│           ├── codereview/       # 代码审查 Agent
│           ├── knowledge/        # 文档 RAG + Neo4j 图谱
│           ├── rag/              # RAG 检索链路（MMR/RRF/重排序）
│           ├── vector/           # Qdrant 向量存储
│           ├── project/          # 项目导入 + 变更影响分析
│           ├── governance/       # 限流/熔断/审计/敏感词
│           ├── config/           # 配置装配
│           └── controller/       # REST API
└── frontend/
    └── src/
        ├── App.vue               # 顶栏 + 模式切换
        ├── components/ScipModal.vue
        └── views/
            ├── ChatView.vue      # 对话界面
            ├── ImportView.vue    # 导入界面
            └── ProjectsView.vue  # 项目列表
```

---

## 常见场景

```
用户（架构师）："为什么支付网关要限流？"

  ① LLM 层级分类 → L2（架构层），置信度 0.85
  ② 角色匹配 → ARCHITECT 主层级 [L1, L2] → 置信度 +15%
  ③ Qdrant filter(level IN [1,2]) → 命中《ADR：支付网关防雪崩设计》
  ④ Neo4j 图谱扩展 → 关联 L1 原则文档
  ⑤ LLM 回答 + 层级标签 [L2 架构层]

用户（开发者）："限流怎么配置？"

  ① LLM 层级分类 → L4（实现层），置信度 0.82
  ② 角色匹配 → DEVELOPER 主层级 [L3, L4, L5] → 置信度 +15%
  ③ Qdrant filter(level IN [3,4,5]) → 命中《限流配置指南》
  ④ 回答含配置示例
```

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot" alt="Spring Boot 3.2.5">
  <img src="https://img.shields.io/badge/Tree--sitter-0.24.4-important" alt="Tree-sitter 0.24.4">
  <img src="https://img.shields.io/badge/SCIP-Protocol-blue" alt="SCIP Protocol">
  <img src="https://img.shields.io/badge/Qdrant-1.15.0-blueviolet" alt="Qdrant 1.15.0">
  <img src="https://img.shields.io/badge/Neo4j-Embedded-008CC1" alt="Neo4j Embedded">
  <img src="https://img.shields.io/badge/LangChain4j-0.36.2-purple" alt="LangChain4j 0.36.2">
  <img src="https://img.shields.io/badge/Tauri-2.x-FFC131?logo=tauri" alt="Tauri 2.x">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License MIT">
</p>

<div align="center">
  <h1>DevKnow</h1>
  <p><strong>开发者的第二大脑 — 代码理解 · 文档 RAG · 项目研读 · 实战护航</strong></p>
  <p>面向个人开发者与中小团队的智能知识平台</p>
</div>

---

## 项目简介

DevKnow 是一个**双通道智能知识平台**，既是团队的代码&文档知识库，也是个人开发者的编程导师。

**贴一个 Git 地址**，系统自动拉取代码并建立索引；**上传一份文档**，自动分块向量化入库；**告诉它你想学什么**，它去 GitHub 找优秀开源项目带你研读。开发者用自然语言提问即可检索代码方法、追溯调用链、查询设计文档、分析变更影响范围，甚至完成从学习到实战的完整链路。

---

## 核心能力

### 🔍 双模式代码索引

两种索引模式互斥，运行时一键切换。

| 维度 | Tree-sitter 模式（默认） | SCIP 模式 |
|------|------------------------|-----------|
| 原理 | AST 语法树解析 | SCIP 协议 protobuf 索引 |
| 精度 | 方法名级别 | 类.方法级别 + 类型签名 |
| 调用链 | 方法名匹配（语法层） | 精确符号引用（语义层，跨文件） |
| 外部依赖 | 无（纯 Java 实现） | 需要 scip-java / scip-go 等 indexer |
| 适用场景 | 快速上手、语言杂的项目 | 追求精度的生产项目 |

### 🎓 项目发现与研读（个人学习）

| 功能 | 说明 |
|------|------|
| **🎯 智能发现** | 告诉 DevKnow"想学微服务网关"，自动解析意图→搜索 GitHub→评分推荐最适学项目 |
| **📖 交互式研读** | 导入项目后自动生成架构总览、代码亮点、设计模式标注，对话式逐层解剖代码 |
| **🧩 学习路径** | 基于模块依赖图推荐阅读顺序：核心模块 → 扩展模块，渐进学习 |
| **🏷️ 模式识别** | 自动标注 Factory / Observer / Strategy 等设计模式在代码中的具体实现 |

### 📄 文档 RAG（层级感知 + 角色感知）

- **双通道检索**：Qdrant 向量（语义） + MySQL ngram（关键词）RRF 融合
- **层级感知（L1~L5）**：LLM 自动分类问题所属层级，A 路定向检索 + B 路低置信度降权补刀
- **角色感知**：7 种角色（架构师/高级开发/一线开发/QA/运维/PM）自动调整搜索层级权重
- **多因子重排序**：覆盖率 + 位置加权，MMR 多样性去重 + CrossEncoder 重排
- **同义词扩展**：15 组中英文 + 拼音映射
- **全格式文档**：PDF / Word / Markdown / TXT（Apache Tika 统一解析）

### 🚀 实战护航（从 0 到 1）

| 功能 | 说明 |
|------|------|
| **🏗️ 脚手架生成** | "用这个项目的模式做一个 XX" → 基于研读项目结构生成同风格骨架 |
| **💬 编码引导** | 按模块引导从架构到具体代码一步步实现（SSE 流式输出） |
| **🐳 DevOps 配置** | 根据项目类型自动生成 Dockerfile / docker-compose / CI 配置 |
| **📋 进度看板** | TODO 看板标记已完成/进行中/待做，进度可视化 |

### 🕸️ Neo4j 知识图谱

- Neo4j Embedded，嵌入 JVM 运行，无需独立容器
- 文档上传后 LLM 自动分析摘要，推断 REFERENCE / DEPENDS_ON / EXTENDS / SEQUEL_TO 关系
- Cypher 多跳遍历，检索命中后自动扩展关联上下文

### ⚡ Git 变更影响分析

输入 commit hash，自动输出影响报告（SSE 实时推送进度）：

```
GET /api/project/{id}/impact/{commitHash} (SSE)
  ├─ git diff 提取变更文件
  ├─ ripple 反向索引追踪调用方
  └─ LLM 合成影响报告（风险等级 + 影响范围 + 回滚命令）
```

### 🔄 增量波及重建

git pull → 对比 commit hash → git diff → ripple 查调用方 → 只重索引波及文件，不走全量。

### 🛡️ 企业级治理

- **语义缓存**：余弦相似度阈值 0.92，按来源文档联动失效
- **双维度限流**：Redis + Lua 滑动窗口，用户级 + 接口级独立配额
- **敏感词过滤**：DFA Trie 树，O(n) 多模式匹配
- **Token 审计**：异步落库 + 每日日报聚合
- **熔断降级**：Resilience4j CircuitBreaker，模型故障时回退纯检索结果

---

## 使用教程

### 依赖清单：你需要准备什么

启动 DevKnow 需要以下组件。Docker 只覆盖了基础设施中间件，不等于全部。

#### 🔴 必须准备

| 组件 | 用途 | 获取方式 |
|------|------|---------|
| **LLM API Key** | 所有 AI 功能的核心（对话/RAG/代码审查/Feynman/面试等） | 注册 OpenAI / DeepSeek / 通义千问 / 智谱等平台获取 `sk-xxx` |
| **Java 17+** | 运行后端 Spring Boot 服务 | [下载 JDK 17](https://adoptium.net/) |
| **MySQL 8.0** | 业务数据库 | Docker **或** [本地安装](https://dev.mysql.com/downloads/installer/) |
| **Redis 7** | 缓存/会话/限流 | Docker **或** [下载 Windows 版](https://github.com/microsoftarchive/redis/releases) 直接运行 `redis-server.exe` |

#### 🟡 推荐但不强制

| 组件 | 缺失影响 | 获取方式 |
|------|---------|---------|
| **Qdrant** | 向量检索降级为内存全量余弦匹配；语义缓存降级 Redis SCAN | Docker 或 [GitHub Release](https://github.com/qdrant/qdrant/releases) 下载 Windows 二进制 |
| **RabbitMQ** | 文档异步解析变同步，不影响功能 | Docker（可跳过） |
| **MinIO** | 文件上传存本地磁盘（需稍改配置） | Docker（可跳过） |

#### 🟢 已内嵌（零依赖）

| 组件 | 说明 |
|------|------|
| **Neo4j** | 嵌入在 JVM 进程内运行，无需任何外部进程 |
| **Tree-sitter** | 纯 Java 实现的 AST 解析器 |

---

### 方式一：有 Docker 的用户（推荐）

#### 前置条件

- JDK 17+
- Docker & Docker Compose
- Maven 3.8+
- Node.js 18+

#### 1. 配置 LLM 模型

```bash
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-xxx
export LLM_CHAT_MODEL=gpt-4o-mini
export EMBEDDING_MODEL=text-embedding-3-small
```
支持任意 OpenAI 协议兼容服务（DeepSeek / 通义 / 智谱 / Ollama 等）。

#### 2. 启动中间件（一键）

```bash
docker compose up -d
```

| 服务 | 端口 | 用途 |
|------|------|------|
| MySQL 8.0 | 3306 | 业务数据 + ngram 全文索引 |
| Redis 7 | 6379 | 缓存 / 聊天记忆 / 限流 |
| RabbitMQ 3.13 | 5672 | 文档异步解析 |
| MinIO | 9000 | 文档文件存储 |
| Qdrant | 6334 | 向量近似搜索（ANN） |

Neo4j 以嵌入模式运行在应用进程内，无需独立容器。

#### 3. 初始化数据库

```bash
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema-v2.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/migration-v3-level.sql
```

#### 4. 编译 & 启动后端

```bash
cd backend
mvn clean package -DskipTests
java -jar target/devknow-1.0.0.jar
```

启动时自动完成：
- Qdrant 集合初始化（COSINE + HNSW 索引）
- Neo4j Embedded 初始化（持久化到 `data/neo4j/`）
- 存量文档自动 LLM 层级分类迁移

#### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
# 浏览器访问 http://localhost:5173
```



---

### 方式三：Tauri 桌面端（可选）

前端已集成 [Tauri](https://v2.tauri.app) 作为桌面壳，可编译为独立窗口，无需浏览器：

```bash
# Windows 下使用专用脚本（自动处理 MSVC 链接器路径）
./build-tauri.bat

# 运行桌面应用
./src-tauri/target/debug/devknow.exe
```

> **注意**：Windows 下必须使用 `build-tauri.bat` 编译，Git Bash `/usr/bin/link.exe` 会与 MSVC `link.exe` 冲突。

---

### 验证是否启动成功

步骤 | 检查项 | 预期结果
------|--------|---------
`docker compose ps` | 5 个中间件状态 | 全部 `Up`（无 Docker 则跳过此步）
`java -jar` 日志 | 控制台最后几行 | `Started DevKnowApplication in XX seconds`
前端浏览器 | `http://localhost:5173` | 看到登录/注册页面
**可用性验证** | 注册新用户 → 导入一个 Git 项目 → 在对话中输入"这个项目是做什么的" | 收到带项目概述的回答

---

## 系统架构

```
                          用户入口
                ┌─────────────────────────────┐
                │  对话 · 发现 · 研读 · 护航   │
                │  导入（SSE）· 模式切换        │
                └──────────┬──────────────────┘
                           │
                    ┌──────┴──────┐
                    │ LLM 路由决策 │  code / doc / both / learn
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
              │  个人学习通道                   │
              │  发现→研读→护航               │
              │  GitHub 搜索 → 架构分析       │
              │  → 代码亮点 → 实战脚手架      │
              └─────────────────────────────┘

中间件层:  MySQL · Qdrant · Redis · RabbitMQ · MinIO · Neo4j Embedded
AI 层:     LLM · Embedding · LangChain4j · LevelClassifier · CodeReviewAgent
治理层:    语义缓存 · 限流 · 熔断 · 敏感词 · Token 审计
```

---

## 项目结构

```
devknow/
├── docker-compose.yml            # 5 个中间件容器编排
├── build-tauri.bat               # Tauri 桌面端一键编译脚本
├── backend/
│   ├── pom.xml                   # Maven 依赖管理
│   ├── sql/                      # 数据库 DDL 迁移脚本
│   └── src/main/
│       ├── proto/scip.proto      # SCIP 协议定义
│       └── java/com/devknow/
│           ├── DevKnowApplication.java
│           ├── auth/             # JWT 认证 + 知识角色
│           ├── chat/             # SSE 流式对话 + LLM 路由
│           ├── codeindex/        # ★ 代码索引核心
│           │   ├── tree/         # Tree-sitter AST 解析器
│           │   └── scip/         # SCIP 解析 + 索引生成
│           ├── codereview/       # 代码审查 Agent
│           ├── discover/         # ★ GitHub 项目发现推荐
│           ├── study/            # ★ 项目研读（架构/亮点/模式）
│           ├── mentor/           # ★ 实战护航引擎
│           ├── scaffold/         # ★ 脚手架 + 部署配置生成
│           ├── todo/             # ★ TODO 进度管理
│           ├── prompt/           # ★ LLM Prompt 模板管理
│           ├── knowledge/        # 文档 RAG + Neo4j 图谱
│           ├── rag/              # RAG 检索链路（MMR/RRF/重排序）
│           ├── vector/           # Qdrant 向量存储
│           ├── project/          # 项目导入 + 变更影响分析
│           ├── governance/       # 限流/熔断/审计/敏感词
│           ├── config/           # 配置装配
│           └── controller/       # REST API
├── src-tauri/                    # ★ Tauri 桌面壳
│   ├── Cargo.toml               # Rust 依赖
│   ├── tauri.conf.json          # 窗口 / 应用配置
│   ├── src/
│   │   ├── main.rs              # 桌面入口
│   │   └── lib.rs               # Tauri 启动逻辑
│   └── icons/                   # 应用图标
└── frontend/
    ├── package.json              # Vue 3 + Vite + @tauri-apps/cli
    ├── vite.config.js            # 含 Tauri file:// 适配
    └── src/
        ├── App.vue               # 顶栏 + 项目选择器 + 模式切换
        ├── api/                  # API 封装（按模块拆分）
        ├── components/           # 通用组件
        │   ├── ScipModal.vue
        │   ├── ArchitectureMap.vue
        │   ├── CodeHighlights.vue
        │   ├── PatternBadge.vue
        │   └── TodoBoard.vue
        ├── composables/          # 公共组合式函数（useSSE）
        ├── stores/               # Pinia 状态管理
        ├── utils/                # 工具函数
        └── views/
            ├── ChatView.vue      # 对话界面
            ├── DiscoverView.vue  # ★ GitHub 发现推荐
            ├── LearnView.vue     # ★ 项目研读
            ├── MentorView.vue    # ★ 实战护航
            ├── ProjectDetailView.vue
            ├── TemplateMarketView.vue
            ├── AdminPromptView.vue
            ├── ImportView.vue    # 导入界面
            └── ProjectsView.vue  # 项目列表
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
| GET | `/api/codeindex/mode` | 获取当前索引模式 |
| PUT | `/api/codeindex/mode` | 切换索引模式 |
| GET | `/api/codeindex/mode/progress` | SSE 模式切换进度 |

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式对话 |

### 项目

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/project/list` | 项目列表 |
| POST | `/api/project/import` | SSE 导入项目 |
| POST | `/api/project/{id}/reindex` | 重建索引 |
| GET | `/api/project/{id}/impact/{commit}` | SSE 变更影响分析 |

### 发现推荐

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/discover/search` | 学习意图发现 → 返回推荐项目 |
| GET | `/api/discover/import` | SSE 只读导入学习项目 |

### 项目研读

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/study/{projectId}/analyze` | 触发架构分析 |
| GET | `/api/study/{projectId}/highlights` | 代码亮点列表 |
| GET | `/api/study/{projectId}/patterns` | 设计模式标注 |
| GET | `/api/study/{projectId}/learning-path` | 学习路径推荐 |

### 实战护航

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mentor/{projectId}/plan` | 生成护航计划 |
| GET | `/api/mentor/{projectId}/achievements` | 获取成就列表 |

### 文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/doc/upload` | 上传文档 |
| DELETE | `/api/doc/{id}` | 删除文档 |

### 知识图谱

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge/graph/{docId}` | 文档关联图谱 |
| POST | `/api/knowledge/graph/relation` | 添加关系 |

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

# GitHub 发现
app.discover.github-token:       # GitHub API Token（可选，不配也可搜索但限频）
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
|------|------|------|
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
| 桌面壳 | Tauri | 2.x |

---

## 常见场景

### 团队场景

```
用户（架构师）："为什么支付网关要限流？"

  ① LLM 层级分类 → L2（架构层），置信度 0.85
  ② 角色匹配 → ARCHITECT 主层级 [L1, L2] → 置信度 +15%
  ③ Qdrant filter(level IN [1,2]) → 命中限流架构文档
  ④ Neo4j 图谱扩展 → 关联设计原则
  ⑤ LLM 回答 + 层级标签 [L2 架构层]

用户（开发者）："限流怎么配置？"

  ① LLM 层级分类 → L4（实现层），置信度 0.82
  ② 角色匹配 → DEVELOPER 主层级 [L3, L4, L5] → 置信度 +15%
  ③ Qdrant filter(level IN [3,4,5]) → 命中配置指南
  ④ 回答含代码配置示例
```

### 个人学习场景

```
用户："我想学微服务网关"

  ① DevKnow 解析意图 → 提取关键词（微服务、网关、API Gateway）
  ② GitHub 搜索 → 评分排序 → 推荐 top 5 项目
  ③ 用户选中项目 → 自动导入并索引
  ④ 生成架构总览 / 代码亮点 / 设计模式标注
  ⑤ 对话式研读：逐层解剖核心模块
  ⑥ "开始实战" → 生成同风格项目骨架 → 引导编码→部署
```

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

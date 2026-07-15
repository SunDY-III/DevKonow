<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot" alt="Spring Boot 3.2.5">
  <img src="https://img.shields.io/badge/Tree--sitter-0.24.4-important" alt="Tree-sitter 0.24.4">
  <img src="https://img.shields.io/badge/SCIP-1.0.0-blue" alt="SCIP Protocol">
  <img src="https://img.shields.io/badge/Qdrant-1.15.0-blueviolet" alt="Qdrant 1.15.0">
  <img src="https://img.shields.io/badge/LangChain4j-0.36.2-purple" alt="LangChain4j 0.36.2">
  <img src="https://img.shields.io/badge/Neo4j-Embedded-008CC1" alt="Neo4j Embedded">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License MIT">
</p>

<div align="center">
  <h1>DevKnow</h1>
  <p><strong>开发者双通道知识助手</strong></p>
  <p>代码通道（Tree-sitter / SCIP 双模式）| 文档通道（RAG + 层级感知 + 角色感知）| 知识图谱 | 波及重建</p>
</div>

---

## 项目简介

**DevKnow** 是一个面向开发团队的智能知识平台。贴一个 Git 地址，系统自动拉取代码并建立索引，开发者用自然语言提问即可检索代码方法、追溯调用链、查询开发文档。支持**双模式代码索引**（Tree-sitter 轻量级 / SCIP 性能级）、**层级感知检索**（L1~L5 知识层级）和**角色感知**（不同角色优先获取匹配层级的文档）。

**双通道架构：**
- **代码通道**：双模式——Tree-sitter AST 解析（轻量级，默认）或 SCIP 协议索引（性能级，精确符号解析），运行时一键切换
- **文档通道**：Tika 解析 + TextSplitter 分块 + Embedding 向量化 + ngram 关键词，语义理解优先
- **层级感知**：LLM 自动分类问题层级（L1 原则/L2 架构/L3 规范/L4 实现/L5 经验），定向检索目标层级
- **角色感知**：架构师优先 L1~L2、开发者优先 L3~L5，角色主层级匹配提升置信度
- **知识图谱**：Neo4j Embedded 存储文档关联关系，多跳扩展上下文
- **波及重建**：Git diff → 提取变更方法 → 查调用方 → 只重索引波及文件

---

## 核心特性

### 🔍 代码通道（双模式：Tree-sitter / SCIP）

- **双模式架构**：两种互斥模式，运行时通过 API 一键切换，无需重启
  - **Tree-sitter 模式**（默认）：轻量级，纯 Java 实现，零外部依赖，所有语言通用
  - **SCIP 模式**（性能级）：读取 SCIP 协议索引文件，精确到类.方法级别的符号解析，跨文件调用链
- **模式切换流程**：`PUT /api/codeindex/mode` → 检测 index.scip → 不存在则自动调用外部 indexer 生成 → 前端 SSE 阻塞弹窗实时展示进度 → 完成后自动切换
- **ripple 反向索引**：Redis Set 存储"方法名 → 调用方文件"，波及重建时秒级定位影响范围
- **精确查询**：`method_name=` 精确查找 + `ngram FULLTEXT` 关键词 + `ripple SMEMBERS` 调用链

### 📄 文档通道（RAG + 层级感知 + 角色感知）

- **混合检索**：向量相似度（Qdrant）+ MySQL ngram 全文检索（关键词）双通道
- **RRF 融合**：Reciprocal Rank Fusion 规避两路分数量纲不一致
- **层级感知（L1~L5）**：LLM 自动判断问题层级，A 路定向检索 + B 路低置信度补刀
- **角色感知**：7 种知识角色（ARCHITECT/SENIOR_DEV/DEVELOPER/QA/DEVOPS/PM），自动调整搜索层级和置信度
- **知识图谱扩展**：Neo4j Embedded 存储文档关联关系，检索命中后多跳扩展关联上下文
- **引用溯源**：回答附带 `[片段n 来源:file.pdf #行号]` 格式的引用
- **文档全格式支持**：PDF / Word / Markdown / TXT，Apache Tika 统一解析

### 🕸️ 知识图谱（Neo4j Embedded）

- **零外部依赖**：Neo4j 嵌入 JVM 进程运行，无需独立容器
- **自动建关系**：文档上传后 LLM 自动分析摘要，推断 REFERENCES / DEPENDS_ON / EXTENDS / SEQUEL_TO 关系
- **多跳遍历**：Cypher 查询 N 跳关联文档，支持上下游链路追溯
- **REST API**：`/api/knowledge/graph/*` 全套增删查改 + 最短路径 + 统计

### ⚡ 波及重建（增量索引）

- **git diff → ripple 查询 → 重索引波及文件**：只处理变更方法及其调用方，不走全量
- **三写一致性架构**：每个 CodeUnit 同时写入 MySQL + Qdrant 向量 + ripple 缓存
- **新提交检测**：`countCommitsBehind()` 对比 HEAD..origin/main

### 🛡️ 企业级治理

- **语义缓存**：相似问题（余弦阈值 0.95）直接返回历史回答，按来源文档联动失效
- **双维度限流**：Redis + Lua 滑动窗口，用户级 + 接口级独立配额
- **敏感词过滤**：DFA Trie 树，O(n) 多模式匹配
- **Token 审计**：异步落库 + 每日定时日报聚合
- **熔断降级**：Resilience4j CircuitBreaker，模型故障时回退纯检索结果

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         DevKnow 全量架构                                 │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─ 用户入口 ──────────────────────────────────────────────────┐        │
│  │  导入页 (SSE 进度) | 对话页 (层级感知问答) | 搜索页 | 图谱  │        │
│  │  模式切换 (Tree-sitter / SCIP 弹窗)                        │        │
│  └──────────────────────────────────────────────────────────────┘        │
│                                │                                         │
│  ┌─ 双通道并行 ─────────────────────────────────────────────────┐        │
│  │  ┌─────────────────────┐  ┌────────────────────────────┐     │        │
│  │  │  代码通道            │  │  文档通道                  │     │        │
│  │  │  Tree-sitter / SCIP │  │  层级感知 + 角色感知       │     │        │
│  │  │  → ripple           │  │  → A 路定向 / B 路补刀    │     │        │
│  │  └─────────────────────┘  └───────────┬────────────────┘     │        │
│  └──────────────────────────────────────────────────────────────┘        │
│                                    │                                     │
│                                    ▼                                     │
│  ┌─ RRF 融合 + 重排序 ──────────────────────────────────────────┐       │
│  │  A 路结果权重 1.0 | B 路权重 0.6 | 规则重排 TopN             │       │
│  └──────────────────────────┬───────────────────────────────────┘       │
│                             │                                           │
│                             ▼                                           │
│  ┌─ Neo4j 知识图谱扩展 ─────────────────────────────────────────┐       │
│  │  检索命中文档 → 多跳关联 → 附加关联文档 chunk                 │       │
│  └──────────────────────────┬───────────────────────────────────┘       │
│                             │                                           │
│                             ▼                                           │
│  ┌─ 基础设施 ──────────────────────────────────────────────────┐        │
│  │  MySQL | Qdrant | Redis | RabbitMQ | MinIO | Neo4j Embedded │        │
│  │  JWT | 限流 | 熔断 | 敏感词 | 审计 | 语义缓存               │        │
│  └──────────────────────────────────────────────────────────────┘        │
│                             │                                           │
│  ┌─ AI 层 ────────────────────────────────────────────────────┐         │
│  │  LLM (OpenAI 协议) | Embedding | LangChain4j                │         │
│  │  LevelClassifier (层级分类) | CodeReviewAgent               │         │
│  └──────────────────────────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| **框架** | Spring Boot 3.2.5, Java 17 | 基础运行时 |
| **数据层** | Spring Data JPA, MySQL 8 | 持久化 + ngram 全文检索 |
| **向量数据库** | Qdrant 1.15.0（轻量级 Rust 实现） | 文档/代码向量存储，ANN 搜索 |
| **图数据库** | Neo4j 5.26.27 Embedded | 知识图谱，嵌入 JVM 无独立容器 |
| **缓存/会话** | Redis 7 | 聊天记忆、语义缓存、ripple 反向索引、限流 |
| **消息队列** | RabbitMQ | 文档异步解析 |
| **对象存储** | MinIO | 文档原文存档 |
| **代码解析（轻量）** | Tree-sitter 0.24.4 | 默认模式，纯 Java，所有语言通用 |
| **代码解析（性能）** | SCIP Protocol + protobuf | 可选模式，需外部 indexer，精确符号解析 |
| **AI 编排** | LangChain4j 0.36.2 | 模型调用、RAG Pipeline、Agent 编排 |
| **模型协议** | OpenAI 协议 | 兼容 GPT / DeepSeek / 通义 / 智谱等 |
| **文档解析** | Apache Tika | PDF / Word / Markdown / TXT |
| **熔断降级** | Resilience4j 2.2.0 | @CircuitBreaker + fallback |
| **鉴权** | JWT (jjwt) | 支持 Header 与 SSE Query 参数两种传参 |

---

## 快速开始

### 前置条件

- JDK 17+
- Docker & Docker Compose（用于启动中间件）
- Maven 3.8+
- LLM API Key（支持任意 OpenAI 协议兼容服务）

### 1. 启动中间件

```bash
docker compose up -d
# 启动后包含：MySQL, Redis, RabbitMQ, MinIO, Qdrant
# Neo4j 嵌入式运行在应用进程内，无需额外容器
```

启动后导入数据库表结构：

```bash
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/schema-v2.sql
mysql -h127.0.0.1 -uroot -proot123 devknow < backend/sql/migration-v3-level.sql
```

### 2. 配置模型 API

```bash
export LLM_BASE_URL=https://your-gpt-proxy.com/v1
export LLM_API_KEY=sk-xxx
export LLM_CHAT_MODEL=gpt-4o-mini
export EMBEDDING_MODEL=text-embedding-3-small
```

### 3. 编译运行

```bash
cd backend
mvn clean compile -q
mvn spring-boot:run
```

启动后自动执行：
- Qdrant 集合初始化
- Neo4j Embedded 初始化（数据持久化到 `data/neo4j/`）
- 存量文档 LLM 层级分类迁移

### 4. 切换代码索引模式（可选）

默认使用 Tree-sitter 模式。切换到 SCIP 模式：

```bash
# 查看当前模式
curl http://localhost:8080/api/codeindex/mode

# 切换到 SCIP（如果项目目录下有 index.scip 则立即切换，否则自动生成）
curl -X PUT http://localhost:8080/api/codeindex/mode \
  -H "Content-Type: application/json" \
  -d '{"mode": "scip", "projectDir": "/path/to/project"}'

# 前端也支持：侧边栏底部模式徽章 → 下拉菜单 → 点击 SCIP → 阻塞弹窗
```

### 5. 设置知识角色（可选）

```bash
# 登录获取 token
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# 设置角色（架构师 / 开发 / QA 等）
curl -X PUT "http://localhost:8080/api/auth/knowledge-role" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"knowledgeRole":"ARCHITECT"}'
```

### 6. 导入项目

```bash
curl -X POST "http://localhost:8080/api/project/import?repoUrl=https://github.com/user/repo.git"
# SSE 推送进度：clone → scan → create → index → done
```

---

## 项目结构

```
src/main/java/com/devknow
├── DevKnowApplication.java
├── auth/                        # 用户认证 + 知识角色 (UserKnowledgeRole)
├── chat/                        # 对话主链路（双通道决策 + SSE 流式）
├── codeindex/                   # ★ 代码索引核心
│   ├── CodeParser.java          # 双模式门面（Tree-sitter / SCIP 运行时切换）
│   ├── CodeIndexMode.java       # 模式枚举
│   ├── CodeIndexModeService.java # 运行时模式管理 + 异步切换 + SSE 推送
│   ├── CodeUnit.java            # 代码单元模型
│   ├── CodeIndexService.java    # 全量索引 + 波及重建
│   ├── GitRepoManager.java      # Git clone/pull/diff/check
│   ├── GitHistoryIndexer.java   # commit 遍历 + 故障标记
│   ├── CodeUnitEntity.java      # MySQL code_unit 表实体
│   ├── CodeUnitEntityRepository.java # 反向调用链查询
│   ├── tree/                    # Tree-sitter 统一语法解析
│   └── scip/                    # ★ SCIP 索引解析器
│       ├── ScipCodeParser.java  # 从 index.scip 构建 CodeUnit
│       ├── ScipParser.java      # 框架占位（旧版）
│       ├── ScipSymbol.java      # 符号字符串解析
│       ├── ScipConfig.java      # 配置
│       └── ScipIndexGenerator.java # 自动调用外部 indexer CLI
├── codereview/                  # 代码审查 Agent
├── project/                     # ★ 项目管理 + 一键导入
├── knowledge/                   # 文档通道 RAG
│   └── graph/                   # ★ Neo4j 知识图谱
├── rag/                         # RAG 检索链路
│   ├── RagService.java          # 层级感知检索 levelAwareRetrieve()
│   ├── Reranker.java, RrfFusion.java, MmrSelector.java
│   └── QueryExpander.java, RagResult.java
├── vector/                      # ★ Qdrant 向量存储
│   ├── VectorStoreService.java  # searchByLevels 层级过滤
│   ├── QdrantClientManager.java # 懒加载 gRPC 客户端
│   └── VectorRecord.java
├── config/                      # 配置装配
│   ├── QdrantConfig.java        # Qdrant 集合初始化
│   ├── Neo4jConfig.java         # Neo4j Embedded 初始化
│   └── rerank/                  # LevelClassifier, RoleLevelMapper
├── controller/                  # REST API
│   ├── GraphController.java     # 知识图谱管理
│   └── CodeIndexController.java # 模式切换 API + SSE 进度
├── cache/                       # 语义缓存
├── governance/                  # 治理层（限流/熔断/审计/敏感词）
├── migration/                   # LevelMigrationRunner 存量迁移
└── common/                      # 公共组件

src/main/proto/
└── scip.proto                   # SCIP 协议 protobuf 定义

frontend/src/
├── App.vue                      # 侧边栏 + 模式切换下拉菜单
├── components/
│   └── ScipModal.vue            # SCIP 模式切换阻塞弹窗（SSE 进度 + 动画）
├── views/
│   ├── ChatView.vue             # 对话界面
│   ├── ImportView.vue           # 项目导入
│   └── ProjectsView.vue         # 项目列表
└── api/index.js                 # API 封装（含模式管理）
```

---

## 双模式对比

| 特性 | Tree-sitter 模式 | SCIP 模式 |
|------|-----------------|-----------|
| **启动依赖** | 无（纯 Java 实现） | 需要安装 scip-java/scip-go 等外部 indexer |
| **精度** | 方法名级别（语法层） | 类.方法级别（符号层），含类型签名 |
| **调用链** | 方法名匹配 | 精确符号引用（跨文件） |
| **首次索引** | 即时，随项目导入自动完成 | 需要运行 indexer 生成 index.scip |
| **模式切换** | 立即生效 | 可能需要等待索引生成（有阻塞弹窗+进度） |
| **适用场景** | 快速上手、语言杂的项目 | 追求精度的生产项目 |

---

## 层级感知问答示例

```
用户（角色：架构师）："为什么支付网关要限流？"

处理流程：
  ① LLM 层级分类 → L2（架构层），置信度 0.85
  ② 角色匹配 → ARCHITECT 主层级 [L1, L2]，命中 → 置信度 +15% → 0.98
  ③ 检索 → Qdrant filter(level IN [1,2]) → 直接命中《ADR：支付网关防雪崩设计》
  ④ 关键词补充 → 同层级搜索结果
  ⑤ 图谱扩展 → 关联 L1 原则文档《共识：简单可依赖》
  ⑥ LLM 回答 + 层级标签

用户（角色：开发者）："限流怎么配？"

处理流程：
  ① LLM 层级分类 → L4（实现层），置信度 0.82
  ② 角色匹配 → DEVELOPER 主层级 [L3, L4, L5]，命中 → 置信度 +15% → 0.94
  ③ 检索 → Qdrant filter(level IN [3,4,5]) → 命中《限流配置指南》
  ④ 回答含配置示例
```

---

## 核心链路

### 导入链路

```
Git clone → StructureScanner 扫描
  → Tree-sitter 模式：逐文件 Tree-sitter AST → CodeUnit → 三写
  → SCIP 模式：读取 index.scip → 提取符号 → 构建 CodeUnit → 三写
  → 保存 HEAD commit hash → 完成
```

### 文档写入链路

```
上传文件 → MD5 秒传 → MinIO 存原文
  → RabbitMQ → Tika 解析 → TextSplitter 分块
  → 逐块向量化 → MySQL 落 chunk + Qdrant 落向量（含 level）
  → Neo4j 创建文档节点 + LLM 自动建关系
```

### 层级感知检索链路

```
用户提问 + userId
  → 查用户角色（knowledge_role）
  → LLM 层级分类（L1~L5，含置信度）
  → RoleLevelMapper 融合角色+层级
  → A 路：Qdrant searchByLevels（payload filter on level）
  → B 路（低置信度时）：全量补刀降权 0.6
  → MySQL keywordSearchByLevel + RRF 融合 + 重排序
  → Neo4j 知识图谱多跳扩展
  → LLM 生成回答
```

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot" alt="Spring Boot 3.2.5">
  <img src="https://img.shields.io/badge/Tree--sitter-0.24.4-important" alt="Tree-sitter 0.24.4">
  <img src="https://img.shields.io/badge/Milvus-2.5.5-blueviolet" alt="Milvus 2.5.5">
  <img src="https://img.shields.io/badge/LangChain4j-0.36.2-purple" alt="LangChain4j 0.36.2">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License MIT">
</p>

<div align="center">
  <h1>DevKnow</h1>
  <p><strong>开发者双通道知识助手</strong></p>
  <p>代码通道（AST + ripple 反向索引）| 文档通道（RAG + 向量检索）| 波及重建 | Claude Code 集成</p>
</div>

---

## 项目简介

**DevKnow** 是一个面向开发团队的双通道知识平台。贴一个 Git 地址，系统自动拉取代码并建立索引，开发者用自然语言提问即可检索代码方法、追溯调用链、查询故障记录、阅读开发文档。

**双通道架构：**
- **代码通道**：Tree-sitter AST 解析方法粒度 + JavaEnhancer 类型增强 + ripple 反向索引，精度优先
- **文档通道**：Tika 解析 + TextSplitter 分块 + Embedding 向量化 + ngram 关键词，语义理解优先
- **波及重建**：Git diff → 提取变更方法 → 查调用方 → 只重索引波及文件，永不索引过期数据

> 基于 zhishu-ai-agent 的 RAG + LangChain4j Agent 骨架改造，保留了原有治理层（限流/熔断/审计/语义缓存）。

---

## 核心特性

### 🔍 代码通道（AST + 反向索引）

- **AST 方法级索引**：Tree-sitter 统一解析 Java/Python/Go/JS/TS，按方法粒度切分
- **LanguageEnhancer 插件化**：JavaEnhancer 使用 JavaParser 做类型解析，调用链精确到类.方法.参数
- **ripple 反向索引**：Redis Set 存储"方法名 → 调用方文件"，波及重建时秒级定位影响范围
- **精确查询**：`method_name=` 精确查找 + `ngram FULLTEXT` 关键词 + `ripple SMEMBERS` 调用链

### 📄 文档通道（RAG）

- **混合检索**：向量相似度（语义）+ MySQL ngram 全文检索（关键词）双通道
- **RRF 融合**：Reciprocal Rank Fusion 规避两路分数量纲不一致
- **规则重排序**：查询词命中数加权精排 TopN
- **引用溯源**：回答附带 `[片段n 来源:file.pdf #行号]` 格式的引用
- **文档全格式支持**：PDF / Word / Markdown / TXT，Apache Tika 统一解析

### ⚡ 波及重建（增量索引）

- **git diff → ripple 查询 → 重索引波及文件**：只处理变更方法及其调用方，不走全量
- **三写一致性架构**：每个 CodeUnit 同时写入 MySQL + Milvus 向量 + ripple 缓存
- **新提交检测**：`countCommitsBehind()` 对比 HEAD..origin/main，支持 Webhook / 轮询 / 手动三种模式

### 🛡️ 企业级治理

- **语义缓存**：相似问题（余弦阈值 0.95）直接返回历史回答，按来源文档联动失效
- **双维度限流**：Redis + Lua 滑动窗口，用户级 + 接口级独立配额
- **敏感词过滤**：DFA Trie 树，O(n) 多模式匹配，输入输出双向拦截
- **Token 审计**：异步落库 + 每日定时日报聚合
- **熔断降级**：Resilience4j CircuitBreaker，模型故障时回退纯检索结果

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     DevKnow 双通道架构                           │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─ 用户入口（前端）────────────────────────────────────┐       │
│  │  导入页（SSE 进度）| 对话页（双通道问答）| 搜索页       │       │
│  └────────────────────────────────────────────────────────┘       │
│                          │                                       │
│   ┌────── 双通道并行 ──────┐                                     │
│   ▼                        ▼                                    │
│ ┌─────────────────┐  ┌──────────────────────┐                    │
│ │  代码通道        │  │  文档通道（RAG）      │                   │
│ │  （精度优先）     │  │  （语义理解优先）      │                   │
│ │                  │  │                       │                   │
│ │ Tree-sitter AST  │  │ Tika + TextSplitter   │                   │
│ │ → CodeUnit       │  │ → Embedding 向量化    │                   │
│ │ → ripple 索引    │  │ → ngram 全文索引      │                   │
│ └─────────────────┘  └──────────────────────┘                    │
│           │                       │                              │
│           └───────────┬───────────┘                              │
│                       ▼                                          │
│  ┌──────────────────────────────────────────┐                    │
│  │  ChatService 路由决策（代码/文档/融合）    │                   │
│  └──────────────────────────────────────────┘                    │
│                       │                                          │
│                       ▼                                          │
│  ┌─ 基础设施 ──────────────────────────────────────────┐        │
│  │ MySQL | Milvus | Redis | RabbitMQ | MinIO            │        │
│  │ JWT 认证 | 限流 | 熔断 | 敏感词 | 审计              │        │
│  └──────────────────────────────────────────────────────┘        │
│                       │                                          │
│  ┌─ AI 层 ────────────────────────────────────────────┐         │
│  │  LLM (OpenAI 协议) | Embedding | LangChain4j       │         │
│  └──────────────────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────────────┘
```

---

## 技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| **框架** | Spring Boot 3.2.5, Java 17 | 基础运行时 |
| **数据层** | Spring Data JPA, MySQL 8 | 持久化 + ngram 全文检索 |
| **向量数据库** | Milvus 2.5.5（IVF_FLAT 索引） | 文档/代码向量存储，ANN 搜索 |
| **缓存/会话** | Redis 7 | 聊天记忆、语义缓存、ripple 反向索引、限流 |
| **消息队列** | RabbitMQ | 文档异步解析 |
| **对象存储** | MinIO | 文档原文存档 |
| **代码解析** | Tree-sitter 0.24.4 | 多语言 AST 解析（Java/Python/Go/JS/TS） |
| **类型增强** | JavaParser 3.26.2 | JavaEnhancer 插件（ import 解析、调用链精确） |
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
# 等待 Milvus 初始化（首次约 30 秒）
```

启动后导入数据库表结构：

```bash
mysql -h127.0.0.1 -uroot -proot zhishu < sql/schema.sql
mysql -h127.0.0.1 -uroot -proot zhishu < sql/schema-v2.sql
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
mvn clean compile -q
mvn spring-boot:run
```

### 4. 导入项目

```bash
curl -X POST "http://localhost:8080/api/project/import?repoUrl=https://github.com/user/repo.git"
# SSE 推送进度：clone → scan → create → index → done
```

---

## 项目结构

```
src/main/java/com/zhishu
├── ZhishuApplication.java
├── auth/                        # 用户认证
├── chat/                        # 对话主链路（双通道决策）
├── codeindex/                   # ★ 代码索引核心
│   ├── CodeParser.java          # Tree-sitter + LanguageEnhancer 编排
│   ├── CodeUnit.java            # 代码单元模型（双层：基础 + 增强）
│   ├── CodeIndexService.java    # 全量索引 + 波及重建
│   ├── GitRepoManager.java      # Git clone/pull/diff/check
│   ├── GitHistoryIndexer.java   # commit 遍历 + 故障标记
│   ├── LanguageEnhancer.java    # 插件接口
│   ├── LanguageEnhancerRegistry.java
│   ├── CodeUnitEntity.java      # MySQL code_unit 表实体
│   ├── CodeUnitEntityRepository.java # 反向调用链查询
│   ├── tree/                    # Tree-sitter 统一语法解析
│   └── enhance/java/            # JavaEnhancer 类型解析插件
├── codereview/                  # ★ 代码审查 Agent（Phase 3）
├── project/                     # ★ 项目管理 + 一键导入
│   ├── ProjectImportService.java # clone→scan→create→index SSE 编排
│   ├── ProjectController.java   # /api/project/import + Webhook
│   ├── ProjectService.java
│   ├── ProjectContextHolder.java
│   └── StructureScanner.java    # 自动检测语言/框架/模块
├── knowledge/                   # 文档通道 RAG
├── rag/                         # RAG 检索链路
├── vector/                      # Milvus 向量存储
├── cache/                       # 语义缓存
├── governance/                  # 治理层（限流/熔断/审计）
├── config/                      # 配置装配
│   ├── MilvusConfig.java       # Milvus 连接 + 集合初始化
│   └── ...
└── common/                      # 公共组件
```

---

## 双通道对话决策

```
用户问题
   │
   ├── "createOrder 在哪" → 代码通道
   │     AST 解析 → method_name 精确匹配
   │     → ripple 查调用链 → 精确返回
   │
   ├── "为什么用 Redis" → 文档通道 RAG
   │     Embedding 向量化 → 余弦相似度
   │     → 返回设计文档段落
   │
   └── "订单超时怎么办" → 双通道融合
         代码搜 timeout 处理 + 文档搜超时设计
         代码优先展示，文档做补充
```

---

## 核心链路

### 导入链路

```
Git clone（临时目录→rename） → StructureScanner 扫描（语言/框架/入口/模块）
  → 创建项目记录 → 全量索引：
       对每个文件：Tree-sitter AST → CodeUnit
       → JavaEnhancer 类型增强
       → 三写：MySQL code_unit + Milvus 向量 + Redis ripple
  → 保存 HEAD commit hash → 完成
```

### 波及重建链路

```
git pull → 取 lastIndexedCommit → git diff lastCommit HEAD
  → 提取变更方法名
  → MySQL 查 / Redis SMEMBERS "谁调了这些方法"
  → 合并：变更文件 + 调用方文件
  → 只重索引这组文件，不走全量
```

### 文档写入链路

```
上传文件 → MD5 秒传判断 → MinIO 存原文
  → RabbitMQ 投递 → Tika 解析文本
  → TextSplitter 语义分块
  → 逐块向量化 → MySQL 落 chunk + Milvus 落向量
```

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

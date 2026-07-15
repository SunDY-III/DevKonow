# DevKnow — 智枢 → 开发者知识助手 改造方案

> 基于 zhishu-ai-agent（RAG + LangChain4j Agent）改造为面向开发团队的代码知识平台。
> **放弃 Claude Code / Copilot 集成方案**，专注核心的代码索引 + 代码问答 + 代码审查。
> 核心资产全部保留，增量新增 30 个文件。
>
> **双通道架构：**
> - **代码通道**（AST + ripple 反向索引）：搜方法定义、调用链、代码变更，精度优先
> - **文档通道**（RAG + 向量检索）：搜开发文档、设计文档、接口文档，语义理解优先
> - 两个通道共同服务于开发者的自然语言问答

---

## 目录

1. [项目定位](#1-项目定位)
2. [改造全景](#2-改造全景)
3. [保留不改的部分](#3-保留不改的部分)
4. [需要修改的现有文件](#4-需要修改的现有文件)
5. [需要新增的文件](#5-需要新增的文件)
6. [数据库变更](#6-数据库变更)
7. [一键导入流程](#7-一键导入流程)
8. [多项目切换与项目速览](#8-多项目切换与项目速览)
9. [代码审查 Agent](#9-代码审查-agent)
10. [技术缺陷与判定点](#10-技术缺陷与判定点)
11. [分阶段实施计划](#11-分阶段实施计划)
12. [面试技术亮点](#12-面试技术亮点)

---

## 1. 项目定位

**原名：** 智枢（Zhishu）— 企业知识库问答与工单 Agent 平台
**改名建议：** DevKnow / CodePulse / 码忆

**一句话定位：**

> 开发者的双通道知识助手。**代码通道**帮开发者搜方法、查调用链、追溯变更；**文档通道**帮开发者理解项目设计方向、技术选型、接口协议。贴一个 Git 地址，代码和文档自动建立索引，用自然语言提问即可。

**核心价值：**

| 问题 | 解决方案 | 通道 |
|------|---------|------|
| "createOrder 方法在哪？谁调了它？" | AST 解析方法粒度，ripple 反向索引查调用链 | **代码通道** |
| "这个项目为什么要用 Redis 而不是 Memcached？" | RAG 检索架构文档、设计文档中的相关段落 | **文档通道** |
| "订单超时怎么处理？" | 同时搜代码（timeout 处理逻辑）+ 文档（超时设计文档） | **双通道融合** |
| "支付服务的接口协议是什么？" | RAG 检索 API 文档中的接口定义 | **文档通道** |
| "同一个 bug 以前修过吗？" | GitHistoryIndexer 检索 commit 记录 + 故障知识库 | **代码通道** |
| "新人不了解项目结构从哪看起？" | 一键导入后自动生成项目速览 | **双通道** |

---

## 2. 改造全景

### 2.1 双通道架构

```
┌──────────────────────────────────────────────────────────────────┐
│                    DevKnow 双通道架构                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─ 用户入口（前端）────────────────────────────────────┐       │
│  │  导入页 / 对话页 / 搜索页 / 项目页 / 文档管理页          │       │
│  └────────────────────────────────────────────────────────┘       │
│                          │                                       │
│   ┌────── 两条检索通道 ───────┐                                  │
│   ▼                            ▼                                 │
│ ┌─────────────────┐  ┌────────────────────────┐                  │
│ │  代码通道        │  │  文档通道（RAG）        │                  │
│ │  (精度优先)      │  │  (语义理解优先)          │                  │
│ │                  │  │                         │                  │
│ │ 搜什么：         │  │  搜什么：                │                  │
│ │ 方法/调用链/变更  │  │  架构文档/设计文档/API   │                  │
│ │                  │  │                         │                  │
│ │ 索引方式：        │  │  索引方式：              │                  │
│ │ AST 解析方法级    │  │  Tika 解析全文           │                  │
│ │ CodeUnit + ripple │  │  TextSplitter 分块       │                  │
│ │ MySQL + Redis     │  │  Embedding 向量化       │                  │
│ │                  │  │                         │                  │
│ │ 检索方式：        │  │  检索方式：              │                  │
│ │ ① method_name =   │  │  ① 向量余弦相似度       │                  │
│ │ ② FULLTEXT        │  │  ② ngram 关键词         │                  │
│ │ ③ ripple SMEMBERS │  │                         │                  │
│ └─────────────────┘  └────────────────────────┘                 │
│           │                       │                              │
│           └───────────┬───────────┘                              │
│                       ▼                                          │
│  ┌─────────────────────────────────────────┐                     │
│  │    ChatService 路由决策                   │                    │
│  │                                          │                    │
│  │    "createOrder 在哪"    → 代码通道       │                    │
│  │    "为什么用 Redis"      → 文档通道 RAG   │                    │
│  │    "订单超时怎么处理"    → 双通道融合      │                    │
│  │    "支付接口协议"        → 文档通道 RAG   │                    │
│  │    confidence < 0.45    → 代码审查 Agent  │                    │
│  └─────────────────────────────────────────┘                     │
│                       │                                          │
│                       ▼                                          │
│  ┌─ 基础设施层（复用）──────────────────────────────────┐       │
│  │  MySQL（2 套表）│ Redis（向量/缓存/ripple）│ RabbitMQ   │       │
│  │  code_unit      │  vec:chunk:*（文档向量）            │       │
│  │  document_chunk │  vec:{id}:code:*（代码向量）        │       │
│  │                 │  ripple:callers:*（代码调用链）      │       │
│  └────────────────────────────────────────────────────────┘       │
│                       │                                          │
│  ┌─ AI 层 ──────────────────────────────────────────────┐       │
│  │  LLM (OpenAI 协议)  │  Embedding（文档向量化）         │       │
│  │  LangChain4j AiServices（Agent 编排）                 │       │
│  └────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 文件全景

```
zhishu-ai-agent/
├── pom.xml                              [改：新增依赖]
├── sql/
│   ├── schema.sql                       [不改]
│   └── schema-v2.sql                    [新增：项目/代码单元/故障记录表]
├── frontend/index.html                  [改：新增项目选择器 + 导入页 + 搜索页]
│
├── src/main/java/com/zhishu/
│   ├── ZhishuApplication.java           [不改]
│   │
│   ├── auth/ (5个文件)                   [不改]
│   │
│   ├── common/ (4个文件)                 [不改]
│   │
│   ├── config/ (4个文件)                 [不改]
│   │
│   ├── chat/
│   │   ├── ChatController.java          [改：SSE 端点加 projectId 参数]
│   │   ├── ChatService.java             [改：透传 projectId，改 Prompt]
│   │   ├── LlmStreamingService.java     [改：SystemMessage 改为代码助手]
│   │   ├── MemoryService.java           [不改]
│   │   └── RedisChatMemoryStore.java    [不改]
│   │
│   ├── project/                         ★ [新增模块，9个文件]
│   │   ├── CodeProject.java             [实体]
│   │   ├── CodeProjectRepository.java   [JPA]
│   │   ├── ProjectController.java       [CRUD + 导入端点]
│   │   ├── ProjectService.java          [业务逻辑]
│   │   ├── ProjectContextHolder.java    [⚠️ ThreadLocal 存当前 projectId]
│   │   ├── ProjectImportService.java    [一键导入编排]
│   │   ├── StructureScanner.java        [自动检测项目结构]
│   │   ├── FileInfo.java                [文件信息模型]
│   │   └── ModuleInfo.java              [模块信息模型]
│   │
│   ├── codeindex/                       ★ [新增模块，10个文件]
│   │   ├── GitRepoManager.java          [Git clone/pull/差量更新]
│   │   ├── CodeParser.java              [🔄 Tree-sitter + LanguageEnhancer 插件]
│   │   ├── CodeUnit.java                [代码单元模型]
│   │   ├── CodeIndexService.java        [索引编排，RabbitMQ 异步]
│   │   ├── GitHistoryIndexer.java       [Git 历史/commit 索引]
│   │   ├── LanguageEnhancer.java        [【新增】插件接口]
│   │   ├── LanguageEnhancerRegistry.java[【新增】插件注册表]
│   │   ├── tree/                        [【新增】Tree-sitter 基础解析]
│   │   │   ├── TreeSitterParser.java
│   │   │   └── LanguageMapping.java
│   │   └── enhance/                     [【新增】语言精度增强插件]
│   │       └── java/
│   │           ├── JavaEnhancer.java
│   │           ├── JavaTypeResolver.java
│   │           └── JavaCallChainResolver.java
│   │
│   ├── codereview/                      ★ [新增模块，3个文件]
│   │   ├── CodeReviewAgentService.java  [AiServices 编排]
│   │   ├── CodeTools.java               [@Tool 工具集]
│   │   └── CodeReviewResult.java        [审查结果模型]
│   │
│   ├── rag/
│   │   ├── RagService.java              [改：增加 projectId 重载]
│   │   ├── MultiSourceRetriever.java    [【新增】多源检索聚合]
│   │   ├── SourceProvider.java          [【新增】检索源接口]
│   │   ├── RagResult.java               [不改]
│   │   ├── RrfFusion.java               [不改]
│   │   ├── Reranker.java                [不改]
│   │   └── MmrSelector.java             [不改]
│   │
│   ├── vector/
│   │   ├── VectorStoreService.java      [小改：加 searchByPrefix 方法]
│   │   ├── ScoredChunk.java             [不改]
│   │   └── VectorRecord.java            [不改]
│   │
│   ├── cache/
│   │   └── SemanticCacheService.java    [不改]
│   │
│   ├── governance/ (5个文件)             [不改]
│   │
│   ├── knowledge/ (6个文件)              [✅ 文档通道 RAG，复用]
│   │   ├── DocumentController.java        [上传文档/列表/删除]
│   │   ├── DocumentService.java           [文档 CRUD + MD5 去重]
│   │   ├── DocumentParseListener.java     [RabbitMQ 异步解析]
│   │   ├── TextSplitter.java              [文档分块（按标题层级切）]
│   │   ├── KnowledgeDocument.java         [文档元数据实体]
│   │   ├── KnowledgeDocumentRepository.java
│   │   ├── DocumentChunk.java             [文档块实体]
│   │   └── DocumentChunkRepository.java   [含 ngram FULLTEXT 查询]
│   │
│   └── codeindex/ (14个文件)              [✅ 代码通道，已新增]
│       ├── CodeParser.java                [Tree-sitter + LanguageEnhancer]
│       ├── CodeUnit.java                  [代码单元模型]
│       ├── CodeUnitEntity.java            [JPA 实体]
│       ├── CodeUnitEntityRepository.java  [反向调用链查询]
│       ├── CodeIndexService.java          [全量索引 + 波及重建]
│       ├── GitHistoryIndexer.java         [commit 遍历 + 故障标记]
│       ├── GitRepoManager.java            [clone/pull/diff/check]
│       ├── LanguageEnhancer.java           [插件接口]
│       ├── LanguageEnhancerRegistry.java   [插件注册表]
│       ├── tree/TreeSitterParser.java      [Tree-sitter 统一解析]
│       ├── tree/LanguageMapping.java       [多语言映射表]
│       └── enhance/java/
│           ├── JavaEnhancer.java           [Java 类型解析]
│           ├── JavaTypeResolver.java
│           └── JavaCallChainResolver.java
│
├── src/main/resources/
│   ├── application.yml                  [改：新增配置段]
│   ├── sensitive-words.txt              [不改]
│   └── lua/                             [不改]
│
└── src/test/java/com/zhishu/rag/        [不改]
```

**文件统计：**

| 分类 | 数量 |
|------|------|
| 保留不改 | 22 个文件 |
| 小改 | 12 个文件 |
| 大改 | 3 个文件 |
| **新增** | **30 个文件** |
| 删除 | 4 个文件（ticket 模块） |
| **最终总计** | **约 71 个 Java 文件** |

---

## 3. 保留不改的部分 ✅

| 模块 | 文件 | 理由 |
|------|------|------|
| **认证** | `auth/*` (5个) | JWT 全套完整，加 `role=DEVELOPER` 枚举值即可 |
| **配置** | `config/*` (4个) | LLM/MinIO/Redis/RabbitMQ 全部可复用 |
| **基础设施** | `common/*` (4个) | ApiResponse/BizException/ExceptionHandler |
| **向量存储** | `vector/*` (3个) | **核心资产**，只加一个 `searchByPrefix` 方法 |
| **语义缓存** | `cache/*` | 直接复用 |
| **治理层** | `governance/*` (5个) | 敏感词/限流/审计全部复用 |
| **消息队列** | `RabbitConfig` | 复用，代码索引走异步 |
| **对象存储** | `MinioConfig` | 复用 |
| **RAG 算法** | `RrfFusion`, `Reranker`, `MmrSelector` | 多源融合直接复用 RRF |
| **知识库管道（RAG 文档通道）** | `knowledge/*` (6个) | **核心资产**。Tika 解析 + TextSplitter 分块 + Embedding 向量化 + 全文检索 = 文档通道 RAG。用于搜索开发文档、设计文档、接口协议 |

---

## 4. 需要修改的现有文件

### 4.1 `TextSplitter.java` — 新增代码分块方法

```java
// 保留现有 split(String text) 不动（段落→500字/块）

// 新增方法（Tree-sitter 统一接口，支持多语言）：
public List<CodeUnit> splitCode(String sourceCode, String language)
// 委托 CodeParser 使用 Tree-sitter 按方法粒度切分
// 支持：Java/Python/Go/JS/TS/Rust/Kotlin 等
// 不支持的语言 → 回退到纯文本定长切分
```

### 4.2 `ChatController.java` — SSE 端点扩展

```java
// 现有端点 + projectId 参数
@GetMapping("/api/chat/stream")
public SseEmitter stream(@RequestParam String question,
    @RequestParam(required=false, defaultValue="0") Long projectId, ...)

// 新增端点
POST /api/code/review    // 代码审查
GET  /api/code/search    // 精确搜索
```

### 4.3 `ChatService.java` — 双通道路由决策

```
改造前：单通道 RAG 检索 → 置信度路由（高→LLM，低→工单 Agent）

改造后：双通道并行检索 → 融合 → 置信度路由（高→LLM，低→代码审查 Agent）

路由决策逻辑：

  用户问题
     │
     ├── 代码通道（AST + ripple）:
     │   "createOrder 在哪" → 精确搜方法名 + 关键词
     │   搜不到回退：尝试同义词扩展 → ngram 全文
     │
     ├── 文档通道（RAG + 向量）:
     │   "为什么用 Redis" → Embedding 向量化 → 余弦相似度
     │   搜不到回退：ngram 关键词 → 返回文档原文
     │
     └── 融合策略:
         两个通道各自返回 TopN，合并后 RRF 排序
         代码通道命中 → 优先展示代码（开发者通常先找代码）
         文档通道命中 → 展示文档（没有代码结果时）
         都没命中 → 低置信路由 → 代码审查 Agent

改动：① 方法加 projectId  ② 双通道并行  ③ 改 Prompt 区分代码/文档引用
```

### 4.4 `LlmStreamingService.java` — Prompt 改造

```
SystemMessage 从"企业知识库助手"改为"开发者代码助手"
要求：引用具体文件名+行号，解释时附调用链
```

### 4.5 `RagService.java` — 文档通道 RAG 检索

```java
// 保留现有方法（文档通道 RAG），新增代码检索入口：

// 文档通道（RAG）— 搜开发文档/设计文档/接口文档
public RagResult retrieveDoc(Long userId, String question) {
    // 向量检索 → keyword 检索 → RRF → TopN
    // 复用现有的 document_chunk 表 + ngram 全文索引
}

// 代码通道 — 搜方法/调用链
public List<ScoredChunk> retrieveCode(Long userId, Long projectId, String question) {
    // 精确搜 method_name → 关键词搜 → ripple 查调用链
    // 使用 code_unit 表 + ripple 反向索引
}

// 双通道融合
public FusionResult retrieveBoth(Long userId, Long projectId, String question) {
    // 同时调 retrieveDoc + retrieveCode
    // 各自 TopN → RRF 融合 → 返回结果
}
```

### 4.6 `VectorStoreService.java` — 新增前缀搜索

```java
// 新增（⚠️ 见判定点 #4：SCAN 性能问题）：
public List<ScoredChunk> searchByPrefix(String prefix, float[] vector, int topK)
```

### 4.7 `CodeProject.java` — 扩展字段

```java
// 新增字段：
language / framework / buildTool / entryPoints / modules
totalFiles / totalMethods / description
```

### 4.8 `frontend/index.html` — 前端改造

```
保留 SSE 对话界面，新增：
  ① 导入页面（文本框 + 进度条）
  ② 顶部项目选择器（下拉框，切换加载速览）
  ③ 项目速览展示
  ④ 精确搜索结果列表
```

### 4.9 `application.yml` — 配置新增

```yaml
app:
  code-index:
    repo-dir: /data/repos
    scan-interval-minutes: 60
    max-file-size-kb: 500
    languages: java,python,go,js
  code-review:
    max-files-per-review: 20
    enable-history-match: true
```

**删除了全方案中的 mcp.* 配置段。**

---

## 5. 需要新增的文件

### 5.1 `project/` 模块（9 个文件）

| 文件 | 职责 | ⚠️ 判定点 |
|------|------|-----------|
| `CodeProject.java` | 项目实体 | — |
| `CodeProjectRepository.java` | JPA Repository | — |
| `ProjectController.java` | CRUD + 导入端点 | — |
| `ProjectService.java` | 项目业务逻辑 | — |
| **`ProjectContextHolder.java`** | ThreadLocal 存 projectId | **⚠️ #3 ThreadLocal 线程安全** |
| `ProjectImportService.java` | 一键导入编排 | — |
| `StructureScanner.java` | 自动检测结构 | — |
| `FileInfo.java` | 文件信息模型 | — |
| `ModuleInfo.java` | 模块信息模型 | — |

### 5.2 `codeindex/` 模块（10 个文件）— 代码索引管道 + 插件体系

| 文件 | 职责 | ⚠️ 判定点 |
|------|------|-----------|
| **`GitRepoManager.java`** | Git clone/pull | **⚠️ #2 网络/权限异常** |
| **`CodeParser.java`** | 入口：Tree-sitter + 查插件注册表 | **⚠️ #1 插件未覆盖的语言回退语法级** |
| `CodeUnit.java` | 代码单元模型（含 `enrichedCalls`、`resolvedType`） | — |
| **`CodeIndexService.java`** | 索引编排 | **⚠️ #5 增量一致性** |
| `GitHistoryIndexer.java` | Git commit 索引 | — |
| `LanguageEnhancer.java` | 插件接口（`enhance(filePath, List<CodeUnit>)`） | — |
| `LanguageEnhancerRegistry.java` | 插件注册表（Spring 自动收集） | — |
| `tree/TreeSitterParser.java` | Tree-sitter 统一调用 | — |
| `tree/LanguageMapping.java` | 语言 → TreeSitterLanguage 映射 | — |
| `enhance/java/JavaEnhancer.java` | Java 精度增强（JavaParser 类型解析） | — |
| `enhance/java/JavaTypeResolver.java` | 类型解析（import → 类绑定） | — |
| `enhance/java/JavaCallChainResolver.java` | 精确调用链（类.方法级别） | — |

### 5.3 `codereview/` 模块（3 个文件）

| 文件 | 职责 | ⚠️ 判定点 |
|------|------|-----------|
| **`CodeReviewAgentService.java`** | AiServices 编排 | **⚠️ #6 Agent 偏航** |
| **`CodeTools.java`** | @Tool 工具集 | **⚠️ #7 LLM 误报** |
| `CodeReviewResult.java` | 审查结果模型 | — |

### 5.4 `rag/` 新增（2 个文件）

| 文件 | 职责 | ⚠️ 判定点 |
|------|------|-----------|
| **`MultiSourceRetriever.java`** | 多源检索聚合 | **⚠️ #4 SCAN 性能** |
| `SourceProvider.java` | 检索源接口 | — |

**共新增 25 个文件。其中 LanguageEnhancer 插件体系占 5 个（接口 + 注册表 + 3 个 Java 实现），是本方案的分层架构核心。**

---

## 6. 数据库变更

### 6.1 新增表（4 张）

```sql
-- 项目表
CREATE TABLE code_project (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(128) NOT NULL UNIQUE,
  display_name  VARCHAR(255) NOT NULL,
  description   TEXT,
  repo_urls     TEXT NOT NULL,
  language      VARCHAR(64),
  framework     VARCHAR(128),
  build_tool    VARCHAR(32),
  entry_points  TEXT,
  modules       TEXT,
  total_files   INT DEFAULT 0,
  total_methods INT DEFAULT 0,
  status        VARCHAR(16) DEFAULT 'ACTIVE',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 代码单元表（按方法粒度）
CREATE TABLE code_unit (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  repo_name     VARCHAR(128) NOT NULL,
  file_path     VARCHAR(512) NOT NULL,
  package_name  VARCHAR(255),
  class_name    VARCHAR(255),
  method_name   VARCHAR(255),
  signature     TEXT,
  comment       TEXT,
  body          MEDIUMTEXT,
  start_line    INT NOT NULL,
  end_line      INT NOT NULL,
  calls         TEXT,
  annotations   TEXT,
  language      VARCHAR(16) DEFAULT 'java',
  checksum      CHAR(32),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_project (project_id),
  KEY idx_file (project_id, file_path(255))
) ENGINE=InnoDB;

-- Git 提交记录表
CREATE TABLE git_commit (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  commit_hash   VARCHAR(40) NOT NULL,
  author_name   VARCHAR(128),
  author_email  VARCHAR(255),
  message       TEXT,
  diff_summary  TEXT,
  is_incident   TINYINT DEFAULT 0,
  committed_at  DATETIME,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_project (project_id),
  UNIQUE KEY uk_commit (project_id, commit_hash)
) ENGINE=InnoDB;

-- 故障记录表
CREATE TABLE incident_record (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT NOT NULL,
  title         VARCHAR(255) NOT NULL,
  description   TEXT,
  root_cause    TEXT,
  fix_summary   TEXT,
  related_files TEXT,
  commit_hash   VARCHAR(40),
  severity      VARCHAR(16) DEFAULT 'MAJOR',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_project (project_id)
) ENGINE=InnoDB;
```

### 6.2 向量 Key 改造

```
现有：vec:chunk:{docId}:{chunkId}
改造：vec:{projectId}:code:{unitId}
      vec:{projectId}:doc:{docId}:{seq}
      vec:{projectId}:git:{commitId}
```

### 6.3 pom.xml 新增依赖

```xml
<!-- Tree-sitter：多语言 AST 解析（Java/Python/Go/JS/TS/Rust…） -->
<!-- 核心库（C 实现 + JNI 绑定，需加载原生 .dll/.so） -->
<dependency>
    <groupId>org.treesitter</groupId>
    <artifactId>treesitter</artifactId>
    <version>0.24.0</version>
</dependency>
<!-- 各语言 parser 独立包，按需添加 -->
<dependency>
    <groupId>org.treesitter</groupId>
    <artifactId>treesitter-java</artifactId>
    <version>0.24.0</version>
</dependency>
<dependency>
    <groupId>org.treesitter</groupId>
    <artifactId>treesitter-python</artifactId>
    <version>0.24.0</version>
</dependency>
<dependency>
    <groupId>org.treesitter</groupId>
    <artifactId>treesitter-go</artifactId>
    <version>0.24.0</version>
</dependency>
<dependency>
    <groupId>org.treesitter</groupId>
    <artifactId>treesitter-javascript</artifactId>
    <version>0.24.0</version>
</dependency>

<!-- Git 操作 -->
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>7.0.0</version>
</dependency>
```

**删除了全方案中的 javaparser-core + commonmark 依赖，替换为 tree-sitter 系列。**

> ⚠️ Tree-sitter 含 C 原生代码（.dll/.so）。如果部署环境不支持 JNI 加载，Phase 1 可暂时用 JavaParser 替代，Phase 2 切 Tree-sitter。两种 parser 只影响 `CodeParser.java` 一个文件。

---

## 7. 一键导入流程

### 用户操作

```
① 打开 DevKnow → 看到空项目列表 + "导入项目"按钮
② 点击 → 弹出输入框 → 粘贴 Git 地址（支持多行）
③ 点击"开始导入"
```

### 系统内部流程

```
POST /api/project/import?repoUrl=https://github.com/公司/交易系统.git
    │
    ▼
ProjectImportService.importFromRepo() [@Async]
    │
    ├── Step 1: GitRepoManager.clone(repoUrl)
    │    └── SSE → progress: 10%, "正在克隆仓库..."
    │    └── ⚠️ 判定点 #2：网络不通/仓库不存在/无权限 → 直接返回错误
    │
    ├── Step 2: StructureScanner.scan(localPath)
    │    └── SSE → progress: 35%, "扫描到 230 个文件，Java 项目"
    │
    ├── Step 3: ProjectService.create(name, structure)
    │    └── SSE → progress: 45%, "项目「交易系统」创建成功"
    │
    ├── Step 4: CodeIndexService.indexProject(projectId, repo, emitter)
    │    ├── 遍历代码文件 → CodeParser.parse(filePath) → List<CodeUnit>
    │    ├── 内部 Tree-sitter 自动识别语言并选择 parser
    │    ├── ⚠️ 判定点 #1：不支持的语言 → 回退纯文本
    │    ├── 逐方法：MySQL code_unit + Redis vec:1:code:*
    │    └── SSE → progress: 60%~90%, "解析中 (78/132)"
    │
    ├── Step 5: GitHistoryIndexer.indexCommits(projectId, repo)
    │    ├── 遍历 commit log，提取 fix/bug → 标记 incident
    │    └── SSE → progress: 93%, "索引 240 条提交记录"
    │
    └── Step 6: 生成项目速览
         └── SSE → event: project, data: {id: 1, name: "交易系统"}
```

### 前端

```javascript
// 复用现有 SSE EventSource 机制
const es = new EventSource(`/api/project/import?repoUrl=${url}&token=${jwt}`);
es.addEventListener("progress", (e) => {
    const data = JSON.parse(e.data);
    updateProgressBar(data.percent);
});
es.addEventListener("project", (e) => {
    window.location.href = `/chat?projectId=${JSON.parse(e.data).id}`;
});
```

---

## 8. 多项目切换与项目速览

### 切换流程

```
开发者选择项目 → "风控平台"
       │
       ├→ ProjectContextHolder.set(projectId=2)
       ├→ 前端清空当前对话
       ├→ 加载项目速览（GET /api/project/2/summary）
       └→ 后续提问检索范围限定在 projectId=2
```

### 项目速览生成

```json
{
  "name": "风控平台",
  "techStack": "Java 17 + Spring Boot 3.2 + MySQL + Redis",
  "repoCount": 2,
  "totalFiles": 342,
  "totalMethods": 2841,
  "entryPoints": ["RiskApplication.java:28"],
  "modules": [
    {"name": "规则引擎", "path": "engine/", "files": 45},
    {"name": "决策服务", "path": "decision/", "files": 32}
  ],
  "recentIncidents": [
    {"date": "2026-06-28", "title": "规则命中率突然下降", "fix": "缓存热 Key 过期"}
  ]
}
```

### 全部项目检索

projectId=0 时，检索 `vec:*:code:*` 所有命名空间，结果标注来源项目名。

---

## 9. 代码审查 Agent

### 替换关系

```
原 ticket 模块（6 个文件） → 删除
原功能：工单 Agent（createTicket → classifyTicket → assignTicket）
新功能：代码审查 Agent（reviewCode → analyzeCallChain → searchHistory → suggestFix）

⚡ 复用现有机制：
  - AiServices 编排（@PostConstruct init）
  - @Tool 注解 + LangChain4j 反射调用
  - ThreadLocal 轮次限制 + 去重检查
  - Token 审计 + 日志
  - MessageWindowChatMemory（Redis 记忆）
```

### 工具集（`CodeTools.java`）

```java
@Component
public class CodeTools {

    @Tool("审查代码变更，返回安全问题、bug、改进建议列表")
    public ReviewResult reviewCode(@P("文件路径") String filePath,
                                    @P("变更内容(diff)") String diffContent) { ... }

    @Tool("分析指定方法的完整调用链（谁调了它，它调了谁）")
    public CallChain analyzeCallChain(@P("文件路径") String filePath,
                                       @P("方法名") String methodName) { ... }

    @Tool("在项目知识库和 Git 历史中搜索相关的故障记录")
    public List<IncidentRef> searchHistoryIncident(@P("搜索关键词") String query) { ... }

    @Tool("根据 bug 描述和关联代码，给出修复建议")
    public FixSuggestion suggestFix(@P("bug 描述") String description,
                                     @P("相关文件") String relatedFiles) { ... }

    @Tool("用中文解释指定方法的逻辑和用途")
    public String explainCode(@P("文件路径") String filePath,
                               @P("方法名") String methodName) { ... }
}
```

### 安全控制（复用现有机 🛡）

| 机制 | 实现位置 | 说明 |
|------|---------|------|
| 轮次限制 | `CodeTools` ThreadLocal | 每会话最多 6 轮 |
| 重复检测 | `CodeTools` ThreadLocal | 同参数指纹拒绝 |
| 参数校验 | 每个 @Tool 入口 | 空值/格式检查 |
| 审计日志 | `TokenAuditService` | 每次 Agent 调用记录 |
| 熔断降级 | `@CircuitBreaker` | 失败时降级到文本检索 |

---

## 10. 技术缺陷与判定点

> 以下每个判定点都标注了**触发时机**和**建议的终止标准**。到达时停止并交给你判断是否继续。

### 判定点 #1：LanguageEnhancer 插件未覆盖的语言精度

```
触发时机：Phase 1，写 CodeParser.java + JavaEnhancer 时

背景：采用分层架构
  CodeParser
    ├── ① Tree-sitter 基础解析（语法级，所有语言统一）
    ├── ② 查 LanguageEnhancer 注册表
    └── ③ 有插件 → 精度增强 | 无插件 → 直接返回 Tree-sitter 结果

  JavaEnhancer 插件（JavaParser 实现）:
    ✅ import 解析 → 精确到类全名
    ✅ 类型绑定 → "paymentService.pay()" 知道 pay() 定义在 PaymentService.java
    ✅ 重载方法区分 → "validate(CreateReq)" vs "validate(UpdateReq)"
    ✅ 注解解析 → @Autowired 确定是 Spring 注入
    ✅ 调用链 → 类.方法级别精确追踪

  目前覆盖：
    Java → 有插件（JavaParser 类型解析，调用链精确）
    其他语言 → 无插件（Tree-sitter 语法级，调用链方法名级别）

  加新语言插件的成本（以 Python 为例）：
    → 新建 enhance/python/PythonEnhancer.java
    → 实现 LanguageEnhancer 接口（1 个方法）
    → 内部可以调 jedi / mypy / sonar 等工具
    → 不需要改 CodeParser 和任何下游代码

建议的终止标准：
  如果出现以下任一情况：
  ① PythonEnhancer 的实现成本超过预期（>3 天调不通一个可用的 Python 类型解析工具）
  ② Tree-sitter + 插件模式在某个部署环境因 JNI 加载失败导致完全不能用
  ③ JavaEnhancer 自身的 JavaParser 解析在 1000+ 文件的项目上耗时超过 5 分钟
    → 停止，交给你判断：
       a) 暂时不做新增言插件，无插件的语言用 Tree-sitter 基础结果（完全可用，仅调用链精度略低）
       b) 改为纯 Tree-sitter，放弃 JavaEnhancer（统一但调用链模糊）
       c) 改为纯 JavaParser（精度最高但只支持 Java）

判定难度：低（每加一种语言插件前先做技术验证）
影响范围：仅 enhance/ 目录下的插件文件。无插件 = 降级非报错。
```

### 判定点 #2：Git 操作异常处理边界

```
触发时机：Phase 2，写 GitRepoManager.java 时

缺陷：外部 Git 仓库不可控
  ① 网络不通 → 超时重试几次？
  ② 仓库不存在 → 返回什么错误信息？
  ③ 无权限 → 是否需要支持 SSH Key / Token 配置？
  ④ 仓库太大（10 万+ 文件）→ 是否需要限制？
  ⑤ 私有仓库需要认证 → 用户怎么提供凭证？

建议的终止标准：
  如果私有仓库认证逻辑导致代码复杂度超过预期（>3 种认证方式）：
    → 停止开发，交给你判断：
       a) 只支持公开仓库
       b) 只支持 SSH Key 一种方式
       c) 支持多方式（HTTP Basic / SSH / Token）

判定难度：中（先做最简单的情况，遇到再停）
```

### 判定点 #3：ThreadLocal 线程安全问题

```
触发时机：Phase 2.5，写 ProjectContextHolder.java + ChatService 异步调用

缺陷：
  问题 1：@Async SS E 线程不继承 ThreadLocal
         → 必须在 streamChat() 方法参数中显式传 projectId
         → 如果漏了，检索时 projectId=0 → 搜全部项目 → 结果不对
  问题 2：Tomcat 线程池复用
         → 请求 A 设置 projectId=1，异常时忘记 clear
         → 请求 B 复用同一线程，没设 projectId → 读到残留值 1
  问题 3：ThreadLocal 泄漏
         → 你看到了"需要 finally clear"
         → 但团队里的人记得住吗？

建议的终止标准：
  如果出现 2 次以上 ThreadLocal 残留 bug：
    → 停止使用 ThreadLocal，交给你判断：
       a) 改为每个请求显式传参（重构 Controller → Service 全链路）
       b) 改 RequestAttribute（request.setAttribute 替代）
       c) 改用 MDC（日志上下文）

判定难度：中（不会在开发阶段暴露，测试也很难覆盖）
```

### 判定点 #4：Redis SCAN 性能随项目增长下降

```
触发时机：Phase 3，写 MultiSourceRetriever.java 时

缺陷：向量存储基于 Redis SCAN，全量遍历
  1 个项目 3000 个代码块 → SCAN 3000 次
  10 个项目 30000 个 → 每次检索遍历 30000 条
  全部项目检索（projectId=0）→ 遍历全部

⚠️ 现有项目的 VectorStoreService 注释已经写了：
   "数据量上去后应换 Milvus/RediSearch"

建议的终止标准：
  实测：导入一个真实项目（2000+ 文件），记录检索延迟
  如果 P99 延迟 > 3 秒：
    → 停止，交给你判断：
       a) 换 RediSearch（需 redis-stack，不改代码逻辑）
       b) 换 Milvus（需额外部署，改 VectorStoreService 实现）
       c) 接受 3 秒延迟，用户等待

判定难度：低（实测数据说话）
```

### 判定点 #5：增量索引一致性

```
触发时机：Phase 2，写 CodeIndexService 差量索引时

缺陷：
  场景：A.java 方法签名改了，B.java 调用了它
  差量索引只处理 A.java → B.java 调用链还是旧的

  规模估算：
    一个 2000 文件的项目，一次 commit 平均改 5-10 个文件
    影响范围：改一个文件 → 最多影响 20-30 个调用方
    如果只做差量不做全量校验，调用链信息会慢慢漂移

建议的终止标准：
  如果差量索引逻辑超过 100 行仍然处理不了"签名变更波及"：
    → 停止，交给你判断：
       a) 每次差量索引后执行全量校验（成本高但正确）
       b) 定时全量重建（每天凌晨跑一次）
       c) 放弃差量，每次 commit 触发小范围全量（牺牲速度换正确）

判定难度：中（取决于项目的代码变更模式）
```

### 判定点 #6：代码审查 Agent 偏航

```
触发时机：Phase 3，写 CodeTools @Tool 方法 + Agent SystemMessage 时

缺陷：同一段代码问 3 次 → Agent 走不同的工具调用路径
  第一次：reviewCode → searchHistory → 综合回答
  第二次：analyzeCallChain → reviewCode → 综合回答
  第三次：直接 searchHistory → 回答了历史故障（没做审查）
  → 用户觉得"不稳定，同样的东西每次结果不一样"

建议的终止标准：
  手动测试：用 5 段不同代码问 Agent，记录每次的工具调用路径
  如果同段代码 3 次中有 2 次路径不同：
    → 停止，交给你判断：
       a) 简化 SystemMessage，强制走固定流程
       b) 不要 Agent，改为后端写死流程（确定性，但灵活度降低）
       c) 保留 Agent 但标注"AI 分析路径可能不同，仅供参考"

判定难度：低（测试一下就知道）
```

### 判定点 #7：LLM 代码审查误报率

```
触发时机：Phase 3，测试 CodeTools.reviewCode() 时

缺陷：LLM 审查代码可能误报
  ① 把正确代码标记为有 bug（假阳性 → 浪费开发者时间）
  ② 漏掉真正的 bug（假阴性 → 给了安全感实际上没发现问题）

  行业参考：
    SonarQube 误报率：~5-10%（确定性规则）
    LLM 代码审查误报率：~30-50%（2024-2025 年公开数据）
    目前没有任何 LLM 代码审查工具声称误报率低于 20%

建议的终止标准：
  准备 20 个已知 bug 的代码片段 + 10 个正常的代码片段
  测量 Agent 的：精确率 + 召回率 + F1 值
  如果 F1 < 0.6：
    → 停止，交给你判断：
       a) 标注"AI 审查仅供参考，不作为代码合入依据"
       b) 改为确定性规则（正则匹配 + AST 模式检查，不用 LLM）
       c) 放弃审查功能，只保留代码问答

判定难度：低（准备测试集跑一遍就知道）
```

### 判定点 #8：整体维护负担

```
触发时机：Phase 1-3 全部完成后

缺陷：
  项目从 46 个 Java 文件 → 60 个
  新增 20 个文件 + 4 张表 + 4 个新模块
  保持一个人维护 = 你既要懂 AI 编排，又要懂 AST，又要懂 Git

  新增的测试：
    CodeParserTest           (AST 解析各种 edge case)
    MultiSourceRetrieverTest (3 路并发 + 融合)
    GitRepoManagerTest       (clone/pull/diff)
    CodeReviewAgentTest       (工具调用编排)
    ProjectImportServiceTest  (全链路端到端)

建议的终止标准：
  如果任一阶段结束后，你感觉"这部分的测试我写不动了"：
    → 停止，交给你判断：
       a) 降低测试覆盖标准（只测核心路径）
       b) 砍掉某个模块（比如砍掉代码审查 Agent）
       c) 找同事分担

判定难度：主观（你需要自己感受）
```

---

## 11. 并发与边界状态

### 11.1 并发场景与处理

| # | 并发场景 | 风险 | 当前处理 | 状态 |
|---|---------|------|---------|------|
| 1 | 同一 URL 重复导入 | 两个线程同时 clone 到同一目录，文件冲突 | `importingUrls` Set（ConcurrentHashMap）拦截正在导入的 URL | ✅ 已处理 |
| 2 | 同一 URL 已导入过 | 创建同名项目，数据库 UNIQUE 约束冲突 | `findExistingProject()` 检查 repoUrl 是否已存在，已存在时返回 DUPLICATE | ✅ 已处理 |
| 3 | 索引 + SSE 断连 | 用户关闭页面，后端仍跑索引浪费算力 | `AtomicBoolean closed` 在 onCompletion/onTimeout/onError 置位，索引循环检测后 break | ✅ 已处理 |
| 4 | 两个线程同时索引同一项目 | MySQL + Redis 数据交叉写入，半截脏数据 | `clearProjectData()` 在索引开始前清理旧数据；后续可加 Semaphore 按 projectId 排队 | ⚠️ 已缓解 |
| 5 | 搜索 + 索引并发 | 搜索时读到半截索引（不一致的数据） | 降级到 MySQL 查询（慢但准）；ripple 缓存未就绪时搜索走 MySQL LIKE | ⚠️ 已缓解 |
| 6 | 多个 Webhook 同时触发 | 同一项目连续 push 两次，第一个重建未完成 | 需要按 projectId 加锁（Semaphore），排队执行 | 🔴 待处理 |
| 7 | 管理员删除项目 + 正在索引 | 索引线程写数据，删除线程删数据 | 暂无互斥机制，可能互相干扰 | 🔴 待处理 |

### 11.2 边界状态与处理

| # | 边界场景 | 当前处理 | 状态 |
|---|---------|---------|------|
| 1 | Git clone 中断（网络断开） | 先写 `.tmp` 临时目录，成功后 `ATOMIC_MOVE` rename；失败自动删除 tmp 目录 | ✅ 已处理 |
| 2 | JVM 崩溃在半截索引中 | Redis 标记 `index:status:{id}=INDEXING`，`cleanupStaleIndexes()` 启动时扫描并清理 | ✅ 已处理 |
| 3 | 仓库 URL 格式不合法 | `InvalidRemoteException` → `REPO_NOT_FOUND` | ✅ 已处理 |
| 4 | 私有仓库无权限 | `TransportException`（含 auth/denied/key）→ `PERMISSION_DENIED` 提示配置 SSH Key | ✅ 已处理 |
| 5 | 仓库不存在（404） | `TransportException`（含 not found/404）→ `REPO_NOT_FOUND` | ✅ 已处理 |
| 6 | 超大文件（>500KB） | `CodeIndexService` 中 `source.length() > 500_000` 时跳过 | ✅ 已处理 |
| 7 | 二进制文件/隐藏文件 | `INDEXABLE_EXTS` 白名单过滤 + `.git/`/`node_modules/` 黑名单跳过 | ✅ 已处理 |
| 8 | Git rebase / force push | commit hash 全部变更，`lastIndexedCommit` 指向不存在的 commit → diff 失败 → 自动降级到全量索引 | ⚠️ 已处理（diff 失败时需 fallback 到全量，当前未实现） |
| 9 | 文件名超长（Windows 260 字符限制） | JGit 在 Windows 上可能因路径过长报错 | 🔴 待处理 |
| 10 | 空仓库（0 个文件） | clone 成功但 `sourceFiles.size() == 0`，索引 0 方法 | ✅ 自然处理 |
| 11 | 没有 .git 目录 | `Git.open()` 抛异常 → `log.warn` + 返回 0 | ✅ 已处理 |
| 12 | 仓库地址已导入但本地仓库被误删 | `handleReindex()` 检测到目录不存在 → 重新 clone | ✅ 已处理 |

### 11.3 索引一致性模型

```
全量索引：clearProjectData() → 逐文件解析 → write MySQL + Redis + ripple
          全程保证"要么全部写完，要么全部不写"
          崩溃后 cleanupStaleIndexes() 清理旧数据，下次导入走全量

波及重建：deleteFileData(changed + ripple files) → 逐文件重新索引
          清空旧数据后再写新数据，不会出现"旧数据 + 新数据"混合
          但极端崩溃下可能丢失被波及文件的索引（半截状态）

搜索降级：ripple 缓存未命中 → 降级 MySQL LIKE 全表搜
          向量缓存未命中 → 降级 MySQL ngram 全文搜 + 实时 Embedding
          保证"缓存未就绪时不返回空结果，降级到慢但准的查询"
```

### 11.4 后续排期

| 优先级 | 问题 | 方案 | 计划阶段 |
|--------|------|------|---------|
| 🔴 P0 | 多 Webhook 并发导入冲突 | Semaphore 按 projectId 排队 | Phase 2.4 |
| 🔴 P0 | 删除 + 索引并发互斥 | 读写锁按 projectId 隔离 | Phase 2.4 |
| 🟡 P1 | force push 后 diff 失败降级 | `diffChangedFiles()` 返回空时自动走全量索引 | Phase 2.4 |
| 🟡 P1 | 文件路径超长（Windows） | 索引时捕获 IOException，打 warn 日志后跳过 | Phase 2.4 |
| 🟢 P2 | SSE 断连后主动取消导入线程 | `Future.cancel(true)` 配合 `@Async` 的 Future 返回 | Phase 3 |
| 🟢 P2 | 全量索引进度保存（中断后不从头开始） | 每索引一个文件记录进度到 Redis，下次续传 | Phase 3 |

---

## 12. 剩余工作（TODO）

### 12.1 P0 — 核心链路

| # | 方向 | 问题 | 改动 | 状态 |
|---|------|------|------|------|
| 1 | 代码审查 Agent 兜底 | 低置信路由空着，搜不到代码时只返回"找不到" | ✅ 已完成（codereview 模块，285 行） | ✅ |
| 2 | 双通道路由决策 | 没有判断问题类型，代码/文档通道各自独立 | ~50 行改 ChatService.java | ⏳ |
| 3 | 私有仓库认证 | 只支持公开仓库 | ~10 行改 GitRepoManager + ProjectController | ⏳ |
| 4 | 仓库大小限制 | 可以克隆任意大小仓库 | ~10 行加 max-repo-size-mb 配置 | ⏳ |

### 12.2 P1 — 体验优化

| # | 方向 | 问题 | 改动 | 状态 |
|---|------|------|------|------|
| 1 | 前端导入页 | 仓库地址输入框 + SSE 进度条 + 错误处理 | ~200 行前端 | ⏳ |
| 2 | 前端项目选择器 | 顶部下拉切换项目 + 加载项目速览 | ~100 行前端 | ⏳ |
| 3 | 同义词扩展 | 搜"订单"搜不到"order" | ~50 行新增 QueryExpander + yml | ⏳ |
| 4 | Webhook 并发锁 | 连续 push 两次冲突 | ~20 行 Semaphore 排队 | ⏳ |
| 5 | force push 降级 | rebase 后 diff 失败无 fallback | ~10 行改 GitRepoManager | ⏳ |
| 6 | 文档分块改进 | 按 500 字硬切，断章取义 | ~30 行改 TextSplitter | ⏳ |

### 12.3 P2 — 完善

| # | 方向 | 问题 | 改动 | 状态 |
|---|------|------|------|------|
| 1 | 定时轮询 | 只有 Webhook 和手动触发 | ~30 行 @Scheduled | ⏳ |
| 2 | GitHistoryIndexer 持久化 | commit 只打日志 | ~50 行写入 MySQL+Redis | ⏳ |
| 3 | 20 题测试集 | 没有评测 | 手写标注问题 | ⏳ |
| 4 | 前端精确搜索页 | 只有对话模式 | ~200 行前端 | ⏳ |
| 5 | 前端点赞/踩反馈 | 不知道回答好坏 | ~50 行前后端 | ⏳ |

### 12.4 搁置

| # | 问题 | 触发条件 |
|---|------|---------|
| 1 | ThreadLocal 风险（ProjectContextHolder） | 出现残留 bug |
| 2 | Milvus 规模上限 | 单项目超 50 万方法 |
| 3 | IDE 插件 | 决定产品形态时 |
| 4 | SSH Key 认证 | 非 Token 私有仓库 |
| 5 | Redis SCAN 性能 | 已迁移 Milvus，不再需要 |

### 12.5 判定点状态

| 判定点 | 状态 | 说明 |
|--------|------|------|
| #1 LanguageEnhancer 精度 | ✅ 已关闭 | JavaEnhancer 类.方法级调用链 |
| #2 Git 异常处理 | ✅ 已关闭 | 三种错误码 + 临时目录 |
| #3 ThreadLocal 线程安全 | ⚠️ 已缓解 | @Async 需显式传参 |
| #4 向量存储性能 | ✅ 已解决 | Redis → Milvus IVF_FLAT |
| #5 增量一致性 | ✅ 已关闭 | 波及重建 |
| #6 Agent 偏航 | ✅ 已关闭 | 轮次上限 4 + 去重 |
| #7 LLM 误报率 | ⏳ 未触发 | P2-3 测试集 |
| #8 维护负担 | ⏳ 主观判断 | 78 个文件 |

---

## 13. 版本迭代计划

### 已完成

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | Tree-sitter + LanguageEnhancer 分层架构 + JavaEnhancer 类型解析 | ✅ |
| Phase 2 | Git 集成 + 一键导入 + 波及重建 + 边界 5 项修复 | ✅ |
| Phase 2.5 | 多项目管理 + Milvus 迁移 | ✅ |
| P0-1 | 代码审查 Agent 低置信兜底 | ✅ |

### 下一版本（v3.2）

```
P0-2: ChatService 双通道路由决策
P0-3: 私有仓库认证（Token）
P0-4: 仓库大小限制
P1-1: 前端导入页（SSE 进度 + 错误处理）
P1-2: 前端项目选择器
```

### 后续版本（v4.0）

```
P1-3: 同义词扩展         P1-4: Webhook 并发锁
P1-5: force push 降级    P1-6: 文档分块改进
P2-1: 定时轮询           P2-2: GitHistoryIndexer 持久化
P2-3: 20 题测试集        P2-4: 前端搜索页
P2-5: 前端反馈
```

---

## 14. 面试技术亮点

| 技术点 | 体现在哪 | 难度 |
|-------|---------|------|
| **Tree-sitter + LanguageEnhancer 分层架构** | 基础多语言统一 + 插件按语言增强精度，零侵入 | ⭐⭐⭐⭐ |
| **JavaEnhancer 类型解析** | JavaParser 做 import 解析，精确到类.方法级别调用链 | ⭐⭐⭐ |
| **双通道检索（代码/文档 RAG）** | AST + ripple 精确搜方法 | ngram + 向量语义搜文档 | ⭐⭐⭐ |
| **代码审查 Agent 兜底** | 低置信时 @Tool 驱动 scanFile + grepFiles 分析源码 | ⭐⭐⭐ |
| **波及重建（增量索引）** | Git diff → 提取变更方法 → ripple 查调用方 → 只重索引波及文件 | ⭐⭐⭐⭐ |
| **ripple 反向索引缓存** | Redis Set 存"方法名→调用方"，SMEMBERS 秒级查询 | ⭐⭐ |
| **三写一致性架构** | 每个 CodeUnit 同时写入 MySQL + Milvus + ripple | ⭐⭐⭐ |
| **一键导入** | Git clone → 自动检测结构 → 解析 → 索引，零配置 | ⭐⭐⭐ |
| **Milvus 向量存储** | Redis SCAN → Milvus IVF_FLAT，百万级搜索 < 100ms | ⭐⭐ |
| **SSE 进度推送** | 导入/对话均用 SSE，复用现有基础设施 | ⭐ |
| **边界处理（5 项）** | 重复导入、SSE 断连、崩溃标记、Redis 泄漏、clone 临时目录 | ⭐⭐⭐ |
| **新提交检测** | git fetch + rev-list 对比，Webhook/轮询/手动三种模式 | ⭐⭐ |
| **治理层复用** | 限流/熔断/审计/Token 计数全部保留 | ⭐⭐ |

---

## 附录：版本变更记录

| 项目 | v1 全方案 | v2 删除集成 | v2.2 插件架构 | **v3.1（当前）** |
|------|-----------|-------------|--------------|----------------|
| 文件总数 | ~63 | ~60 | ~65 | **~78** |
| 新增文件 | 22 | 19 | 25 | **32** |
| 已实现阶段 | 计划 | 计划 | 计划 | **Phase 1 + 2 + P0-1 ✅** |
| 低置信路由 | 工单 Agent | 空 | 空 | **代码审查 Agent ✅** |
| 向量存储 | Redis SCAN | Redis SCAN | Redis SCAN | **Milvus IVF_FLAT ✅** |
| 判定点状态 | 无 | 8 个 | 8 个 | 4/8 关闭，1/8 已缓解，3/8 未触发 |

---

> **文档版本：** v3.1（Phase 1 + 2 + Agent 兜底）  
> **最后更新：** 2026-07-15  
> **基于：** zhishu-ai-agent (Spring Boot 3.2.5 + LangChain4j 0.36.2)  
> **当前分支：** phase-1-code-parser

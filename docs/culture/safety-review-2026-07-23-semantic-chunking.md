# 安全员审查报告：语义分块（P0-2）

**审查日期**：2026-07-23
**审查范围**：SemanticStructureParser + ContextualDescriptionGenerator + DocumentParseListener 改造
**审查类型**：Diff 层 ✅ | 全量层 ✅

---

## 1️⃣ 线程与并发

### Diff 层
- SemanticStructureParser：纯计算，无锁、无线程、无状态
- ContextualDescriptionGenerator：单次 batch 调用是同步的，在 RabbitMQ consumer 线程中串行执行
- DocumentParseListener：未引入新线程，共用原有 consumer 线程池

### 全量层（审计项目全部线程池配置）
- `DocumentParseListener` 走 RabbitMQ `SimpleMessageListenerContainer`，prefetch=1
  → 一次只处理一个文档，无并发问题
- `LLM_CONCURRENCY_LIMITER`（Semaphore 8）仅在 Chat SSE 路径使用，不在此后台路径中
  → **结论**：不会与在线 LLM 调用竞争资源，但也可能占满 embedding API 配额。当前项目的 embedding API 没有限流保护，latency 敏感场景需注意

**判定**：✅ 通过

---

## 2️⃣ 数据库

### Diff 层
- `document_chunk` 新增 `context_description VARCHAR(500)` 列，已有 migration-v7
- JPA `ddl-auto: none`，依赖 migration 脚本，手动执行

### 全量层（审计全量 JPA 查询模式）

**N+1 风险检查**（扫描全部 Repository）：
- `DocumentChunkRepository` 只有 `findByDocId` / `deleteByDocId`，无关联查询 → ✅
- `DocumentParseListener` 使用 `chunkRepository.saveAll(entities)` 批量写入 → ✅
- `vectorStoreService.saveBatch(records)` 批量写入 → ✅

**长事务检查**：
- `parse()` 方法在 `parse()` 方法内包含以下操作，运行在同一个 consumer 线程中：
  1. Tika 抽取文本（I/O 阻塞）
  2. LLM 嵌入（网络 I/O）
  3. LLM 上下文描述生成（网络 I/O）← **新增**
  4. MySQL batch insert
  5. Qdrant batch upsert
  6. Neo4j 同步
- 这些操作在同一个 `parse()` 调用中串行，不使用 `@Transactional`
- 无数据库事务风险，但整个 parse 过程可能会持续几十秒
  → **monitor**：RabbitMQ 消费者线程可能被长时间占用，堆积时考虑增大 prefetch 或引入超时

**索引检查**：
- `document_chunk` 表中 `doc_id` 有索引（已有），`context_description` 不需要索引（不用于查询条件）

**存量数据兼容**：
- 已有 chunk 的 `context_description = NULL`，embedding 时走旧路径（只用 content），不报错
- 重新解析旧文档才会生成 context_description

**判定**：✅ 通过（建议：关注 RabbitMQ 消费者堆积）

---

## 3️⃣ 安全

### Diff 层
- 无新端点、无用户输入处理、无敏感信息输出
- 日志只打印 docId 和 chunk 数量，不打印用户数据

### 全量层
- 不涉及鉴权 / XSS / SQL 注入 / 敏感信息

**判定**：✅ 通过

---

## 4️⃣ 资源管理

### Diff 层
- Tika/MinIO 流使用 try-with-resources（复用原有代码）
- 无新增文件流、HTTP 客户端、SSE 连接

### 全量层
- `DocumentParseListener` 原有代码已正确处理 MinIO 流（try-with-resources + channel ack/nack）
- parse 失败时 cleanup 逻辑正确：`chunkRepository.deleteByDocId` + `vectorStoreService.deleteByDoc`

**判定**：✅ 通过

---

## 5️⃣ LLM 成本 ← 重点关注

### Diff 层

**新增调用点**：`ContextualDescriptionGenerator.generateBatch()`
- 模型：`fastChatLanguageModel`（轻量模型） ✅
- 频率：每 10 chunk 一批，每批 1 次 LLM 调用
- 成本估算（50 chunk 文档）：
  - 5 批 × 8,600 tokens = 43,000 tokens / 文档
  - 中文文档更贵（同长度更多 token），实际可能翻倍
- 开关注：`app.rag.contextual-description.enabled=true`，可关闭 ✅

**安全设计**：
- 有 try-catch 降级（LLM 调用失败时使用标题/首句作为描述） ✅
- batch 大小可配置 ✅
- 内容截断 800 字，不会超上下文 ✅
- 在后台任务中执行，不影响在线响应延迟 ✅

### 全量层（审计项目全部 LLM 调用点）

| 调用点 | 模型 | 频率 | 成本量级 | 是否必要 |
|--------|------|------|---------|---------|
| `LevelClassifier.classify()` | 主力模型 | 每次 chat | 低（单轮1次） | 可用 fastModel |
| `ChatService.classifyQuestion()` | 主力模型 | 每次 chat | 低 | 可用 fastModel |
| `ChatService.classifyCodeSubRoute()` | 主力模型 | 每次 code 路由 | 低 | ✅ |
| `ChatService.classifyDocSubRoute()` | 主力模型 | 每次 doc 路由 | 低 | ✅ |
| `HydeGenerator.generateHypothesis()` | 主力模型 | 每次 RAG 检索 | 中 | ✅ 影响检索质量 |
| `CrossEncoderReranker.batchScore()` | 主力模型 | 每次 RAG 检索 | 中 | 可用 fastModel（分类任务） |
| `HallucinationGuard.filterRelevantChunks()` | fastModel | 每次 RAG | 中 | ✅ |
| `HallucinationGuard.verifyAnswer()` | fastModel | 每次 chat | 低 | ✅ |
| `HallucinationGuard.traceCitations()` | 主力模型 | 每次 chat | 低 | ✅ |
| `MemoryService.compress()` | fastModel | 记忆超 30 轮时 | 低 | ✅ |
| `ReActAgent` (AiServices) | 流式模型 | 每次 chat | 高 | ✅ 核心功能 |
| **`ContextualDescriptionGenerator`（新增）** | **fastModel** | **每个文档解析** | **中** | **文档上传时为低频** |

**发现**：`LevelClassifier` 和 `classifyQuestion` 用的都是主力模型而非 fastModel。这些是分类任务，可以用 fastModel 替代以节约成本。但不是本次改动的范围，建议单独优化。

**判定**：✅ 通过（新增调用点设计合理，建议后续优化分类任务模型选型）

---

## 6️⃣ 缓存安全

### Diff 层
- 未引入新缓存
- `SemanticCacheService` 不受影响（缓存 key 基于 question+answer，不包含 chunk context）

### 全量层
- 已有设计的联动失效（源文档更新 → `invalidateByDoc` 清除关联缓存）不变
- Qdrant 中新增 `context_description` payload 不影响已有的缓存键和向量

**判定**：✅ 通过

---

## 7️⃣ 边界处理

### Diff 层
- **空值**：`SemanticStructureParser.parse(null)` → 返回 `List.of()` ✅
- **空文本**：`parsePlainText("")` → 返回空列表 ✅
- **无标题文本**：`detectType()` 回退 `PLAIN_TEXT` → 段落聚合 ✅
- **长内容**：LLM prompt 截断 800 字符 ✅
- **无 Markdown 但包含特殊符号**：标题检测不会误判 ✅
- **LLM 返回格式异常**：JSON 解析 try-catch，降级为标题描述 ✅

### 全量层
- 无除零、溢出等数值风险

**判定**：✅ 通过

---

## 8️⃣ 日志

### Diff 层
- `SemanticStructureParser`：无日志（纯计算）
- `ContextualDescriptionGenerator`：`log.info` 完成统计 + `log.warn` 降级 ✅
- `DocumentParseListener`：新增 "chunks={}, contextual={}" 统计 ✅

### 全量层
- 日志不含 token / API key / 密码
- `SLF4J` 绑定无冲突

**判定**：✅ 通过

---

## 审查结论

| 维度 | 判定 | 备注 |
|------|------|------|
| 1️⃣ 线程与并发 | ✅ 通过 | 后台串行，无竞争 |
| 2️⃣ 数据库 | ✅ 通过 | migration-v7 已补，关注消费者堆积 |
| 3️⃣ 安全 | ✅ 通过 | |
| 4️⃣ 资源管理 | ✅ 通过 | |
| 5️⃣ LLM 成本 | ✅ 通过 | 新增调用点在后台任务，有开关可控制 |
| 6️⃣ 缓存安全 | ✅ 通过 | |
| 7️⃣ 边界处理 | ✅ 通过 | |
| 8️⃣ 日志 | ✅ 通过 | |

**总判定**：✅ 可合入。两个注意点已记入 migration 和 monitor 建议。

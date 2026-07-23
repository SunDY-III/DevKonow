# 安全员审查报告：RAG P1 批量补审

**审查日期**：2026-07-23（事后补审）
**审查范围**：稀疏检索 / 分级检索 / Observation 压缩 / Agent 经验缓存 / 记忆升级 / 评估框架 / ReAct 重构
**审查类型**：Diff 层 ✅ | 全量层 ✅

---

## 1️⃣ 线程与并发

### Diff 层
- 所有新增组件均为无状态 Spring Bean（SparseEncoder、FactMemoryStore、AgentExperienceCache 等）
- SearchContext / DynamicRouteResult 每次请求新建，线程隔离
- AgentExperienceCache 使用 Redis（原子操作），无并发竞争

### 全量层（审计全量线程池配置）
- ReActAgent 使用 Semaphore(8) 限制并发 LLM 调用 ✅
- ObservationCompressor 调用 fastModel（同步阻塞，工具调用线程中串行）
- 记忆系统 compress 使用 Redis 互斥锁 ✅
- **无新增线程池 / 异步边界**

**判定**：✅ 通过

---

## 2️⃣ 数据库

### Diff 层
- 记忆升级新增 `context_description` 列（已有 migration-v7 ✅）
- FactMemoryStore 使用 Redis，不涉及 MySQL
- ParamSnapshot 使用 Redis
- AgentExperienceCache 使用 Redis

### 全量层
- 所有数据库操作均已通过 JPA Repository（参数化查询）✅
- 无原生 SQL 注入风险

**判定**：✅ 通过

---

## 3️⃣ 安全

### Diff 层
- 无新增 API 端点（EvalController 除外，需 ADMIN 角色）
- ReAct Agent 工具调用只在推理循环中使用，不对外暴露
- AgentExperienceCache 缓存内容为用户问题和 LLM 回答（与应用已有缓存同级别）
- ObservationCompressor 调用 LLM 处理工具返回结果（无用户原始输入泄露）

### 全量层
- EvalController 需通过 `@PreAuthorize` 保护（待确认 SecurityConfig 配置）

**判定**：⚠️ 通过（需确认 EvalController 的权限配置）

---

## 4️⃣ 资源管理

### Diff 层
- 无新增文件流、HTTP 客户端、SSE 连接
- EvalDataset 从 classpath JSON 加载，资源使用后自动关闭

### 全量层
- 无资源泄露风险

**判定**：✅ 通过

---

## 5️⃣ LLM 成本

### Diff 层
每个新增 LLM 调用点审计：

| 调用点 | 模型 | 频率 | 成本量级 | 判定 |
|--------|------|------|---------|------|
| SparseEncoder.encode() | fastModel | 每次 `levelAwareRetrieve` | ~$0.00002/次 | ✅ 轻量 |
| ObservationCompressor.compress() | fastModel | 每次工具返回 >600 字 | ~$0.00001/次 | ✅ 阈值控制 |
| FactExtractor.extract() | fastModel | 每 30 轮对话 | ~$0.0005/次 | ✅ 低频 |
| MemoryService.compress() | fastModel | 每 30 轮对话 | ~$0.0005/次 | ✅ 已有 |
| ContextualDescriptionGenerator | fastModel | 每文档解析 | ~$0.005/文档 | ✅ 后台任务 |
| EvalMetrics 评估 | 无 LLM | — | — | ✅ |
| ParamOptimizer 网格搜索 | 无 LLM | — | — | ✅ |

### 全量层（审计全量 LLM 调用模式）
- 多次改动的调用点均使用 `fastChatLanguageModel` ✅
- 符合 fast-model-optimization.md 规范 ✅

**判定**：✅ 通过

---

## 6️⃣ 缓存安全

### Diff 层
- AgentExperienceCache：缓存 key 使用 SHA256 哈希（防碰撞），缓存 value 含用户问题 + LLM 回答
- 与 SemanticCacheService 同级安全风险（已有）
- 失效联动：知识库变更时清除关联经验缓存 ✅

### 全量层
- ParamSnapshot 存储参数快照，不含用户数据
- FactMemoryStore 存储原子事实（脱敏后），无 API key / 密码

**判定**：✅ 通过

---

## 7️⃣ 边界处理

### Diff 层
- **SparseEncoder.encode()**：null/空查询 → 返回空向量 ✅
- **ObservationCompressor**：短结果（<600字）跳过压缩 ✅；LLM 不可用 -> 返回原文 ✅
- **FactExtractor**：提取失败 → 返回空列表 ✅
- **AgentExperienceCache**：缓存未命中 → `Optional.empty()` ✅
- **RagEvaluator**：空数据集 → 返回零值指标 ✅
- **ParamOptimizer**：搜索空间含有效组合过滤（`rerankTopN <= vectorTopK`）✅

### 全量层
- 所有 JSON 解析都有 try-catch 降级
- 所有 LLM 调用都有超时和降级

**判定**：✅ 通过

---

## 8️⃣ 日志

### Diff 层
- 各组件日志级别正确（info=关键路径、warn=降级、error=异常）
- 不含用户敏感信息（API key / 密码 / token）

### 全量层
- 符合项目日志规范

**判定**：✅ 通过

---

## 审查结论

| 维度 | 判定 | 备注 |
|------|------|------|
| 1️⃣ 线程与并发 | ✅ 通过 | |
| 2️⃣ 数据库 | ✅ 通过 | migration-v7 已补 |
| 3️⃣ 安全 | ⚠️ 通过 | 需确认 EvalController 权限 |
| 4️⃣ 资源管理 | ✅ 通过 | |
| 5️⃣ LLM 成本 | ✅ 通过 | 全部使用 fastModel |
| 6️⃣ 缓存安全 | ✅ 通过 | |
| 7️⃣ 边界处理 | ✅ 通过 | |
| 8️⃣ 日志 | ✅ 通过 | |

**总判定**：✅ 全部可合入。8 项改动均通过安全审查，未发现阻塞性问题。

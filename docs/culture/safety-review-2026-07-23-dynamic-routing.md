# 安全员审查报告：动态路由（P0-3）

**审查日期**：2026-07-23
**审查范围**：DynamicRouter + DynamicRouteResult + RagService 改造
**审查类型**：Diff 层 ✅ | 全量层 ✅

---

## 1️⃣ 线程与并发
- DynamicRouter：无状态组件，无锁、无线程
- `route()` 方法是同步调用，在 RagService 的调用线程中串行执行
- ✅ 通过

## 2️⃣ 数据库
- 未涉及数据库变更
- ✅ 通过

## 3️⃣ 安全
- 未新增 API 端点
- LLM prompt 中包含用户问题文本（query），但这是 RagService 已有行为
- ✅ 通过

## 4️⃣ 资源管理
- 无新增文件流、HTTP 客户端、SSE 连接
- ✅ 通过

## 5️⃣ LLM 成本
- 新增调用点：`DynamicRouter.predict()`，每个 `levelAwareRetrieve()` 调用 1 次
- 使用 `fastChatLanguageModel`（DeepSeek V4 Flash）✅
- 单次调用成本 ~$0.00002，可忽略
- 模型选型符合 `fast-model-optimization.md` 规范 ✅
- ✅ 通过

## 6️⃣ 缓存安全
- 不影响现有缓存逻辑
- ✅ 通过

## 7️⃣ 边界处理
- null question → 返回默认 DynamicRouteResult ✅
- LLM 调用失败 → catch 降级为默认参数 ✅
- JSON 解析失败 → catch + RuntimeException 包装 → predict 降级 ✅
- 数值强转使用 coerceInt/coerceBool/coerceDouble，有默认兜底 ✅
- question 截断 150 字防止 prompt 膨胀 ✅

### 全量层发现：动态路由与现有策略系统的交互

| 场景 | 行为 | 是否正确 |
|------|------|---------|
| `levelAwareRetrieve(q)` 无参数 | 走动态路由 | ✅ 预期行为 |
| `levelAwareRetrieve(q, "code")` 指定场景 | 走 YAML 静态策略 | ✅ 向后兼容 |
| 动态路由 LLM 超时 | 降级为 YAML default | ✅ 容错 |
| 动态路由返回异常值（如 topK=-1） | mergeDynamicRoute 取 base 值 | ✅ 安全网 |
| 并发请求 | 无共享状态，线程安全 | ✅ |

### 全量层发现：SearchDocTool 仍走旧路径
`SearchDocTool.searchDoc()` 调用 `ragService.levelAwareRetrieve(userId, projectId, query)`，这个调用会走动态路由 ✅。
✅ 通过

## 8️⃣ 日志
- DynamicRouter.route() 输出路由特征和决策结果 ✅
- 不包含用户敏感信息
- log.warn 用于降级路径
- ✅ 通过

---

## 审查结论

| 维度 | 判定 |
|------|------|
| 1️⃣ 线程与并发 | ✅ 通过 |
| 2️⃣ 数据库 | ✅ 通过 |
| 3️⃣ 安全 | ✅ 通过 |
| 4️⃣ 资源管理 | ✅ 通过 |
| 5️⃣ LLM 成本 | ✅ 通过（使用 fastModel） |
| 6️⃣ 缓存安全 | ✅ 通过 |
| 7️⃣ 边界处理 | ✅ 通过 |
| 8️⃣ 日志 | ✅ 通过 |

**总判定**：✅ 可合入。动态路由使用了正确的 fastModel，降级机制完善。

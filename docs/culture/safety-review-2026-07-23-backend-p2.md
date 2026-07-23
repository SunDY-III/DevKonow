# 安全员审查报告：后端 P2 批量补审

**审查日期**：2026-07-23（事后补审）
**审查范围**：DTO/Entity 分离 / 幂等性保障 / 连接池监控 / Docker 镜像优化 / ZGC 评估
**审查类型**：Diff 层 ✅ | 全量层 ✅

---

## 1️⃣ 线程与并发
- DTO 转换：无状态纯函数 ✅
- IdempotencyFilter：无锁，Redis SETNX 原子操作 ✅
- HikariCP metrics：Micrometer 线程安全 ✅
- ZGC：仅 JVM 参数变更，不影响代码逻辑 ✅
- **判定**：✅ 通过

## 2️⃣ 数据库
- V6 migration（processed_events 表）：唯一约束防重复 ✅
- IdempotencyService 使用 Redis（非 MySQL），无事务风险 ✅
- HikariCP leak-detection：仅检测不拦截 ✅
- **判定**：✅ 通过

## 3️⃣ 安全
- DocumentDTO 排除 objectKey、fileMd5、deleted 等内部字段 ✅
- Idempotency-Key 无敏感信息泄露 ✅
- processed_events 表只存 event_id + type，无用户数据 ✅
- **判定**：✅ 通过

## 4️⃣ 资源管理
- Docker 分层 JAR：多阶段构建，清理 builder 层 ✅
- .dockerignore 排除测试文件，减少构建上下文 ✅
- **判定**：✅ 通过

## 5️⃣ LLM 成本
- 本次改动不涉及 LLM 调用 ✅
- **判定**：✅ 通过

## 6️⃣ 缓存安全
- IdempotencyService 缓存 TTL 24h 自动过期 ✅
- 不与语义缓存冲突（key 前缀不同） ✅
- **判定**：✅ 通过

## 7️⃣ 边界处理
- DocumentDTO.from(null) → return null ✅
- IdempotencyKeyFilter.handle(null key) → 放行 ✅
- IdempotencyService.tryAcquire(null) → 放行 ✅
- MessageDeduplicator.tryProcess() → INSERT IGNORE 幂等 ✅
- **判定**：✅ 通过

## 8️⃣ 日志
- 连接泄漏检测：HikariCP 自动 WARN，不打印敏感数据 ✅
- Idempotency 日志：只记录 key hash，不记录完整 key ✅
- **判定**：✅ 通过

---

## 审查结论

| 维度 | 判定 |
|------|------|
| 1️⃣ 线程与并发 | ✅ 通过 |
| 2️⃣ 数据库 | ✅ 通过 |
| 3️⃣ 安全 | ✅ 通过 |
| 4️⃣ 资源管理 | ✅ 通过 |
| 5️⃣ LLM 成本 | ✅ 通过 |
| 6️⃣ 缓存安全 | ✅ 通过 |
| 7️⃣ 边界处理 | ✅ 通过 |
| 8️⃣ 日志 | ✅ 通过 |

**总判定**：✅ 全部可合入，无阻塞性问题。

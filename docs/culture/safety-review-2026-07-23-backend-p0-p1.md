# 安全员审查报告：后端基础设施 P0/P1（批量）

**审查日期**：2026-07-23（事后补审）
**审查范围**：Dependabot + OWASP / Micrometer+Prometheus / correlationId / Testcontainers / JDK 21
**审查类型**：Diff 层 ✅ | 全量层 ✅

---

## 1️⃣ 线程与并发

### Diff 层
- **JDK 21 虚拟线程**：`spring.threads.virtual.enabled=true` → Spring Boot 3.2.5 使用虚拟线程替代平台线程池
- `@Async` 任务 + SSE 流式 → 受益于虚拟线程（轻量、无需池化）
- Semaphore 并发控制逻辑不受影响（信号量语义不变）
- CorrelationIdFilter：无状态，无锁

### 全量层
- **@Async 线程池**：之前用 `ThreadPoolTaskExecutor`（core=8），启用虚拟线程后 `@Async` 自动使用虚拟线程，无需自定义线程池。原有 ThreadPoolConfig.java 中的线程池配置变为无效（不会被使用），应清理或标注 `@Deprecated`。
- **Tomcat**：虚拟线程下 Tomcat 使用虚拟线程处理请求，`server.tomcat.threads.max` 配置不再生效（虚拟线程无上限）。建议移除或注释相关配置。

**判定**：⚠️ 通过（建议后续清理 ThreadPoolConfig.java 和 tomcat.threads.max）

---

## 2️⃣ 数据库

### Diff 层
- 无数据库变更
- Testcontainers 使用独立 MySQL 容器，不影响生产数据

### 全量层
- Testcontainers 的 MySQL 使用 `mysql:8.0` 镜像，与应用生产版本一致
- 测试数据独立，无泄露风险

**判定**：✅ 通过

---

## 3️⃣ 安全

### Diff 层
- **Dependabot**：自动创建依赖更新 PR，需要人工 review 后合并 → 降低供应链攻击风险
- **OWASP Dependency-Check**：CVSS ≥ 7 时构建失败 → 阻断高危漏洞
- **CorrelationIdFilter**：`X-Trace-Id` 请求头反射回响应头 → 无安全风险（只是透传 ID）
- **Micrometer `/actuator/prometheus`**：暴露指标端点 → ⚠️ 生产环境应限制访问（Spring Security 已配置）

### 全量层
- 现有 Spring Security 已配置 API 认证，`/actuator/prometheus` 需显式放行或保护
- Docker Compose 中 Prometheus 通过 `backend-net` 访问 backend，不经过 nginx → 无认证风险（内网）

**判定**：✅ 通过（注意生产环境 `/actuator/prometheus` 的保护）

---

## 4️⃣ 资源管理

### Diff 层
- Testcontainers 容器在测试完成后自动销毁（Ryuk 容器）
- Prometheus + Grafana 容器资源限制已配置（docker-compose）
- JDK 21 镜像从 `17-jre-alpine` 升级到 `21-jre-alpine`，镜像大小基本不变

### 全量层
- Docker Compose 中所有服务已有 resource limits
- 健康探针已配置 liveness/readiness

**判定**：✅ 通过

---

## 5️⃣ LLM 成本

- 本次改动不涉及 LLM 调用
- ✅ 通过

---

## 6️⃣ 缓存安全

- 不影响现有缓存逻辑
- ✅ 通过

---

## 7️⃣ 边界处理

### Diff 层
- **JDK 21 兼容性**：已验证编译和测试通过
- **Testcontainers 降级**：容器不可用时测试跳过（`@Autowired(required=false)`）
- **Dependabot 配置**：周频扫描，PR 上限 10，不会淹没收件箱
- **CorrelationId 自动降级**：前端未传入时自动生成 UUID

### 全量层
- 无除零、溢出等数值风险

**判定**：✅ 通过

---

## 8️⃣ 日志

### Diff 层
- CorrelationIdFilter 在 MDC 中注入 `traceId`，logback JSON 格式已包含 MDC 字段
- 不记录用户敏感信息

### 全量层
- 日志配置已支持 JSON 格式（含 timestamp/thread/logger/level/MDC/stacktrace）

**判定**：✅ 通过

---

## 审查结论

| 维度 | 判定 | 备注 |
|------|------|------|
| 1️⃣ 线程与并发 | ⚠️ 通过 | 建议清理 ThreadPoolConfig |
| 2️⃣ 数据库 | ✅ 通过 | |
| 3️⃣ 安全 | ✅ 通过 | 注意 `/actuator/prometheus` 保护 |
| 4️⃣ 资源管理 | ✅ 通过 | |
| 5️⃣ LLM 成本 | ✅ 通过 | |
| 6️⃣ 缓存安全 | ✅ 通过 | |
| 7️⃣ 边界处理 | ✅ 通过 | |
| 8️⃣ 日志 | ✅ 通过 | |

**总判定**：✅ 全部可合入。建议后续清理 ThreadPoolConfig.java 中已无效的线程池配置。

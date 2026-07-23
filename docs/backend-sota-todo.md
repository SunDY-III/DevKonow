# DevKnow 后端 → SOTA 升级路线图

> 基于 [后端 SOTA 对比](memory://d--DevKnow-DevKnow/backend-sota-comparison-2026-07.md) 2026-07 更新。
> 对比对象：Spring Boot 生态下的 SOTA 工程实践（Micrometer/OTel/Testcontainers/ArchUnit 等）。
> 优先级 P0=阻塞性问题、P1=强烈建议、P2=锦上添花。

## 当前综合评分

```
维度              SOTA 标准    DevKnow    差距
─────────────────────────────────────────────
JDK/框架          4.5          3.5        ⚠️
架构模式          4.0          3.0        ⚠️
可观测性          4.5          1.5        🔴 最大差距
测试              4.5          0.5        🔴 严重缺失
安全              4.0          3.5        ✅
数据层            4.0          3.5        ⚠️
部署运维          4.0          3.0        ⚠️
代码质量          4.0          2.5        ⚠️
─────────────────────────────────────────────
综合              4.06         2.63
```

---

## P0 — 高优先级（安全+可观测，直接影响线上稳定性）

### □ P0-1 可观测性基础设施（Micrometer + Prometheus + Grafana）

**现状**：仅 `@Slf4j` 日志输出，无指标采集、无追踪、无量纲看板。

**SOTA 做法**：Spring Boot 3.x + Micrometer Observation → Prometheus + Grafana + Jaeger。

**实现要点**：
- [ ] `build.gradle` / `pom.xml` 添加 Micrometer + Prometheus 依赖
  ```xml
  <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>
  ```
- [ ] `application.yml` 暴露 Prometheus 端点
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info,metrics,prometheus
    metrics:
      tags:
        application: devknow
  ```
- [ ] Actuator 健康检查增强：Liveness + Readiness + Startup 探针
  ```yaml
  management:
    endpoint:
      health:
        probes:
          enabled: true
  ```
- [ ] 自定义指标埋点：
  - RAG 管道各步骤耗时（向量搜索/关键词/RRF/MMR/ReRank/CRAG）
  - Token 消耗速率（来自 TokenAuditService）
  - 语义缓存命中率
  - CRAG 纠错触发率
  - 幻觉修正率
- [ ] docker-compose 集成 Prometheus + Grafana
- [ ] Grafana 预构建看板（请求量/P99延迟/缓存命中率/Token消耗趋势）

**参考**：Spring Boot Actuator、Micrometer、Prometheus 官方文档

**影响**：🔴 **看不到系统表现就无法衡量所有改进效果** | **人天**：2~3d

---

### □ P0-2 结构化日志 + 分布式追踪

**现状**：纯文本日志，无法跨请求关联、无法链路追踪。

**SOTA 做法**：JSON 结构化日志 + MDC correlationId + OpenTelemetry 分布式追踪。

**实现要点**：
- [ ] Logback 配置 JSON 格式输出（logstash-logback-encoder 已引入）
  ```xml
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>
  ```
- [ ] 请求级 correlationId（`MDC.put("traceId", ...)` via OncePerRequestFilter）
- [ ] OpenTelemetry 依赖引入 + 自动配置
  - `opentelemetry-exporters-otlp`（导出到 Jaeger/Tempo）
  - `opentelemetry-extension-trace-propagators`（W3C Trace Context）
- [ ] docker-compose 集成 Jaeger

**参考**：OpenTelemetry Spring Boot Starter、Logstash Encoder

**影响**：🔴 **无追踪→无法定位性能瓶颈和错误根因** | **人天**：2~3d

---

### □ P0-3 依赖安全扫描

**现状**：无自动化依赖漏洞检测。

**SOTA 做法**：Dependabot / Snyk / OWASP Dependency-Check 集成 CI。

**实现要点**：
- [ ] GitHub Dependabot 配置（`.github/dependabot.yml`）
  ```yaml
  version: 2
  updates:
    - package-ecosystem: "maven"
      directory: "/backend"
      schedule:
        interval: "weekly"
  ```
- [ ] OWASP Dependency-Check Maven 插件
  ```xml
  <plugin>
      <groupId>org.owasp</groupId>
      <artifactId>dependency-check-maven</artifactId>
      <version>10.x</version>
      <configuration>
          <failBuildOnCVSS>7</failBuildOnCVSS>
      </configuration>
  </plugin>
  ```
- [ ] CI 中集成安全扫描步骤

**影响**：🔴 **依赖漏洞是供应链攻击入口** | **人天**：0.5d

---

## P1 — 强烈建议（工程基础，直接影响开发效率和回归风险）

### □ P1-1 测试基础设施（Testcontainers + JUnit 5）

**现状**：几乎无测试，重构无安全网。

**SOTA 做法**：Testcontainers 真实中间件测试 + 分层测试策略。

**实现要点**：
- [ ] Testcontainers 依赖引入
  ```xml
  <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers</artifactId>
      <scope>test</scope>
  </dependency>
  <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mysql</artifactId>
      <scope>test</scope>
  </dependency>
  <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>rabbitmq</artifactId>
      <scope>test</scope>
  </dependency>
  ```
- [ ] 基础测试基类：`AbstractIntegrationTest`（启动 MySQL + Redis + RabbitMQ + Qdrant 容器）
- [ ] 优先覆盖核心 RAG 管道：
  - `RagService.levelAwareRetrieve()` — 给定 query，验证返回 chunks 非空
  - `RagService.exploreRetrieve()` — 轻量路径验证
  - `DynamicRouter.route()` — 动态路由参数合理范围验证
  - `MemoryService.append()` + `load()` — 三层记忆完整性验证
  - `SparseRetrievalService.search()` — 稀疏检索结果验证
- [ ] @WebMvcTest 覆盖 API 端点：
  - `EvalController` — 评估 API 响应结构验证
  - `ChatController` — SSE 流式输出验证
- [ ] JaCoCo 配置 + CI 报告
- [ ] 初始覆盖率目标：40%（核心管道）

**影响**：⭐⭐⭐⭐ | **人天**：5~8d

---

### □ P1-2 JDK 17 → 21 迁移

**现状**：JDK 17 LTS，无法使用虚拟线程。

**SOTA 做法**：JDK 21 LTS + 虚拟线程 + 结构化并发。

**实现要点**：
- [ ] GitHub Actions 中增加 JDK 21 构建验证
- [ ] 评估虚拟线程对 @Async 任务的影响：
  - 启动虚拟线程：`spring.threads.virtual.enabled=true`
  - Spring Boot 3.2+ 已支持虚拟线程
  - @Async + SSE 流式场景是最大受益者
- [ ] JDK 21 新 API 可选采用：
  - `SequencedCollection` / `SequencedMap`（替代部分 List/Map 操作）
  - `String` 新增 `indexOf(String, int, int)` 等
- [ ] 验证现有库兼容性（重点关注 Neo4j Embedded、Tree-sitter、Qdrant client）
- [ ] Docker 基础镜像更新：`eclipse-temurin:21-jdk-alpine`

**影响**：⭐⭐⭐⭐ | **人天**：2~3d

---

### □ P1-3 Flyway 数据库迁移

**现状**：手动 SQL 迁移脚本，JPA `ddl-auto: none`，新增字段需手动执行。

**SOTA 做法**：Flyway / Liquibase 版本化迁移，自动执行。

**实现要点**：
- [ ] Flyway 依赖引入：
  ```xml
  <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
  </dependency>
  ```
- [ ] 现有迁移文件整理：
  - `sql/schema.sql` → `src/main/resources/db/migration/V1__init.sql`
  - `sql/migration-v3-level.sql` → `V2__add_level.sql`
  - `sql/migration-v5-method-call.sql` → `V3__add_method_call.sql`
  - `sql/migration-v6-knowledge-point.sql` → `V4__add_knowledge_point.sql`
  - `sql/migration-v7-context-description.sql` → `V5__add_context_description.sql`
- [ ] `application.yml` 配置：
  ```yaml
  spring:
    flyway:
      enabled: true
      locations: classpath:db/migration
  ```
- [ ] 验证：空数据库启动 → 自动执行全部迁移
- [ ] JPA `ddl-auto` 保持 `none`（由 Flyway 管理）

**影响**：⭐⭐⭐ | **人天**：1~2d

---

### □ P1-4 SonarQube 静态分析

**现状**：无静态分析，代码质量问题靠人工审查。

**SOTA 做法**：SonarQube / SpotBugs 自动检测代码异味、安全漏洞、技术债务。

**实现要点**：
- [ ] Docker Compose 添加 SonarQube 服务
- [ ] Maven `sonar-maven-plugin` 配置
- [ ] CI 集成 `mvn verify sonar:sonar`
- [ ] 质量门禁：新代码覆盖率 ≥ 60%、安全漏洞零容忍、代码异味不新增
- [ ] 首次扫描评估当前技术债务基线

**影响**：⭐⭐⭐ | **人天**：1~2d

---

### □ P1-5 ArchUnit 架构测试

**现状**：无架构约束，包结构随时间腐化。

**SOTA 做法**：ArchUnit 在 CI 中自动验证包依赖方向、分层规则。

**实现要点**：
- [ ] ArchUnit 依赖：
  ```xml
  <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <scope>test</scope>
  </dependency>
  ```
- [ ] 核心架构规则：
  - Controller 层不能直接访问 Repository
  - Service 层不能依赖 Controller 层
  - `com.devknow.rag` 内部包（eval / router / sparse）不能跨依赖
  - 循环依赖检测
- [ ] CI 中自动执行

**影响**：⭐⭐⭐ | **人天**：1d

---

## P2 — 推荐优化（提升质量，非阻塞）

### □ P2-1 DTO/Entity 分离 + 按领域分包

**现状**：部分 API 直接暴露 Entity，包结构按技术分层。

**实现要点**：
- [ ] 审计所有 Controller 返回类型，Entity→DTO 转换
- [ ] 评估包结构调整：`com.devknow.{domain}` vs 当前 `com.devknow.{layer}`
- [ ] `@ControllerAdvice` + RFC 9457 Problem Details 统一错误响应

**人天**：3~5d

---

### □ P2-2 幂等性保障

**现状**：文档上传、消息消费等场景无幂等防护。

**实现要点**：
- [ ] 文档上传：MD5 幂等（已实现 ✅）
- [ ] RabbitMQ 消费者：processed_events 表防重复处理
- [ ] API 端点：`Idempotency-Key` 头支持（关键写操作）

**人天**：2~3d

---

### □ P2-3 数据库连接池监控

**现状**：HikariCP 默认配置，无监控。

**实现要点**：
- [ ] HikariCP Metrics 集成 Micrometer
- [ ] Grafana 看板添加连接池指标（活跃连接/等待/超时）
- [ ] 连接泄漏检测：`leakDetectionThreshold: 60000`

**人天**：0.5d

---

### □ P2-4 Docker 镜像优化

**现状**：标准 JDK 镜像较大（~300MB）。

**实现要点**：
- [ ] 多阶段构建（Maven build → distroless runtime）
- [ ] `eclipse-temurin:21-jre-alpine` 作为运行时基础镜像
- [ ] 减少层大小：`.dockerignore` 排除测试资源

**人天**：1d

---

### □ P2-5 ZGC 评估与配置

**现状**：默认 G1GC，未针对 SSE 流式场景优化。

**实现要点**：
- [ ] 测试环境启用 ZGC：`-XX:+UseZGC -XX:MaxHeapSize=2g`
- [ ] 对比 G1GC vs ZGC 的 SSE 流式响应 P99 延迟
- [ ] 如无明显改善则保持 G1GC（默认已足够）

**人天**：0.5d

---

## 执行路线图

```
第1周（P0 — 可观测 + 安全扫盲）：
  ├── P0-3 依赖安全扫描（0.5d）
  ├── P0-1 Prometheus + Grafana（2d）
  ├── P0-2 结构化日志 + OTel（2d）
  └── P0-1 自定义指标埋点（1d）

第2~3周（P1 — 测试 + 工程质量）：
  ├── P1-1 Testcontainers 测试基础设施（5d）
  ├── P1-3 Flyway 迁移（1d）
  ├── P1-2 JDK 21 迁移（2d）
  └── P1-5 ArchUnit 架构测试（1d）

第4周（P1 — 持续改进 + P2）：
  ├── P1-4 SonarQube 静态分析（1d）
  ├── P2-1 DTO/Entity 分离（3d）
  ├── P2-2 幂等性保障（2d）
  └── P2-4 Docker 镜像优化（1d）
```

---

## 与 RAG TODO 的关系

```
RAG 功能 TODO（rag-sota-todo.md）   后端工程 TODO（backend-sota-todo.md）
─────────────────────────────       ─────────────────────────────
✅ P0 检索管道 3/3 完成              P0 可观测 + 安全（起步中）
✅ P1 记忆/评估等 6/8 完成            P1 测试 + JDK + 迁移
❌ P1 GraphRAG 社区摘要              P1 SonarQube + ArchUnit
❌ P1 双 Agent + 图编排              P2 DTO/幂等/镜像/ZGC

建议策略：交替推进
  单周：RAG 功能（GraphRAG/双Agent）
  双周：后端工程（测试/可观测/JDK）

  确保每次功能增长的交付物都带着测试和指标一起交付。
```

## 注意

- **不推荐并行推动太多项**。P0 的三个可观测项可以并行（依赖不冲突），P1 的测试基础设施应优先于架构调整。
- 测试（P1-1）是投资回报最高的单项——它为后续所有重构和功能开发提供安全网。
- JDK 17→21（P1-2）迁移风险极低（LTS→LTS），但收益显著（虚拟线程）。
- 以上人天均为初次搭建估计。后续日常维护成本远低于搭建成本。

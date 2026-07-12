<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot" alt="Spring Boot 3.2.5">
  <img src="https://img.shields.io/badge/LangChain4j-0.36.2-purple" alt="LangChain4j 0.36.2">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License MIT">
  <img src="https://img.shields.io/badge/LLM-OpenAI%20Protocol-blueviolet" alt="OpenAI Protocol">
</p>

<div align="center">
  <h1>智枢 · Zhishu AI Agent</h1>
  <p><strong>企业知识库问答与智能工单 Agent 平台</strong></p>
  <p>RAG 检索增强生成 + Agent 工具编排双引擎 | SSE 流式输出 | 全链路治理</p>
</div>

---

## 目录

- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [API 文档](#api-文档)
- [项目结构](#项目结构)
- [核心链路](#核心链路)
- [开发指南](#开发指南)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 项目简介

**智枢 (Zhishu)** 是一个面向企业的智能知识库问答与工单自动化平台。它通过 **RAG（检索增强生成）** 让 LLM 基于私有知识库回答问题，并附上片段级引用溯源；当知识库无法解答时，自动激活 **工单 Agent**，通过 Function Calling 完成创建工单、智能分类、负载派单、进度查询的完整业务闭环。

> 适合作为 Java 后端与大模型应用方向的工程实践项目，涵盖从基础设施到 AI 编排的完整技术栈。

---

## 核心特性

### 🔍 RAG 检索增强生成
- **混合检索**：向量相似度（语义）+ MySQL ngram 全文检索（专名/编号）双通道
- **RRF 融合**：Reciprocal Rank Fusion 规避两路分数量纲不一致
- **规则重排序**：查询词命中数加权精排 TopN
- **MMR 多样性选择**：最大边际相关，降低多片段间的语义冗余
- **引用溯源**：回答附带 `[片段n 来源:file.pdf #行号]` 格式的引用

### 🤖 智能工单 Agent
- **ReAct 循环**：LLM 按 Reasoning + Acting 模式自动编排工具调用
- **4 大内置工具**：创建工单 → 智能分类 → 负载派单 → 进度查询
- **可靠性兜底**：轮次限制（防死循环烧 Token）+ 重复调用检测 + 参数校验
- **状态机 + 乐观锁**：工单状态严格流转 + JPA `@Version` 并发控制
- **ZSet 最小负载派单**：Redis 有序集合实时跟踪处理人负载

### ⚡ SSE 流式交互
- **全链路 SSE**：从提问到回答全程 Server-Sent Events 推送
- **多事件协议**：`token`（增量正文）/ `source`（引用来源）/ `cached`（缓存命中）/ `ticket`（转工单）/ `error` / `done`
- **断连自动释放**：AtomicBoolean 检测 + onCompletion 回调，防止连接泄漏

### 🛡️ 企业级治理
- **语义缓存**：相似问题（余弦阈值 0.95）直接返回历史回答，按来源文档联动失效
- **双维度限流**：Redis + Lua 滑动窗口，用户级 + 接口级独立配额
- **敏感词过滤**：DFA（确定性有限自动机）Trie 树，O(n) 多模式匹配，输入输出双向拦截
- **Token 审计**：异步落库 + 每日定时日报聚合
- **熔断降级**：Resilience4j CircuitBreaker，模型故障时回退纯检索结果

---

## 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│  前端 frontend/index.html — SSE 流式对话 / 文档管理            │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP / SSE (JWT Auth)
┌──────────────────────────▼───────────────────────────────────┐
│  Spring Boot 3 应用层                                         │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ 拦截器链                                                │  │
│  │  JwtInterceptor → RateLimitInterceptor → UserContext   │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌──────────┐  ┌─────────────┐  ┌──────────────────────────┐ │
│  │ Chat      │  │ Knowledge   │  │ Ticket Agent             │ │
│  │ SSE 流式   │  │ 文档上传     │  │ @Tool 编排 / ReAct       │ │
│  │ 多轮记忆   │  │ Tika 解析   │  │ 状态机 + 乐观锁          │ │
│  │ 语义缓存   │  │ 语义切分    │  │ 负载派单 (ZSet)          │ │
│  └────┬─────┘  │ 向量化入库   │  └──────────┬───────────────┘ │
│       │        └──────┬──────┘              │                  │
│       ▼               ▼                     ▼                  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ AI 编排层 (LangChain4j)                                 │  │
│  │  · RAG Pipeline (混合检索 + RRF + Rerank + MMR)        │  │
│  │  · AiServices + @Tool (Function Calling)               │  │
│  │  · ChatMemoryStore (Redis 持久化 + 摘要压缩)             │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  中间件                                                       │
│  MySQL │ Redis(缓存/会话/限流/向量/负载) │ RabbitMQ │ MinIO   │
└──────────────────────────────────────────────────────────────┘
```

### 核心流程

```
用户提问
   │
   ├─▶ 敏感词过滤 ──▶ 通过
   │                     │
   │                     ▼
   │              语义缓存查询
   │                     │
   │              ┌──────┴──────┐
   │              │ 命中         │ 未命中
   │              ▼              ▼
   │         返回缓存结果   混合检索（向量 + 关键词）
   │                              │
   │                         RRF 融合 + 重排序
   │                              │
   │                    ┌─────────┴─────────┐
   │                    │ 置信度 ≥ 阈值      │ 置信度 < 阈值
   │                    ▼                    ▼
   │             LLM 流式生成          工单 Agent 接管
   │             + 引用溯源            ReAct 工具编排
   │                    │                    │
   │                    ▼                    ▼
   │              记忆落库 · 缓存回填 · Token 审计
```

---

## 技术栈

| 层次 | 选型 | 说明 |
|---|---|---|
| **框架** | Spring Boot 3.2.5, Java 17 | 基础运行时 |
| **数据层** | Spring Data JPA, MySQL 8 | 持久化 + ngram 全文检索 |
| **缓存/会话** | Redis | 向量存储、聊天记忆、语义缓存、限流、负载 |
| **消息队列** | RabbitMQ | 文档异步解析 |
| **对象存储** | MinIO | 文档原文存档 |
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
```

启动后导入数据库表结构：

```bash
mysql -h127.0.0.1 -uroot -proot zhishu < sql/schema.sql
```

### 2. 配置模型 API

智枢统一使用 OpenAI 协议客户端，支持任意兼容服务：

**第三方 GPT 中转 / OpenAI 官方（需鉴权）：**

```bash
export LLM_BASE_URL=https://your-gpt-proxy.com/v1   # 务必带 /v1
export LLM_API_KEY=sk-xxx
export LLM_CHAT_MODEL=gpt-4o-mini
export EMBEDDING_MODEL=text-embedding-3-small
```

**本地网关（one-api / Ollama 等，多数不校验 key）：**

```bash
export LLM_BASE_URL=http://localhost:3000/v1
# LLM_API_KEY 留空即可
```

### 3. 编译运行

```bash
# 编译
mvn clean compile

# 运行
mvn spring-boot:run
```

浏览器打开 `frontend/index.html`，注册登录后即可使用。

### 4. 运行测试

```bash
mvn test
```

---

## 配置参考

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LLM_BASE_URL` | `https://api.openai.com/v1` | OpenAI 协议地址 |
| `LLM_API_KEY` | (空) | API Key，本地网关可留空 |
| `LLM_CHAT_MODEL` | `gpt-4o-mini` | 对话模型 |
| `LLM_EMBEDDING_BASE_URL` | 同 `LLM_BASE_URL` | Embedding 专用地址 |
| `LLM_EMBEDDING_API_KEY` | 同 `LLM_API_KEY` | Embedding 专用 Key |
| `LLM_EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding 模型 |
| `LLM_EMBEDDING_DIMENSIONS` | `0` | 自定义向量维度（0=模型默认） |
| `LLM_ORG_ID` | (空) | OpenAI Organization |
| `LLM_CUSTOM_HEADERS` | (空) | 自定义请求头，格式 `K1:V1;K2:V2` |
| `LLM_MAX_RETRIES` | `2` | 调用失败重试次数 |
| `LLM_CHAT_TEMPERATURE` | `0.2` | 非流式模型温度 |
| `LLM_STREAM_TEMPERATURE` | `0.3` | 流式模型温度 |

### 应用配置 (`application.yml` 关键段)

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `app.rag.chunk-size` | `500` | 文档切分长度（字符） |
| `app.rag.chunk-overlap` | `80` | 切分滑动窗口重叠 |
| `app.rag.vector-top-k` | `8` | 向量召回数 |
| `app.rag.keyword-top-k` | `8` | 关键词召回数 |
| `app.rag.rerank-top-n` | `4` | 重排后入 Prompt 片段数 |
| `app.rag.confidence-threshold` | `0.45` | 知识库置信度阈值（低于此值转工单） |
| `app.semantic-cache.threshold` | `0.95` | 语义缓存命中阈值 |
| `app.memory.window-size` | `10` | 对话窗口保留轮数 |
| `app.memory.summary-trigger` | `30` | 触发摘要压缩的消息数 |
| `app.agent.max-tool-rounds` | `6` | Agent 单次最大工具调用轮数 |
| `app.rate-limit.user-capacity` | `20` | 用户级限流：每分钟最大请求数 |
| `app.rate-limit.api-capacity` | `200` | 接口级限流 |

---

## API 文档

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 用户注册（账号不存在时自动注册） |
| POST | `/api/auth/login` | 用户登录，返回 JWT Token |

### 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/chat/stream` | SSE 流式对话。参数：`conversationId`, `question`, `token` |

**SSE 事件协议：**

```
event: source     → 引用来源 JSON 数组
event: cached     → 命中语义缓存标记
event: ticket     → 已转工单 Agent 标记
event: token      → 增量生成文本
event: done       → 生成结束
event: error      → 异常信息
```

### 文档管理

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/doc/upload` | 上传文档（multipart，MD5 幂等去重） |
| GET | `/api/doc/list` | 当前用户文档列表 |
| GET | `/api/doc/{id}/progress` | 文档解析进度（前端轮询） |
| DELETE | `/api/doc/{id}` | 删除文档（软删除 + 向量清理 + 缓存失效） |

### 工单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ticket/list` | 当前用户工单列表 |
| POST | `/api/ticket/{ticketNo}/transit` | 人工状态流转 |

---

## 项目结构

```
src/main/java/com/zhishu
├── ZhishuApplication.java       # 启动入口
├── auth/                        # 用户认证（JWT 登录/注册/拦截）
│   ├── AuthController.java
│   ├── JwtInterceptor.java      # 鉴权拦截器，支持 Header & SSE Query 传参
│   ├── JwtUtil.java
│   ├── User.java
│   └── UserRepository.java
├── chat/                        # 对话主链路
│   ├── ChatController.java      # SSE 端点，超时 150s
│   ├── ChatService.java         # 路由编排：敏感词→缓存→RAG→Agent→流式生成
│   ├── LlmStreamingService.java # 流式生成 + @CircuitBreaker 熔断降级
│   ├── MemoryService.java       # 滑动窗口 + 历史摘要压缩
│   └── RedisChatMemoryStore.java# Redis 持久化 ChatMemoryStore
├── knowledge/                   # 知识库（文档上传→解析→切分→向量化）
│   ├── DocumentController.java
│   ├── DocumentService.java     # MD5 幂等上传、软删除、缓存失效联动
│   ├── DocumentParseListener.java# RabbitMQ 消费者：Tika 解析 + 向量化入库
│   ├── DocumentChunk.java
│   ├── DocumentChunkRepository.java# MySQL ngram 全文索引
│   ├── KnowledgeDocument.java
│   ├── KnowledgeDocumentRepository.java
│   └── TextSplitter.java        # 语义切分 + overlap 滑动窗口
├── rag/                         # RAG 检索链路
│   ├── RagService.java          # 混合检索 → RRF → Rerank → 置信度路由
│   ├── RagResult.java
│   ├── RrfFusion.java           # Reciprocal Rank Fusion
│   ├── Reranker.java            # 查询词命中加权重排序
│   └── MmrSelector.java         # MMR 多样性选择
├── ticket/                      # 工单 Agent
│   ├── TicketAgentService.java  # AiServices 编排（@Tool + Memory + LLM）
│   ├── TicketTools.java         # @Tool 工具集 + ThreadLocal 上下文防泄漏
│   ├── TicketService.java       # 业务逻辑 + 状态机 + 乐观锁
│   ├── TicketController.java
│   ├── Ticket.java              # @Version 乐观锁实体
│   ├── TicketStatus.java        # 状态机：PENDING→PROCESSING→RESOLVED→CLOSED
│   ├── TicketRepository.java
│   └── AssignService.java       # Redis ZSet 最小负载派单
├── vector/                      # 向量存储抽象层
│   ├── VectorStoreService.java  # Redis SCAN + 余弦相似度
│   ├── VectorRecord.java
│   └── ScoredChunk.java
├── cache/                       # 语义缓存
│   └── SemanticCacheService.java# 余弦相似度匹配 + 文档联动失效
├── governance/                  # 治理层
│   ├── RateLimitInterceptor.java# Redis+Lua 滑动窗口限流
│   ├── SensitiveWordFilter.java # DFA Trie 敏感词过滤
│   ├── TokenAuditService.java   # Token 审计（@Async 落库 + @Scheduled 日报）
│   ├── TokenUsageLog.java
│   ├── TokenUsageLogRepository.java
│   └── UsageController.java
├── config/                      # 配置装配
│   ├── LlmConfig.java           # LangChain4j 模型工厂
│   ├── MinioConfig.java
│   ├── RabbitConfig.java
│   └── WebConfig.java           # 拦截器注册 + CORS
└── common/                      # 公共组件
    ├── ApiResponse.java
    ├── BizException.java
    ├── GlobalExceptionHandler.java
    └── UserContext.java          # ThreadLocal 用户上下文
```

---

## 核心链路详解

### RAG 读侧链路

```
提问 → 敏感词过滤 → 语义缓存查询
  → 向量召回 (Redis SCAN + 余弦相似度, TopK=8)
  → 关键词召回 (MySQL ngram 全文检索, TopK=8)
  → RRF 融合 (score = Σ 1/(k+rank), k=60)
  → 规则重排序 (查询词命中数加权)
  → 置信度判断 (阈值 0.45)
  → [置信度达标] 组装 Prompt ([片段n] 编号) → LLM 流式生成
  → SSE 推送 token/source/done 事件
  → 回填语义缓存 + Token 审计
```

### Agent 接管链路

```
[置信度 < 0.45] → TicketAgentService
  → AiServices: TicketAssistant.handle(memoryId, question)
  → LLM ReAct 循环:
      感知 → 推理 → 调用 @Tool → 观察结果 → 再推理 → ...
  → 工具集: createTicket → classifyTicket → assignTicket / queryTicketStatus
  → 可靠性兜底: 轮次上限(6) + 同参去重 + 参数校验
  → 返回工单摘要给用户
```

### 文档写入链路

```
上传文件 → MD5 秒传判断 → MinIO 存原文
  → RabbitMQ 投递 → Tika 解析文本
  → 语义段落切分 (chunkSize=500, overlap=80)
  → 逐块向量化 (Embedding Model)
  → MySQL 落 chunk + Redis 落向量
  → 进度上报 (Redis, 前端轮询)
```

---

## 开发指南

### 本地开发环境

```bash
# 启动全部中间件
docker compose up -d

# 持续编译（热重载）
mvn compile -q

# 运行全部测试
mvn test

# 打包
mvn package -DskipTests
```

### 代码规范

- Java 17 语言特性（`record`、`sealed`、模式匹配等）
- Spring 构造器注入（`@RequiredArgsConstructor`）
- RESTful 路径命名，统一响应格式 `ApiResponse`
- 异常由 `GlobalExceptionHandler` 统一处理
- 关键逻辑处附「面试点」中文注释说明设计思路

---

## 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/your-feature`)
3. 提交变更 (`git commit -m 'feat: add some feature'`)
4. 推送到分支 (`git push origin feature/your-feature`)
5. 创建 Pull Request

### Commit 规范

- `feat:` — 新功能
- `fix:` — 缺陷修复
- `test:` — 测试相关
- `docs:` — 文档更新
- `refactor:` — 代码重构
- `perf:` — 性能优化
- `chore:` — 构建/工具链

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2024-present

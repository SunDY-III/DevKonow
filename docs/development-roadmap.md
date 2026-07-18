# DevKnow 发展全景地图

> 基于 2026 年 7 月代码深度审计 + 行业调研（Sourcegraph Cody / GitHub Copilot / Cursor）
> 关联: [待办清单](../TODO.md)

---

## 一、行业定位：DevKnow 在哪

### 竞品格局 (2026)

| 产品 | 定价 | 核心差异化 | DevKnow 可借鉴 |
|------|------|-----------|---------------|
| GitHub Copilot | $10-39/月 | Agent Mode (65 tools), 语义代码搜索, 自定义 Agent (.agent.md), MCP | Agent 化架构、语义代码搜索 |
| Sourcegraph Cody | $59/月 (Enterprise Only) | 多仓库上下文, Auto-Edit, 提示库, 安全过滤器 | 跨仓库检索、Auto-Edit 能力 |
| Cursor | $20-40/月 | IDE 深度绑定, Agent 模式, Composer | 上下文管理、多文件编辑 |
| **DevKnow** | **自托管** | **双通道 RAG + Neo4j 图谱 + 自研重排序 + 任意 LLM** | — |

### DevKnow 的独特优势

1. **自托管** — 代码不出内网，适合金融/政务/军工等合规场景
2. **双通道索引** — Tree-sitter（轻量）+ SCIP（精确），运行时切换
3. **Neo4j 知识图谱** — 文档间关系 + LLM 自动建边，竞品多无此能力
4. **端到端自研 RAG** — 6 级管道（扩展→检索→RRF→MMR→Rerank→图谱扩展），每个环节可控
5. **任意 LLM** — 兼容 OpenAI 协议，不必绑定特定供应商
6. **代码审查 Agent 兜底** — 纯检索失败时有 Agent 自主 scanFile + grepFiles 兜底

---

## 二、算法优化空间（7 个方向）

### 方向 1: 查询理解 — 从"简单扩展"到"深度理解"

```
当前: 同义词扩展 (YAML) + LLM 路由分类 (code/doc/both)
↓
目标: 多策略查询理解引擎
```

| 技术 | 当前 | 目标方案 | 工作量 | 收益 |
|------|------|---------|--------|------|
| HyDE（虚构文档嵌入） | ❌ 无 | 先生成虚构回答，用其 embedding 检索 | ~3d | Recall↑10-15% |
| 查询重写（Query Rewriting） | ❌ 无 | LLM 将口语化问题重写为检索友好的关键词组合 | ~2d | 复杂查询↑20% |
| 查询分解 | ❌ 无 | 复杂问题拆成多个子问题分别检索 | ~5d | 多跳问题↑30% |
| Step-Back Prompting | ❌ 无 | 先问"需要什么背景知识"，再检索 | ~2d | 抽象问题↑15% |
| 路由分类器 | ✅ LLM 调用 | 改用 BERT 小模型替代 LLM | ~3d | 延迟↓90%, 成本↓95% |

### 方向 2: 检索策略 — 从"静态混合"到"自适应多路"

```
当前: 向量搜索 (by levels) + 关键词搜索 (ngram) → RRF 融合
↓
目标: 动态路由的混合检索矩阵
```

| 技术 | 说明 | 工作量 | 优先级 |
|------|------|--------|--------|
| Sparse-Dense Hybrid | Qdrant 原生支持稀疏向量 | ~5d | P1 |
| Late Interaction (ColBERT style) | Token 级交互评分 | ~7d | P2 |
| 多向量检索 | 标题+摘要+正文多路召回归并 | ~4d | P1 |
| 自适应 Top-K | 按层级/置信度动态调整 | ~2d | P0 |
| 时间衰减 | 新文档权重更高 | ~1d | P0 |

### 方向 3: 重排序 — 从"启发式加权"到"学习排序"

```
当前: 5 因子加权 (0.4 RRF + 0.2 代码重叠 + 0.2 路径匹配 + ...)
↓
目标: Cross-encoder + 启发式混合
```

| 技术 | 说明 | 工作量 | 优先级 |
|------|------|--------|--------|
| Cross-encoder 重排 | BAAI BGE / Cohere rerank | ~3d | **P0** |
| LambdaRank / ListNet | 用点赞数据训练排序模型 | ~10d | P2 |
| 调用链传播增强 | A→B→C 多跳调用链评分 | ~3d | P1 |

### 方向 4: 多样性 — 从"MMR 硬编码"到"自适应覆盖"

| 技术 | 说明 | 工作量 | 优先级 |
|------|------|--------|--------|
| **修复 MMR 图衰减实现** | 按真实 hops 计算 α，而非硬编码 0.89 | ~1d | **⬅️ P0 紧急** |
| 自适应 λ | 问题复杂度→自动调节权重 | ~3d | P1 |
| DPP (Determinantal Point Process) | 数学上更优雅的多样性方案 | ~5d | P2 |
| 主题覆盖度 | 确保 TopN 覆盖所有子主题 | ~4d | P1 |

### 方向 5: 知识图谱 — 从"文档关系"到"代码+文档双图谱"

```
当前: Neo4j 文档节点 (4 种关系类型) + LLM 自动建边
↓
目标: 代码实体图谱 + 文档图谱 + 跨图谱桥接
```

| 技术 | 说明 | 工作量 | 优先级 |
|------|------|--------|--------|
| GraphRAG 社区检测 | Leiden 算法聚类 + 社区摘要 | ~7d | P1 |
| 代码符号图谱 | 类/方法/接口写入 Neo4j | ~10d | P1 |
| 代码⇄文档桥接 | 自动建立跨图谱边 | ~5d | P2 |
| 图谱可视化 | 前端展示文档/代码关系图 | ~10d | P2 |
| 增量图谱更新 | 变更时只更新受影响子图 | ~3d | P1 |

### 方向 6: 代码理解 — 从"符号索引"到"代码智能"

```
当前: Tree-sitter AST + SCIP 符号 + ripple 调用链反向索引
↓
目标: 全量代码图 + 数据流 + 类型推理
```

| 能力 | 当前 | 目标 | 工作量 | 优先级 |
|------|------|------|--------|--------|
| 代码调用图可视化 | ✅ 部分调用链 | 交互式全量调用图浏览器 | ~15d | P1 |
| Go To Definition | ❌ 无 | SCIP/LSP 符号跳转 | ~10d | **P0** |
| Find References | ❌ 无 | 跨文件符号引用搜索 | ~7d | P1 |
| 数据流分析 | ❌ 无 | 跟踪变量创建/修改/传递 | ~20d | P2 |
| 类型推理 | ❌ 无 | 动态语言中推断类型 | ~15d | P2 |
| Tree-sitter+SCIP 混合 | ❌ 互斥 | Tree-sitter 基础解析 + SCIP 符号增强 | ~5d | **P0** |
| **LIKE 查询替换** | ❌ 瓶颈 | 倒排索引或关联表替代 LIKE %name% | ~5d | **⬅️ P0 紧急** |
| **buildVectorText 含 body** | ❌ 丢失语义 | 将方法 body 纳入向量化文本 | ~2d | **P0** |
| 跨语言符号桥接 | ❌ 无 | Java↔JS↔Go 调用链 | ~10d | P2 |

### 方向 7: Agentic RAG — 从"单轮检索"到"多轮推理"

```
当前: 检索 → LLM 生成 (单轮)
↓
目标: 规划 → 检索 → 评估 → 重检索 → 生成 (多轮 Agent 循环)
```

| 技术 | 说明 | 工作量 | 优先级 |
|------|------|--------|--------|
| Self-RAG | 自评文档质量：有用/无用/矛盾 | ~7d | P1 |
| CRAG (Corrective RAG) | 评估器 3 档 + 触发补搜 | ~5d | **P0** |
| 迭代检索 | 首轮不够→构造新 query→再检索 | ~5d | P1 |
| 多步推理 | 复杂问题拆解为推理链 | ~10d | P2 |
| **Agent 工具增强** | 添加调用链查询、git diff、批量分析工具 | ~7d | **P0** |
| **语义缓存用 Qdrant 替代 Redis SCAN** | 向量近似度搜索替代全量 SCAN | ~5d | **P0** |
| **影响分析传入 diff 内容** | 给 LLM 传实际代码变更而非仅路径 | ~3d | **P0** |

---

## 三、新功能拓展方向（5 个产品方向）

### 方向 A：IDE 集成（对标 Copilot/Cody）

| 能力 | 依赖 | 工作量 |
|------|------|--------|
| VS Code 插件 | SCIP + 检索 API | ~15d |
| 代码侧边栏问答 | WebSocket + Chat API | ~10d |
| 代码自动补全 | SCIP 索引 + 补全模型 | ~10d |
| Inline Edit | 代码 diff + LLM 生成 | ~7d |
| JetBrains 插件 | VS Code 插件基础上 | ~20d |

### 方向 B：代码智能（对标 Sourcegraph Code Graph）

| 能力 | 依赖 | 工作量 |
|------|------|--------|
| 代码图谱浏览器 | Neo4j + 前端 D3.js | ~15d |
| 符号精确搜索 | SCIP 符号表 | ~7d |
| 代码视图（行内引用） | 前端语法高亮 | ~10d |
| PR Diff 影响分析 | ImpactAnalysis 增强 | ~5d |

### 方向 C：自动化代码审查

| 能力 | 依赖 | 工作量 |
|------|------|--------|
| 自动 PR Review | Webhook + CodeReviewAgent | ~10d |
| Bug 定位 + Fix 建议 | ImpactAnalysis + CodeGen | ~10d |
| 代码规范检查 | SCIP AST + 自定义规则 | ~7d |
| 技术债追踪 | 重复代码/复杂度检测 | ~7d |

### 方向 D：知识管理平台

| 能力 | 依赖 | 工作量 |
|------|------|--------|
| 自动文档生成 | CodeUnit → LLM 生成 | ~10d |
| ADR 自动链接 | KnowledgeGraph 增强 | ~5d |
| 知识图谱可视化 | Neo4j + 前端 | ~10d |
| 文档-代码偏差检测 | Tree-sitter + DocumentService | ~7d |

### 方向 E：多仓库企业级

| 能力 | 依赖 | 工作量 |
|------|------|--------|
| 跨仓库搜索 | VectorStore 多 collection | ~7d |
| 依赖追踪 | 跨项目调用链 | ~10d |
| RBAC 权限模型 | 用户系统 + 仓库粒度 | ~5d |
| 批量自动导入 | GitHub/GitLab API | ~5d |

---

## 四、推荐路线图

### 短期（1-2 月）— 算法硬伤修复 + 体验补齐

```
P0 — 算法 Bug 修复 (4 项)
├── MMR 图衰减公式修复 (1d)
├── Cross-encoder 重排引入 (3d)
├── LIKE %methodName% → 倒排索引/关联表 (5d)
├── buildVectorText 加入方法 body (2d)
└── CRAG 纠错评估器 (5d)

P1 — 前端补齐 (3 项)
├── Deep Search 按钮 + Agent 思考轨迹 (5d)
├── 点赞/踩反馈 (2d)
├── 渐进式渲染 (3d)
└── 20 题测试集自动化运行 (2d)
```

### 中期（3-4 月）— Agentic + 代码智能

```
P0 — Agentic 能力
├── Agent 工具增强（调用链查询/git diff/批量分析）(7d)
├── 语义缓存切换 Qdrant (5d)
├── 影响分析传入 diff 内容 (3d)
├── 迭代检索 (5d)
├── Deep Search Agent 后端 (10d)
└── HyDE 虚构文档嵌入 (3d)

P1 — 代码智能
├── 代码符号图谱 Neo4j (10d)
├── Go To Definition (7d)
├── Tree-sitter + SCIP 混合模式 (5d)
├── 代码图谱浏览器前端 (10d)
└── 代码⇄文档桥接 (5d)
```

### 长期（5-8 月）— IDE 集成 + 企业化

```
P0 — IDE 集成
├── VS Code 插件 (15d)
├── LSP 协议实现 (10d)
├── 代码自动补全 (10d)
└── Inline Edit (7d)

P1 — 企业化
├── 多仓库搜索 (7d)
├── 统一权限模型 (5d)
├── CI/CD 日志分析 (10d)
├── 自动 PR Review (10d)
└── GraphRAG 社区检测 (7d)
```

---

## 五、核心差异点

在 Copilot 和 Cody 已占据市场的 2026 年，DevKnow 打三张差异化牌：

| 牌 | 做什么 | 为谁做 |
|----|--------|--------|
| 🛡️ **自托管+合规** | 代码不出内网 | 军工/金融/政务等合规敏感的甲方团队 |
| 🧩 **图谱增强 RAG** | 文档关系图 + 代码符号图，比向量搜索深一层 | 知识密集型团队（文档多、系统复杂） |
| 🔌 **任意 LLM + 透明管道** | 不绑定模型，管道每个环节可调/可换 | 技术驱动型团队（想要掌控而非黑盒） |

---

## 六、技术债清单（当前应优先修复）

| # | 问题 | 位置 | 影响 | 建议修复 |
|---|------|------|------|---------|
| 1 | MMR `computeGraphAlpha` 硬编码 α=0.89 | MmrSelector.java:174-180 | 跳数区分度丢失 | 按真实 hops 计算 1-0.3e^(-h) |
| 2 | `findCallersByMethodName` 用 LIKE %name% | CodeUnitEntityRepository | 全表扫描，不扩展 | 改用 ripple Redis Set 或 calls_methods 关联表 |
| 3 | `buildVectorText` 舍弃方法 body | CodeIndexService | 无 body 方法语义分辨率低 | 截取 body 前 200 字符加入向量文本 |
| 4 | Tree-sitter/SCIP 互斥 | CodeParser | 无法互补 | 改为 Tree-sitter 降级 SCIP 增强的混合模式 |
| 5 | 语义缓存 Redis SCAN 遍历 | SemanticCacheService | 缓存积累后线性下降 | 改用 Qdrant 做近似度检索 |
| 6 | Agent 只有 grep+read 工具 | CodeTools | 无法做调用链分析 | 添加调用链查询、git diff 工具 |
| 7 | 影响分析不传 diff 内容 | ImpactAnalysisService | 分析停留在文件级别 | 将变更代码行送入 LLM Prompt |
| 8 | 500KB 文件静默跳过 | CodeIndexService | 大文件索引不完整 | 改为日志告警 + index_skip_log 表 |
| 9 | 代码/文档共用同个 embedding 模型 | RagService + CodeIndexService | 语义空间不对齐 | 引入代码专用 embedder (starencoder) |
| 10 | LevelClassifier 零样本不稳定 | LevelClassifier | 边界查询分类偏移 | 加 few-shot 示例 + LLM JSON mode |

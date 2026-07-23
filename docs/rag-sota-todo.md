# DevKnow RAG → SOTA 升级路线图

> 基于 [SOTA 对比分析](memory://d--DevKnow-DevKnow/sota-comparison-2026-07.md) 更新。
> 对比对象：LangChain(119K⭐) / LlamaIndex(44K⭐) / Haystack / DSPy。
> 优先级按差距影响 × 实现成本排序。

## 完成状态总览

| 大项 | 状态 | 对比 SOTA |
|------|------|-----------|
| P0 检索管道（3项） | ✅ **全部完成** | SOTA 持平或超出 |
| P1 记忆/评估/稀疏/分级/K3（6项） | ✅ **全部完成** | 多项超出 |
| P1-1 GraphRAG 社区摘要 | ❌ 未完成 | 落后于 LlamaIndex |
| P1-8 双 Agent 核查 | ❌ 未完成 | 落后于 K3 Swarm |
| P2-1 可观测平台 | ❌ **需升级** | 最大差距 |
| P2-2/3/4 其他 | ❌ 未完成 | 增量改进 |

---

## P0 — 高优先级（已全部完成）

### ✅ P0-1 Agentic / 迭代检索（替代单次检索即生成）

**状态**：✅ 已完成

**实现**：基于 LangChain4j AiServices + @Tool 的 ReAct Agent。
**对比 SOTA**：⏺ **持平** — AiServices 自动管理 ReAct 循环，与 LangChain AgentExecutor 同等能力。
**差距**：无图状态机编排（与 LangGraph 的差距在 P1-8 中独立跟踪）。

**参考**：Self-RAG (Asai et al. 2023), ReAct (Yao et al. 2023)

---

### ✅ P0-2 语义分块（替代固定字符切分）

**状态**：✅ 已完成

**实现**：SemanticStructureParser（Markdown 语义边界）+ ContextualDescriptionGenerator（LLM 上下文描述）。
**对比 SOTA**：✅ **超出（Contextual Retrieval）** — LlamaIndex 有层级分块，但无 LLM 上下文描述嵌入。Anthropic 2024 论文方法的 Java 实现。

**参考**：Anthropic Contextual Retrieval (2024), LlamaIndex Semantic Chunking

---

### ✅ P0-3 动态路由 / 自适应策略（替代静态 YAML 配置）

**状态**：✅ 已完成

**实现**：DynamicRouter（fastModel 预测每查询最优参数）+ DynamicRouteResult。
**对比 SOTA**：⏺ **持平** — DRAGIN(2024) 论文级实现。主流框架均无直接等价功能（LangChain 需手写路由逻辑）。

**参考**：DRAGIN (2024), Router-based RAG, RouteLLM

---

## P1 — 中优先级（6/8 已完成）

### ✅ P1-2 记忆系统升级（替代单层摘要压缩）

**状态**：✅ 已完成

**实现**：三层记忆（短期原文 + 中期结构化摘要 JSON + 长期 Redis 原子事实）。
**对比 SOTA**：✅ **超出** — 主流框架记忆停留在"滑动窗口+摘要"（LangChain MessageWindowChatMemory、LlamaIndex ChatMemoryBuffer）。DevKnow 的三层记忆+事实修正机制是独特设计。

---

### ✅ P1-3 端到端管道优化（替代手动调参）

**状态**：✅ 基础框架已完成

**实现**：EvalSample + EvalMetrics + RagEvaluator + ParamOptimizer（网格搜索）+ ParamSnapshot（Redis 版本管理）。
**对比 SOTA**：⚠️ **基础级** — 有评估框架和网格搜索，但缺少 LangSmith 级别的 trace 链路、A/B 测试、在线评估。相比 DSPy 的自动优化管道有差距。

**待完成子项**：
- [ ] A/B 测试集成到 ChatService 路由层
- [ ] 评估数据集扩充至 100+ 条
- [ ] 在线评估指标采集（用户反馈 + 隐式信号）

---

### ✅ P1-4 稀疏检索引入（补充关键词搜索）

**状态**：✅ 已完成

**实现**：SparseEncoder（LLM 提取带权术语）+ SparseRetrievalService（term weight × MySQL ngram × TF 因子）+ 三路 RRF。
**对比 SOTA**：⚠️ **LLM 模拟 vs 真实 SPLADE** — LlamaIndex 有 BM25 原生支持。当前实现用 LLM 替代 SPLADE 模型 forward，优劣势已记录。

**待完成子项**：
- [ ] 同义词扩展升级：基于 embedding 的语义扩展替代规则映射
- [ ] 查询改写：独立 QueryRewriter 组件

---

### ✅ P1-5 分级检索策略（参考 K3 3:1 注意力混合）

**状态**：✅ 已完成

**实现**：RagService.exploreRetrieve() 轻量路径（向量4+关键词4+两路RRF→Top3）+ SearchDocTool 分级调用。
**对比 SOTA**：⏺ **独特实现** — 主流框架无直接等价功能。LangChain/LlamaIndex 工具调用每次都走完整检索。

---

### ✅ P1-6 Observation 压缩（参考 K3 ReNAct / NotesWriting）

**状态**：✅ 已完成

**实现**：ObservationCompressor（fastModel 压缩工具返回结果 → LLM 上下文减 ~90%）。
**对比 SOTA**：⏺ **独特实现** — 主流框架无直接等价功能。参考论文 NotesWriting(2025)/ReNAct，2025 年 ACL 论文实现。

---

### ✅ P1-7 Agent 经验缓存（参考 K3 Mooncake >90% 缓存命中率）

**状态**：✅ 已完成

**实现**：AgentExperienceCache（Redis/SHA256 签名/联动失效）。
**对比 SOTA**：⏺ **独特实现** — 主流框架无直接等价功能。LangChain 有 SemanticCache，但不缓存 Agent 检索路径。

---

### □ P1-1 GraphRAG 社区摘要（替代仅关系扩展）

**来源**：SOTA 对比（LlamaIndex 已内置 GraphRAG）

**现状**：GraphExpander + KnowledgeGraphService 只做文档间关系扩展（REFERENCE/DEPENDS_ON/EXTENDS/SEQUEL_TO）。

**SOTA 做法**：Microsoft GraphRAG — 在知识图谱上做社区检测 + 层级摘要。LlamaIndex 已内置算法。

**实现要点**：
- [ ] 在现有 Neo4j 图结构上实现 Leiden 社区检测算法
- [ ] 社区层级构建：L1（整库视角）→ L2（模块视角）→ L3（文档内部）
- [ ] 每个社区用 LLM 生成自然语言摘要
- [ ] 检索时：question 分类 → 匹配社区层级 → 注入对应社区摘要
- [ ] 摘要缓存：社区结构稳定时复用，增量更新时重建变化部分

**影响**：⭐⭐⭐⭐ | **成本**：⭐⭐⭐⭐⭐ | **预估人天**：15~20d

---

### □ P1-8 双 Agent 架构：生成 + 事实核查（参考 K3 Swarm Agent）

**来源**：K3 方案调研 + SOTA 对比

**实现要点**：
- [ ] 从当前幻觉三关中提取"事实核查"能力为独立 Agent
- [ ] 核查 Agent 与生成 Agent 并行运行
- [ ] 核查 Agent 走 fastChatLanguageModel
- [ ] SSE 协议支持"增量校正"（当前只有最终 corrected 事件）

**影响**：⭐⭐⭐⭐ | **成本**：⭐⭐⭐⭐ | **预估人天**：8~12d

---

### □ P1-9 图状态机编排（缩小与 LangGraph 的差距）

**来源**：SOTA 对比（LangGraph 核心能力）

**现状**：当前 ReAct Agent 使用 AiServices 线性循环，无法表达条件分支、并行任务、子 Agent 编排。

**SOTA 做法**：LangGraph 的 StateGraph — 节点+边有向图，支持 checkpointing、human-in-the-loop、条件路由。

**实现要点**：
- [ ] 评估 langgraph4j 开源项目是否可集成（当前 LangChain4j 生态已有移植）
- [ ] 或自研轻量状态机：定义 GraphNode / GraphEdge / StateGraph 核心抽象
- [ ] 支持：顺序执行、条件分支、并行扇出
- [ ] 支持：checkpoint 持久化（断点续跑）
- [ ] 与现有 AiServices + @Tool 工具系统兼容

**影响**：⭐⭐⭐⭐ | **成本**：⭐⭐⭐⭐ | **预估人天**：12~18d

---

## P2 — 低优先级（工程完善 / 可观测）

### □ P2-1 可观测性与评估平台 ← **与 SOTA 最大差距**

**现状**：仅 `@Slf4j` 日志输出，无 trace 链路、无检索质量统计面板。

**SOTA 做法**：LangSmith（LangChain 官方）/ Phoenix（Arize）/ 自建 trace 平台。
**对比差距**：🔴 **最大单点差距** — LangChain 有 LangSmith（追踪+评估+调试一体化），LlamaIndex 有内置 faithfulness/relevancy 指标。DevKnow 目前全无。

**实现要点**：
- [ ] 每请求生成 traceId，贯穿 检索→重排→生成→验证 全链路
- [ ] 关键指标采集（分阶段耗时、件数、置信度分布）
- [ ] 构建质量看板（Grafana + Prometheus / 自建）
- [ ] 失败模式归类（检索为空 / CRAG 丢弃 / 幻觉修正 / 熔断触发）
- [ ] 定期回归测试：自动运行评估数据集检测管道退化

**影响**：⭐⭐⭐⭐ | **成本**：⭐⭐⭐ | **预估人天**：8~12d

---

### □ P2-2 检索反馈闭环

- [ ] SSE 事件增加 `feedback_request` 事件（点赞/点踩/标记不准确）
- [ ] 反馈落库：关联 traceId、chunkIds
- [ ] 负反馈分析 → 触发重索引/参数调整

**影响**：⭐⭐⭐ | **成本**：⭐⭐ | **预估人天**：5~8d

---

### □ P2-3 模型升级与多模态扩展

- [ ] embedding 模型升级评估
- [ ] 重排序模型指定具体版本
- [ ] 多模态支持（图片/图表检索）
- [ ] 代码专用 embedding

**影响**：⭐⭐ | **成本**：⭐⭐⭐⭐ | **预估人天**：5~10d

---

### □ P2-4 架构/代码质量改进

- [ ] RagService 9 步流水线抽取为独立 stages
- [ ] 所有 LLM 调用统一超时/重试/fallback
- [ ] 补全单元测试
- [ ] 容器化自动伸缩评估

**影响**：⭐⭐ | **成本**：⭐⭐ | **预估人天**：5~8d

---

## 各维度对比 SOTA 评分

```
维度              DevKnow   LangChain   LlamaIndex   差距
─────────────────────────────────────────────────────
检索管道           4.5        3.5         4.5        ⏺ 持平
Agent 编排         3.0        5.0         2.5        🔴 落后于 LangGraph
GraphRAG          2.0        2.5         4.0        🔴 落后于 LlamaIndex
评估可观测         2.5        4.5         3.5        🔴 最大差距
幻觉防护           4.5        2.5         3.0        ✅ 超出
安全治理           4.5        1.5         2.0        ✅ 大幅超出
性能延迟           4.5        3.0         4.0        ✅ 超出
生态集成           1.0        5.0         4.0        🔴 无法追赶（私有）
─────────────────────────────────────────────────────
综合(等权)         3.31       3.44        3.44
```

---

## 推荐执行顺序

```
短期（聚焦 P0/P1 收尾 + 可观测）：
  ┌─ P2-1 可观测平台 — 最大差距，建议优先投入 8~12d
  ├─ P1-1 GraphRAG 社区摘要 — 唯一剩余大能力缺口 15~20d
  ├─ P1-8 双 Agent 核查 — 幻觉防护终极形态 8~12d
  └─ P1-9 图状态机编排 — 取决于 Agent 场景复杂度 12~18d

长期：
  ┌─ 其余 P2 项穿插进行
  └─ 评估数据集扩充 + pipeline 自动化
```

## 核心结论

> DevKnow 在 **检索管道、幻觉防护、安全治理** 三个维度达到或超过 SOTA。
> 差距集中在 **可观测性（无 trace 面板）、GraphRAG（仅关系扩展）、Agent 编排（无图状态机）**。
> 生态集成（无第三方连接器）是私有项目先天局限，不应作为优化目标。
> **建议优先投入可观测平台** — 看不到系统表现就无法衡量后续所有改进的效果。

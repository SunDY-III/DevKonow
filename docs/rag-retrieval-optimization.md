# RAG 检索策略优化：技术亮点详解

> 本文档解释 DevKnow 中三项自研优化算法的原理与设计思路。
> 不调外部 API，不引入黑盒模型，完全基于已有基础设施（知识图谱、代码索引、层级分类器）。

---

## 一、图感知 MMR（Graph-aware MMR）

### 问题

标准 MMR 的多样性惩罚只靠 embedding 余弦相似度，但两个 chunk 语义相似 ≠ 它们真的冗余。

**举例：**
- Chunk A：`OrderService.createOrder()` 的接口定义
- Chunk B：`OrderService.createOrder()` 的具体实现

这两个 chunk 的 embedding 可能高度相似（都在说同一件事），标准 MMR 会认为它们冗余，把其中一个砍掉——但**接口定义和具体实现是互补信息**，用户问"订单创建流程"时两个都应保留。

### 算法

```
标准 MMR:  diversity_penalty = max(cos(d, selected))

图感知 MMR: diversity_penalty = max(cos(d, selected) × α(hops(doc_d, doc_selected)))
```

其中 **α(h)** 是图距离衰减因子：

| h | 含义 | α(h) | 效果 |
|---|------|------|------|
| 0 | 同一文档 | 0.70 | 加强冗余惩罚（确实是同一篇文档的不同 chunk） |
| 1 | 直接图关联 | ≈0.89 | 降低惩罚，保留互补信息 |
| 2 | 间接关联 | ≈0.96 | 轻微降低惩罚 |
| ∞ | 无关 | 1.00 | 退化为标准 MMR |

**公式：** α(h) = 1 - 0.3 × exp(-h)

### 实现

```java
// MmrSelector.java 核心改动
private double pairSim(float[] a, float[] b, Long docIdA, Long docIdB,
                       Map<Long, Set<Long>> graphRelatedDocIds) {
    double cos = VectorStoreService.cosine(a, b);
    if (graphRelatedDocIds != null && docIdA != null && docIdB != null) {
        double alpha = computeGraphAlpha(docIdA, docIdB, graphRelatedDocIds);
        return cos * alpha;
    }
    return cos; // 无图谱数据 → 标准 MMR
}
```

### 数据流

```
RagService.levelAwareRetrieve()
  ├── candidate chunks（RRF 融合后）
  ├── KnowledgeGraphService.findRelated(docId, 2)  ← 对每个候选 docId 查图
  ├── 构建 Map<docId, Set<relatedDocId>>            ← 图关联索引
  └── MmrSelector.select(..., graphRelatedDocIds)    ← 传入图数据
       └── pairSim() 中计算 α(h) 衰减             ← 透明降级
```

### 亮点

- **融合异构数据源：** 向量语义 + 图拓扑结构的联合建模
- **零外部依赖：** 完全利用已有的 Neo4j 知识图谱
- **优雅降级：** 不传图数据 → 退化为标准 MMR，不改调用方接口
- **公式可解释：** α(h) 有清晰的数学含义，不是拍脑袋参数

---

## 二、自适应 λ 调度（Adaptive λ Scheduler）

### 问题

标准 MMR 的 λ 参数是固定值（如 0.7），但不同检索场景需要不同的相关-多样性权衡：

- 用户问"订单系统整体架构" → **高多样性**，需覆盖全局
- 用户问"createOrder 方法的第 3 行为什么报错" → **高相关**，要精确定位

一个 λ 值不可能同时满足这两种场景。

### 算法

```
λ_adaptive = baseλ(targetLevel) + Δλ(confidence)
```

**基值由问题层级决定：**

| 层级 | 场景 | 基值 λ | 理由 |
|------|------|--------|------|
| L1 | 战略/架构 | 0.35 | 需要广度覆盖，多样性优先 |
| L2 | 系统设计 | 0.50 | 平衡偏多样 |
| L3 | 模块逻辑 | 0.60 | 平衡偏相关 |
| L4 | 实现细节 | 0.75 | 相关优先 |
| L5 | 具体代码行 | 0.85 | 强相关精准定位 |

**置信度二次调节：**

| 置信度 | Δλ | 意图 |
|--------|----|------|
| < 0.5 | -0.10 | LLM 不确定 → 扩多样性，多捞候选 |
| > 0.8 | +0.10 | LLM 很确定 → 聚焦相关，减少噪声 |

### 实现

```java
// RagService.java
private double computeAdaptiveLambda(int targetLevel, double confidence) {
    double baseLambda;
    switch (targetLevel) {
        case 1:  baseLambda = 0.35; break;
        case 2:  baseLambda = 0.50; break;
        case 3:  baseLambda = 0.60; break;
        case 4:  baseLambda = 0.75; break;
        case 5:  baseLambda = 0.85; break;
        default: baseLambda = 0.60;
    }
    if (confidence < 0.5)       baseLambda -= 0.10;
    else if (confidence > 0.8)  baseLambda += 0.10;
    return clamp(0.3, 0.95, baseLambda);
}
```

### 动态候选池联动

与自适应 λ 配套，候选池也从固定 12 改为动态计算：

```java
int candidatePoolSize = Math.max(rerankTopN * 4, 20);
```

MMR 选择需要有足够大的候选池才能发挥多样性效果。3~5 倍于最终保留数是行业共识。

### 亮点

- **零额外成本：** LevelClassifier 已有的分类结果直接复用，不增加 LLM 调用
- **问题感知：** 不同类型的问题获得不同的检索策略，不是一刀切
- **置信度反馈：** LLM 自己不确定时自动放宽搜索范围，确定时收紧精度

---

## 三、代码结构感知重排序（Code Structure Reranker）

### 问题

通用 Reranker 把 chunk 当纯文本处理，丢失了代码特有的结构信息：

- `createOrder` 在代码里是一个方法名，在通用 reranker 眼里只是普通词
- 文件中定义了哪些方法、调用了哪些方法——这些信息对回答开发问题至关重要

### 算法

```
final_score = 0.4 × RRF_score
            + 0.2 × query_code_overlap   代码术语在 chunk 中的命中率
            + 0.2 × file_path_match      文件路径与代码术语的匹配度
            + 0.1 × query_coverage       通用查询词覆盖率
            + 0.1 × position_bonus       位置加分
            + (调用链加分)                 额外加分项
```

### 三阶段特征工程

#### 1. 代码术语提取（`extractCodeTerms`）

从自然语言问题中识别代码标识符，覆盖三种写法：

```
"createOrder"              →  ["create", "order"]             (CamelCase 拆分)
"orderService.createOrder" →  ["orderservice", "service",     (点号调用链)
                                "createorder", "create", "order"]
"createOrder(req)"         →  ["createorder"]                  (括号方法调用)
```

#### 2. 代码重叠评分（`computeQueryCodeOverlap`）

检查 chunk 内容中出现了多少代码术语：

```
query: "createOrder 为什么会超时"
codeTerms: [create, order, createorder]
chunk: "...createOrder() 方法中调用了 paymentService..."
→ 命中 create + order + createorder = 3/3 = 1.0
```

#### 3. 文件路径匹配（`computeFilePathMatch`）

检查 chunk 所在的文件路径是否匹配代码术语：

```
query: "OrderService 做了什么事"
codeTerms: [order, service, orderservice]
chunk.fileName: "src/main/java/com/trade/service/OrderService.java"
→ 包含 "orderservice" → 1.0
```

#### 4. 调用链评分（`computeCallGraphScore`）

利用 DB 中 `findCallersByMethodName` 查询，检查 chunk 是否在目标方法的调用链中：

```
query: "createOrder"
DB 查询: codeUnitRepo.findCallersByMethodName(projectId, "createorder")
→ 返回: ["OrderFacade.java", "PaymentService.java", "OrderController.java"]
chunk.fileName = "OrderController.java" → 命中 → +0.5
```

### 数据流

```
Reranker.rerank(query, candidates, topN, projectId)
  │
  ├── extractCodeTerms(query)             词法分析提取代码标识符
  ├── precomputeCallGraph(projectId, terms) 批量查 DB，建 (term → callers) 映射
  │
  └── for each candidate:
       ├── computeQueryCodeOverlap()       代码术语命中率
       ├── computeFilePathMatch()          文件路径匹配
       └── computeCallGraphScore()         调用链相关性（额外加分）
```

### 亮点

- **领域专用：** 针对代码场景的特征工程，不是通用 NLP 重排
- **零外部模型：** 不用 Cross-Encoder 或 LLM rerank，纯规则 + DB 查询
- **已有数据复用：** 调用链数据来自 `CodeUnitEntityRepository`，与波及重建共用同一套索引
- **分级加分：** 调用链作为"额外加分"而非权重挤压，避免基础排名被过度修改
- **兜底路径：** 不传 projectId → 跳过调用链评分，适用于文档通道

---

## 三项优化的协同

```
用户问题
    │
    ▼
LevelClassifier (L1~L5 + 置信度)
    │
    ├──→ 自适应 λ ───────────────────── 决定 MMR 偏相关还是偏多样
    │
    ▼
向量检索 + 关键词检索 → RRF 融合
    │
    ▼
图感知 MMR（λ_adaptive + α(h) 图谱衰减）
    │
    ├──→ KnowledgeGraphService.findRelated() 提供图距离
    │
    ▼
代码结构感知重排序（3 项代码特征 + 2 项通用特征）
    │
    ├──→ CodeUnitEntityRepository 提供调用链数据
    │
    ▼
图谱后扩展 → LLM 生成
```

每一项都利用**已有基础设施**，不引入新依赖：

| 优化 | 利用的已有数据 | 所在模块 |
|------|--------------|---------|
| 图感知 MMR | Neo4j 知识图谱 | `KnowledgeGraphService` |
| 自适应 λ | LevelClassifier 结果 | `LevelClassifier` |
| 代码结构重排 | CodeUnit 调用链 | `CodeUnitEntityRepository` |

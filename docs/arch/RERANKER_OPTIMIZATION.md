# Reranker 优化 架构实现文档

> 对应计划：`docs/plans/RERANKER_OPTIMIZATION_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

对本地 BGE Reranker 进行三阶段优化：Java 侧超时/熔断/降级、Python 服务常驻化/预加载/预热、基于分数的置信度过滤。确保 Reranker 不稳定时不拖慢在线问答主链路。

### 1.2 核心设计决策

1. **Java 侧链路加固**：确定性超时、连接池保护、滑动窗口熔断、全方位指标观测。
2. **降级链路**：BGE HTTP → LLM Reranker → Noop（RRF 融合顺序保留）。
3. **Python 服务化**：模型预加载、深度预热、就绪探针、过期请求丢弃。
4. **置信度过滤**：对 Agent 隔离（prompt 不含低分证据）、对 UI 透明（元数据保留 filtered 标记）。
5. **分数隔离原则**：置信度过滤仅对 `scoreType == reranker` 生效，严禁影响 retrieval/fallback 路径。

## 2. 整体架构

### 2.1 Reranker 在线链路

```
用户查询
    │
    ▼
KnowledgeBaseSimilaritySearcher / SessionFileSimilaritySearcher
    ↓ 初步检索结果 (RetrievalHit[])
    │
    ▼
BgeHttpRetrievalReranker.rerank()
    ├── 熔断检查 (RerankerCircuitBreaker)
    │   ├── OPEN → 降级返回 fused order
    │   └── CLOSED/HALF_OPEN → 继续
    │
    ├── HTTP 调用 localhost:7997/rerank
    │   ├── 超时/连接失败 → 记录失败 → 降级
    │   └── 成功 → 检查置信度
    │
    ├── 置信度过滤 (enableConfidenceFilter)
    │   ├── Top1 Score < threshold → markAsFiltered()
    │   └── Top1 Score >= threshold → 正常返回
    │
    └── 返回 ranked results
```

### 2.2 降级链路

```
BgeHttpRetrievalReranker (HTTP BGE)
    │ 超时 / 熔断 / 连接失败
    ▼
LlmRetrievalReranker (LLM 重排)
    │ 不可用
    ▼
NoopRetrievalReranker (保留 RRF 融合顺序)
```

## 3. 文件清单

### 3.2 后端代码

| 文件路径 | 职责 |
|---|---|
| **Reranker 核心** | |
| `rag/retrieve/RetrievalReranker.java` | Reranker 接口 |
| `rag/retrieve/BgeHttpRetrievalReranker.java` | BGE HTTP 远程 Reranker（含熔断/超时/置信度过滤） |
| `rag/retrieve/LlmRetrievalReranker.java` | LLM Reranker |
| `rag/retrieve/NoopRetrievalReranker.java` | 空实现降级 |
| **熔断器** | |
| `rag/retrieve/RerankerCircuitBreaker.java` | 滑动窗口熔断器（CLOSED → OPEN → HALF_OPEN） |
| `rag/retrieve/RerankerProperties.java` | `@ConfigurationProperties(prefix = "rag.retrieval.reranker")` |
| **指标** | |
| `BgeHttpRetrievalReranker` 内置 Micrometer | 6 个指标（requests, latency, attempts, payload.chars, candidates, circuit.state） |

### 3.3 Python 服务

| 文件路径 | 职责 |
|---|---|
| `tools/bge-reranker-server/rerank_server.py` | FastAPI + FlagEmbedding.FlagReranker |
| `tools/bge-reranker-server/start-reranker.ps1` | 启动脚本（预加载+预热+readiness） |

### 3.4 配置文件

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `rag.retrieval.reranker.max-candidates` | — | 最大候选数 |
| `rag.retrieval.reranker.max-chunk-chars` | — | 最大 chunk 字符数 |
| `rag.retrieval.reranker.enable-confidence-filter` | `true` | 置信度过滤开关 |
| `rag.retrieval.reranker.score-threshold` | `0.15` | 置信度阈值 |

## 4. 核心功能实现

### 4.1 BGE HTTP Reranker — BgeHttpRetrievalReranker

**实现位置：** `rag/retrieve/BgeHttpRetrievalReranker.java`

**Micrometer 指标：**

| 指标名 | 类型 | Tag 维度 |
|---|---|---|
| `chatagent.reranker.requests` | Counter | outcome (success/timeout/connect_error/circuit_open/filtered/parse_error) |
| `chatagent.reranker.latency` | Timer | provider, outcome |
| `chatagent.reranker.attempts` | Counter | — |
| `chatagent.reranker.payload.chars` | DistributionSummary | — |
| `chatagent.reranker.candidates` | DistributionSummary | — |
| `chatagent.reranker.circuit.state` | Gauge | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |

### 4.2 熔断器 — RerankerCircuitBreaker

**实现位置：** `rag/retrieve/RerankerCircuitBreaker.java`

**状态机：**

```
CLOSED (正常)
    │ 连续失败达到阈值
    ▼
OPEN (拒绝请求，直接降级)
    │ 冷却时间到期
    ▼
HALF_OPEN (放行探测请求)
    ├── 成功 → CLOSED
    └── 失败 → OPEN
```

### 4.3 置信度过滤

**实现位置：** `BgeHttpRetrievalReranker.java`

**过滤逻辑：**

1. 仅对 `scoreType == reranker` 且 `enableConfidenceFilter == true` 的结果生效
2. Top1 Score < threshold → `markAsFiltered()`：所有 Hit 的 scoreType 设为 `filtered`，score 置 null
3. **Prompt 隔离**：`RetrievalHitFormatter` 自动跳过 `filtered` Hit，不纳入 LLM prompt
4. **元数据保留**：filtered Hit 仍在 citations 数组中，UI 可展示 "Filtered by confidence"

```java
// 置信度过滤核心逻辑
if (enableConfidenceFilter && scoreType == RERANKER) {
    if (rankedResults.get(0).score() < scoreThreshold) {
        log.warn("Reranker top1 score {} below threshold {}", top1Score, scoreThreshold);
        return markAsFiltered(rankedResults);
    }
}
```

### 4.4 Python 服务常驻化

**Stage 2 改造：**

1. **预加载**：服务启动时自动加载默认模型
2. **深度预热**：启动后发送 dummy 请求完成 GPU warm-up
3. **就绪探针**：`/ready` 端点，Java 侧据此判断服务就绪
4. **过期请求丢弃**：超时到达的请求直接丢弃不处理

## 5. 已知限制与后续规划

- **Micro-batch 未实现**：Python 侧暂无批量 rerank 优化。
- **动态 candidate 控制**：当前 max-candidates 固定，未根据查询复杂度动态调整。
- **Gzip/Protobuf 未引入**：序列化层面仍有优化空间。

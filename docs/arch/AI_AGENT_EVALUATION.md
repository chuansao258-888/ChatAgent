# AI Agent & RAG 评估体系 架构实现文档

> 对应计划：`docs/plans/AI_AGENT_EVALUATION_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

构建一套多维度、可量化、可复现的评估体系，覆盖 ChatAgent 在检索质量、意图路由准确率、会话记忆质量、延迟、安全性和业务价值六大维度的评估能力。计划分为四个阶段：基线确立、多模型专项、安全加固与 CI 集成、MQ 稳定性验收。

### 1.2 核心设计决策

1. **六维评估模型**：RAG 质量、意图路由、会话记忆、延迟分解、安全对抗、业务价值 — 覆盖技术质量到用户体验。
2. **Golden Dataset 驱动**：使用冻结的黄金数据集作为评估基准，数据集按 50/10/20/20 切分（dev-tune / dev-smoke / test / holdout），其中 dev-smoke 专供 PR gate 使用。
3. **分层 Gating 策略**：PR Smoke（快速拦截 >15% 退化）、Nightly Benchmark（趋势跟踪 >5% 告警）、Release Hard Gate（holdout 验证）三层递进。
4. **可复现性合约**：每次 benchmark 锁定模型版本、Embedding 版本、Reranker 版本、向量索引快照、语料版本等环境变量。
5. **Micrometer 作为指标基建**：利用已有 Micrometer 埋点记录延迟、成功率等指标，Phase 1 仅需日志 + Prometheus 端点。

## 2. 整体架构

### 2.1 评估体系架构概览

```
┌───────────────────────────────────────────────────────────────┐
│                     评估执行层                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ PR Smoke │  │ Nightly  │  │ Release  │  │ Ad-hoc   │      │
│  │ Gate     │  │ Benchmark│  │ Hard Gate│  │ Debug    │      │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘      │
│       │              │              │              │           │
│  ┌────▼──────────────▼──────────────▼──────────────▼─────┐    │
│  │              Golden Dataset (Git 版本化)                │    │
│  │  intent-golden.json | rag-golden.json | golden-pdfs/  │    │
│  └────────────────────────┬──────────────────────────────┘    │
└───────────────────────────┼───────────────────────────────────┘
                            │
┌───────────────────────────┼───────────────────────────────────┐
│                     指标采集层                                  │
│  ┌────────────────────────▼──────────────────────────────┐    │
│  │               Micrometer + Prometheus                  │    │
│  └──┬──────┬──────┬──────┬──────┬──────┬──────┬──────────┘    │
│     │      │      │      │      │      │      │               │
│  Intent  RAG   Reranker  LLM   MCP   Chat   VDP              │
│  Router  Search  Metrics  Route Metrics Turn   Metrics        │
│  Metrics Metrics         Metrics       Metrics                │
└──────────────────────────────────────────────────────────────┘
                            │
┌───────────────────────────┼───────────────────────────────────┐
│                     被测系统 (ChatAgent)                        │
│  IntentRouter → KnowledgeBaseSimilaritySearcher → Reranker     │
│  → LLM Generation → ChatEventProcessor → MetricRecorder       │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 核心评估流程

1. **Golden Dataset 准备**：从生产日志筛选高频 Query + 手写边界用例 + LLM 辅助生成对抗样本，标注后按比例切分。
2. **最小可观测性前置**：在 IntentRouter、KnowledgeBaseSimilaritySearcher、BgeHttpRetrievalReranker 和 LLM 调用处加 Micrometer Timer。
3. **基线运行**：跑通测试集，记录当前 Hit Rate、Precision、路由准确率和 Latency 基线。
4. **持续评估**：通过三层 Gating（PR/Nightly/Release）在 CI/CD 中自动化运行。

## 3. 文件清单

### 3.1 已有的指标采集代码

| 文件路径 | 职责 |
|---|---|
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java` | Reranker 实现，内置 Micrometer 指标埋点 |
| `chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/RoutingMetrics.java` | LLM 路由指标（attempts、latency、circuit decisions/events） |
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/parser/VdpMetricsSupport.java` | VDP 解析指标工具类 |
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/mcp/metrics/McpMetricsRecorder.java` | MCP 工具调用指标 |
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/metrics/ChatTurnMetricRecorder.java` | 聊天轮次指标持久化 |

### 3.2 测试基础设施

| 文件路径 | 职责 |
|---|---|
| `chatagent/bootstrap/src/test/java/com/yulong/chatagent/intent/application/IntentRouterTest.java` | IntentRouter 单元测试（5 个用例覆盖启发式/LLM/回退/澄清） |
| `chatagent/bootstrap/src/test/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalRerankerTest.java` | BGE Reranker 测试（10 个用例覆盖熔断/超时/重试/探测） |
| `chatagent/bootstrap/src/test/java/com/yulong/chatagent/rag/retrieve/RerankerCircuitBreakerTest.java` | 断路器状态转换测试（7 个用例） |
| `chatagent/bootstrap/src/test/java/com/yulong/chatagent/rag/retrieve/LlmRetrievalRerankerTest.java` | LLM Reranker 测试 |
| `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizerTest.java` | 增量摘要测试 |
| `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/event/ChatEventProcessorTest.java` | Chat 事件处理测试（含 metric 记录验证） |

### 3.3 Golden Dataset（已存在部分）

| 文件路径 | 说明 |
|---|---|
| `chatagent/bootstrap/src/test/resources/golden-pdfs/expected/_example.segments.json` | PDF 解析黄金快照：示例文档 |
| `chatagent/bootstrap/src/test/resources/golden-pdfs/expected/heading-01.segments.json` | PDF 解析黄金快照：标题类文档 |
| `chatagent/bootstrap/src/test/resources/golden-pdfs/expected/mixed-01.segments.json` | PDF 解析黄金快照：混合内容文档 |
| `chatagent/bootstrap/src/test/resources/golden-pdfs/expected/scanned-01.segments.json` | PDF 解析黄金快照：扫描文档 |
| `chatagent/bootstrap/src/test/resources/golden-pdfs/expected/table-01.segments.json` | PDF 解析黄金快照：表格文档 |

### 3.4 待创建的评估资产（计划中尚未落地）

| 资产 | 状态 | 说明 |
|---|---|---|
| `intent-golden.json` | 未创建 | 意图路由黄金测试集（L1/L2/L3 + 澄清场景） |
| `rag-golden.json` | 未创建 | RAG QA 黄金测试集（Query, Context, Ground_Truth） |
| Ragas / TruLens 集成 | 未集成 | 评估框架 |
| Eval 配置文件 | 未创建 | 模型版本锁定、采样参数等 |
| Grafana Dashboard | 未创建 | 评估指标可视化仪表盘 |

### 3.5 配置文件

| 文件路径 | 说明 |
|---|---|
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/RerankerProperties.java` | `@ConfigurationProperties(prefix = "rag.retrieval.reranker")`，Reranker 调参配置 |
| `chatagent/bootstrap/src/main/resources/application.yaml` | 全局配置，含 LLM、Reranker、MCP 等参数 |

## 4. 核心功能实现

### 4.1 Micrometer 指标埋点体系

#### 4.1.1 Reranker 指标（BgeHttpRetrievalReranker）

**实现位置：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java`

**已实现的指标：**

| 指标名 | 类型 | Tag 维度 |
|---|---|---|
| `chatagent.reranker.requests` | Counter | outcome (success/timeout/connect_error/circuit_open/filtered/parse_error) |
| `chatagent.reranker.latency` | Timer | provider, outcome |
| `chatagent.reranker.attempts` | Counter | — |
| `chatagent.reranker.payload.chars` | DistributionSummary | — |
| `chatagent.reranker.candidates` | DistributionSummary | — |
| `chatagent.reranker.circuit.state` | Gauge | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |

**示例代码（指标记录模式）：**

```java
// 成功路径
timer.record(Duration.ofMillis(elapsed));
counter.increment();
// 失败路径 — 熔断状态
circuitStateGauge.set(CircuitBreakerState.OPEN.ordinal());
requestsCounter.increment(Tag.of("outcome", "circuit_open"));
```

#### 4.1.2 LLM 路由指标（RoutingMetrics）

**实现位置：** `chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/RoutingMetrics.java`

**已实现的指标：**

| 指标名 | 类型 | Tag 维度 |
|---|---|---|
| `chatagent.llm.routing.attempts` | Counter | mode, model, outcome, fallback_available |
| `chatagent.llm.routing.latency` | Timer | mode, model, outcome |
| `chatagent.llm.circuit.decisions` | Counter | model, decision |
| `chatagent.llm.circuit.events` | Counter | model, event |

**设计特点：** 当 `MeterRegistry` 不存在时全部 no-op，不改变启动要求。

#### 4.1.3 聊天轮次指标（ChatTurnMetricRecorder）

**实现位置：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/metrics/ChatTurnMetricRecorder.java`

**实现逻辑：** 每轮对话结束后，将一条指标记录持久化到 `t_chat_turn_metric` 表。

**记录字段：**

| 字段 | 说明 |
|---|---|
| sessionId | 会话 ID |
| userId | 用户 ID |
| turnId | 轮次 ID |
| agentId | Agent ID |
| status | 成功/失败状态 |
| errorType | 错误类型 |
| durationMs | 耗时（毫秒） |
| knowledgeHit | 是否命中知识库 |

**集成方式：** `ChatEventProcessor` 在每次 chat turn 成功或失败后调用 `chatTurnMetricRecorder.record(event, runResult)`。

```java
// ChatEventProcessor 中的调用
private void recordMetricQuietly(ChatEvent event, AgentRunResult result) {
    try {
        chatTurnMetricRecorder.record(event, result);
    } catch (Exception e) {
        log.warn("Failed to record turn metric", e);
    }
}
```

#### 4.1.4 MCP 工具调用指标（McpMetricsRecorder）

**实现位置：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/mcp/metrics/McpMetricsRecorder.java`

**已实现的指标：**

| 指标名 | 类型 | Tag 维度 |
|---|---|---|
| `chatagent.mcp.calls` | Counter | server, tool, protocol, outcome |
| `chatagent.mcp.failures` | Counter | error_code |
| `chatagent.mcp.latency` | Timer | per tool |
| `chatagent.mcp.rate_limited` | Counter | — |
| `chatagent.mcp.schema_drift` | Counter | stale tool count |
| `chatagent.mcp.circuit.state` | Gauge | per server |

### 4.2 断路器与降级评估

**实现位置：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/RerankerCircuitBreaker.java`

**实现逻辑：** 自定义滑动窗口断路器，支持 CLOSED → OPEN → HALF_OPEN 状态转换。

**关键行为：**
- CLOSED 状态：正常转发请求，连续失败达到阈值后转为 OPEN
- OPEN 状态：直接返回降级结果，定时器到期后转为 HALF_OPEN
- HALF_OPEN 状态：放行探测请求，成功则恢复 CLOSED，失败则回到 OPEN

```java
// 断路器状态转换示意
public synchronized State tryAcquire() {
    if (state == State.OPEN) {
        if (hasCooldownExpired()) {
            state = State.HALF_OPEN;
            return State.HALF_OPEN;
        }
        return State.OPEN; // 拒绝
    }
    return state; // CLOSED 或 HALF_OPEN 放行
}
```

### 4.3 Reranker 降级链路

**降级顺序：** `BgeHttpRetrievalReranker`（BGE HTTP 远程）→ `LlmRetrievalReranker`（LLM 重排）→ `NoopRetrievalReranker`（不排序直接返回）

**相关文件：**

| 文件 | 职责 |
|---|---|
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/RetrievalReranker.java` | Reranker 接口 |
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java` | BGE HTTP 远程 Reranker |
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/LlmRetrievalReranker.java` | LLM Reranker |
| `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/NoopRetrievalReranker.java` | 空实现降级 |

### 4.4 Golden PDF 验证测试框架

**实现位置：** `chatagent/bootstrap/src/test/java/com/yulong/chatagent/rag/parser/GoldenPdfValidationTest.java`（通过 golden-pdfs 目录结构推断）

**实现逻辑：** 参数化测试，发现 `golden-pdfs/{scanned,tables,headings,mixed}/` 下的 PDF 文件，解析后与 `golden-pdfs/expected/*.segments.json` 黄金快照对比。

**快照格式示例：**

```json
{
  "expectedSegmentCount": 2,
  "extractionMode": "NATIVE_TEXT",
  "perPageAssertions": [
    {
      "page": 1,
      "mustContain": ["关键词1", "关键词2"],
      "mustNotContain": ["不应出现的内容"],
      "expectedRoute": "NATIVE_TEXT"
    }
  ]
}
```

### 4.5 IncrementalSummarizer（会话记忆评估目标）

**实现位置：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizer.java`

**核心能力：**
- 滚动 L2 摘要：使用 LLM 调用生成增量摘要
- 锚定实体提取：通过正则提取日期、金额、订单号等关键实体
- 确定性回退：LLM 不可用时使用确定性摘要策略
- 实体合并：跨摘要合并锚定实体，防止关键信息丢失

**配套组件：**

| 文件 | 职责 |
|---|---|
| `AsyncSummaryListener.java` | 异步摘要监听 |
| `AtomicConversationTurn.java` | 原子对话轮次 |
| `ConversationTurnCompletionPublisher.java` | 轮次完成事件发布 |
| `RedisLockManager.java` | 分布式锁管理 |
| `SummaryWatermarkRange.java` | 摘要水位范围 |
| `SummaryWatermarkService.java` | 水位管理服务 |
| `TurnBasedContextExtractor.java` | 基于轮次的上下文提取 |

## 5. 配置说明

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `rag.retrieval.reranker.*` | — | Reranker 超时、熔断阈值、置信度过滤、重试次数 |
| `chat.routing.http-connect-timeout-seconds` | — | 路由 HTTP 连接超时 |
| `chat.routing.http-read-timeout-seconds` | — | 路由 HTTP 读取超时 |

## 6. 六维评估模型与指标口径

| 维度 | 核心指标 | 公式 | 当前状态 |
|---|---|---|---|
| **RAG 质量** | `rag.hit_rate_at_5` | `count(gt_chunk in top5) / N` | 待建黄金数据集 |
| **RAG 质量** | `rag.faithfulness` | LLM-as-Judge (0-1) | 待集成 Ragas |
| **意图路由** | `intent.l1_accuracy` | `correct_l1 / N` | 待建黄金数据集 |
| **意图路由** | `intent.clarification_precision` | `TP_clarify / all_clarify` | 待建黄金数据集 |
| **延迟** | `latency.intent_routing_p95` | P95(intent_route_ms) | Micrometer 已埋点 |
| **延迟** | `chatagent.reranker.latency` | Timer 分布 | Micrometer 已埋点 |
| **安全** | Prompt Injection 拦截率 | — | 待红蓝对抗 |
| **业务** | `biz.thumbs_up_rate` | `thumbs_up / msg_with_btn` | 待前端埋点 |

## 7. 分层 Gating 策略

```
PR Smoke Gate (每次 PR)
├── 数据集: dev-smoke split（冻结子集）, ~20 条核心用例
├── 耗时: < 3 min
├── 阻断条件: 任意核心指标下降 > 15%
└── 目的: 快速拦截严重回归

Nightly Full Benchmark (每晚)
├── 数据集: 完整 test split
├── 耗时: ~15-30 min
├── 行为: 下降 > 5% 触发告警，不阻断
└── 目的: 捕捉渐进式退化

Release Hard Gate (发版前)
├── 数据集: holdout split（从不参与调参）
├── 最小样本量: n >= 50
├── 阻断条件: 任意核心指标低于基线 5%
└── 目的: 最终质量保证
```

## 8. 已知限制与后续规划

- **Golden Dataset 尚未创建**：`intent-golden.json` 和 `rag-golden.json` 需要从生产日志采样并标注，需数据负责人授权。
- **无评估框架集成**：Ragas/TruLens 尚未接入，faithfulness 等指标需要 LLM-as-Judge 能力。
- **无可视化仪表盘**：Grafana Dashboard 需要在 Phase 3 建立。
- **Phase 4 依赖 MQ 重构**：MQ 稳定性评估需等待 `MQ_REFACTOR_PLAN` 落地。
- **KnowledgeBaseSimilaritySearcher 无单元测试**：当前仅通过集成测试间接覆盖，评估方案需要独立的单元测试。
- **Reranker 降级 ROI 未量化**：BGE→LLM→Noop 降级链路已实现但未做 A/B 对比实验。

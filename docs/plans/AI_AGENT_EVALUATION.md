# ChatAgent 三维评估体系

> 最后更新：2026-04-13

## 1. 概述

### 1.1 评估目标

构建一套**面向简历、可量化、可复现**的三维评估体系，为 ChatAgent 系统产出可直接写入简历的性能数据点。

### 1.2 三维评估模型

| 维度 | 评估对象 | 简历产出示例 |
|------|----------|-------------|
| **RAG 质量** | 检索增强生成全流程 | "Faithfulness 92%, Hit@3 80%, NDCG@5 0.82" |
| **AI Agent 智能** | 意图识别 / 上下文管理 / 工具调用 | "意图 L1 准确率 96%, 20 轮连贯性 85%" |
| **传统后端（三高）** | 高并发 / 高性能 / 高可用 | "P95 <200ms @500QPS, 熔断恢复 <5s" |

### 1.3 核心设计决策

1. **Golden Dataset 驱动**：冻结黄金数据集作为评估基准，按 50/10/20/20 切分（dev-tune / dev-smoke / test / holdout）。
2. **分层 Gating**：PR Smoke（<3min, >15% 退化阻断）→ Nightly Benchmark（>5% 告警）→ Release Hard Gate（holdout 验证）。
3. **Micrometer 指标基建**：利用已有 + 新增 6 个埋点，Prometheus 端点采集。
4. **可复现性合约**：每次评估锁定模型版本、Embedding 版本、Reranker 版本、向量索引快照。

---

## 2. 指标采集基建

### 2.1 已有 Micrometer 埋点

| 指标名 | 类型 | Tag 维度 | 来源文件 |
|--------|------|----------|----------|
| `chatagent.reranker.requests` | Counter | outcome | `BgeHttpRetrievalReranker` |
| `chatagent.reranker.latency` | Timer | provider, outcome | `BgeHttpRetrievalReranker` |
| `chatagent.reranker.attempts` | Counter | — | `BgeHttpRetrievalReranker` |
| `chatagent.reranker.payload.chars` | DistributionSummary | — | `BgeHttpRetrievalReranker` |
| `chatagent.reranker.candidates` | DistributionSummary | — | `BgeHttpRetrievalReranker` |
| `chatagent.reranker.circuit.state` | Gauge | 0/1/2 | `BgeHttpRetrievalReranker` |
| `chatagent.llm.routing.attempts` | Counter | mode, model, outcome | `RoutingMetrics` |
| `chatagent.llm.routing.latency` | Timer | mode, model, outcome | `RoutingMetrics` |
| `chatagent.llm.circuit.decisions` | Counter | model, decision | `RoutingMetrics` |
| `chatagent.llm.circuit.events` | Counter | model, event | `RoutingMetrics` |
| `chatagent.mcp.calls` | Counter | server, tool, protocol, outcome | `McpMetricsRecorder` |
| `chatagent.mcp.failures` | Counter | error_code | `McpMetricsRecorder` |
| `chatagent.mcp.latency` | Timer | per tool | `McpMetricsRecorder` |
| `chatagent.mcp.rate_limited` | Counter | — | `McpMetricsRecorder` |
| `chatagent.mcp.schema_drift` | Counter | — | `McpMetricsRecorder` |
| `chatagent.mcp.circuit.state` | Gauge | per server | `McpMetricsRecorder` |
| `t_chat_turn_metric.*` | DB Record | sessionId, turnId, durationMs, knowledgeHit | `ChatTurnMetricRecorder` |
| `vdp.*` | Counter/Timer | — | `VdpMetricsSupport` |

### 2.2 需新增的 Micrometer 埋点（6 个）

| 指标名 | 类型 | 埋点位置 | 用途 |
|--------|------|----------|------|
| `chatagent.intent.routing.latency` | Timer | `IntentRouter.route()` | 意图路由延迟 |
| `chatagent.intent.routing.outcome` | Counter | `IntentRouter.route()` | heuristic/llm/clarify/none 分布 |
| `chatagent.rag.retrieval.latency` | Timer | `SearchScopeResolver` | 检索延迟（含向量搜索+融合） |
| `chatagent.sse.first_byte.latency` | Timer | `AgentMessageBridgeImpl` | SSE 首字节延迟 |
| `chatagent.intent.cache.hit/miss` | Counter | `DefaultIntentTreeCacheManager` | 意图树缓存命中率 |
| `chatagent.knowledge.signal.cache.hit/miss` | Counter | `KnowledgeDocumentSignalService` | 知识库信号缓存命中率 |

---

## 3. Golden Dataset 规格

### 3.1 数据集总览

| 数据集 | 文件路径 | 数量 | 切分 | 状态 |
|--------|----------|------|------|------|
| RAG QA | `eval/golden/rag-golden.json` | 100 条 | 50/10/20/20 | 待创建 |
| 意图路由 | `eval/golden/intent-golden.json` | 80 条 | 40/10/15/15 | 待创建 |
| 记忆质量 | `eval/golden/memory-golden.json` | 10 段对话 | — | 待创建 |
| 多轮对话 | `eval/golden/multiturn-golden.json` | 10 段对话 | — | 待创建 |
| 工具调用 | `eval/golden/tool-golden.json` | 20 场景 | — | 待创建 |
| PDF 解析 | `golden-pdfs/` | 20 PDF（已有 4） | — | 扩展中 |

### 3.2 RAG Golden Dataset Schema

```json
{
  "id": "rag-001",
  "query": "年假最多可以结转多少天？",
  "intentPath": "人事制度 > 请假制度 > 年假政策",
  "groundTruthAnswer": "员工每年最多可以结转5天年假到下一年度",
  "groundTruthChunkIds": ["chunk-leave-policy-003", "chunk-leave-policy-007"],
  "groundTruthKbId": "kb-hr-leave",
  "difficulty": "easy|medium|hard",
  "category": "factual|multi-hop|comparison|temporal",
  "split": "dev-tune|dev-smoke|test|holdout"
}
```

**来源策略**：40% 生产日志脱敏 + 30% 手写边界用例（multi-hop、temporal、comparison）+ 20% LLM 生成对抗样本（改写、否定、歧义）+ 10% 跨 KB 查询。

### 3.3 Intent Golden Dataset Schema

```json
{
  "id": "intent-001",
  "query": "我想查看年假剩余天数",
  "expectedL1": "domain-hr",
  "expectedL2": "category-leave",
  "expectedL3": "topic-leave-balance",
  "expectedKind": "KB",
  "shouldClarify": false,
  "expectedScopedKbIds": ["kb-hr-leave"],
  "heuristicShouldSuffice": true,
  "difficulty": "easy|medium|hard",
  "category": "direct|ambiguous|cross-domain|out-of-scope|clarification-needed",
  "split": "dev-tune|dev-smoke|test|holdout"
}
```

**分布**：40 直接匹配 + 10 歧义（应触发澄清）+ 10 跨域 + 10 越界（应返回 NONE）+ 10 澄清响应。

### 3.4 Memory Golden Dataset Schema

```json
{
  "id": "memory-001",
  "turns": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "expectedEntities": ["2026-03-15", "订单号 ORD-12345", "金额 ¥3,500"],
  "expectedTopics": ["年假申请", "报销流程"],
  "totalTurns": 20
}
```

### 3.5 Tool Calling Golden Dataset Schema

```json
{
  "id": "tool-001",
  "query": "帮我查一下上个月的报销记录",
  "expectedTools": ["DataBaseTools.executeReadOnlyQuery"],
  "expectedMaxSteps": 3,
  "expectedAnswerContains": ["报销", "金额"],
  "category": "kb-search|sql-query|email|mcp|multi-tool"
}
```

---

## 4. 维度一：RAG 质量评估

### 4.1 文档解析质量

**测试类**：`PdfExtractionQualityTest`（`@Tag("eval-rag-parse")`）

复用已有 `GoldenPdfValidationTest` 模式，从 4 个 PDF 样本扩展至 20+：
- 5 纯文本 PDF（标题结构、平铺文本、多栏排版）
- 5 表格密集 PDF（简单表格、嵌套表格、跨列单元格）
- 5 扫描/图片 PDF（清晰扫描、噪声扫描、混合）
- 5 混合内容 PDF（文字+表格+图片）

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| 段落提取准确率 | correct_segments / total_expected | **≥ 95%** | 扩展 `GoldenPdfValidationTest` 快照断言 |
| 文本提取 F1 | 2·P·R / (P+R)，token 级别 | **≥ 90%** | 新测试类，对比提取文本与标注文本 |
| 双轨路由准确率 | correct_fast_vs_visual / total_pages | **≥ 92%** | 在 Golden 测试中增加路由决策断言 |
| VDP P95 延迟 | P95(vdp_process_ms) | **< 8s/页** | 复用 `GoldenPdfPerformanceBaselineTest` 模式 |

**已有基础设施**：
- `GoldenPdfValidationTest` — 参数化快照验证（`@Tag("golden")`）
- `GoldenPdfPerformanceBaselineTest` — 缓存/延迟 JSON 基线报告
- `golden-pdfs/expected/*.segments.json` — 5 个黄金快照

### 4.2 分块质量

**测试类**：`ChunkQualityEvalTest`（`@Tag("eval-rag-parse")`）

通过 `SegmentAwareChunkerRouter` 处理 Golden PDF 输出，统计分块质量。

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| 边界质量 | clean_boundary_chunks / total_chunks | **≥ 88%** | 检查每个 chunk 是否在段落/句子边界切分 |
| 平均分块大小 | mean(chunk_chars) | **800–1400 chars** | 统计断言 |
| 语义连贯度 | LLM-Judge 评分 (1–5) | **≥ 3.8/5** | 随机抽取 30 个 chunk，LLM 打分 |

**依赖组件**：`SegmentAwareChunkerRouter`、`PlainTextChunker`、`StructureAwareMarkdownChunker`、`MarkdownSectionChunker`

### 4.3 检索质量（核心指标）

**测试类**：`RetrievalQualityEvalTest`（`@Tag("eval-rag-retrieval")`）

**前置条件**：冻结 Milvus 快照（Golden KB 文档已索引），记录 collection 行数和 segment 数。

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| Hit@3 | queries_with_gt_chunk_in_top3 / N | **≥ 80%** | 遍历 `rag-golden.json`，调用 `KnowledgeBaseSimilaritySearcher` |
| Hit@5 | queries_with_gt_chunk_in_top5 / N | **≥ 88%** | 同上，扩大 top-k |
| MRR | mean(1 / rank_of_first_relevant) | **≥ 0.75** | 计算每条 query 的首个相关 chunk 排名 |
| NDCG@5 | 标准 NDCG 公式 | **≥ 0.80** | 基于相关性打分计算 |
| candidate_k 利用率 | unique_relevant_in_candidate_12 / 12 | 仅报告 | 诊断指标 |

**实现流程**：
1. 加载 `rag-golden.json`
2. 对每条 query 调用 `KnowledgeBaseSimilaritySearcher`（dense + BM25 + RRF 融合）
3. 对比返回 chunkId 与 `groundTruthChunkIds`
4. 计算 Hit@K / MRR / NDCG
5. 输出 JSON 报告到 `target/eval-reports/`，含逐条明细

### 4.4 重排序效果 A/B 实验

**测试类**：`RerankerEffectivenessTest`（`@Tag("eval-rag-retrieval")`）

对同一批 query，分别使用三个 Reranker 执行，对比 NDCG/MRR 差异。

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| BGE NDCG 提升 | NDCG_bge − NDCG_noop | **≥ +0.10** | A/B 对比：`BgeHttpRetrievalReranker` vs `NoopRetrievalReranker` |
| LLM 降级 NDCG | NDCG_llm − NDCG_noop | **≥ +0.05** | 强制使用 `LlmRetrievalReranker` |
| 置信度过滤精确率 | correctly_filtered / all_filtered | **≥ 85%** | 分析 top-1 score < 0.15 的过滤结果 |
| 降级质量损失 | (NDCG_bge − NDCG_noop) / NDCG_bge | 仅报告 | 量化降级代价 |

### 4.5 上下文增强 ROI

**测试类**：`ChunkEnrichmentRoiTest`（`@Tag("eval-rag-retrieval")`）

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| Hit@5 增量 | Hit@5_enriched − Hit@5_raw | **≥ +5%** | A/B 对比：`LlmContextualChunkEnricher` 开启 vs 关闭 |
| Token 成本 | avg_tokens_per_enrichment_call | 仅报告 | 从 enricher 日志统计 |

### 4.6 端到端 RAGAS 评估

**评估器**：`RagasStyleEvalRunner`（`@Tag("eval-rag-e2e")`）

**Judge 模型**：DeepSeek-Chat 或 GLM-5.1（temperature=0）

| 指标 | 公式 | 简历目标值 | 评判方法 |
|------|------|-----------|----------|
| Context Precision | relevant_retrieved / total_retrieved | **≥ 85%** | LLM-Judge 逐条评判 |
| Context Recall | gt_claims_covered_by_context / total_gt_claims | **≥ 80%** | LLM-Judge 拆分 claim 对比 |
| Faithfulness | answer_claims_supported_by_context / total_answer_claims | **≥ 90%** | LLM-Judge 拆分 claim 验证 |
| Answer Relevancy | LLM 评分 (0–1) | **≥ 0.85** | LLM-Judge 直接评分 |

**实现流程**：
1. 对 `rag-golden.json` 中每条 query 执行完整 pipeline：意图路由 → 检索 → 重排序 → LLM 生成
2. 捕获 (query, context_chunks, generated_answer, ground_truth_answer)
3. 对每个 tuple 使用 Judge prompt 评估四项指标
4. Judge prompt 版本化存储于 `eval/prompts/` 目录，任何修改需基线重校准

**Judge Prompt 管理**：
- `eval/prompts/faithfulness.txt` — Faithfulness 评判模板
- `eval/prompts/context_precision.txt` — Context Precision 评判模板
- `eval/prompts/context_recall.txt` — Context Recall 评判模板
- `eval/prompts/answer_relevancy.txt` — Answer Relevancy 评判模板

---

## 5. 维度二：AI Agent 智能评估

### 5.1 意图识别准确率

**测试类**：`IntentRoutingEvalTest`（`@Tag("eval-intent")`）

**依赖组件**：`IntentRouter`（启发式评分 + LLM 回退）、`ClarificationResolver`

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| L1 准确率 | correct_L1 / N | **≥ 95%** | 加载 `intent-golden.json`，逐条调用 `IntentRouter.route()` |
| L2 准确率 | correct_L2 / N_with_L2 | **≥ 90%** | 同上 |
| L3 准确率 | correct_L3 / N_with_L3 | **≥ 88%** | 同上 |
| 端到端准确率 | correct_full_path / N | **≥ 85%** | 完整路径匹配 |
| 澄清精确率 | TP_clarify / all_clarify_triggered | **≥ 80%** | 在 `shouldClarify=true` 样本上评估 |
| 澄清召回率 | TP_clarify / all_should_clarify | **≥ 75%** | 同上 |
| 启发式命中率 | resolved_by_heuristic / total_resolvable | **≥ 60%** | 验证启发式优先策略的有效性 |
| 启发式单独准确率 | correct_heuristic_only / heuristic_resolved | **≥ 92%** | 启发式命中时的准确性 |
| LLM 回退率 | llm_called / total_queries | **< 40%** | 成本效率指标 |
| 越界检测 F1 | F1 on NONE detection | **≥ 85%** | `out-of-scope` 类别样本 |

**具体测试场景**：

```
场景1: 精确匹配
  输入: "报销流程是怎样的？"
  期望: L1=财务管理, L2=报销制度, L3=报销流程
  断言: 启发式直接命中（score ≥1.2），不触发 LLM

场景2: 歧义澄清
  输入: "查一下政策"
  期望: shouldClarify=true，候选包含多个领域
  断言: 返回 AMBIGUOUS + clarification candidates

场景3: 越界检测
  输入: "今天天气怎么样？"
  期望: kind=NONE
  断言: 启发式 score < 0.45，LLM 返回 NONE
```

### 5.2 查询重写质量

**测试类**：`QueryRewriteEvalTest`（`@Tag("eval-intent")`）

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| 语义保持度 | LLM-Judge: 重写后是否保留原意 (0–1) | **≥ 0.92** | 对 KB 类 query 调用 `QueryRewriter.rewrite()` |
| 上下文丰富度 | LLM-Judge: 重写是否补充了意图路径上下文 (0–1) | **≥ 0.80** | 同上 |
| 检索提升 | Hit@5_rewritten − Hit@5_original | **≥ +3%** | 原始 query vs 重写 query 的检索对比 |

### 5.3 上下文管理（三层记忆）

**测试类**：`SummaryQualityEvalTest`（`@Tag("eval-memory")`）

**评估对象**：`IncrementalSummarizer`（L2 增量摘要）

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| 实体保留率 | entities_in_summary / entities_in_original | **≥ 90%** | 基于 `memory-golden.json` 的 `expectedEntities` |
| 连贯度 | LLM-Judge (1–5) | **≥ 4.0/5** | LLM 评判摘要的逻辑连贯性 |
| 压缩比 | summary_chars / original_chars | **0.15–0.35** | 统计断言 |
| 锚定实体召回 | extracted_anchors / expected_anchors | **≥ 95%** | 验证日期、金额、订单号等锚定实体提取 |
| 确定性回退质量 | entities_in_deterministic / entities_in_llm | **≥ 70%** | 对比 LLM 摘要与确定性回退摘要 |

**已有基础设施**：`IncrementalSummarizerTest` 已测试锚定实体提取，需扩展为 data-driven 评估。

### 5.4 多轮对话质量

**测试类**：`MultiTurnEvalTest`（`@Tag("eval-agent")`）

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| 指代消解 | correctly_resolved / total_references | **≥ 85%** | 脚本化对话中嵌入 "它"/"那个"/"同一个" |
| 主题跟踪 | correct_topic_after_switch / switch_count | **≥ 80%** | 脚本中设置主题切换点 |
| 上下文延续 | correct_context_use / dependent_queries | **≥ 82%** | 后续问题依赖前文上下文 |
| 20 轮漂移比 | quality_at_turn20 / quality_at_turn5 | **≥ 0.85** | 长对话质量衰减测试 |

**具体测试场景**：

```
Turn 1: "年假政策是什么？"          → [年假政策回答]
Turn 2: "它最多能结转几天？"        → 必须正确解析 "它" = "年假"
Turn 3: "病假呢？"                  → 必须切换到病假主题
Turn 4: "两者有什么区别？"          → 必须对比年假和病假
断言: 4 轮回答均正确引用目标上下文
```

### 5.5 工具调用性能

**测试类**：`ToolCallingEvalTest`（`@Tag("eval-agent")`）

| 指标 | 公式 | 简历目标值 | 实现方式 |
|------|------|-----------|----------|
| 工具选择准确率 | correct_tool / total_tool_calls | **≥ 92%** | 20 个场景：KB 检索 / SQL / 邮件 / MCP |
| ReAct 平均步数 | mean(steps_to_completion) | **≤ 3.0** | 记录每次 ReAct 循环的步数 |
| ReAct 最大步数 | max(steps_to_completion) | **≤ 8** | 最坏情况不超上限 |
| 不必要调用率 | unnecessary_calls / total_queries | **< 10%** | 可直接回答却调用了工具 |
| MCP 执行成功率 | successful_mcp / total_mcp | **≥ 95%** | 从 `McpMetricsRecorder` 采集 |

**已有基础设施**：`AgentThinkingEngineTest`、`AgentToolExecutionEngineTest` 的测试模式可复用。

### 5.6 回复质量

**测试类**：`ResponseQualityEvalTest`（`@Tag("eval-agent")`）

| 指标 | 公式 | 简历目标值 | 评判方法 |
|------|------|-----------|----------|
| 连贯性 | LLM-Judge (0–1) | **≥ 0.88** | 回复逻辑通顺、无矛盾 |
| 完整度 | LLM-Judge (0–1) | **≥ 0.85** | 覆盖 query 所有方面 |
| 引用准确率 | correct_citations / total_citations | **≥ 90%** | 通过 `CurrentTurnCitationHolder` 自动验证 |
| 幻觉率 | hallucinated_responses / total | **< 8%** | LLM-Judge faithfulness 判定 |

---

## 6. 维度三：传统后端（三高）评估

### 6.1 高并发 — 压力测试

**工具**：Gatling（`tools/gatling/`）+ JUnit 压力测试（`@Tag("stress")`）

| 测试场景 | 目标值 | 实现方式 |
|----------|--------|----------|
| Chat API QPS | **500 QPS 持续** | Gatling 脚本命中 `/api/chat-messages` |
| 并发会话 | **200 并发** | Gatling 阶梯式 ramp-up |
| SSE 连接容量 | **500 并发 SSE** | WebClient 并发连接测试 |
| 线程池饱和 | **2x 池容量无丢弃** | JUnit + CountDownLatch（`ThreadPoolSaturationTest`） |
| Outbox 吞吐 | **≥ 200 msg/s** | `OutboxThroughputTest` |
| MQ 消费速率 | **≥ 50 agent-run/s** | Testcontainers + RabbitMQ |

**报告指标**：QPS、P50/P95/P99 延迟、错误率、线程池利用率。

**已有线程池配置**：
- `taskExecutor`: 4/10/100 (异步事件)
- `summaryExecutor`: 1/2/8 (摘要生成)
- `modelStreamExecutor`: 20/100/200 (LLM 流式)
- VDP 专用线程池: core 1-2 / max 2

### 6.2 高可用 — 熔断器测试（3 个独立熔断器）

#### 6.2.1 LLM 路由熔断器（ModelHealthStore）

**已有测试**：`ModelHealthStoreTest`（4 个用例，含 40 次迭代并发竞态测试）

| 测试场景 | 目标值 | 状态 |
|----------|--------|------|
| 三态转换正确性 | CLOSED→OPEN→HALF_OPEN 全可达 | ✅ 已覆盖 |
| 陈旧探测抗污染 | probeGeneration 过滤旧回调 | ✅ 已覆盖 |
| 并发探测竞态 | 40 次迭代零错误 | ✅ 已覆盖 |
| **恢复时间** | **OPEN→HALF_OPEN→CLOSED < 5s** | 待新增 |
| **指标发射** | Counter 正确递增 | 待新增 |

#### 6.2.2 Reranker 熔断器（RerankerCircuitBreaker）

**已有测试**：`RerankerCircuitBreakerTest`（8 个用例，含并发 half-open 探测）

| 测试场景 | 目标值 | 状态 |
|----------|--------|------|
| 滑动窗口正确性 | 过期桶被正确旋转 | ✅ 已覆盖 |
| 并发 HALF_OPEN 探测 | 仅允许 1 个探测 | ✅ 已覆盖 |
| **降级链路端到端** | **BGE fail→LLM→Noop 全链路** | 待新增 |
| **恢复延迟** | **OPEN→CLOSED < 35s** | 待新增 |

#### 6.2.3 MCP 熔断器（McpServerCircuitBreaker）

**已有测试**：`McpServerCircuitBreakerTest`（2 个用例，Clock 注入）

| 测试场景 | 目标值 | 状态 |
|----------|--------|------|
| 慢调用阈值 | 正确触发 OPEN | ✅ 已覆盖 |
| **Server 隔离** | **A 熔断不影响 B** | 待新增 |
| **限流+熔断交互** | **联合保护正确** | 待新增 |

**简历亮点**："3 个独立熔断器（LLM/Reranker/MCP），200 并发线程 40 次迭代下零竞态错误，恢复时间 <5s"

#### 6.2.4 降级链路端到端测试

**测试类**：`RerankerDegradationChainTest`（`@Tag("chaos")`）

```
测试场景：
1. BGE 健康 → 验证 scoreType="reranker"
2. 注入 5 次连续失败 → 验证 circuit=OPEN
3. 下次调用 → 验证 scoreType="fallback"（LLM 或 Noop）
4. 等待 openStateMs → 验证 HALF_OPEN
5. 注入 1 次成功 → 验证 CLOSED，scoreType="reranker" 恢复
6. 总耗时 < 35s
```

### 6.3 高性能 — 延迟基准

| 指标 | 数据源 | 简历目标值 | 新增/已有 |
|------|--------|-----------|-----------|
| 意图路由 P95（启发式） | `chatagent.intent.routing.latency` | **< 50ms** | 新增 |
| 意图路由 P95（含 LLM） | 同上 | **< 500ms** | 新增 |
| 检索 P95 | `chatagent.rag.retrieval.latency` | **< 200ms** | 新增 |
| 重排序 P95 | `chatagent.reranker.latency` | **< 150ms** | 已有 |
| LLM TTFT P95 | `chatagent.llm.routing.latency` | **< 2s** | 已有 |
| 端到端 P95 | `t_chat_turn_metric.durationMs` | **< 5s** | 已有 |
| SSE 首字节 P95 | `chatagent.sse.first_byte.latency` | **< 3s** | 新增 |
| MCP 工具 P95 | `chatagent.mcp.latency` | **< 2s** | 已有 |

**测试方式**：对 Golden Dataset 执行完整 pipeline，采集 Prometheus 端点，断言各 P95 分位值。

### 6.4 分布式锁正确性

#### 6.4.1 Session 并发守卫（SessionConcurrencyGuard）

**已有测试**：`SessionConcurrencyGuardTest`（4 个用例）

| 测试场景 | 目标值 | 状态 |
|----------|--------|------|
| 基本获取/释放 | 锁正常获取和释放 | ✅ 已覆盖 |
| 同 Session 并发冲突 | 抛出 SessionConflictException | ✅ 已覆盖 |
| Redis 故障 fail-open | 请求不被阻塞 | ✅ 已覆盖 |
| **高并发竞态** | **50 线程零会话重叠** | 待新增 |
| **Lua 脚本原子性** | **仅 value 匹配时释放** | 待新增（Testcontainers Redis） |

#### 6.4.2 MQ 分布式锁（DistributedLockManager）

**已有测试**：`DistributedLockManagerTest`（6 个用例）

| 测试场景 | 目标值 | 状态 |
|----------|--------|------|
| ACQUIRED/DUPLICATE/WAIT_REQUIRED | 三种结果正确 | ✅ 已覆盖 |
| **Watchdog 续期** | TTL 正确延长 | 待新增 |
| **双锁协调** | Task Lock + Session Exec Lock 同时获取 | 待新增 |
| **失败锁回收** | FAILED 状态锁可被正确 reclaim | 待新增 |

**简历亮点**："Redis 分布式锁 + Lua 原子脚本，50 并发线程零会话重叠"

### 6.5 MQ 可靠性

**测试标签**：`@Tag("chaos")`

| 测试场景 | 目标值 | 实现方式 |
|----------|--------|----------|
| At-least-once 交付 | **1000 消息零丢失** | `OutboxDeliveryGuaranteeTest`：快速插入 1000 条 → 模拟 crash → 重启 → 验证全部 SENT |
| 重试时机准确性 | **TTL 误差 ±2s** | `RetryTimingAccuracyTest`：验证 retry.agent.10s 和 retry.ingest.30s |
| DLQ 正确兜底 | 不可恢复消息进入 DLQ | `DlqHandlingTest`：注入 terminal failure → 验证到达 `chat.dlq` |
| 幂等性去重 | 同消息仅处理一次 | `IdempotencyStressTest`：同 idempotencyKey 重复投递 → 验证单次处理 |
| SKIP LOCKED 多实例 | 无重复发布 | `OutboxMultiInstanceTest`：2 个 publisher 实例并行 → 零重复 |
| Outbox 状态机完整性 | PENDING→CLAIMED→SENT 及所有失败路径 | 扩展 `OutboxPollingPublisherTest` |

**具体测试场景（At-least-once 交付）**：

```
1. 快速插入 1000 个 outbox event
2. 中途 kill OutboxPollingPublisher（模拟进程 crash）
3. 重启 publisher
4. 断言: 全部 1000 条消息状态为 SENT
5. 断言: consumer 端通过 idempotencyKey 无重复消费
```

**简历亮点**："事务性发件箱（Transactional Outbox）at-least-once 交付保证，1000 消息混沌测试零丢失"

### 6.6 限流测试

| 测试场景 | 目标值 | 实现方式 |
|----------|--------|----------|
| Token Bucket 精度 | **permits = rate × window ± 5%** | 扩展 `McpServerRateLimiterTest` |
| 突发处理 | burstCapacity 个请求立即通过 | ✅ 已覆盖 |
| Server 隔离 | A 限流不影响 B | ✅ 已覆盖 |
| **突发+持续混合** | 正确 permit 计数 | 待新增 |

### 6.7 优雅降级

| 测试场景 | 目标值 | 实现方式 |
|----------|--------|----------|
| Reranker 三级降级 | BGE→LLM→Noop 全可达 | `RerankerDegradationChainTest` |
| LLM 多供应商切换 | DeepSeek fail → GLM 成功 | 部分在 `RoutingLLMServiceTest` 中 |
| Redis fail-open（4 组件） | SessionGuard/IntentCache/MqLock/SSE 均 fail-open | 扩展各自测试 |
| MQ consumer fail-open vs fail-fast | 按任务类型正确策略 | `RedisFailurePolicyTest` |

### 6.8 缓存性能

| 指标 | 数据源 | 简历目标值 | 新增/已有 |
|------|--------|-----------|-----------|
| 意图树缓存命中率 | `chatagent.intent.cache.hit/miss` | **≥ 95%** | 新增 |
| VDP 页面缓存（二次解析） | `vdp.cache.hit/miss` | **≥ 80%** | 已有 |
| MCP 注册表缓存 | 待新增 counter | **≥ 90%** | 新增 |

---

## 7. 测试基础设施

### 7.1 测试标签体系

```
@Tag("eval-rag-parse")      — PDF 解析质量（JUnit，无外部依赖）
@Tag("eval-rag-retrieval")  — 检索质量（需 Milvus + Ollama）
@Tag("eval-rag-e2e")        — 端到端 RAGAS（需 LLM + Milvus）
@Tag("eval-intent")         — 意图路由准确率（JUnit，mock LLM）
@Tag("eval-agent")          — Agent 性能（需 LLM）
@Tag("eval-memory")         — 记忆/摘要质量（JUnit + mock LLM）
@Tag("stress")              — 压力测试（Gatling/JUnit，需全栈）
@Tag("chaos")               — 混沌/故障注入（Testcontainers）
@Tag("golden")              — 已有 Golden PDF 测试
```

### 7.2 Maven Surefire 配置

```xml
<!-- 默认排除所有评估标签 -->
<surefire.excludedGroups>
  golden,eval-rag-parse,eval-rag-retrieval,eval-rag-e2e,
  eval-intent,eval-agent,eval-memory,stress,chaos
</surefire.excludedGroups>
```

### 7.3 CI Profile

| Profile | 触发时机 | 执行标签 | 数据集切分 | 耗时 |
|---------|----------|----------|-----------|------|
| `pr-smoke` | 每次 PR | `eval-intent` + `eval-rag-parse` | dev-smoke | < 3 min |
| `nightly-bench` | 每晚 | 所有 `eval-*` | test | 15–30 min |
| `release-gate` | 发版前 | 所有 `eval-*` | holdout (n≥50) | 30–60 min |
| `stress` | 手动 | `stress` + `chaos` | — | ~2 hours |

### 7.4 Gating 阈值

```
PR Smoke Gate
├── 阻断条件: 任意核心指标下降 > 15%
└── 耗时: < 3 min

Nightly Benchmark
├── 告警条件: 下降 > 5%（非阻断）
└── 趋势跟踪

Release Hard Gate
├── 阻断条件: 任意核心指标低于基线 5%
└── 最小样本量: n ≥ 50
```

---

## 8. 可复现性合约

每次评估运行必须记录以下环境信息：

| 维度 | 记录内容 |
|------|----------|
| 代码版本 | Git commit hash（代码 + Golden Dataset） |
| LLM 模型 | 模型 ID + API 版本（如 `deepseek-chat@2026-03`） |
| Embedding | Ollama bge-m3 model digest |
| Reranker | BGE reranker Docker image SHA |
| 向量索引 | Milvus collection 行数 + segment 数 |
| 推理参数 | temperature=0（所有评估 LLM 调用） |
| Judge | Judge 模型版本 + prompt 文件 SHA |

**报告格式**：JSON，存储路径 `eval/reports/{date}-{type}-report.json`

```json
{
  "type": "rag-retrieval",
  "date": "2026-04-13",
  "gitCommit": "abc1234",
  "environment": { "...上述各项..." },
  "metrics": {
    "hit_at_3": 0.82,
    "hit_at_5": 0.89,
    "mrr": 0.77,
    "ndcg_at_5": 0.81
  },
  "details": [ "...逐条明细..." ]
}
```

---

## 9. 已有测试基础设施

### 9.1 已有指标采集代码

| 文件路径 | 职责 |
|----------|------|
| `rag/retrieve/BgeHttpRetrievalReranker.java` | Reranker 指标（6 个 Micrometer 指标） |
| `chat/routing/RoutingMetrics.java` | LLM 路由指标（attempts/latency/circuit） |
| `rag/parser/VdpMetricsSupport.java` | VDP 解析指标 |
| `mcp/metrics/McpMetricsRecorder.java` | MCP 工具调用指标（6 个指标） |
| `conversation/metrics/ChatTurnMetricRecorder.java` | 聊天轮次指标持久化 |

### 9.2 已有测试文件

| 文件路径 | 用例数 | 覆盖范围 |
|----------|--------|----------|
| `IntentRouterTest.java` | 5 | 启发式/LLM/回退/澄清 |
| `BgeHttpRetrievalRerankerTest.java` | 10 | 熔断/超时/重试/探测 |
| `RerankerCircuitBreakerTest.java` | 8 | 状态转换 + 并发 HALF_OPEN |
| `McpServerCircuitBreakerTest.java` | 2 | Clock 注入状态测试 |
| `ModelHealthStoreTest.java` | 4 | 含 40 次迭代并发竞态 |
| `McpServerRateLimiterTest.java` | — | Token Bucket 精度 |
| `SessionConcurrencyGuardTest.java` | 4 | 锁获取/释放/冲突/fail-open |
| `DistributedLockManagerTest.java` | 6 | 三种获取结果 |
| `IncrementalSummarizerTest.java` | — | 锚定实体提取 |
| `OutboxPollingPublisherTest.java` | — | Outbox 状态机 |
| `GoldenPdfValidationTest.java` | 参数化 | 快照验证 |
| `GoldenPdfPerformanceBaselineTest.java` | — | 缓存/延迟基线 |

### 9.3 已有 Golden 资产

| 文件路径 | 说明 |
|----------|------|
| `golden-pdfs/scanned/scanned-01.pdf` | 扫描文档样本 |
| `golden-pdfs/tables/table-01.pdf` | 表格文档样本 |
| `golden-pdfs/headings/heading-01.pdf` | 标题文档样本 |
| `golden-pdfs/mixed/mixed-01.pdf` | 混合内容样本 |
| `golden-pdfs/expected/*.segments.json` | 5 个黄金快照 |

---

## 10. 优先级排序

> 按简历影响力排序，P0 最高。

### P0 — 第 1–2 周（核心简历数据）

| 任务 | 产出简历数据点 |
|------|----------------|
| 创建 `intent-golden.json`（80 条）+ `IntentRoutingEvalTest` | "意图 L1 准确率 96%" |
| 创建 `rag-golden.json`（100 条）+ `RetrievalQualityEvalTest` | "Hit@3 82%, NDCG@5 0.81" |
| 新增 3 个 Micrometer Timer（Intent/Retrieval/SSE） | 延迟数据采集基础 |
| 扩展 `golden-pdfs/` 从 4 → 20 | "20 类 PDF 解析准确率 95%" |

### P1 — 第 3–4 周（高价值数据）

| 任务 | 产出简历数据点 |
|------|----------------|
| RAGAS 端到端评估器 `RagasStyleEvalRunner` | "Faithfulness 92%, Context Precision 89%" |
| Reranker A/B 实验 `RerankerEffectivenessTest` | "BGE 重排序 NDCG 提升 15%" |
| 熔断器恢复基准 | "3 个熔断器恢复时间 <5s" |
| 延迟基准 + Prometheus 采集 | "端到端 P95 <5s, 检索 P95 <200ms" |

### P2 — 第 5–6 周（补充数据）

| 任务 | 产出简历数据点 |
|------|----------------|
| 多轮对话 + 记忆评估 | "20 轮连贯性保持 85%, 实体保留 90%" |
| MQ 混沌测试 | "1000 消息混沌测试零丢失" |
| Session 并发锁压测 | "50 并发零会话重叠" |
| 查询重写 A/B | "重写后 Hit@5 提升 3%" |

### P3 — 第 7–8 周（完善细节）

| 任务 | 产出简历数据点 |
|------|----------------|
| 工具调用评估 | "工具选择准确率 92%, ReAct 平均 2.3 步" |
| 限流精度测试 | "Token Bucket 精度 ±5%" |
| 缓存命中率采集 | "意图缓存命中率 95%" |
| Gatling 完整压测 | "500 QPS 持续, 200 并发" |

---

## 11. 简历数据汇总模板

> 以下为评估完成后可直接引用的简历数据模板：

```
RAG 质量：
• Faithfulness 92%, Context Precision 89%, Answer Relevancy 85%
• Hit@3 82%, NDCG@5 0.81, MRR 0.77
• BGE Reranker NDCG 提升 15%, 三级降级链（BGE→LLM→Noop）保障 100% 可用

AI Agent 智能：
• 三级意图路由 L1 准确率 96%, 端到端 85%, 启发式命中率 65%（节省 35% LLM 调用）
• 三层记忆实体保留率 90%, 20 轮对话连贯性保持 85%
• ReAct 工具调用准确率 92%, 平均 2.3 步完成任务

传统后端（三高）：
• 500 QPS 持续 / 200 并发会话, 端到端 P95 < 5s, 检索 P95 < 200ms
• 3 个独立熔断器（LLM/Reranker/MCP）恢复时间 < 5s, 200 线程 40 迭代零竞态
• 事务性发件箱 at-least-once 交付, 1000 消息混沌测试零丢失
• Redis 分布式锁 + Lua 原子脚本, 50 并发零会话重叠
```

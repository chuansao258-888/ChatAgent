# AI Agent & RAG 企业级评估方案 (V3.1)

## 1. 评估背景与目标
本方案旨在构建一套多维度、可量化、**可复现**的评估体系，确保 ChatAgent 在检索质量、决策准确性及系统稳定性上的卓越表现。V3.0 在指标定义和架构覆盖的基础上，重点补齐了**执行层协议**：数据集治理、指标口径合约、环境可复现性、分层 Gating 策略和标准化报告模板，使方案从"可读"升级为"可执行、可审计"。

---

## 2. 评估实施优先级

| 优先级 | 行动项 | 原因 |
| :--- | :--- | :--- |
| **P0** | **构建 Golden Dataset + 跑基线** | 没有当前基线数据，一切目标值都是空谈。必须先测出当前水位。 |
| **P0** | **分层意图路由测试集** | `IntentRouter` 是系统的核心调度链路，当前缺乏各层级的量化准确率。 |
| **P0** | **最小可观测性埋点** | Phase 1 即需记录各阶段 Latency 基线，必须前置最小 tracing/metering 基建。 |
| **P1** | **Reranker 降级对比实验** | 验证 `BgeHttpRetrievalReranker` 熔断触发后的实际影响与恢复能力。 |
| **P1** | **Chunk Enricher ROI 实验** | `LlmContextualChunkEnricher` 直接影响 API 成本与检索质量，需量化收益。 |
| **P2** | **安全与对抗性测试** | 面向企业部署，必须覆盖 Prompt Injection 和 RAG Poisoning。 |
| **P2** | **全链路可观测性完善** | 在最小埋点基础上完善 Grafana 仪表盘与 `x-trace-id` 全链路透传。 |
| **P3** | **MQ 稳定性评估** | 依赖 `MQ_REFACTOR_PLAN` 落地后执行。 |

---

## 3. 六维评估模型与关键指标

### 3.1 RAG 质量与增益评估 (The RAG Triad + Enhancements)
针对知识库检索与生成全链路进行评估，重点包含现有增强组件的 ROI。
- **核心四元组**：
  - **Context Precision (上下文精准度)**：检索出的 Chunk 中相关信息的占比。
  - **Context Recall (上下文召回率)**：标准答案所需信息是否被完整检索。
  - **Faithfulness (忠实度)**：回答是否严格基于 Context，严控幻觉。
  - **Answer Relevance (答案相关性)**：回答是否直接针对用户 Query 切题，无冗余信息。
- **Contextual Chunk Enrichment 评估**：
  - **Hit Rate 增益**：对比开启 vs 关闭 `LlmContextualChunkEnricher` 的 Hit Rate @ 5。
  - **ROI 分析**：统计单次 Enrichment 的额外 Token 消耗 vs 检索准确率提升的性价比。
- **Reranker Circuit Breaker 降级评估**：
  - **降级质量衰减**：当 `BgeHttpRetrievalReranker` 熔断（OPEN 状态）并降级为 `LlmRetrievalReranker` 或 Noop 时，RAG 质量的下降幅度。
  - **半开恢复率**：HALF_OPEN 状态下的服务恢复成功率与延迟波动。

### 3.2 复合意图路由与决策评估 (Intent Routing)
针对三层级 `IntentTree` + 启发式评分 + LLM 回退 + 歧义澄清的复杂链路。
- **分层路由准确率**：
  - L1 (Domain) 分类准确率。
  - L2 (Category) 分类准确率。
  - L3 (Topic) 分类准确率。
- **澄清触发合理性 (Clarification Precision)**：
  - 该问的问了（True Positive），不该问的没问（True Negative）。
- **多 LLM Provider 路由对比**：
  - 对比 DeepSeek、智谱、Ollama 在意图分类 (`classifier-model`) 上的准确率。
  - 构建各 Provider 生成质量、延迟、Token 成本的 Tradeoff 矩阵。

### 3.3 会话记忆与上下文质量 (Session Memory)
针对 `IncrementalSummarizer` 及长上下文管理。
- **实体保留率**：经过 L1/L2 会话摘要后，早期轮次的关键实体和约束条件是否被保留。
- **长会话漂移测试**：在 >20 轮对话后，评估 Agent 对初始上下文的遗忘率和逻辑漂移程度。

### 3.4 细粒度端到端延迟评估 (Latency Breakdown)
依赖可观测性基础设施进行持续度量（目标值需在 Phase 1 基线后确立）：
- **意图路由耗时**：包括本地启发式匹配与 LLM Classifier 调用的总耗时。
- **RAG 检索阶段耗时**：向量检索 + Reranker (含 Http 调用) 耗时。
- **LLM 生成首字延迟 (TTFT)**：从模型接收请求到 SSE 推送第一个 Token 的耗时。

### 3.5 安全与对抗性评估 (Security & Red Teaming)
- **Prompt Injection 防护**：用户通过恶意 Query 尝试操控意图路由（例如越权查询或绕过限制）的拦截率。
- **RAG Poisoning 演练**：向知识库注入包含恶意指令或矛盾信息的脏文档，评估系统的鲁棒性与清洗能力。

### 3.6 业务价值与体验 (Business Value & User Experience)
技术指标的最终落脚点是用户真实感知。以下指标需严格定义事件口径后方可采集（详见附件 B）。
- **点赞/点踩率 (Thumbs up/down)**：UI 端收集的用户直接反馈，作为整体质量的北极星指标。
  - 事件定义：用户在单条 Assistant 消息上主动点击 thumbs-up / thumbs-down。
  - 分母：当日/当周所有展示了反馈按钮的 Assistant 消息数。
  - 最小样本量：单场景 >= 30 条反馈后方可出报告。
  - 切片维度：按意图节点 (L1/L2)、知识库 ID、LLM Provider 分别统计。
- **修正成本**：用户需要进行多少次补充追问（Follow-up）或重新表述（Rephrase）才能得到满意答案。
  - 事件定义：同一 Session 内，用户在收到 Assistant 回复后 60s 内再次发送消息视为 Follow-up；若该消息与前一条 Query 的语义相似度 > 0.85 则计为 Rephrase。
  - 分母：当日/当周所有完成的 Session 数。
- **Token 效率 (ROI)**：评估端到端流程中输入/输出 Token 消耗总和与最终业务价值的性价比。
  - 口径：(input_tokens + output_tokens) / 用户满意会话数（有 thumbs-up 或无 follow-up 即结束的会话）。

---

## 4. 分阶段执行步骤 (Roadmap)

### 第一阶段：基线确立与黄金测试集 (Phase 1: Baseline & Golden Dataset)
- **构建测试集**（详见附件 A — Golden Dataset Spec）：
  - `intent-golden.json`：包含 L1/L2/L3 意图和需澄清场景的测试用例。
  - `rag-golden.json`：包含 `(Query, Context, Ground_Truth)` 的 QA 测试集。
  - 数据集按 **50% dev-tune / 10% dev-smoke / 20% test / 20% holdout** 切分。dev-tune 用于日常迭代调参，dev-smoke 冻结后专供 PR gate，两者用途隔离以避免对 smoke 子集过拟合。
- **最小可观测性前置**：
  - 在 `IntentRouter`、`KnowledgeBaseSimilaritySearcher`、`BgeHttpRetrievalReranker` 和 LLM 调用处各加一个 Micrometer Timer，确保 Phase 1 的延迟基线数据可信。
  - 无需完整 Grafana 仪表盘，日志 + Prometheus 端点即可。
- **测量当前基线**：
  - 跑通测试集，记录当前的 Hit Rate、Precision、各层级路由准确率和各阶段 Latency，**确立基线数据**。
  - 基线运行必须遵守可复现性合约（详见附件 C）。
- **实施组件隔离测试**：
  - 运行 Enricher 开启/关闭的 A/B 测试，记录成本与增益。
  - 注入网络延迟模拟 Reranker 熔断，记录降级后的生成质量。

### 第二阶段：多模型与长会话专项 (Phase 2: Models & Memory)
- **Provider Tradeoff 评测**：切换 `ChatModelRouter` 的底层模型，输出不同厂商在意图识别和生成上的评分报告。
- **长会话压测**：编写脚本模拟 20-50 轮真实连贯对话，校验 `IncrementalSummarizer` 的摘要质量。

### 第三阶段：安全加固、可观测性完善与 CI 集成 (Phase 3: Security, Observability & Automation)
- **全链路可观测性完善**：
  - 在 Phase 1 最小埋点基础上，强制透传 `x-trace-id` 至全链路。
  - 建立 **Grafana 仪表盘**，展示意图路由、RAG 检索、Reranker、LLM 生成四段核心耗时的实时曲线与 P95/P99。
- **分层 CI/CD Gating**（详见附件 D — Gating Policy）：
  - **PR Smoke Gate**：每次 PR 在 dev-smoke split 上跑轻量 smoke eval（~20 条核心用例），< 3 min，仅拦截严重回归（指标下降 > 15%）。
  - **Nightly Full Benchmark**：每晚在完整 test split 上运行全量评估，生成趋势报告。指标下降 > 5% 触发告警通知，但不阻断。
  - **Release Hard Gate**：发版前在 holdout split 上运行，要求所有核心指标不低于基线 5%，且样本量满足最小统计要求（n >= 50），方可放行。
  - Golden Dataset 随代码仓库进行版本化管理。
- **红蓝对抗**：执行 Prompt Injection 和脏数据注入测试，记录并修复漏洞。

### 第四阶段：MQ 实施与稳定性验收 (Phase 4: MQ Stability - 待 MQ 落地后执行)
- 验证 `t_mq_outbox` 的状态流转机制与轮询可靠性。
- **故障注入测试**：停机测试，验证 `retry.ingest.30s` 与 DLQ 的死信回流。
- **幂等性压测**：高并发触发 Outbox 重发，验证 `DistributedLockManager` 拦截重复落库的准确性。

---

## 5. 执行性附件

### 附件 A：Golden Dataset Spec

| 项目 | 规范 |
| :--- | :--- |
| **样本来源** | 从生产日志中筛选高频 Query + 团队手写边界用例 + LLM 辅助生成的对抗样本 |
| **PII 与敏感数据处理** | 从生产日志采样的 Query 必须经过脱敏处理：(1) 移除用户姓名、邮箱、手机号等 PII；(2) 替换业务敏感字段为占位符；(3) 脱敏后的数据集仅限评估相关人员访问，不得外传；(4) 原始日志采样需获得数据负责人书面授权；(5) 数据集保留周期与生产日志一致，过期后从仓库中删除并记录销毁日志 |
| **标注人员** | 至少 2 人独立标注，标注者需熟悉业务领域和意图树结构 |
| **仲裁机制** | 标注不一致时，由第三人（项目负责人或领域专家）终裁并记录分歧原因 |
| **标注规范** | 每条用例必须标注：预期意图路径 (L1>L2>L3)、是否应触发澄清、Ground Truth 答案、相关 KB chunk IDs |
| **切分策略** | 50% dev-tune（日常迭代调参）/ 10% dev-smoke（PR gate 专用，冻结后不参与调参）/ 20% test（nightly benchmark）/ 20% holdout（release gate，禁止用于调参） |
| **版本化** | 数据集文件纳入 Git 管理，变更需通过 PR review，CHANGELOG 记录每次修改内容与原因 |
| **规模目标** | Phase 1 初始：RAG >= 100 条，Intent >= 80 条（覆盖所有 L1 节点，每个 L1 至少 10 条） |
| **更新节奏** | 每次意图树结构变更或知识库大批量更新后，同步补充对应测试用例 |

### 附件 B：Metric Contract

每个指标必须定义以下字段，避免"看起来量化，实际不可复现"：

| 字段 | 说明 |
| :--- | :--- |
| **指标名称** | 唯一标识（如 `rag.hit_rate_at_5`） |
| **定义** | 自然语言描述 |
| **公式** | 数学公式（如 `relevant_in_top5 / total_queries`） |
| **分母** | 明确分母是什么（全量 query？仅命中 KB 的 query？仅有 ground truth 的 query？） |
| **采样频率** | PR smoke / nightly / release / 实时 |
| **数据源** | Golden dataset / 生产日志 / Prometheus metric |
| **负责人** | 谁对这个指标的解释和异常负责 |
| **告警阈值** | 下降多少触发告警，下降多少阻断发版 |

**核心指标口径示例**：

| 指标 | 公式 | 分母 | 采样 |
| :--- | :--- | :--- | :--- |
| `rag.hit_rate_at_5` | `count(ground_truth_chunk in top_5_results) / N` | Golden dataset 中有 ground truth chunk 标注的 query 数 | PR smoke + nightly |
| `rag.faithfulness` | LLM-as-a-Judge 评分（0-1），评估回答中每个 claim 是否有 context 支撑 | 全量 golden dataset query | nightly + release |
| `intent.l1_accuracy` | `correct_l1_predictions / N` | Intent golden dataset 中有 L1 标注的 query 数 | PR smoke + nightly |
| `intent.clarification_precision` | `true_positive_clarifications / all_triggered_clarifications` | 所有触发了澄清的 query 数 | nightly |
| `latency.intent_routing_p95` | P95(intent_route_duration_ms) | 所有经过意图路由的请求 | 实时 (Prometheus) |
| `biz.thumbs_up_rate` | `thumbs_up_count / messages_with_feedback_button` | 展示了反馈按钮的 Assistant 消息数 | 周报 |

### 附件 C：Reproducibility Contract

评估结果的可复现性是可信度的前提。每次 benchmark 运行必须锁定以下环境变量：

| 锁定项 | 说明 | 如何锁定 |
| :--- | :--- | :--- |
| **模型版本** | LLM provider 的具体模型 ID 和 API 版本 | 记录在 eval config 文件中（如 `deepseek-chat@2026-03`） |
| **Embedding 模型** | Ollama bge-m3 的具体版本 | 记录 Ollama model digest |
| **Reranker 模型** | bge-reranker-v2-m3 的具体版本 | 记录 Docker image SHA 或模型文件 hash |
| **向量索引快照** | Milvus collection 的状态 | 在 eval 前 flush + 记录 segment 数和 row count |
| **语料版本** | 知识库文档的版本 | 记录知识库最后更新时间戳或 Git commit hash |
| **Golden Dataset 版本** | 测试集的 Git commit | 自动记录在报告 header 中 |
| **随机性控制** | LLM temperature, top_p 等采样参数 | Eval 时强制 `temperature=0`（或固定 seed），消除采样随机性 |
| **Judge 模型与 Prompt** | LLM-as-a-Judge 使用的模型 ID、API 版本、评分 prompt（rubric）和解析逻辑 | Judge prompt 纳入 `eval/prompts/` 目录版本化管理；变更 judge model 或 rubric 时必须在旧版本上同时跑一次对照，记录分数漂移幅度，超过 ±3% 需在报告中标注并重新校准基线 |
| **系统参数** | `top_k`, `score_threshold`, `max_candidates` 等 | 从 application.yaml 自动提取并记录 |

### 附件 D：Gating Policy

三层 Gating 替代单一阻断规则，平衡 CI 响应速度与评估置信度：

```
PR Smoke Gate (每次 PR)
├── 数据集: dev-smoke split（冻结子集，与 dev-tune 隔离）, ~20 条核心用例
├── 耗时: < 3 min
├── 阻断条件: 任意核心指标下降 > 15%（严重回归）
└── 目的: 快速拦截明显破坏，不产生噪音

Nightly Full Benchmark (每晚)
├── 数据集: 完整 test split
├── 耗时: ~15-30 min
├── 行为: 生成趋势报告，指标下降 > 5% 触发告警通知
├── 不阻断合并，但标记 regression flag
└── 目的: 捕捉渐进式退化，积累趋势数据

Release Hard Gate (发版前)
├── 数据集: holdout split（从不用于日常开发和调参）
├── 最小样本量: n >= 50
├── 阻断条件: 任意核心指标低于历史基线 5%
├── 必须附带 Reproducibility Contract 记录
└── 目的: 最终质量保证，防止退化上线
```

### 附件 E：Eval Report Template

每次评估（nightly / release）固定输出以下结构的报告：

```
=== ChatAgent Eval Report ===
Date:           2026-03-30
Type:           nightly | release
Dataset:        test-split v1.2 (commit: abc1234)
Environment:    [Reproducibility Contract snapshot]

--- Summary ---
| Metric                      | Baseline | Current | Delta  | Status |
| rag.hit_rate_at_5           | 0.88     | 0.91    | +3.4%  | PASS   |
| rag.faithfulness            | 0.93     | 0.92    | -1.1%  | PASS   |
| intent.l1_accuracy          | 0.94     | 0.87    | -7.4%  | WARN   |
| intent.clarification_prec   | 0.82     | 0.85    | +3.7%  | PASS   |
| latency.intent_routing_p95  | 320ms    | 450ms   | +40.6% | WARN   |

--- Regressions ---
- intent.l1_accuracy: 下降 7.4%，疑似与 PR #142 的 prompt 修改相关。
  失败用例: [case_id: 23, 47, 51] — 详见 intent-golden.json。

--- Cost Delta ---
| Item              | Baseline   | Current    |
| Enricher tokens   | 12,400/run | 12,400/run |
| Classifier tokens | 8,200/run  | 9,100/run  |

--- Recommended Actions ---
1. 回查 PR #142 对 L1 domain prompt 的变更。
2. 在 case 23/47/51 上做单条 debug trace。

--- Sign-off ---
[ ] Reviewed by: ___
[ ] Approved for release: Yes / No
```

---

## 6. 推荐工具链
- **评估框架**：[Ragas](https://github.com/explodinggradients/ragas), [TruLens](https://github.com/truera/trulens)
- **性能压测**：JMeter, k6
- **可观测性**：Prometheus + Grafana, ELK (Logstash 收集 MQ Header)
- **LLM Judge**：GPT-4o 或 DeepSeek-V3 (作为评估模型)

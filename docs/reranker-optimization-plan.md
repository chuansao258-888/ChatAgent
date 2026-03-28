# Reranker 优化计划

## 1. 文档范围

本文档覆盖本地 reranker 链路的前两个优化阶段，并补充一个启动前的基线观测阶段：

- Stage 1.0：基线指标观测
- Stage 1：Java 侧 timeout、熔断、降级与可观测性
- Stage 2：Python reranker 服务常驻、预加载、预热与 readiness
- Stage 3：基于分数的置信度过滤与防幻觉增强

本文档**不包含**以下内容：

- Python 侧 micro-batch
- 跨进程零拷贝优化
- Reranker 模型本身的微调训练

这些内容建议在当前在线链路稳定之后，再作为下一阶段推进。

---

## 2. 当前架构

当前在线 rerank 链路如下：

1. [SessionFileTools.java](../chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/tools/SessionFileTools.java)
2. [SearchScopeResolver.java](../chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/SearchScopeResolver.java)
3. [BgeHttpRetrievalReranker.java](../chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java)
4. [rerank_server.py](../tools/bge-reranker-server/rerank_server.py)

当前行为：

- Java 同步调用 `http://localhost:7997/rerank`
- rerank 失败时，Java 已经会回退到 fused order
- 当前已有两个 payload 限制参数：
  - `rag.retrieval.reranker.max-candidates`
  - `rag.retrieval.reranker.max-chunk-chars`
- Python 服务使用 FastAPI + `FlagEmbedding.FlagReranker`
- 模型是懒加载的：第一条请求才触发模型初始化

---

## 3. 优化目标

### 3.1 Stage 1.0 目标
- 在不改行为逻辑的前提下，先拿到 reranker 的真实运行基线
- 观察 P50 / P95 / P99 及失败频率

### 3.2 Stage 1 目标
- 避免 reranker 不稳定拖慢在线问答主链路
- 给同步 rerank 请求设置明确的时延上界
- reranker 不可用时快速失败并平滑降级
- 让 timeout、失败率、fallback 次数可观测

### 3.3 Stage 2 目标
- 把 Python reranker 从“懒启动模型服务”变成“常驻可探活服务”
- 在接收流量前完成默认模型加载与预热
- 消除首条请求的冷启动尖刺
- 实现 Java 侧对 Python 侧就绪状态的识别

### 3.4 Stage 3 目标
- 建立检索结果的“信任边界”，从源头拦截不相关噪声
- 降低 AI 在知识库无匹配时的“幻觉回答”概率
- 实现基于分数的自动化意图澄清触发机制

---

## 4. Stage 1.0 & Stage 1：Java 侧链路加固（已落地）

*(此处内容详见历史记录)*

---

## 5. Stage 2：Python 侧服务化改造（已落地）

*(此处内容详见历史记录)*

---

## 11. Stage 3：基于分数的置信度过滤 (Confidence Filtering) ✅

### 11.1 核心策略

即便经过了向量检索与重排，召回的内容仍可能与用户问题无关（如问题完全超出知识库范围）。如果不加过滤地将这些低分内容喂给 LLM，会诱导模型产生幻觉。

**具体逻辑**：
1.  **适用范围**：`enableConfidenceFilter` 仅对 `bge-http` 成功返回、且 `scoreType == reranker` 的结果生效。严禁对 `retrieval`、`fallback` 或其他非重排分数应用此阈值。
2.  **分数检查**：在 `BgeHttpRetrievalReranker` 获得成功重排结果后，检查 Top 1 的 `relevance_score`。
3.  **阈值判定**：如果 `Top1_Score < CHATAGENT_RERANK_THRESHOLD`：
    *   认为本次检索“无效”。
    *   不再将这些 Hit 的正文证据纳入 Agent prompt。
    *   执行 `markAsFiltered()`。
4.  **后续处理**：由编排层根据标记决定是返回“未找到相关信息”还是触发“意图澄清”。

### 11.2 详细工作项

#### 11.2.1 增加置信度配置
为 `RerankerProperties` 增加以下配置项：
- `scoreThreshold`: 默认值建议设为 `0.15` (需根据 baseline 数据调优)。
- `enableConfidenceFilter`: 过滤功能总开关。

#### 11.2.2 改造 `BgeHttpRetrievalReranker`
实现过滤逻辑：
- 在 `rerank` 方法最后阶段，对比 `rankedResults.get(0).score()` 与 `threshold`。
- 如果低于阈值，记录一条警告日志并返回 `markAsFiltered()`。
- `markAsFiltered()`：将所有 Hit 的 `scoreType` 设为 `filtered`，并将 `score` 置为 `null`（确保 UI 不会错误展示为 0.00）。

#### 11.2.3 扩展 Metrics 指标
新增指标维度：
- `chatagent.reranker.requests` 的 `outcome` 增加 `filtered` 值。
- **价值**：通过观察 `filtered` 的比例，可以评估知识库的覆盖率以及用户提问是否频繁超出边界。

#### 11.2.4 契约与全链路联动 (Integration)
为了同时实现“防幻觉（不给 Agent 证据）”与“透明度（前端可见过滤详情）”，采用以下联动逻辑：
- **数据透传**：`SearchScopeResolver` 保持返回包含 `scoreType == filtered` 的 `RetrievalHit` 列表，不在此处物理截断。
- **提示词隔离 (Prompt Filtering)**：改造 `RetrievalHitFormatter`。在生成 `promptText` 时，**自动跳过**所有 `scoreType == filtered` 的命中标注；但在生成 `citations` 数组（元数据）时，**保留**这些命中并携带 `filtered` 标记。
- **UI 增强**：在来源面板（CitationSourcePanel）中识别 `filtered` 类型并展示专用文案（如 "Filtered by confidence"），且不展示分数。
- **Prompt 降级策略**：Agent 接收到的 `promptText` 为空时，必须触发其内置的诚实回答逻辑（“未在知识库中找到相关信息”）。

### 11.3 测试要求 ✅

为确保过滤边界不被破坏，已通过以下测试用例验证：
1.  [x] **有效拦截**：Reranker 成功返回且分数 < 阈值时，`scoreType` 必须变为 `filtered` 且 `score` 为 `null`。
2.  [x] **边界守卫**：验证 `retrieval` 路径（未启用 Reranker）和 `fallback` 路径（Reranker 超时/失败）的分数**不受** `score-threshold` 影响，确保基础召回可用性。
3.  [x] **提示词隔离**：验证 `filtered` 命中的内容**不会**出现在最终拼接给 LLM 的 System Prompt 文本中。
4.  [x] **元数据完整性**：验证 `filtered` 命中的详情仍存在于消息的 `citations` 元数据中，确保 UI 可渲染过滤状态。
5.  [x] **混合场景验证 (Mixed Case)**：同时包含有效（reranker）命中和被过滤（filtered）命中时，验证仅高置信度证据进入 prompt，而低置信度证据被物理隔离。

### 11.4 建议新增配置

```yaml
rag:
  retrieval:
    reranker:
      # 置信度过滤配置
      enable-confidence-filter: ${CHATAGENT_RAG_RERANKER_FILTER_ENABLED:true}
      score-threshold: ${CHATAGENT_RAG_RERANKER_SCORE_THRESHOLD:0.15}
```

---

## 12. 总结

Reranker 优化计划的所有阶段已圆满落地：

- **Stage 1 & 1.0**：Java 侧链路加固，引入了确定性超时、连接池保护、滑动窗口熔断及全方位指标观测。
- **Stage 2**：Python 侧服务化改造，实现了模型预加载、深度预热、就绪探针及过期请求丢弃。
- **Stage 3**：置信度过滤，建立了“对 Agent 隔离、对 UI 透明”的防幻觉机制。

系统现在具备了极高的生产稳定性和防幻觉能力。

---

## 13. 后续阶段预留 (Backlog)

以下内容不进入当前交付：

- Python 侧 micro-batch
- 动态 candidate 数量控制
- rerank payload 结构优化
- HTTP Gzip 压缩
- Protobuf 替代 JSON

当前优先级仍然是：

- 先保命：timeout、熔断、降级
- 再提速：preload、warmup、ready
- 最后再做吞吐与序列化层面的优化

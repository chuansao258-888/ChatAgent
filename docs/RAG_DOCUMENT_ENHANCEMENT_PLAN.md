# RAG 文档增强落地计划 (V4.1 - 最终工程闭环版)

## 1. 背景与目标
当前 ChatAgent 的知识库 RAG 主链已经具备基础的文档解析、切片、上下文增强和向量入库能力。然而在 Document-level Enhancement 方面存在功能缺失。

本方案在 V3.x 探讨了检索数学模型与大方向的基础上，结合深度工程评审，解决代码落地的**“最后一公里工程挑战”**（管线阻抗、热路径延迟、超长文本的 Reduce 灾难、数据库建模）。

本期目标：**落地企业级稳定、防 OOM、防超时、防脏数据的文档级增强链路，并安全接入 Rerank 阶段（Phase 1）。**

---

## 2. 核心架构决策（针对工程风险的 5 项拍板）

### 2.1 Context 载体拆分（解决双管线阻抗失配）
当前 `FileIngestionContext` 被 `Session` 和 `KnowledgeBase` 两条管线混用，导致字段堆叠和语义混乱。
**决策**：提取基类，各自适配。
- `BaseIngestionContext`：包含 `rawBytes`, `rawText`, `enhancedText`, `fileExtension`, `chunkDrafts` 等核心内容（注：`enhancedText` 必须留在基类供切片引擎消费）。
- `SessionIngestionContext`：继承基类，附加 `sessionId`, `ChatSessionFileDTO`。
- `KnowledgeIngestionContext`：继承基类，附加文档级特征 `documentId`, `keywords`, `questions`, `enhancerMetadata`, `enhancerCacheKey`。

### 2.2 增强结果的 Ownership
**决策**：`DocumentEnhancementResult` 是单次 LLM 调用的**一次性传输对象（Transient DTO）**。
- `DocumentEnhancer` 返回该 DTO 后，由外层（如 `KnowledgeDocumentIngestionServiceImpl`）负责将其解包，并将值**唯一回写**到 `KnowledgeIngestionContext` 中。
- 管线内所有下游（如持久化层、切片层）只能从 Context 中读取数据，禁止越级持有 DTO。

### 2.3 Session-file 管线的隔离
**决策**：会话级文件（Session File）**不参与**文档级增强。
- 会话文件要求极高的实时性，且生命周期极短，为其生成 keywords/questions 是负收益。
- `FileIngestionServiceImpl` 强制走 No-op 实现（返回空 DTO，`enhancedText` 为 null），确保现有接口 fallback 到 `rawText` 的逻辑正常流转。

### 2.4 热路径（Phase 1 Rerank 接入）的延迟预算
Phase 1 要求在 Reranker 中消费 Document 级的 keywords/questions，这需要拿 Chunk 的 `documentId` 做关联。
**决策**：**严禁在热路径查询 MySQL**。
- 采用 **Redis 双写 + Fail-open 策略** 加载 Document Signals（写入时同步写DB与Redis；缓存写入失败不阻断，热路径查询 Redis Miss 时回源 DB 填充并返回）。
- 在 `SearchScopeResolver` 产生 N 个候选 `documentId` 后，通过 Redis 的 `MGET` 或 Pipeline 批量获取文档的 keywords/questions 缓存。反查延迟必须控制在 < 20ms。

### 2.5 长文档 Map-Reduce 的取舍与硬限制
对于超长文档，`CONTEXT_ENHANCE`（正文排版修复）的 Reduce 拼接会带来严重的上下文断裂和极高的 LLM 成本。
**决策**：
- **短文档（单次上下文可容纳）**：执行 `CONTEXT_ENHANCE` + `DOC_META_EXTRACT`。
- **长文档（触发切分窗口）**：**放弃 `CONTEXT_ENHANCE`**，正文直接回退使用 `rawText` 进入 Chunker。仅对 `DOC_META_EXTRACT` 执行 Map-Reduce。
- `DOC_META_EXTRACT` 的 Reduce 上限硬控：最终只保留 `Top 10 keywords` 和 `Top 5 questions`。

---

## 3. 设计原则与任务模型

### 3.1 两层增强职责分离
- **DocumentEnhancer**：切片前，针对整篇文档，产出 `keywords / questions / metadata`，让 chunker 吃到干净输入。
- **ChunkEnricher**：切片后，针对单个 chunk，产出 `contextText / retrievalText`，解释 chunk 的局部作用。

### 3.2 任务收敛
本期只允许两个 LLM 任务：

1. **`CONTEXT_ENHANCE`**
   - 目标：正文整理、排版修复、术语标准化。
   - **防御机制**：增加幻觉校验，若产出的 `enhancedBody` 长度与原文长度比值偏差超过阈值（如缩小超 50% 或膨胀超 200%），视为发生重写幻觉，自动回退到 `rawText`。
2. **`DOC_META_EXTRACT`**
   - 目标：JSON Mode 提取，单次产出 `keywords`, `questions`, `metadata`。

### 3.3 稳定身份与复合缓存
引入基于 **SHA-256** 的复合缓存键（存储于 Redis，配置合理的 TTL）：
`CacheKey = SHA256(Content) + SHA256(PromptVersion) + ModelId + ConfigFlags`

---

## 4. 数据模型与持久化落地 (DB Schema)

**决策**：禁止将结构化信号硬塞入 `knowledge_document.metadata` 的无索引 String 字段。必须建立专门的 Side Table。

### 4.1 新增表 DDL
```sql
CREATE TABLE knowledge_document_enhancement (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL UNIQUE,
    -- 反规范化字段，用于提升按知识库批量清理或检索过滤的性能。需在业务层保证文档迁移 KB 时的一致性同步。
    knowledge_base_id VARCHAR(64) NOT NULL,
    keywords_json JSON,           -- 存储 Top 10 Keywords 数组
    questions_json JSON,          -- 存储 Top 5 Questions 数组
    -- 文档分类，允许值：'policy', 'manual', 'code', 'invoice', 'other'
    doc_type VARCHAR(32),         
    -- 合规过滤信号（如下游需要对含 PII 文档做脱敏或访问阻断则消费此字段，否则预留记录）
    contains_pii BOOLEAN,         
    enhancer_cache_key VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_id (knowledge_base_id)
);
```

### 4.2 LLM 输出的 JSON Schema 与服务端归一化
必须定义严格的 JSON Schema 引导 LLM 输出：
```json
{
  "type": "object",
  "properties": {
    "keywords": { "type": "array", "items": { "type": "string" }, "maxItems": 10 },
    "questions": { "type": "array", "items": { "type": "string" }, "maxItems": 5 },
    "metadata": {
      "type": "object",
      "properties": {
        "doc_type": { "type": "string", "enum": ["policy", "manual", "code", "invoice", "other"] },
        "contains_pii": { "type": "boolean" }
      }
    }
  },
  "required": ["keywords", "questions", "metadata"]
}
```
**服务端必须执行二次归一化**（不可全盘信任 LLM）：
- 对所有 List 元素执行 Trim、过滤空串、过滤超长无效条目、严格裁剪到设定的 TopN 数量。
- 若 `doc_type` 返回非 Enum 值，强制回退为 `other`。

---

## 5. 文档级信号进入检索的两阶段方案

### 5.1 Phase 1：文档级信号只做 rerank-side 辅助
**定位**：不改动召回（Recall）基准，不污染 RRF 数学分数。只在候选产生后，将信息交给 Reranker 提供额外上下文。

流程：
`query` -> `dense/sparse recall` -> `RRF 融合` -> **批量从 Redis(MGET) 获取文档级 keywords/questions** -> `附加至 Rerank 上下文` -> `rerank` -> `topK`

**Reranker 消费路径决策**：
由于当前存在外部独立模型（如 BGE Reranker）和 LLM Reranker：
- **外部语义模型 (如 BgeHttpRetrievalReranker)**：将提取出的 `keywords/questions` 格式化后直接前置拼接进传给模型的 `document` 文本中，例如 `"[Doc Keywords: A, B, C] \n [Related Questions: X, Y] \n\n {Chunk Content}"`。
- **LLM Reranker (如 LlmRetrievalReranker)**：直接利用结构化提示词传入 System/Context blocks 中，引导 LLM 进行相关性比对。

### 5.2 Phase 2：通过 side index 让文档级信号进入 recall
**定位**：若业务证实确有必要，再建独立的 Milvus Document Collection 跑独立召回。
- *迁移策略储备*：当 Phase 2 上线时，需提供脚本对已入库的 `knowledge_document_enhancement` 的 questions_json 执行批量 embedding 后回填到新 Collection。

---

## 6. 监控与运维 (SLO 与告警阈值)

指标必须可量化、可告警：
- **缓存命中率**：期望 > 80%（同文档重复上传场景）。低于 30% 需预警。
- **任务超时/失败率**：允许 Fail-open。如果连续 5 分钟 LLM API 失败率 > 5%，触发预警。
- **Ingestion 延迟**：单文档 Enhance 耗时 P95 需控制在 30 秒内（超出说明 Map-Reduce 窗口设置过细）。
- **Reranker Join 延迟**：查询 Redis 反查 Document Signal 耗时必须 < 20ms。

---

## 7. 实施顺序 (Rollout Plan)

1. **Context 与接口重构**：抽取 `BaseIngestionContext`，拆分双管线，`DocumentEnhancer` 返回结构化 DTO。
2. **Schema 与持久化落地**：执行 DDL，创建 `knowledge_document_enhancement` 表及对应的 Mapper/DAO。
3. **LLM 实现与长文档防御**：实现 `DOC_META_EXTRACT` (带服务端归一化) 和 `CONTEXT_ENHANCE` (带长度幻觉防御)。
4. **Redis 缓存接入**：实施双写 + 热路径回源容灾机制（Key 为 `doc_signal:{documentId}`）。
5. **Phase 1 检索接入**：修改 Reranker 数据准备逻辑，批量 MGET 附加文档信号上下文并适配 BGE/LLM 模型消费方式。
6. **灰度与压测**：对比大文件 Ingestion 耗时，验证 Rerank 阶段的延时没有超出预算。

---

## 8. 总结：工程红线
1. 决不允许 Session 会话管线触发文档级 Enhance。
2. 决不允许在超长文档上执行 `CONTEXT_ENHANCE` 的强行切片与合并。
3. 决不允许在 Rerank 热路径上使用 MySQL Join 查询文档级信号。
4. 决不允许将控制平面（`warnings`, `contains_pii` 等）混入送给 LLM 的正文 Chunks 中。
5. 决不允许无视 LLM 输出格式，必须在服务端对生成的列表与类型进行强校验和上限裁剪。
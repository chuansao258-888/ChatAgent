# RAG 检索增强生成模块 (com.yulong.chatagent.rag)

## 模块概述

RAG 模块是 ChatAgent 的**知识引擎**，实现了完整的文档摄取 (Ingestion) 和检索 (Retrieval) 流水线。支持多种文档格式的解析（PDF/Markdown/Tika/图片）、智能分块、上下文增强、向量化存储（Milvus）、混合检索（稠密 + BM25 稀疏）和重排序（BGE/LLM），是项目中代码量最大、复杂度最高的模块。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/`

---

## 1. 总体架构

```
═══════════════════ 摄取流水线 (Ingestion) ═══════════════════

文件上传 → FileSizeGuard(30MB) → FileTypeDetector → DocumentParserSelector
                                                          │
                    ┌─────────────────────────────────────┤
                    │                                     │
                    ▼                                     ▼
            ┌──────────────┐                    ┌────────────────┐
            │ PDF 解析器    │                    │ 其他解析器     │
            │ (质量路由+VDP)│                    │ (MD/Tika/Image)│
            └──────┬───────┘                    └───────┬────────┘
                    │                                     │
                    └──────────────┬──────────────────────┘
                                   ▼
                          ParseResult + ParseSegments
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
            文档增强          智能分块        块级上下文增强
          (LlmDocument      (SegmentAware    (LlmContextual
          Enhancer)         ChunkerRouter)   ChunkEnricher)
                    │              │              │
                    └──────────────┼──────────────┘
                                   ▼
                          Embedding (Ollama bge-m3)
                                   │
                                   ▼
                          Milvus 向量存储 (双 Collection)

═══════════════════ 检索流水线 (Retrieval) ═══════════════════

用户查询 → Embedding → Milvus 混合搜索 (Dense + BM25)
                                        │
                              ┌─────────┼─────────┐
                              ▼                   ▼
                    会话文件搜索              知识库搜索
                    (SessionFile)            (KnowledgeBase)
                              │                   │
                              └────────┬──────────┘
                                       ▼
                                RRF 融合排序
                                       │
                                       ▼
                            重排序 (BGE/LLM/Noop)
                            └─ 熔断器保护
                            └─ 置信度过滤
                                       │
                                       ▼
                            格式化 + 引用追踪
                                       │
                                       ▼
                            返回给 Agent 使用
```

---

## 2. 文档解析流水线

### 2.1 DocumentParserSelector (解析器选择器)

**文件：** `parser/DocumentParserSelector.java`

```java
selectParser(prefix, filename, mimeType, pipelineSource):
    1. FileTypeDetector.detect() → DetectedFileType
    2. 被拒绝的文件类型 → 抛 FileRejectedException
    3. 遍历所有 DocumentParser Bean：
       - 过滤 supports(detectedFileType)
       - 按 getSelectionPriority() 排序
       - 取第一个匹配
    4. 无匹配 → 回退到 Tika
```

### 2.2 FileTypeDetector (文件类型检测)

**文件：** `parser/FileTypeDetector.java`

三层检测：Apache Tika magic-byte + 扩展名 + MIME 类型

| 类型 | 处理 |
|------|------|
| 图片 | SESSION 管道接受，KNOWLEDGE 管道拒绝 |
| 拒绝扩展名 | .exe, .dll, .zip, .mp3, .mp4, .xls 等 |
| 支持扩展名 | .md, .markdown, .txt, .pdf, .doc, .docx |
| 二进制校验 | 强二进制 MIME 与声明扩展名不匹配则拒绝 |

### 2.3 FileSizeGuard (文件大小防护)

**文件：** `ingestion/FileSizeGuard.java`

- 硬限制：30MB
- 在 `readBytes()` 之前检查，防止 OOM

### 2.4 PdfDocumentParser — PDF 解析核心 (535行)

**文件：** `parser/PdfDocumentParser.java`

这是最复杂的解析器，包含 7 个内部协作者：

| 协作者 | 职责 |
|--------|------|
| `PdfQualityRouter` | 逐页质量评估和路由决策 |
| `PdfPageTextExtractor` | 通过 PDFBox 提取文本 |
| `PdfVdpDispatcher` | 视觉轨道页面分发到 VDP 引擎 |
| `PdfSegmentAssembler` | 组装最终 ParseSegment 列表 |
| `PdfPageRenderer` | 渲染 PDF 页面为图片 (可配置DPI，默认120) |
| `PdfVdpCache` | 缓存渲染的页面图片 |
| `PdfVdpBatchPlanner` | 决定批量还是逐页分发 |

**质量路由流程 (extractPages, 第298-416行)：**
```
1. PDFBox 提取所有页面文本
2. TextCleanupUtil 清洗文本
3. 逐页 qualityRouter.decideRoute():
   - 文本密度高 → Fast-Track（原生提取）
   - 文本密度低/扫描件 → Visual-Track（渲染为图片 → VDP 解析）
4. Visual-Track 页面异步分发到 VDP 引擎
5. segmentAssembler 组装结果
```

**质量评估 (assessQuality, 第457-471行)：**

| 等级 | 条件 |
|------|------|
| `LOW` | 0字符 / <200字符且>1MB / <30字符每页 |
| `HIGH` | >=80字符每页 |
| `MEDIUM` | 介于 LOW 和 HIGH 之间 |

### 2.5 VDP 引擎 (Visual Document Processing)

**VdpEngineRouter** (`parser/VdpEngineRouter.java`) 根据模式和管道来源选择引擎：

| 引擎 | 模式 | 说明 |
|------|------|------|
| `VlmVdpEngine` | PAGE_IMAGE | 单页 VLM（视觉语言模型），如 gpt-4o-mini/Qwen-VL |
| `MinerUVdpEngine` | PDF_PAGE_BATCH | 批量 PDF，调用外部 MinerU HTTP 服务 |
| `NoopVdpEngine` | - | 降级回退，返回占位文本 |

**VlmVdpEngine** (`parser/VlmVdpEngine.java`)：
- 通过 ChatModelRouter 将页面图片发送给视觉语言模型
- SHA-256 内容摘要缓存，避免重复处理
- JSON 响应解析，带非 JSON 回退恢复
- 仅支持 `PAGE_IMAGE` 模式

**MinerUVdpEngine** (`parser/MinerUVdpEngine.java`)：
- 提交 PDF 到 `/tasks` 端点（Multipart 上传）
- 轮询状态直到完成
- 获取结果并规范化为逐页 Markdown
- 支持 text、headers、tables、images、charts、equations、code 等内容类型
- 仅支持 `PDF_PAGE_BATCH` 模式

### 2.6 其他解析器

| 解析器 | 文件类型 | 产出 |
|--------|---------|------|
| `MarkdownDocumentParser` | .md/.markdown | 单个 FULL segment |
| `TikaDocumentParser` | 其他所有格式 | 单个 FULL segment（TextCleanupUtil 清洗后） |
| `ImageDocumentParser` | 图片 | 单个 FIGURE segment（VLM 转录后） |

---

## 3. 智能分块 (Chunking)

### 3.1 SegmentAwareChunkerRouter (分块策略路由)

**文件：** `ingestion/SegmentAwareChunkerRouter.java`

根据主导 segment 类型选择分块策略：

| SegmentType | 分块策略 |
|-------------|---------|
| `FULL` | Markdown 检测 → StructureAwareMarkdownChunker / PlainTextChunker |
| `PAGE` | 自定义页面合并：累积到 targetChars(1200)，不超过 maxChars(1800) |
| `FIGURE` | 每个 segment 独立分块 |
| `TABLE` / `SECTION` | 文本拼接 → PlainTextChunker |

### 3.2 PlainTextChunker (固定大小分块)

**文件：** `ingestion/PlainTextChunker.java`

| 参数 | 值 |
|------|-----|
| targetChars | 1200 |
| maxChars | 1500 |
| minChars | 500 |
| overlapChars | 150 |

**边界查找优先级 (findChunkEnd)：** 双换行 → 单换行 → 中文句号 → 英文句号 → 空格

### 3.3 StructureAwareMarkdownChunker (结构感知分块)

**文件：** `ingestion/StructureAwareMarkdownChunker.java`

两遍分块：
1. **行级分类：** HEADING / CODE / ATOMIC（图片/链接）/ PARAGRAPH
2. **块级打包：** 按标题层级分组，贪心打包（maxChars=1800, minChars=600）
3. **小尾合并：** 过小的尾部 chunk 合并回前一个

---

## 4. 文档增强 (Document Enhancement)

### 4.1 LlmDocumentEnhancer (文档级增强)

**文件：** `ingestion/LlmDocumentEnhancer.java`

仅对 Knowledge 管道生效（Session 文件生命周期短，不增强）。

| 模式 | 触发条件 | 操作 |
|------|---------|------|
| `short_full` | 单 FULL segment + 字符数 < shortDocCharLimit + 质量≥MEDIUM | CONTEXT_ENHANCE (LLM重写) + DOC_META_EXTRACT (关键词/问题提取) |
| `meta_only` | 多 segment 或大文档 | Map-Reduce 元数据提取 (Top 10 关键词, Top 5 问题) |

**幻觉检测 (passesLengthGuard)：** 增强后文本长度 < 原文 0.5 倍（过度压缩）或 > 2.0 倍（幻觉膨胀）则拒绝。

### 4.2 LlmContextualChunkEnricher (块级上下文增强)

**文件：** `ingestion/LlmContextualChunkEnricher.java`

Anthropic 风格的上下文检索增强：
1. 将整个文档（截断到 maxDocumentChars）+ 单个 chunk 发送给 LLM
2. LLM 返回一段上下文描述，将 chunk 定位在文档中
3. `retrievalText = contextText + chunkContent`
4. 此 enriched retrievalText 用于 embedding、BM25 和重排序

---

## 5. 向量存储 (Milvus)

### 5.1 双 Collection 架构

| Collection | 用途 | 管理类 |
|-----------|------|--------|
| `chat_file_chunk` | 会话附件文件 | `DefaultMilvusIndexService` |
| `chat_knowledge_chunk` | 知识库文档 | `DefaultKnowledgeBaseMilvusIndexService` |

**统一 Schema 模式：**
- 主键：`chunk_id` (VarChar 64)
- 稠密向量：`embedding` (FloatVector, 默认 1024 维)
- BM25：`bm25_text` (VarChar + analyzer) + `bm25_sparse` (SparseFloatVector)
- 文本字段：`content`, `context_text`, `retrieval_text` (VarChar 65535)

### 5.2 混合搜索

每个 Collection 同时支持：
- **稠密搜索：** `FloatVec` 在 `embedding` 字段上的向量相似度
- **BM25 稀疏搜索：** `EmbeddedText` 在 `bm25_sparse` 字段上的全文检索
- **RRF 融合：** 将稠密和稀疏结果合并

---

## 6. 检索流程

### 6.1 SearchScopeResolver (检索编排器)

**文件：** `SearchScopeResolver.java`

```
searchBySession(sessionId, query, intentResolution):
    1. 加载会话 → 解析附件文件 ID 列表
    2. 搜索会话文件 Collection (SessionFileSimilaritySearcher)
    3. 解析知识库命中：
       - 有 IntentResolution + kind=KB → 搜索范围知识库
       - 范围搜索为空 + scopePolicy=FALLBACK_ALLOWED → 扩展到所有 Agent KB
       - 无 IntentResolution → 搜索所有 Agent KB
    4. RRF 融合两个来源的命中结果
    5. 附加知识库信号 (关键词/问题)
    6. 重排序 + TopK 截断
```

### 6.2 RRF 融合算法 (fuseHits)

```java
score = 1.0 / (rrfK + rank + 1)    // rrfK 默认 60
// 两个来源中出现的同一 chunk 累加分数
```

### 6.3 SessionFileSimilaritySearcher / KnowledgeBaseSimilaritySearcher

两个搜索器使用相同模式：
```
1. OllamaEmbeddingClient.embed(query) → 查询向量
2. Milvus 稠密搜索
3. Milvus BM25 稀疏搜索
4. RRF 融合
5. 返回候选命中列表
```

---

## 7. 重排序 (Reranking)

### 7.1 重排序链

```
BgeHttpRetrievalReranker (首选)
    │ 熔断器保护
    │ 置信度过滤
    │ 专用连接池
    ▼
LlmRetrievalReranker (降级)
    │ 通过 LLM 排序
    ▼
NoopRetrievalReranker (最终降级)
    │ 保持 RRF 融合顺序
    ▼
返回结果
```

### 7.2 BgeHttpRetrievalReranker (BGE 重排序器)

**文件：** `retrieve/BgeHttpRetrievalReranker.java`

激活条件：`rag.retrieval.reranker.provider=bge-http`

**熔断器保护 (第125-129行)：**
- 每次调用前检查 `circuitBreaker.allowRequest()`
- OPEN 状态 → 直接返回候选，标记为 fallback
- HALF_OPEN → 先运行就绪探测

**专用连接池 (第92-109行)：** 独立的 `ConnectionProvider` (名称 `reranker-pool`)，避免与其他 HTTP 流量干扰。

**连接错误重试 (第214-237行)：** 最多重试 `retryConnectErrors` 次（默认1次）。

### 7.3 RerankerCircuitBreaker (重排序熔断器)

**文件：** `retrieve/RerankerCircuitBreaker.java`

滑动窗口熔断器，10 个桶 × 10 秒 = 100 秒窗口：

| 状态 | 行为 |
|------|------|
| CLOSED | 正常通过 |
| OPEN | 拒绝所有请求，等待 openStateMs (默认30秒) |
| HALF_OPEN | 允许 halfOpenProbeCount (默认1) 个探测请求 |

**触发条件（全部满足）：**
- 总请求数 >= minimumRequestVolume (10)
- 失败数 >= failureThreshold (5)
- 失败率 >= failureRateThresholdPercent (50%)

### 7.4 置信度过滤 (Confidence Filtering)

**BgeHttpRetrievalReranker 中的实现：**

```
1. 重排序完成后，检查 Top-1 的分数
2. 如果 Top-1 分数 < scoreThreshold (默认 0.15)
   → 所有候选标记为 scoreType="filtered"
3. RetrievalHitFormatter 中：
   - filtered 的命中从 LLM Prompt 中排除
   - 但仍保留在引用列表中供 UI 显示
```

**设计原则：** 置信度过滤只影响 `scoreType == reranker` 的结果，严格不影响检索/回退路径的分数。

### 7.5 知识库信号注入

**KnowledgeDocumentSignalService** 在重排序前为知识库命中附加信号：
- 从 Redis MGET 加载文档关键词和问题（延迟预算 < 20ms）
- Redis 未命中则回退到数据库并回填 Redis
- Fail-open：Redis/数据库都不可用时不影响检索

---

## 8. 引用追踪

### 8.1 RetrievalHitFormatter

**文件：** `application/RetrievalHitFormatter.java`

```
formatWithCitations():
    1. 遍历命中，转换为 CitationMetadata
    2. 跳过 scoreType="filtered" 的命中（不加入 Prompt）
    3. 为包含的命中生成编号块：
       [1] 来源标签 > 章节路径
       上下文: ...
       内容: ...
    4. 返回 FormattedRetrievalPrompt：
       - promptText: 组装的证据块 + 引用指令
       - citations: 所有 CitationMetadata（含 filtered 的）
```

### 8.2 CitationMetadata

**文件：** `model/CitationMetadata.java`

与 RetrievalHit 镜像的字段 + 额外的 `snippet` 字段（截断到180字符）和 `isFallback` 标志。

引用元数据通过 `CurrentTurnCitationHolder` 在工具执行期间暂存，最终附加到助手消息的 `metadata` JSONB 字段中。

---

## 9. 摄取流程详解

### 9.1 FileIngestionService (会话文件管道)

**文件：** `ingestion/FileIngestionService.java`

```
ingest() (异步 @Async):
    1. initializeContext() → SessionIngestionContext
    2. fetchSource():
       → FileSizeGuard.guardBeforeRead() [拒绝>30MB]
       → DocumentParserSelector.selectParser() [类型检测+选择]
    3. parseDocument() → ParseResult + segments
    4. 拒绝 OCR_REQUIRED 或 REJECTED 质量
    5. enhanceDocument() → LlmDocumentEnhancer (知识库专用)
    6. chunkDocument() → SegmentAwareChunkerRouter
    7. enrichChunks() → LlmContextualChunkEnricher
    8. buildFileChunks() → 创建 FileChunkDTO
    9. persistChunks():
       → 删除旧 chunks
       → 保存新 chunks 到 DB
       → Milvus upsert (embed → chunk mapper → index service)
   10. markCompleted()
```

---

## 10. 技术亮点总结

### 高性能
- **混合检索：** Dense + BM25 稀疏，RRF 融合，取长补短
- **原始 SSE 解析：** ProviderDirectStreamSupport 绕过 Spring AI
- **重排序信号热路径：** Redis MGET < 20ms 延迟预算

### 高可用
- **重排序降级链：** BGE HTTP → LLM Reranker → Noop (保持 RRF 顺序)
- **RerankerCircuitBreaker：** 滑动窗口熔断器保护
- **Fail-Open 设计：** 信号服务、缓存等非关键路径故障不影响主流程
- **重排序专用连接池：** 隔离故障域

### PDF 解析
- **双轨道路由：** Fast-Track (原生提取) + Visual-Track (VLM/MinerU)
- **三种 VDP 引擎：** VLM / MinerU / Noop 降级
- **质量评估四级：** HIGH / MEDIUM / LOW / REJECTED

### 智能分块
- **Segment 感知路由：** 根据 ParseSegment 类型选择最佳分块策略
- **结构感知 Markdown 分块：** 保持标题层级、代码块完整性
- **Anthropic 风格上下文增强：** 块级 LLM 上下文注入

### 置信度过滤
- **Agent 隔离：** 低分证据不进入 Prompt，模型不会基于不可靠信息回答
- **UI 透明：** 过滤的引用仍保留在 UI 中，用户可见
- **分数隔离原则：** 只影响 reranker 分数，不影响检索/回退路径

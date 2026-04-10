# RAG 文档增强 架构实现文档

> 对应计划：`docs/plans/RAG_DOCUMENT_ENHANCEMENT_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

为 RAG 入库管线构建生产级的文档级增强能力：提取关键词、生成问题、上下文增强，将文档级信号安全注入检索和重排阶段。

### 1.2 核心设计决策

1. **Context Carrier 拆分**：`BaseIngestionContext` 基类 → Session/Knowledge 独立子类，修复共享单一 Context 的阻抗失配。
2. **Session 管线隔离**：Session 临时文件不参与文档级增强（生命周期短、实时性要求高）。
3. **热路径延迟预算**：Rerank 时加载文档信号走 Redis MGET（延迟 <20ms），MySQL JOIN 严禁上热路径。
4. **长文档 Map-Reduce 限制**：短文档做 `CONTEXT_ENHANCE` + `DOC_META_EXTRACT`；长文档跳过上下文增强，仅做 Map-Reduce 元数据提取（Top 10 关键词、Top 5 问题）。
5. **侧表存储**：`knowledge_document_enhancement` 独立侧表，不塞入 metadata JSON。

## 2. 整体架构

### 2.1 增强管线架构

```
Knowledge Document 上传
    │
    ▼
KnowledgeDocumentIngestionServiceImpl
    │
    ├── 1. Parse (DocumentParserSelector → ParseResult)
    │
    ├── 2. Document Enhance (LlmDocumentEnhancer)
    │   ├── 短文档: CONTEXT_ENHANCE + DOC_META_EXTRACT
    │   └── 长文档: DOC_META_EXTRACT only (Map-Reduce)
    │   │
    │   └── 输出: DocumentEnhancementResult (transient DTO)
    │       → keywords[], questions[], enhancedText
    │
    ├── 3. Chunk (SegmentAwareChunkerRouter)
    │   └── 使用 enhancedText (而非 rawText) 作为分块输入
    │
    ├── 4. Chunk Enrich (LlmContextualChunkEnricher)
    │   └── 使用 resolveDocumentPrefix() 获取上下文前缀
    │
    ├── 5. 存储增强结果到 knowledge_document_enhancement
    │
    └── 6. Index (Milvus)
```

### 2.2 检索时信号注入

```
用户查询 → KnowledgeBaseSimilaritySearcher
    ↓
初步检索结果 (RetrievalHit[])
    ↓
BgeHttpRetrievalReranker.rerank()
    ├── KnowledgeDocumentSignalService.getSignals(docIds)
    │   ├── 先查 Redis (MGET, <20ms)
    │   └── Miss → 查 DB → 回填 Redis (fail-open)
    │
    └── 将 keywords/questions 注入 Rerank Prompt
```

## 3. 文件清单

### 3.1 数据库迁移

| 文件路径 | 说明 |
|---|---|
| `V10__phase6_knowledge_document_enhancement.sql` | `knowledge_document_enhancement` 侧表 |

### 3.2 后端代码

| 文件路径 | 职责 |
|---|---|
| **增强核心** | |
| `rag/ingestion/DocumentEnhancer.java` | 文档增强接口 |
| `rag/ingestion/NoopDocumentEnhancer.java` | 空实现（Session 管线使用） |
| `rag/ingestion/LlmDocumentEnhancer.java` | LLM 增强（CONTEXT_ENHANCE + DOC_META_EXTRACT） |
| `rag/ingestion/DocumentEnhancementResult.java` | 增强结果瞬态 DTO |
| `rag/ingestion/enhance/DocumentEnhancerProperties.java` | 增强配置 |
| **信号服务** | |
| `rag/retrieve/KnowledgeDocumentSignalService.java` | 加载文档信号（Redis → DB 回填） |
| `rag/retrieve/KnowledgeDocumentSignal.java` | 信号模型（keywords + questions） |
| `rag/retrieve/KnowledgeDocumentSignalProperties.java` | 信号配置 |
| **Context** | |
| `rag/ingestion/model/BaseIngestionContext.java` | 基础入库上下文 |
| `rag/ingestion/model/SessionIngestionContext.java` | Session 管线上下文 |
| `rag/ingestion/model/KnowledgeIngestionContext.java` | Knowledge 管线上下文 |
| **持久化** | |
| `support/persistence/entity/KnowledgeDocumentEnhancement.java` | 增强侧表实体 |
| `support/dto/KnowledgeDocumentEnhancementDTO.java` | 增强 DTO |
| `knowledge/port/KnowledgeDocumentEnhancementRepository.java` | Repository 接口 |
| `support/persistence/adapter/knowledge/MyBatisKnowledgeDocumentEnhancementRepository.java` | MyBatis 适配器 |

## 4. 核心功能实现

### 4.1 LLM 文档增强 — LlmDocumentEnhancer

**实现位置：** `rag/ingestion/LlmDocumentEnhancer.java`

**决策矩阵：**

| 文档条件 | CONTEXT_ENHANCE | DOC_META_EXTRACT |
|---|---|---|
| 单一 FULL segment + 短文档 | 是 | 是 |
| 多 segment 或长文档 | 否（使用 rawText） | 是（Map-Reduce） |

**Map-Reduce 窗口限制：** Top 10 关键词、Top 5 问题。

**幻觉检测：** 长度比 <0.5（过度压缩）或 >2.0（幻觉膨胀）时回退到 rawText。

**SHA-256 缓存键：** `content + promptVersion + model + config` 复合键。

### 4.2 信号注入 — KnowledgeDocumentSignalService

**实现位置：** `rag/retrieve/KnowledgeDocumentSignalService.java`

**加载策略：** Redis MGET（<20ms 预算）→ Miss 时查 DB → 回填 Redis（fail-open）。

```java
// Rerank 时信号注入
Map<String, KnowledgeDocumentSignal> signals = signalService.getSignals(docIds);
// 将 keywords/questions 组装进 Rerank Prompt
```

### 4.3 Chunk Enricher 上下文前缀

**实现位置：** `rag/ingestion/LlmContextualChunkEnricher.java`

**改动：** 使用 `context.resolveDocumentPrefix(maxChars)` 替代全文本截断，安全访问 segment 级文档前缀。

## 5. 配置说明

| 配置项 | 说明 |
|---|---|
| `rag.enhance.contextual.*` | CONTEXT_ENHANCE 开关和参数 |
| `rag.enhance.doc-meta.*` | DOC_META_EXTRACT 开关和参数 |
| `rag.retrieve.signal.*` | 信号服务 Redis/DB 配置 |

## 6. 已知限制与后续规划

- **Phase 2 检索增强**：当前信号仅注入 Rerank；Phase 2 计划独立 Milvus 文档级 collection。
- **Enricher ROI 未量化**：`LlmContextualChunkEnricher` 的成本/收益尚未做 A/B 实验。

# RAG Segment Pipeline 架构实现文档

> 对应计划：`docs/plans/RAG_SEGMENT_PIPELINE_DESIGN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

将 RAG 入库管线的核心数据单元从扁平 `String rawText` 转换为结构化 `List<ParseSegment>`。解决三个基础问题：(1) 大文件 OOM；(2) 长文档 LLM 增强 Map-Reduce 窗口未定义；(3) PDF 结构丢失。

### 1.2 核心设计决策

1. **ParseSegment 原子单元**：record（text, index, SegmentType, metadata），替代扁平字符串。
2. **双轨 ParseResult**：同时持有 `text`（兼容）和 `segments`（新货币），Phase 3 移除 text 字段。
3. **4 阶段渐进交付**：每阶段独立可合并、可回滚。
4. **OOM 纵深防御**：FileSizeGuard 入口（30MB）+ Parser 级冗余检查 + Segment 累积硬字符限制。
5. **Context 继承**：`BaseIngestionContext` → `SessionIngestionContext` / `KnowledgeIngestionContext`，提供 `resolveChunkSegments()` 和 `resolveDocumentPrefix(maxChars)`。
6. **SegmentAwareChunkerRouter**：适配现有 `StructureAwareMarkdownChunker` 和 `PlainTextChunker`，新增 PAGE segment 分页分块。

## 2. 整体架构

### 2.1 Segment Pipeline 全景

```
文件上传
    │
    ▼
FileSizeGuard (30MB)
    │
    ▼
DocumentParserSelector → DocumentParser
    │
    ▼
ParseResult
├── text (兼容, Phase 3 移除)
└── segments: List<ParseSegment>
    ├── FULL segment (通用文档)
    ├── PAGE segments (PDF 逐页)
    ├── SECTION segments (Markdown)
    ├── TABLE segments (表格)
    └── FIGURE segments (图片/VDP)
    │
    ▼
BaseIngestionContext (承载 segments)
├── SessionIngestionContext
└── KnowledgeIngestionContext
    │
    ├── LlmDocumentEnhancer
    │   ├── 短文档: CONTEXT_ENHANCE + DOC_META_EXTRACT
    │   └── 长文档: DOC_META_EXTRACT (Map-Reduce on segment windows)
    │
    ▼
SegmentAwareChunkerRouter
    ├── FULL/SECTION → StructureAwareMarkdownChunker / PlainTextChunker
    └── PAGE → 页感知分块
    │
    ▼
LlmContextualChunkEnricher
    └── resolveDocumentPrefix(maxChars) 获取安全上下文前缀
    │
    ▼
Milvus Index
```

### 2.2 4 阶段交付路线

| Phase | 目标 | 兼容性 |
|---|---|---|
| Phase 1 | Parse 层重构，输出 segments | 双轨兼容（text + segments） |
| Phase 2 | Context 继承 + SegmentAwareChunkerRouter | 兼容 |
| Phase 3 | Enhancer/Enricher 迁移，删除 compat 字段 | 移除 text 字段 |
| Phase 4 | 侧表 + Redis 双写 + Reranker 信号注入 | 增量 |

## 3. 文件清单

### 3.1 后端代码

| 文件路径 | 职责 |
|---|---|
| **Segment 核心** | |
| `rag/parser/ParseSegment.java` | Pipeline 原子单元 record |
| `rag/parser/SegmentType.java` | 枚举：FULL / PAGE / TABLE / SECTION / FIGURE |
| `rag/parser/ParseResult.java` | 结构化解析结果（Builder 模式） |
| `rag/parser/QualityLevel.java` | 枚举：HIGH / MEDIUM / LOW / REJECTED |
| **Context 继承** | |
| `rag/ingestion/model/BaseIngestionContext.java` | 基础入库上下文（rawText + segments 双轨） |
| `rag/ingestion/model/SessionIngestionContext.java` | Session 管线上下文 |
| `rag/ingestion/model/KnowledgeIngestionContext.java` | Knowledge 管线上下文 |
| **Chunker** | |
| `rag/ingestion/DocumentChunker.java` | 新 Chunker 接口（消费 `List<ParseSegment>`） |
| `rag/ingestion/SegmentAwareChunkerRouter.java` | Segment 感知的分块路由器 |
| `rag/ingestion/StructureAwareMarkdownChunker.java` | 结构感知 Markdown 分块器（现有，不修改） |
| `rag/ingestion/PlainTextChunker.java` | 纯文本分块器（现有，不修改） |
| `rag/ingestion/MarkdownSectionChunker.java` | Markdown Section 分块器 |
| **Enhancer / Enricher** | |
| `rag/ingestion/LlmDocumentEnhancer.java` | LLM 文档增强（segment 级决策矩阵） |
| `rag/ingestion/LlmContextualChunkEnricher.java` | Chunk 上下文增强（使用 `resolveDocumentPrefix`） |
| **防护** | |
| `rag/ingestion/FileSizeGuard.java` | 入口级 30MB 硬限制 |
| **Ingestion Service** | |
| `rag/ingestion/KnowledgeDocumentIngestionServiceImpl.java` | Knowledge 入库管线编排 |
| `rag/ingestion/FileIngestionServiceImpl.java` | Session 入库管线编排 |

## 4. 核心功能实现

### 4.1 ParseSegment — Pipeline 原子单元

**实现位置：** `rag/parser/ParseSegment.java`

```java
public record ParseSegment(
    String text,           // 段落文本
    int index,             // 段落索引
    SegmentType type,      // FULL/PAGE/TABLE/SECTION/FIGURE
    Map<String, Object> metadata  // 段落元数据
) {}
```

**SegmentType 语义：**

| 类型 | 来源 | 说明 |
|---|---|---|
| FULL | Tika/通用解析器 | 完整文档文本 |
| PAGE | PdfDocumentParser | PDF 逐页输出 |
| TABLE | PdfDocumentParser | 表格结构化提取 |
| SECTION | MarkdownDocumentParser | Markdown 章节切分 |
| FIGURE | ImageDocumentParser/VDP | 图片/VLM 解析结果 |

### 4.2 BaseIngestionContext — 双轨兼容

**实现位置：** `rag/ingestion/model/BaseIngestionContext.java`

**双轨设计：**

```java
// Phase 1-2 兼容字段
private String rawText;        // 旧管线：全文
private String enhancedText;   // 增强后全文

// 新管线字段
private List<ParseSegment> segments;
private List<ParseSegment> enhancedSegments;

// 安全访问方法
public List<ParseSegment> resolveChunkSegments();  // 优先 segments，回退 text
public String resolveDocumentPrefix(int maxChars);  // 从 segments 拼接前缀
```

### 4.3 SegmentAwareChunkerRouter

**实现位置：** `rag/ingestion/SegmentAwareChunkerRouter.java`

**路由逻辑：**

| Segment 类型 | 分块策略 |
|---|---|
| FULL | 委托 PlainTextChunker |
| SECTION | 委托 StructureAwareMarkdownChunker |
| PAGE | 页感知分块（每页独立分块，保留页边界） |
| TABLE | 整体保留，不分块 |
| FIGURE | 整体保留 |

**设计原则：** 不修改现有 `StructureAwareMarkdownChunker` 和 `PlainTextChunker` 的内部逻辑，仅作为 delegate 调用。

### 4.4 LlmDocumentEnhancer — Segment 级决策矩阵

**实现位置：** `rag/ingestion/LlmDocumentEnhancer.java`

**增强决策：**

```
if (segments.size() == 1 && segments[0].type == FULL && totalChars < threshold)
    → CONTEXT_ENHANCE + DOC_META_EXTRACT (完整增强)
else
    → DOC_META_EXTRACT only (Map-Reduce on segment windows)
```

**Map-Reduce 窗口：** 长文档按 segment 窗口切分，每个窗口独立提取元数据，最后合并。硬限制 Top 10 关键词、Top 5 问题。

### 4.5 OOM 纵深防御

| 层级 | 防护机制 | 位置 |
|---|---|---|
| 入口 | FileSizeGuard 30MB 硬拦截 | FileIngestionService 入口 |
| Parser | PDFBox 解析级 30MB 冗余检查 | PdfDocumentParser |
| Segment | 累积字符硬限制 | SegmentAwareChunkerRouter |

## 5. 已知限制与后续规划

- **text 字段尚未移除**：当前处于 Phase 2 阶段，text 字段仍保留兼容。
- **流式 API 未实现**：大文件仍需全量加载到内存，未来计划 `parseSegments()` 流式接口。
- **Enhancer ROI 未量化**：Map-Reduce 增强的 Token 消耗 vs 检索质量提升需要 A/B 实验验证。

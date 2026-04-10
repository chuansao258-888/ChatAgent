# RAG 文件解析架构 架构实现文档

> 对应计划：`docs/plans/RAG_FILE_PARSING_ARCHITECTURE_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

为 RAG 入库管线中的 `parse → enhance → chunk → enrich → index` 主链路建立独立的解析层架构基线。定义文件类型检测、解析器路由、结构化输出和 OOM 防护。

### 1.2 核心设计决策

1. **Tika 保持通用解析骨干**：.doc, .docx, .pptx 等通用格式继续使用 Tika。
2. **Markdown 专用解析器**：`MarkdownDocumentParser` 独立处理 .md 文件。
3. **PDF 一等公民**：`PdfDocumentParser` 基于 Apache PDFBox，支持页级提取和质量评估。
4. **动态解析器路由**：`DocumentParserSelector` 通过 MimeType + 扩展名注入，不硬编码。
5. **结构化 ParseResult**：Builder 模式替代简单 2 字段 record，含 text + segments + quality + diagnostics。
6. **解析质量评级**：HIGH / MEDIUM / LOW / REJECTED 四级质量评估。
7. **异步 OCR 状态机**：扫描 PDF 的 OCR 不阻塞同步入库流程。
8. **文件大小硬拦截**：`FileSizeGuard` 入口级 30MB 限制，`readBytes()` 前拦截。

## 2. 整体架构

### 2.1 解析层架构

```
文件上传
    │
    ▼
FileSizeGuard (30MB 硬拦截)
    │
    ▼
FileTypeDetector (Mime + Extension + Magic Number)
    │
    ▼
DocumentParserSelector.selectParser(fileType)
    │
    ├── PDF (.pdf) → PdfDocumentParser (PDFBox)
    │   ├── 文本页 → Fast-Track (原生提取)
    │   ├── 扫描页 → Visual-Track (VDP Engine)
    │   └── 输出: ParseResult(text + segments + quality)
    │
    ├── Markdown (.md) → MarkdownDocumentParser
    │   └── 输出: ParseResult(带 SECTION segments)
    │
    ├── Image (.png/.jpg) → ImageDocumentParser (VDP)
    │   └── 输出: ParseResult(FIGURE segment)
    │
    └── 通用格式 (.doc/.docx/...) → TikaDocumentParser
        └── 输出: ParseResult(FULL text)
```

## 3. 文件清单

### 3.2 后端代码

| 文件路径 | 职责 |
|---|---|
| **核心接口与路由** | |
| `rag/parser/DocumentParser.java` | 解析器核心接口 |
| `rag/parser/DocumentParserSelector.java` | 动态解析器路由（FileTypeDetector 驱动） |
| `rag/parser/FileTypeDetector.java` | 统一文件类型检测（Mime + Ext + Magic Number） |
| `rag/parser/DetectedFileType.java` | 检测到的文件类型 |
| **解析器实现** | |
| `rag/parser/PdfDocumentParser.java` | PDFBox PDF 解析器（~1500行，含 Fast/Visual Track 路由） |
| `rag/parser/MarkdownDocumentParser.java` | Markdown 专用解析器 |
| `rag/parser/TikaDocumentParser.java` | Tika 通用解析器 |
| `rag/parser/ImageDocumentParser.java` | 图片解析器（路由到 VDP Engine） |
| **结果与质量** | |
| `rag/parser/ParseResult.java` | 结构化解析结果（Builder 模式） |
| `rag/parser/ParseSegment.java` | Pipeline 原子单元（text, index, type, metadata） |
| `rag/parser/SegmentType.java` | 枚举：FULL / PAGE / TABLE / SECTION / FIGURE |
| `rag/parser/QualityLevel.java` | 枚举：HIGH / MEDIUM / LOW / REJECTED |
| `rag/parser/ParserType.java` | 解析器类型枚举 |
| **防护** | |
| `rag/ingestion/FileSizeGuard.java` | 入口级 30MB 硬限制 |
| `rag/parser/FileRejectedException.java` | 文件拒绝异常 |
| `rag/parser/PipelineSource.java` | 枚举：SESSION / KNOWLEDGE 管线来源 |

## 4. 核心功能实现

### 4.1 动态解析器路由 — DocumentParserSelector

**实现位置：** `rag/parser/DocumentParserSelector.java`

**实现逻辑：** 通过 `FileTypeDetector` 检测文件类型（Mime + Extension + Magic Number 三重检测），路由到对应的解析器。

```java
// 解析器选择逻辑
public DocumentParser selectParser(DetectedFileType fileType) {
    return switch (fileType.category()) {
        case PDF -> pdfDocumentParser;
        case MARKDOWN -> markdownDocumentParser;
        case IMAGE -> imageDocumentParser;
        default -> tikaDocumentParser; // 通用回退
    };
}
```

### 4.2 PDF 解析器 — PdfDocumentParser

**实现位置：** `rag/parser/PdfDocumentParser.java`

**实现逻辑：** 基于 Apache PDFBox 的页级提取，支持 Fast-Track 和 Visual-Track 双轨路由。

- **Fast-Track**：文本页直接提取原生文本，输出 PAGE segment
- **Visual-Track**：扫描/图片页渲染为 PNG → VDP Engine 解析，输出 PAGE segment
- **质量评估**：根据文本密度、OCR 比例等指标输出 QualityLevel
- **诊断信息**：ParseResult 包含 diagnostics（警告、质量评级），不混入 LLM 内容

### 4.3 结构化 ParseResult

**实现位置：** `rag/parser/ParseResult.java`

**设计特点：** Builder 模式，双轨兼容：

```java
ParseResult result = ParseResult.builder()
    .text("兼容用全文")           // Phase 1-2 兼容
    .segments(parseSegments)       // 新管线货币
    .qualityLevel(QualityLevel.HIGH)
    .parserType(ParserType.PDF)
    .diagnostics(diagnostics)      // 控制面数据
    .build();
```

### 4.4 文件大小防护 — FileSizeGuard

**实现位置：** `rag/ingestion/FileSizeGuard.java`

**防护策略：** 在 `readBytes()` 调用前硬拦截超过 30MB 的文件，防止 OOM。

## 5. 已知限制与后续规划

- **流式 API 待实现**：当前仍为全量内存模式，未来计划 `parseSegments()` 流式 API。
- **Notebook 解析**：.ipynb 解析器作为预研，当前不纳入正式管线。

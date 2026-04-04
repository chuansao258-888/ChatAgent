# RAG Segment-based Pipeline 设计方案 (V2.0 - 目标架构 + 分期实施版)

## 1. 背景与动机

当前 ChatAgent 的 ingestion 管线以 `String rawText` 为核心货币：
- Parser 返回一整个 String
- Enhancer 接收和返回 String
- Chunker 消费 String

这个模型存在三个根本性问题：

1. **大文件 OOM**：`Files.readAllBytes()` + `Tika.parseToString()` 对大 PDF 会产出巨大 String，有 OOM 风险
2. **长文档 Map-Reduce 窗口无定义**：Enhancement 计划需要对长文档做分窗口 LLM 调用，但在纯 String 上硬切字符是粗暴的、会破坏语义的
3. **PDF 结构丢失**：Tika 把多页 PDF 压平为单一文本，页边界、表格边界全部丢失，chunker 无法利用这些天然切分点

业界成熟方案（Unstructured.io、LangChain、LlamaIndex）的共同模式是：**Parser 输出带位置信息的结构化 segment 列表，而非单一 String**。

### 1.1 本文档的定位

本方案描述 ChatAgent ingestion 管线的 **目标架构（Target Architecture）**，同时给出 **分 4 期渐进式落地** 的实施路径。

**与已收敛文档的关系**：
- **RAG_FILE_PARSING_ARCHITECTURE_PLAN.md**：本方案的 Parse 层设计建立在该文档的 8 条 MUST 决策之上，并保留其「先硬拦截，再逐步引入流式/分段解析」的核心策略。
- **RAG_DOCUMENT_ENHANCEMENT_PLAN.md**：本方案的 Enhancer/Enricher 层设计兼容该文档的 `rawText`/`enhancedText` 过渡面，在前期阶段不推翻其兼容 backbone。

**核心原则**：每一期可独立 merge、独立回滚、独立验证。不做一次性总变更。

---

## 2. 总览：当前 vs 目标 vs 过渡态

```
当前:
  byte[] -> Parser -> String text -> Enhancer(String) -> Chunker(String) -> Enricher -> Index
                      ^ 全文一坨

过渡态 (Phase 1-2):
  [入口硬拦截] -> byte[] -> Parser -> ParseResult { text + segments }
    -> Enhancer(rawText/enhancedText 兼容面) -> Chunker(可选消费 segments) -> Enricher -> Index
    ^ 新旧并存，segments 渐进式接入

最终目标 (Phase 3-4):
  [入口硬拦截] -> byte[] -> Parser -> List<ParseSegment>
    -> Enhancer(segments) -> Chunker(segments) -> Enricher -> Index
    ^ 带位置的结构化单元
```

**最终目标**：管线的货币单位从 `String` 变成 `List<ParseSegment>`。但这个迁移通过 4 期渐进完成，每期保留向后兼容面。

---

## 3. 数据模型层

### 3.1 ParseSegment -- 管线原子单位

```java
/**
 * Atomic unit produced by parsers. A segment may represent a page, a logical
 * section, a table, or the entire document for simple formats.
 */
public record ParseSegment(
    String text,
    int index,                     // 段序号（页码或逻辑段号, 0-based）
    SegmentType type,
    Map<String, Object> metadata   // 页码、标题、坐标等
) {
    public int charCount() {
        return text == null ? 0 : text.length();
    }
}
```

### 3.2 SegmentType

```java
public enum SegmentType {
    FULL,       // 整篇文档（Markdown, TXT, DOCX）
    PAGE,       // PDF 按页
    TABLE,      // 独立表格
    SECTION     // 逻辑章节（预留，未来 Notebook cell 等）
}
```

各 Parser 的 segment 产出约定：
- **MarkdownDocumentParser** -> 1 个 `FULL` segment
- **TikaDocumentParser** -> 1 个 `FULL` segment
- **PdfDocumentParser** -> N 个 `PAGE` segment（PDFBox 按页抽取）
- **未来 NotebookDocumentParser** -> N 个 `SECTION` segment（按 cell 组）

### 3.3 QualityLevel

```java
public enum QualityLevel {
    HIGH,       // 原生文本，结构较好
    MEDIUM,     // 文本可用，存在结构损失
    LOW,        // 质量差，可能需要 OCR
    REJECTED    // 不允许进入 RAG 主链
}
```

### 3.4 ParseResult -- 同时持有 text + segments（过渡兼容设计）

**设计决策**：ParseResult 同时保留 `text`（兼容现有消费方）和 `segments`（新管线货币）。
- **Phase 1-2**：`text` 仍是主消费面，`segments` 渐进接入。
- **Phase 3+**：`text` 降级为 `getFullText()` 的 alias，消费方全部迁移到 `segments`。
- 这个双轨策略保证了与 RAG_FILE_PARSING_ARCHITECTURE_PLAN 和 RAG_DOCUMENT_ENHANCEMENT_PLAN 的兼容。

```java
@Data
@Builder
public class ParseResult {

    // -- 兼容面（Phase 1-2 保留，Phase 3+ 由 getFullText() 派生） --
    private String text;                // 解析后的全文本（与 Parsing 计划 §6 兼容）

    // -- 新管线货币 --
    private List<ParseSegment> segments;

    private String parserType;          // "Markdown" / "Tika" / "PDFBox"
    private String extractionMode;      // NATIVE_TEXT / OCR_REQUIRED / OCR_FALLBACK
    private QualityLevel qualityLevel;  // nullable = 未评估（Markdown 等无需评估的格式）

    @Builder.Default
    private Map<String, Object> diagnostics = new HashMap<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    // -- 便捷方法 --

    /** 向后兼容：单文本快捷构造（同时设置 text 和 segments） */
    public static ParseResult ofText(String text) {
        return ParseResult.builder()
                .text(text)
                .segments(List.of(new ParseSegment(text, 0, SegmentType.FULL, Map.of())))
                .build();
    }

    /**
     * 拼接全文（给仍需 String 的过渡消费方用）。
     * Phase 1-2：优先返回 text 字段（如果已设置）；
     * Phase 3+：text 字段被移除后，此方法从 segments 派生。
     */
    public String getFullText() {
        if (StringUtils.hasText(text)) return text;
        if (segments == null || segments.isEmpty()) return "";
        return segments.stream()
                .map(ParseSegment::text)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
    }

    /** 总字符数（用于判断短/长文档阈值） */
    public int totalChars() {
        if (segments != null && !segments.isEmpty()) {
            return segments.stream().mapToInt(ParseSegment::charCount).sum();
        }
        return text == null ? 0 : text.length();
    }

    /** 是否多段 */
    public boolean isMultiSegment() {
        return segments != null && segments.size() > 1;
    }
}
```

### 3.5 DocumentEnhancementResult -- 增强产物（Transient DTO）

```java
public record DocumentEnhancementResult(
    List<ParseSegment> enhancedSegments,  // 短文档增强后的 segments（长文档为 null）
    List<String> keywords,                // Top 10
    List<String> questions,               // Top 5
    Map<String, Object> metadata,         // doc_type, contains_pii 等
    String cacheKey
) {
    public static DocumentEnhancementResult empty() {
        return new DocumentEnhancementResult(null, List.of(), List.of(), Map.of(), null);
    }
}
```

---

## 4. Context 继承体系

### 4.1 设计决策
- `segments` 和 `enhancedSegments` 放在基类供切片引擎和 Enricher 消费
- `keywords / questions / enhancerMetadata` 只放在 KnowledgeIngestionContext
- Session 管线走 No-op Enhancer，`enhancedSegments` 始终为 null

### 4.2 BaseIngestionContext

```java
@Data
@SuperBuilder
public abstract class BaseIngestionContext {
    private String fileExtension;
    private byte[] rawBytes;

    // -- 过渡兼容面（Phase 1-2 保留，Phase 3+ 移除） --
    // 与 Enhancement 计划 §2.1 兼容：enhancedText 必须留在基类供切片引擎消费
    private String rawText;
    private String enhancedText;

    // -- 新管线货币（Phase 2+ 渐进接入） --
    private List<ParseSegment> segments;            // Parser 产出，不可变
    private List<ParseSegment> enhancedSegments;    // Enhancer 产出（可能为 null）

    private ParseResult parseResult;                // 完整 parse 结果（含 quality/warnings）
    private List<KnowledgeChunkDraft> chunkDrafts;

    // -- 便捷方法 --

    /**
     * Chunker 消费入口（Phase 2+）：优先增强后的 segments，fallback 到原始 segments。
     * Phase 1 期间 segments 可能为 null，此时从 rawText/enhancedText 构造临时 segment。
     */
    public List<ParseSegment> resolveChunkSegments() {
        if (enhancedSegments != null && !enhancedSegments.isEmpty()) {
            return enhancedSegments;
        }
        if (segments != null && !segments.isEmpty()) {
            return segments;
        }
        // Phase 1 兼容 fallback：从旧字段构造临时 segment
        String fallbackText = StringUtils.hasText(enhancedText) ? enhancedText : rawText;
        if (StringUtils.hasText(fallbackText)) {
            return List.of(new ParseSegment(fallbackText, 0, SegmentType.FULL, Map.of()));
        }
        return List.of();
    }

    /**
     * ChunkEnricher 需要整篇文档文本做上下文，但禁止无限拼接。
     * 逐 segment 累积，达到 maxChars 立即停止，避免巨型 String 分配。
     */
    public String resolveDocumentPrefix(int maxChars) {
        List<ParseSegment> segs = resolveChunkSegments();
        if (segs == null || segs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(Math.min(maxChars + 256, 65536));
        for (ParseSegment seg : segs) {
            if (!StringUtils.hasText(seg.text())) continue;
            if (sb.length() > 0) sb.append("\n\n");
            int remaining = maxChars - sb.length();
            if (remaining <= 0) break;
            if (seg.text().length() <= remaining) {
                sb.append(seg.text());
            } else {
                sb.append(seg.text(), 0, remaining);
                break;
            }
        }
        return sb.toString();
    }
}
```

### 4.3 SessionIngestionContext

```java
@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class SessionIngestionContext extends BaseIngestionContext {
    private String sessionId;
    private ChatSessionFileDTO sessionFile;
}
```

### 4.4 KnowledgeIngestionContext

```java
@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class KnowledgeIngestionContext extends BaseIngestionContext {
    private String documentId;
    private String knowledgeBaseId;
    // -- Enhancement 计划的产物 --
    private List<String> keywords;
    private List<String> questions;
    private Map<String, Object> enhancerMetadata;
    private String enhancerCacheKey;
}
```

### 4.5 旧 FileIngestionContext 迁移策略
- **Phase 1**：`FileIngestionContext.java` 保留不动。新增的 `BaseIngestionContext` 继承体系与其并存。
- **Phase 2**：让 `FileIngestionContext` 继承 `BaseIngestionContext`（适配器模式），现有消费方无需改动。
- **Phase 3**：所有消费方迁移到 `SessionIngestionContext` / `KnowledgeIngestionContext` 后，删除 `FileIngestionContext`。

---

## 5. OOM 防护：入口级硬拦截（Phase 1 必须先落）

**设计决策**：OOM 防护必须在 `readBytes()` **之前**执行，而非在 Parser 内部。这保证大文件根本不进入堆内存。

与 RAG_FILE_PARSING_ARCHITECTURE_PLAN §8.4 对齐：「在 `Files.readAllBytes` 之前，如果文件 > 30MB，直接报错抛出 REJECTED 异常」。

```java
/**
 * 入口级文件大小拦截。在 readBytes() 之前调用，保证大文件不进堆。
 * 两条管线（Session / Knowledge）必须在构建 Context 之前统一调用。
 */
public class FileSizeGuard {

    private static final long MAX_FILE_BYTES = 30 * 1024 * 1024; // 30MB

    /**
     * @param fileSize 文件字节数（从存储服务获取，不需要读入内存）
     * @param filename 用于日志和错误消息
     * @throws FileRejectedException 超限时抛出，管线捕获后标记 REJECTED
     */
    public static void guardBeforeRead(long fileSize, String filename) {
        if (fileSize > MAX_FILE_BYTES) {
            throw new FileRejectedException(
                "File '%s' is %d bytes, exceeds %d byte limit"
                    .formatted(filename, fileSize, MAX_FILE_BYTES));
        }
    }
}
```

**层级关系**：
| 层级 | 防护 | 职责 |
|------|------|------|
| **入口层（FileSizeGuard）** | `readBytes()` 之前 | 硬拦截大文件，防止字节进堆 |
| **Parser 层（PdfDocumentParser）** | `parse()` 内部 | 质量评估、OCR 分流、REJECTED 标记 |

入口层是第一道防线，Parser 层是第二道。两层不可省略、不可合并。PdfDocumentParser 内部的 30MB 检查作为**冗余校验**保留（防御性编程）。

---

## 6. Parser 层

### 6.1 DocumentParser 接口

接口签名不变（已返回 `ParseResult`），内部产出从 `String text` 变为 `List<ParseSegment>`。
新增 `supportsExtension()` 方法，让 Parser 自声明支持的扩展名，Router 通过遍历注入列表路由（符合开闭原则）。

```java
public interface DocumentParser {
    String getParserType();
    ParseResult parse(byte[] content, String mimeType, Map<String, Object> options);

    /**
     * Parser 显式声明是否支持给定的文件类型探测结果。
     * 由 FileTypeDetector 综合 MimeType/Ext/MagicNumber 判定后传入。
     */
    boolean supports(DetectedFileType type);
}
```

### 6.2 MarkdownDocumentParser -- 行为不变

```java
@Override
public boolean supports(DetectedFileType type) {
    return "text/markdown".equals(type.getMimeType()) 
           || Set.of("md", "markdown").contains(type.getExtension());
}

@Override
public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
    if (content == null || content.length == 0) {
        return ParseResult.ofText("");
    }
    String text = new String(content, StandardCharsets.UTF_8);
    return ParseResult.builder()
            .text(text)          // Phase 1-2 兼容面
            .segments(List.of(new ParseSegment(text, 0, SegmentType.FULL, Map.of())))
            .parserType("Markdown")
            .qualityLevel(null)  // Markdown 不需要质量评估
            .build();
}
```

### 6.3 TikaDocumentParser -- 单 segment 包装

```java
@Override
public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
    if (content == null || content.length == 0) {
        return ParseResult.ofText("");
    }
    try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
        String text = TIKA.parseToString(is);
        String cleaned = TextCleanupUtil.cleanup(text);
        return ParseResult.builder()
                .text(cleaned)   // Phase 1-2 兼容面
                .segments(List.of(new ParseSegment(cleaned, 0, SegmentType.FULL, Map.of())))
                .parserType("Tika")
                .build();
    }
}
```

### 6.4 PdfDocumentParser -- 按页产出 PAGE segments（新增）

技术选型：**Apache PDFBox**（比 Tika 更底层，支持页级内容抽取和质量诊断指标计算）。

```java
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_FILE_BYTES = 30 * 1024 * 1024; // 30MB 硬拦截

    @Override
    public String getParserType() { return "PDFBox"; }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content.length > MAX_FILE_BYTES) {
            return ParseResult.builder()
                    .segments(List.of())
                    .parserType("PDFBox")
                    .qualityLevel(QualityLevel.REJECTED)
                    .warnings(List.of("File exceeds 30MB limit"))
                    .build();
        }

        try (PDDocument doc = Loader.loadPDF(content)) {
            return extractPages(doc, content.length);
        }
    }

    private ParseResult extractPages(PDDocument doc, int fileSizeBytes) {
        PDFTextStripper stripper = new PDFTextStripper();
        int pageCount = doc.getNumberOfPages();
        List<ParseSegment> segments = new ArrayList<>(pageCount);
        int totalChars = 0;

        for (int i = 1; i <= pageCount; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = TextCleanupUtil.cleanup(stripper.getText(doc));
            totalChars += pageText.length();
            segments.add(new ParseSegment(
                    pageText, i - 1, SegmentType.PAGE,
                    Map.of("pageNumber", i)
            ));
        }

        // -- 质量评估 --
        double charsPerPage = pageCount > 0 ? (double) totalChars / pageCount : 0;
        boolean ocrCandidate = charsPerPage < 50 && pageCount >= 2;
        QualityLevel quality = assessQuality(totalChars, charsPerPage, fileSizeBytes);
        String extractionMode = (quality == QualityLevel.LOW && ocrCandidate)
                ? "OCR_REQUIRED" : "NATIVE_TEXT";

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("totalChars", totalChars);
        diagnostics.put("pageCount", pageCount);
        diagnostics.put("charsPerPage", charsPerPage);
        diagnostics.put("ocrCandidate", ocrCandidate);

        // Phase 1-2 兼容：text = 逐页拼接（用于现有消费方 fallback）
        String fullText = segments.stream()
                .map(ParseSegment::text)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));

        return ParseResult.builder()
                .text(fullText)             // Phase 1-2 兼容面
                .segments(segments)
                .parserType("PDFBox")
                .extractionMode(extractionMode)
                .qualityLevel(quality)
                .diagnostics(diagnostics)
                .metadata(Map.of("pageCount", pageCount))
                .build();
    }

    private QualityLevel assessQuality(int totalChars, double charsPerPage, int fileSizeBytes) {
        if (totalChars == 0) return QualityLevel.LOW;
        if (fileSizeBytes >= 1_000_000 && totalChars < 200) return QualityLevel.LOW;
        if (charsPerPage < 30) return QualityLevel.LOW;
        if (charsPerPage >= 80) return QualityLevel.HIGH;
        return QualityLevel.MEDIUM;
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return "application/pdf".equals(type.getMimeType()) 
               || "pdf".equalsIgnoreCase(type.getExtension());
    }
}
```
**OOM 防护（双层）**：
1. **入口层**：`FileSizeGuard.guardBeforeRead()` 在 `readBytes()` 之前硬拦截超 30MB 文件（§5）。
2. **Parser 层**：`PdfDocumentParser` 内部的 30MB 检查作为冗余校验保留。PDFBox 逐页调用 `stripper.getText()`，不产出全量拼接 String。每页文本通常几 KB，内存可控。

**Phase 1-2 残留风险声明**：
Phase 1-2 期间，PDF 仍会把 pages 逐页拼接回 `text` / `rawText`（兼容现有消费方），因此 OOM 风险是**显著降低**而非**完全消除**。这个阶段的内存安全**完全依赖 30MB 入口硬拦截**——不能把「segments 已上」误解为「内存问题已彻底解决」。只有 Phase 3 移除 `text` 兼容字段后，全文拼接路径才被彻底切断。

### 6.5 OCR 异步状态机集成

当 `PdfDocumentParser` 返回 `extractionMode == "OCR_REQUIRED"` 时：
1. `KnowledgeDocumentIngestionServiceImpl` 捕获该标记
2. 将 `parseStatus` 置为 `PARSING_OCR_PENDING`
3. 推入 RabbitMQ 交由独立 OCR Worker 节点消费
4. OCR Worker 完成后触发重新入库（从 enhance 阶段拉起）
5. 主 ingestion 方法**安全 return**，不抛异常

### 6.6 动态路由与类型探测 (符合 RAG_FILE_PARSING_ARCHITECTURE_PLAN §5.3)

**设计决策**：废弃单纯依靠扩展名的路由。引入统一的 `FileTypeDetector`，结合 **MimeType + 扩展名 + Magic Number (前部字节嗅探)** 进行精准识别。

```java
@Component
public class DocumentParserSelector {

    private final List<DocumentParser> parsers;
    private final DocumentParser fallbackParser; // Tika
    private final FileTypeDetector fileTypeDetector; // 注入统一探测器

    public DocumentParserSelector(List<DocumentParser> parsers, FileTypeDetector detector) {
        this.parsers = parsers;
        this.fileTypeDetector = detector;
        this.fallbackParser = parsers.stream()
                .filter(p -> "Tika".equals(p.getParserType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tika parser not found"));
    }

    /**
     * 统一路由入口。
     * 1. 优先从存储层获取前部少量字节 (如 8KB Magic Prefix) + 元信息
     * 2. 调用 fileTypeDetector 综合判定真实类型 (MimeType/Ext/MagicNumber)
     * 3. 判定结果若为 REJECTED 则直接抛出异常或返回空，实现“左移”拦截
     * 4. 遍历所有 Parser 的 supports(detectorResult) 命中则返回，否则 Tika 兜底
     */
    public DocumentParser selectParser(byte[] prefix, String originalFilename, String mimeType) {
        DetectedFileType type = fileTypeDetector.detect(prefix, originalFilename, mimeType);
        
        if (type.isRejected()) {
            throw new FileRejectedException("Unsupported file type: " + type.getMimeType());
        }

        return parsers.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElse(fallbackParser);
    }
}
```

### 6.7 二进制显式拒绝 (Shift-Left)

**工程红线**：为了防止堆内存浪费，必须在 **全量读取文件内容之前** 完成类型探测。探测器应仅消费文件元数据和文件头部的 Magic Number（如前 8KB），命中非知识类二进制黑名单即刻阻断。全量 `readBytes()` 仅对通过探测的文件执行。

### 6.8 企业级多模态 PDF 解析 (VLM 架构)

**背景与痛点**：传统基于 `PDFTextStripper` 的解析会天然丢弃插图。然而，企业知识库中大量的核心业务逻辑、架构拓扑和统计数据以流程图、架构图、表格截图的形式存在。且 Session 管线要求极低的同步返回延迟，传统的耗时异步 OCR 无法满足需求。

**目标架构**：引入 **VLM（视觉大语言模型，如 gpt-4o-mini / Qwen-VL）** 替代传统纯文本 OCR，结合 **Java 17 CompletableFuture 和专用异步线程池 (IO 密集型 ThreadPoolExecutor)**，实现“毫秒级提取 + 并发秒级理解”的多模态图文混排解析。

#### 6.8.1 图文混排解析流程 (MultimodalVisionParser)

底层解析器从纯文本抽取升级为流式图文混合抽取：
1. **坐标级抽取**：遍历 PDF 页面元素，分离出原生 Text 和 Image 对象，并精准记录它们的 `(X, Y)` 绝对坐标。
2. **图像降噪**：过滤长宽 < 50px 的小图标、公司 Logo、透明水印等无效视觉噪音，节省 VLM 成本。
3. **图像摘要 VLM (并发)**：将提取出的有效图片字节流，投递给专用异步线程池并行调用 VLM。Prompt 设定为：“详细描述图片内容，若是表格请转为 Markdown，若是架构图请描述拓扑关系。”
4. **坐标归并拼接**：根据 Y 轴坐标从上到下，将原生文本与 VLM 返回的“图片描述”无缝交织拼接，最终产出图文并茂的富文本放入 `ParseSegment.text`，彻底消灭知识盲区。

#### 6.8.2 Session 管线：低延迟同步防超时机制

Session 管线要求用户立等可取，必须配备严密的防超时保障（Guardrails）：
1. **极速模型专供**：强制路由至极速 VLM（如 gpt-4o-mini），单图耗时控制在 0.8s - 1.5s，绝不使用重型长考模型。
2. **强制高并发屏障**：使用专为 IO 密集型定制的 `ThreadPoolExecutor` 将单页的所有图片解析任务并发抛出，并用 `CompletableFuture.allOf(...).join()` 聚合。由于是纯 IO 密集型，1 张图与 10 张图的整体解析耗时几乎持平。
3. **时间硬熔断 (Circuit Breaker)**：为并发等待设置严格超时（如 `get(5, TimeUnit.SECONDS)`）。若 VLM 响应超时，优雅降级，在对应的图片位置插入 `[图片解析超时]` 的占位符，绝不阻断整个 Session 问答。
4. **数量硬熔断**：入栈前校验，若单文档图片数量超限（如 > 20 张），直接拦截并报错：“图片过多，为保证问答响应速度，请至知识库后台上传”，引导用户分流。

#### 6.8.3 Knowledge 管线：高吞吐量与成本优化

知识库管线重吞吐轻延迟，核心目标是**控制 API 成本和防范大批量并发限流 (HTTP 429)**：
1. **全局图像去重 (Cache)**：企业文档常包含跨页重复的宣发图或页眉。提取图像后立即计算 `SHA-256` 并查询 Redis（`vlm_img_cache:{sha256}`）。缓存命中则直接复用，可降低 30% - 50% 的冗余 VLM 费用。
2. **QPS 兜底与退避**：大量重度图文 PDF 并发解析极易打穿 VLM 厂商的并发配额。必须接入 `Resilience4j` 的 `RateLimiter`，或配合外层 MQ `AbstractRetryingMqConsumer`，遇到 HTTP 429 自动进入指数退避（Exponential Backoff）重试。

---

## 7. Enhancer 层

### 7.1 接口变更

```java
public interface DocumentEnhancer {
    DocumentEnhancementResult enhance(BaseIngestionContext context);
}
```

不再接收 `String rawText`。Enhancer 从 `context.getSegments()` 读取 segment 列表。

### 7.2 NoopDocumentEnhancer（Session 管线用）

```java
@Component
public class NoopDocumentEnhancer implements DocumentEnhancer {
    @Override
    public DocumentEnhancementResult enhance(BaseIngestionContext context) {
        return DocumentEnhancementResult.empty();
    }
}
```

### 7.3 LlmDocumentEnhancer（Knowledge 管线用）

Segment 模型天然解决了 Enhancement 计划中 "窗口怎么定义" 的问题：**窗口 = 一组连续 segments**。

```java
@Component
@ConditionalOnProperty(...)
public class LlmDocumentEnhancer implements DocumentEnhancer {

    private static final int SHORT_DOC_CHAR_LIMIT = 12_000; // 约 3K tokens

    @Override
    public DocumentEnhancementResult enhance(BaseIngestionContext context) {
        List<ParseSegment> segments = context.getSegments();
        int totalChars = segments.stream().mapToInt(ParseSegment::charCount).sum();
        boolean isSingleFull = segments.size() == 1 && segments.get(0).type() == SegmentType.FULL;
        boolean isShort = totalChars <= SHORT_DOC_CHAR_LIMIT;

        // ── 决策矩阵 ──
        // CONTEXT_ENHANCE 仅对"单个 FULL segment 且短文档"执行。
        // 多 PAGE segment 即使总字符数短，也禁止合并为 FULL（否则丢失页结构）。
        if (isSingleFull && isShort) {
            return enhanceShortFullDocument(segments.get(0));
        }

        // 其余所有情况：跳过 CONTEXT_ENHANCE，只做 DOC_META_EXTRACT
        return extractMetaOnly(segments);
    }

    /**
     * 单 FULL segment 短文档：CONTEXT_ENHANCE + DOC_META_EXTRACT。
     * 仅适用于 Markdown / TXT / 短 DOCX 等产出单 segment 的格式。
     */
    private DocumentEnhancementResult enhanceShortFullDocument(ParseSegment segment) {
        String rawText = segment.text();

        // LLM 调用 1: CONTEXT_ENHANCE
        String enhanced = runContextEnhance(rawText);
        if (isHallucinated(rawText, enhanced)) {
            enhanced = rawText; // 幻觉校验：长度偏差超 50%/200% 则回退
        }

        // LLM 调用 2: DOC_META_EXTRACT
        DocMetaResult meta = runDocMetaExtract(enhanced);

        return new DocumentEnhancementResult(
                List.of(new ParseSegment(enhanced, 0, SegmentType.FULL, Map.of())),
                normalize(meta.keywords(), 10),
                normalize(meta.questions(), 5),
                normalizeMetadata(meta.metadata()),
                computeCacheKey(List.of(segment))
        );
    }

    /**
     * 所有非"单 FULL 短文档"场景：只做 DOC_META_EXTRACT。
     * 包括：短 PDF（多 PAGE）、长 Markdown（单 FULL 但超阈值）、长 PDF 等。
     * 对于超大单 segment，先物理切分为子窗口再 Map。
     */
    private DocumentEnhancementResult extractMetaOnly(List<ParseSegment> segments) {
        // 将 segments 展开为不超过 LLM 窗口的 text windows
        List<String> windows = splitToWindows(segments, MAP_WINDOW_MAX_CHARS);

        List<DocMetaResult> mapResults = new ArrayList<>();
        for (String windowText : windows) {
            try {
                mapResults.add(runDocMetaExtract(windowText)); // 每窗口 1 次 LLM
            } catch (Exception e) {
                log.warn("Map DOC_META_EXTRACT failed for window, skipping");
                // Fail-open：单窗口失败不阻断
            }
        }

        // Reduce：合并 + 去重 + TopN（纯 Java，无 LLM 调用）
        DocMetaResult reduced = reduceMetaResults(mapResults);

        return new DocumentEnhancementResult(
                null,  // 不修改正文，管线自动 fallback 到原始 segments
                reduced.keywords(),
                reduced.questions(),
                reduced.metadata(),
                computeCacheKey(segments)
        );
    }

    /**
     * 将 segments 展开为不超过 maxCharsPerWindow 的 text windows。
     * 关键能力：如果单个 segment 超过窗口上限，按段落边界物理切分为多个子窗口。
     * 这解决了巨型 FULL segment（如 10 万字 Markdown）直接送 LLM 导致 OOM 的问题。
     */
    private List<String> splitToWindows(List<ParseSegment> segments, int maxCharsPerWindow) {
        List<String> windows = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (ParseSegment seg : segments) {
            // 单个 segment 就超窗口 -> 按段落边界物理切分
            if (seg.charCount() > maxCharsPerWindow) {
                // 先 flush 已有 buffer
                if (buffer.length() > 0) {
                    windows.add(buffer.toString());
                    buffer.setLength(0);
                }
                // 将超大 segment 切成多个子窗口
                windows.addAll(splitTextByParagraphs(seg.text(), maxCharsPerWindow));
                continue;
            }

            // 累积后超窗口 -> flush
            if (buffer.length() + seg.charCount() > maxCharsPerWindow && buffer.length() > 0) {
                windows.add(buffer.toString());
                buffer.setLength(0);
            }

            if (buffer.length() > 0) buffer.append("\n\n");
            buffer.append(seg.text());
        }

        if (buffer.length() > 0) {
            windows.add(buffer.toString());
        }
        return windows;
    }

    /** 按段落边界切分超长文本，保证每段不超过 maxChars */
    private List<String> splitTextByParagraphs(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                // 向前寻找段落边界
                int boundary = text.lastIndexOf("\n\n", end);
                if (boundary > start) {
                    end = boundary + 2;
                } else {
                    // 找不到段落边界，找句号
                    boundary = text.lastIndexOf("。", end);
                    if (boundary <= start) boundary = text.lastIndexOf(". ", end);
                    if (boundary > start) end = boundary + 1;
                    // 都找不到则硬切
                }
            }
            result.add(text.substring(start, end).trim());
            start = end;
        }
        return result;
    }
}
```

### 7.4 DOC_META_EXTRACT 的 JSON Schema 约束

LLM 调用时必须附带以下 JSON Schema：

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

### 7.5 服务端二次归一化（硬要求）

LLM 输出不可全盘信任，服务端必须执行：
- 对 `keywords` / `questions` 每个元素执行 `trim()`、过滤空串、过滤超长项（>200 字符）、去重
- 裁剪到硬上限：keywords Top 10, questions Top 5
- 若 `doc_type` 不在合法枚举内，强制回退为 `"other"`
- 若 `contains_pii` 缺失或非 boolean，默认 `false`

### 7.6 CONTEXT_ENHANCE 幻觉校验

若 `enhancedBody` 长度与原文长度比值满足以下任一条件，视为幻觉，回退使用原文：
- 缩小超过 50%（`ratio < 0.5`）
- 膨胀超过 200%（`ratio > 2.0`）

### 7.7 复合缓存

```
CacheKey = SHA256(Content) + SHA256(PromptVersion) + ModelId + ConfigFlags
```

存储于 Redis，采用双写 + Fail-open 策略。缓存命中则跳过 LLM 调用直接返回。

---

## 8. Chunker 层

### 8.1 新接口

```java
public interface DocumentChunker {
    List<KnowledgeChunkDraft> chunk(List<ParseSegment> segments);
}
```

### 8.2 SegmentAwareChunkerRouter -- 适配器

现有 `StructureAwareMarkdownChunker` 和 `PlainTextChunker` 的内部代码完全不动。它们变成 Router 的委托目标。

```java
@Component
@RequiredArgsConstructor
public class SegmentAwareChunkerRouter implements DocumentChunker {

    private final StructureAwareMarkdownChunker markdownChunker;
    private final PlainTextChunker plainTextChunker;

    @Value("${chatagent.rag.chunk.page.target-chars:1200}")
    private int pageTargetChars;
    @Value("${chatagent.rag.chunk.page.max-chars:1800}")
    private int pageMaxChars;

    @Override
    public List<KnowledgeChunkDraft> chunk(List<ParseSegment> segments) {
        if (segments == null || segments.isEmpty()) return List.of();

        SegmentType dominantType = segments.get(0).type();

        return switch (dominantType) {
            // 单 FULL segment -> 直接委托现有 chunker，行为零变化
            case FULL -> chunkFullSegment(segments.get(0));
            // 多 PAGE segments -> 页感知切片
            case PAGE -> chunkPageSegments(segments);
            // TABLE / SECTION -> 降级到 plaintext
            case TABLE, SECTION -> plainTextChunker.chunk(
                    segments.stream().map(ParseSegment::text)
                            .collect(Collectors.joining("\n\n"))
            );
        };
    }

    private List<KnowledgeChunkDraft> chunkFullSegment(ParseSegment segment) {
        String text = segment.text();
        if (looksLikeMarkdown(text)) {
            return markdownChunker.chunk(text);
        }
        return plainTextChunker.chunk(text);
    }

    private boolean looksLikeMarkdown(String text) {
        if (text == null || text.length() < 10) return false;
        // 简单启发式：包含 markdown heading 或 code fence
        return text.contains("\n#") || text.contains("\n```") || text.startsWith("#");
    }

    /**
     * 页感知切片策略：
     * 1. 逐页累积文本，页边界是优选切分点
     * 2. 当累积量接近 targetChars 时切出一个 chunk
     * 3. 单页超长时降级到 PlainTextChunker 对该页内部切
     * 4. 将源 ParseSegment 的 pageNumber 等 metadata 合并到 chunk metadata 中
     */
    private List<KnowledgeChunkDraft> chunkPageSegments(List<ParseSegment> pages) {
        List<KnowledgeChunkDraft> allDrafts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        List<ParseSegment> bufferedSegments = new ArrayList<>(); // 追踪 buffer 中的源 segments

        for (ParseSegment page : pages) {
            // 单页超长 -> 先 flush buffer，再对该页独立切片
            if (page.charCount() > pageMaxChars) {
                flushBuffer(buffer, bufferedSegments, allDrafts);
                allDrafts.addAll(plainTextChunker.chunk(page.text()));
                buffer = new StringBuilder();
                bufferedSegments = new ArrayList<>();
                continue;
            }

            // 累积后超目标 -> flush 再开始新 buffer
            if (buffer.length() + page.charCount() > pageTargetChars && buffer.length() > 0) {
                flushBuffer(buffer, bufferedSegments, allDrafts);
                buffer = new StringBuilder();
                bufferedSegments = new ArrayList<>();
            }

            if (buffer.length() > 0) buffer.append("\n\n");
            buffer.append(page.text());
            bufferedSegments.add(page);
        }

        // flush 剩余
        if (buffer.length() > 0) {
            flushBuffer(buffer, bufferedSegments, allDrafts);
        }

        return allDrafts;
    }

    /**
     * flush 时将源 segments 的 metadata（特别是 pageNumber）合并到 chunk metadata 中。
     * 确保 PDFBox 辛苦提取的页码信息不会在切片过程中丢失。
     */
    private void flushBuffer(StringBuilder buffer, List<ParseSegment> sourceSegments,
                             List<KnowledgeChunkDraft> drafts) {
        String text = buffer.toString().trim();
        if (!StringUtils.hasText(text)) return;

        // 从源 segments 中提取页码范围
        int minPage = sourceSegments.stream()
                .map(s -> s.metadata().get("pageNumber"))
                .filter(v -> v instanceof Integer)
                .mapToInt(v -> (Integer) v)
                .min().orElse(-1);
        int maxPage = sourceSegments.stream()
                .map(s -> s.metadata().get("pageNumber"))
                .filter(v -> v instanceof Integer)
                .mapToInt(v -> (Integer) v)
                .max().orElse(-1);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chunkStrategy", "page_aware");
        meta.put("contentLength", text.length());
        meta.put("chunkIndex", drafts.size());
        if (minPage > 0 && maxPage > 0) {
            meta.put("pageStart", minPage);  // 1-based, 来自 PdfDocumentParser
            meta.put("pageEnd", maxPage);
            meta.put("pageRange", minPage == maxPage
                    ? String.valueOf(minPage)
                    : minPage + "-" + maxPage);
        }
        // 合并源 segments 中的其他自定义 metadata（如未来的坐标信息等）
        for (ParseSegment seg : sourceSegments) {
            seg.metadata().forEach((k, v) -> {
                if (!"pageNumber".equals(k)) {
                    meta.putIfAbsent("seg_" + k, v);
                }
            });
        }

        drafts.add(new KnowledgeChunkDraft(text, writeJson(meta), text));
        buffer.setLength(0);
    }
}
```

### 8.3 设计要点
- **MarkdownChunker / PlainTextChunker 内部代码完全不动**，被 Router 委托调用
- **PAGE segments 尊重页边界**作为优选切分点，但不强制（小页合并、大页拆分）
- **Chunk metadata 携带 pageRange**，方便下游 trace 和 debug

---

## 9. ChunkEnricher 层 -- 最小改动

### 9.1 接口变化

```java
public interface ChunkEnricher {
    List<KnowledgeChunkDraft> enrich(BaseIngestionContext context, List<KnowledgeChunkDraft> drafts);
}
```

签名从 `FileIngestionContext` 改为 `BaseIngestionContext`。

### 9.2 LlmContextualChunkEnricher 改动

唯一变化：`resolveWholeDocument()` 方法改为调用 `context.resolveDocumentPrefix(maxChars)`。

```java
// 旧:
private String resolveWholeDocument(FileIngestionContext context) {
    if (StringUtils.hasText(context.getEnhancedText())) return context.getEnhancedText();
    return context.getRawText();
}

// 新:
private String resolveWholeDocument(BaseIngestionContext context) {
    return context.resolveDocumentPrefix(properties.getMaxDocumentChars());
}
```

**关键安全保障**：`resolveDocumentPrefix` 逐 segment 累积，达到 `maxDocumentChars`（如 30000）立即停止循环。即使文档有 1000 页，也不会在内存中拼接出巨型 String。旧代码中先拼接全文再 `truncate()` 的模式被彻底消除。

内部其余逻辑（per-chunk LLM 调用、contextText 拼接、metadata 写入）不变。

---

## 10. 管线编排

### 10.1 KnowledgeDocumentIngestionServiceImpl

```java
public void ingestSync(String knowledgeBaseId, KnowledgeDocumentDTO doc) {
    // 0. 入口级硬拦截：大文件不进堆（§5）
    FileSizeGuard.guardBeforeRead(
        documentStorageService.getFileSize(doc), doc.getOriginalFilename());

    // 0.1 探测真实类型（Shift-Left 拦截）：仅读 8KB Prefix 探测真实类型
    byte[] prefix = documentStorageService.readPrefix(doc, 8192);
    DocumentParser parser = parserSelector.selectParser(prefix, doc.getOriginalFilename(), doc.getMimeType());

    // 1. 构建 Context（通过类型探测后，再读取全量字节进堆）
    KnowledgeIngestionContext context = KnowledgeIngestionContext.builder()
            .documentId(doc.getId())
            .knowledgeBaseId(knowledgeBaseId)
            .fileExtension(getFileExtension(doc.getOriginalFilename()))
            .rawBytes(readBytes(doc))
            .build();

    // 2. Parse（动态路由，PDF 返回 PAGE segments）
    ParseResult result = parser.parse(context.getRawBytes(), doc.getMimeType(), Map.of());
    context.setParseResult(result);
    context.setSegments(result.getSegments());
    context.setRawText(result.getFullText());   // Phase 1-2 兼容：同步设置 rawText
    context.setRawBytes(null);                  // 释放原始字节，防内存驻留

    // 2.5 OCR 异步分流
    if ("OCR_REQUIRED".equals(result.getExtractionMode())) {
        markOcrPending(doc);
        publishToOcrQueue(doc);
        return;                             // 安全退出，不抛异常
    }

    // 2.5 质量拒绝
    if (result.getQualityLevel() == QualityLevel.REJECTED) {
        markRejected(doc, result.getWarnings());
        return;
    }

    // 3. Enhance（返回 Transient DTO，立刻解包到 context）
    DocumentEnhancementResult enhancement = documentEnhancer.enhance(context);
    context.setEnhancedSegments(enhancement.enhancedSegments());
    context.setKeywords(enhancement.keywords());
    context.setQuestions(enhancement.questions());
    context.setEnhancerMetadata(enhancement.metadata());
    context.setEnhancerCacheKey(enhancement.cacheKey());

    // 4. Chunk（消费 segments，自动适配 FULL/PAGE）
    context.setChunkDrafts(documentChunker.chunk(context.resolveChunkSegments()));

    // 5. Enrich（chunk 级上下文注入）
    context.setChunkDrafts(chunkEnricher.enrich(context, context.getChunkDrafts()));

    // 6. Persist + Index
    List<KnowledgeChunkDTO> chunks = buildKnowledgeChunks(context);
    persistAndIndex(knowledgeBaseId, doc, chunks);

    // 7. 持久化增强结果到 side table + Redis 双写
    persistEnhancementSignals(context);

    markCompleted(doc);
}
```

### 10.2 FileIngestionServiceImpl（Session 管线）

**关键修复**：Session 管线必须与 Knowledge 管线一样处理 REJECTED 和 OCR_REQUIRED 分支。
如果 Session 允许上传 PDF，不做质量检查会导致扫描件 PDF 产出近空 chunks，浪费 LLM token。

```java
public void ingest(String sessionId, ChatSessionFileDTO sessionFile) {
    // 0. 入口级硬拦截：大文件不进堆（§5）
    FileSizeGuard.guardBeforeRead(
        fileStorageService.getFileSize(sessionFile), sessionFile.getOriginalFilename());

    // 0.1 探测真实类型（Shift-Left 拦截）：仅读 8KB Prefix 探测
    byte[] prefix = fileStorageService.readPrefix(sessionFile, 8192);
    DocumentParser parser = parserSelector.selectParser(prefix, sessionFile.getOriginalFilename(), sessionFile.getMimeType());

    // 1. 构建 Context（通过类型探测后，再读取全量字节进堆）
    SessionIngestionContext context = SessionIngestionContext.builder()
            .sessionId(sessionId)
            .sessionFile(sessionFile)
            .fileExtension(getFileExtension(sessionFile.getOriginalFilename()))
            .rawBytes(readBytes(sessionFile))
            .build();

    // 2. Parse
    ParseResult result = parser.parse(context.getRawBytes(), sessionFile.getMimeType(), Map.of());
    context.setSegments(result.getSegments());
    context.setRawText(result.getFullText());   // Phase 1-2 兼容
    context.setRawBytes(null);

    // 2.5 质量拒绝（Session 不支持 OCR 异步降级，直接拒绝）
    if ("OCR_REQUIRED".equals(result.getExtractionMode())
            || result.getQualityLevel() == QualityLevel.REJECTED) {
        markRejected(context, result.getWarnings());
        return;                             // 安全退出
    }

    // 3. Skip Enhancement（Session 管线不参与文档级增强）

    // 4. Chunk（消费原始 segments）
    context.setChunkDrafts(documentChunker.chunk(context.resolveChunkSegments()));

    // 5. Enrich
    context.setChunkDrafts(chunkEnricher.enrich(context, context.getChunkDrafts()));

    // 6. Persist + Index
    List<FileChunkDTO> chunks = buildFileChunks(context);
    persistAndIndex(context, chunks);

    markCompleted(context);
}
```

**Session vs Knowledge 的 OCR 处理差异**：
| 场景 | Knowledge 管线 | Session 管线 |
|------|----------------|--------------|
| `OCR_REQUIRED` | 标记 `PARSING_OCR_PENDING`，推入 MQ，异步处理 | 直接 `REJECTED`（Session 不支持异步等待） |
| `REJECTED` | 标记拒绝，退出 | 标记拒绝，退出 |
| `LOW` quality (非 OCR) | 继续处理，但 Enhancer 跳过 CONTEXT_ENHANCE | 继续处理 |

---

## 11. 全链路数据流图（目标态，Phase 4 完成后）

```
  [FileSizeGuard: 30MB 硬拦截]                                    ← Phase 1
                |
                v
  [FileTypeDetector: Magic Prefix 嗅探探测路由]                   ← Phase 1
                |
                v
        Markdown           Tika              PDFBox              ← Phase 1
           |                 |                  |
     [1 FULL seg]      [1 FULL seg]      [N PAGE segs]
     [text 兼容面]     [text 兼容面]     [text = join(pages)]
           |                 |                  |
           +--------+--------+---------+--------+
                    |                  |
           REJECTED/OCR guard   REJECTED/OCR guard               ← Phase 1
           (Session: 直接拒绝)  (Knowledge: OCR 异步分流)
                    |                  |
                    v                  v
          BaseIngestionContext.segments                           ← Phase 2
                    |
     +--------------+--------------+
     v                              v
Session 管线                   Knowledge 管线
(skip enhance)                        |
     |               +--------------+--------------+
     |               v (单 FULL 短文档)              v (其余)      ← Phase 3
     |        CONTEXT_ENHANCE              splitToWindows()
     |        DOC_META_EXTRACT              Map: DOC_META_EXTRACT x N
     |        -> enhancedSegments           Reduce: dedup + TopN
     |           [1 FULL seg]               -> enhancedSegments = null
     |                                        (保留原始 PAGE segments)
     |               +--------------+--------------+
     |                              v
     |                context.resolveChunkSegments()
     |                              |
     +--------------+---------------+
                    v
      SegmentAwareChunkerRouter                                  ← Phase 2
     +----------+----------+
     v          v          v
1 FULL seg  1 FULL seg  N PAGE segs
(markdown)   (plain)     (page-aware)
     |          |          |
     v          v          v
MarkdownChunker PlainText  累积页->切片
(现有逻辑不动) Chunker    (新逻辑)
     +----------+----------+
                v
      List<KnowledgeChunkDraft>
                v
 LlmContextualChunkEnricher                                     ← Phase 3
  (用 resolveDocumentPrefix()
   做上下文注入)
                v
        Persist + Index
                v
  [Side Table + Redis 双写 -> Reranker 信号注入]                  ← Phase 4
```

---

## 12. 持久化与检索接入

### 12.1 增强结果持久化 (Side Table)

```sql
CREATE TABLE knowledge_document_enhancement (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL UNIQUE,
    -- 反规范化字段，用于按知识库批量清理/检索过滤。文档迁移 KB 时业务层需同步。
    knowledge_base_id VARCHAR(64) NOT NULL,
    keywords_json JSON,
    questions_json JSON,
    doc_type VARCHAR(32),
    contains_pii BOOLEAN,
    enhancer_cache_key VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_id (knowledge_base_id)
);
```

### 12.2 Redis 缓存策略

- **Key 格式**：`doc_signal:{documentId}`
- **写入策略**：双写（DB + Redis 同步写入）。Redis 写入失败不阻断 ingestion（Fail-open）。
- **读取策略**：热路径 MGET 批量查询。Miss 时回源 DB 并填充 Redis。
- **TTL**：根据业务配置（建议 24h），结合 DB 回源兜底保证可用性。
- **SLA**：反查延迟 < 20ms。

### 12.3 Phase 4 检索接入

在 RRF 融合之后、Rerank 之前，批量加载已召回 chunk 对应文档的信号：

```
query -> dense/sparse recall -> RRF 融合
  -> 收集候选 documentId 集合
  -> Redis MGET 批量获取 keywords/questions
  -> 附加至 Reranker 上下文
  -> rerank -> topK
```

**Reranker 消费路径**：
- **BgeHttpRetrievalReranker**：将 keywords/questions 格式化后前置拼接到 document 文本：`"[Doc Keywords: A, B] \n [Questions: X, Y] \n\n {Chunk Content}"`
- **LlmRetrievalReranker**：通过结构化 System/Context Prompt 传入

---

## 13. 本方案同时解决的问题

| 问题 | 解法 | 落地阶段 |
|------|------|----------|
| 大文件 OOM（字节进堆） | `FileSizeGuard` 入口级硬拦截，`readBytes()` 之前执行（§5） | Phase 1 |
| 大文件 OOM（parse 后） | PDF parser 逐页抽取，不调用 parseToString()。30MB 冗余校验 | Phase 1 |
| Session 管线质量缺口 | Session 管线补上 REJECTED/OCR_REQUIRED guard（§10.2） | Phase 1 |
| 新增 parser 路由 | 基于 FileTypeDetector (Mime/Ext/Magic) 的动态路由注入 | Phase 1 |
| PDF 结构丢失 | PAGE segment 保留页边界，chunker 尊重页边界切分 | Phase 2 |
| PDF 页码 metadata 丢失 | `flushBuffer` 从源 segments 合并 pageStart/pageEnd 到 chunk metadata | Phase 2 |
| Chunker 重写 | 不重写。SegmentAwareChunkerRouter 适配现有 chunker | Phase 2 |
| Enricher 拼接 OOM | `resolveDocumentPrefix(maxChars)` 逐 segment 累积到上限即停 | Phase 3 |
| 长文档 Map-Reduce 窗口定义 | `splitToWindows()` 按 segment 累积 + 超大 segment 按段落物理切分 | Phase 3 |
| 超长单 FULL segment 打爆 LLM | `splitTextByParagraphs()` 将巨型 Markdown/TXT 切成多个子窗口 | Phase 3 |
| 短 PDF 增强后结构坍塌 | CONTEXT_ENHANCE 仅对单 FULL segment 执行，多 PAGE segment 一律跳过 | Phase 3 |
| CONTEXT_ENHANCE 跨窗口断裂 | 非单 FULL 短文档全部跳过 CONTEXT_ENHANCE | Phase 3 |
| 双管线阻抗 | BaseIngestionContext 继承体系，Session 走 No-op | Phase 2-3 |
| parseSegments() 新接口 | 不需要。parse() 返回的 ParseResult 本身就是 segment 列表 | Phase 1 |
| 爆炸半径过大 | 4 期分步落地，每期可独立 merge/回滚/验证（§15） | 全局 |
| 兼容面断裂 | Phase 1-2 保留 `ParseResult.text` + `rawText`/`enhancedText`，Phase 3 才移除 | Phase 1-3 |

---

## 14. 改动文件清单（按 Phase 分组）

### Phase 1 文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `FileSizeGuard.java` | 入口级文件大小硬拦截 |
| 新增 | `FileRejectedException.java` | 文件拒绝异常 |
| 新增 | `ParseSegment.java` | 管线原子单元 record |
| 新增 | `SegmentType.java` | FULL / PAGE / TABLE / SECTION 枚举 |
| 新增 | `QualityLevel.java` | HIGH / MEDIUM / LOW / REJECTED 枚举 |
| 新增 | `PdfDocumentParser.java` | PDFBox 按页解析 |
| 扩展 | `ParseResult.java` | 从 2 字段 record 改为 Builder class，同时持有 `text` + `segments` |
| 改造 | `MarkdownDocumentParser.java` | 同时返回 `text` + `segments`（现有消费方零影响） |
| 改造 | `TikaDocumentParser.java` | 同时返回 `text` + `segments`（现有消费方零影响） |
| 改造 | `DocumentParserSelector.java` | 新增 `selectParser()` (基于 FileTypeDetector) |
| 改造 | `FileIngestionServiceImpl.java` | 接入 FileSizeGuard + 动态路由 + REJECTED/OCR guard |
| 改造 | `KnowledgeDocumentIngestionServiceImpl.java` | 接入 FileSizeGuard + 动态路由 |

### Phase 2 文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `BaseIngestionContext.java` | Context 基类（含 rawText/enhancedText 过渡字段） |
| 新增 | `SessionIngestionContext.java` | Session 管线 Context |
| 新增 | `KnowledgeIngestionContext.java` | Knowledge 管线 Context |
| 新增 | `DocumentChunker.java` | 新 Chunker 接口 |
| 新增 | `SegmentAwareChunkerRouter.java` | 适配现有 chunker 的路由器 |
| 改造 | `FileIngestionContext.java` | 继承 `BaseIngestionContext`（适配器） |

### Phase 3 文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `DocumentEnhancementResult.java` | Enhancement Transient DTO |
| 新增 | `LlmDocumentEnhancer.java` | 实现 CONTEXT_ENHANCE + DOC_META_EXTRACT |
| 改造 | `DocumentEnhancer.java` | 签名改为 `enhance(BaseIngestionContext)` |
| 改造 | `NoopDocumentEnhancer.java` | 适配新签名 |
| 改造 | `ChunkEnricher.java` | 签名改为 `BaseIngestionContext` |
| 改造 | `LlmContextualChunkEnricher.java` | `resolveWholeDocument()` 改为 `context.resolveDocumentPrefix()` |
| 改造 | `NoopChunkEnricher.java` | 适配新签名 |
| 改造 | `FileIngestionServiceImpl.java` | 切换到 `SessionIngestionContext` |
| 改造 | `KnowledgeDocumentIngestionServiceImpl.java` | 完整消费 segments + enhancement |
| 删除 | `FileIngestionContext.java` | 被继承体系完全替代 |
| 清理 | `ParseResult.java` | 移除 `text` 兼容字段 |
| 清理 | `BaseIngestionContext.java` | 移除 `rawText`/`enhancedText` 过渡字段 |

### Phase 4 文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `knowledge_document_enhancement` DDL | Side table |
| 新增 | `KnowledgeDocumentEnhancementMapper.java` | DAO |
| 改造 | `KnowledgeDocumentIngestionServiceImpl.java` | 末尾持久化增强结果 |
| 新增 | Redis 双写逻辑 | Fail-open 策略 |
| 改造 | Reranker 数据准备 | 消费文档信号 |

### 不动文件（全 Phase）

| 文件 | 说明 |
|------|------|
| `StructureAwareMarkdownChunker.java` | 被 Router 委托调用，内部逻辑不变 |
| `PlainTextChunker.java` | 被 Router 委托调用，内部逻辑不变 |
| `KnowledgeChunkDraft.java` | 不变 |

---

## 15. 分期实施计划

**核心原则**：每期可独立 merge、独立回滚、独立验证。不做一次性总变更。

---

### Phase 1：Parse 侧（保留 `ParseResult.text` 兼容面）

**目标**：完成 Parser 层重构，不动 Enhancer / Chunker / Enricher 的接口和内部逻辑。现有消费方继续通过 `ParseResult.text` / `rawText` 工作。

| 步骤 | 内容 | 说明 |
|------|------|------|
| 1.1 | 新增 `FileSizeGuard` | 入口级硬拦截，`readBytes()` 之前执行（§5） |
| 1.2 | 新增 `ParseSegment`, `SegmentType`, `QualityLevel` | 数据模型层（§3.1-3.3） |
| 1.3 | 扩展 `ParseResult`（Builder 模式） | 同时持有 `text` + `segments`，`ofText()` 同时设置两者（§3.4） |
| 1.4 | 新增 `PdfDocumentParser` | PDFBox 按页解析，质量分级，OCR 状态机标记（§6.4） |
| 1.5 | 改造 `DocumentParserSelector` | 新增 `selectParser()`，基于 FileTypeDetector 动态路由（§6.6） |
| 1.6 | 两条管线接入 `FileSizeGuard` | 在 `readBytes()` 之前调用 |
| 1.7 | 两条管线接入动态路由 | 废弃旧路由逻辑，改用 `selectParser(prefix, name, mime)` |
| 1.8 | Knowledge 管线接入 OCR/REJECTED 分支 | 已有（§10.1） |
| 1.9 | **Session 管线补上 REJECTED/OCR guard** | OCR_REQUIRED 直接 REJECTED（§10.2） |
| 1.10 | `MarkdownDocumentParser` / `TikaDocumentParser` 适配 | 同时产出 `text` + `segments`，现有消费方零影响 |

**Phase 1 完成后的状态**：
- Parser 层已完成重构，PDF 有独立 parser
- 管线入口有文件大小硬拦截
- 两条管线都有质量检查 guard
- 现有 Enhancer / Chunker / Enricher 继续消费 `rawText` / `enhancedText`，零改动
- `ParseResult.segments` 已产出但尚未被下游消费

**兼容面**：`ParseResult.text`、`FileIngestionContext`、`rawText`/`enhancedText` 全部保留。

---

### Phase 2：Segment 兼容层

**目标**：让 Chunker 能可选消费 `segments`，新增 Context 继承体系但不删 `FileIngestionContext`。

| 步骤 | 内容 | 说明 |
|------|------|------|
| 2.1 | 新增 `BaseIngestionContext` 继承体系 | 含 `rawText`/`enhancedText` 过渡字段（§4.2-4.4） |
| 2.2 | `FileIngestionContext` 继承 `BaseIngestionContext` | 适配器模式，现有消费方无需改动（§4.5） |
| 2.3 | 新增 `DocumentChunker` 接口 + `SegmentAwareChunkerRouter` | 适配现有 chunker（§8.1-8.2） |
| 2.4 | 管线编排中 Chunker 切换到消费 `resolveChunkSegments()` | 有 segments 用 segments，否则 fallback 到 rawText |

**Phase 2 完成后的状态**：
- Chunker 已切换到 segment 消费，PDF 享受页感知切片
- Markdown / TXT 通过 `resolveChunkSegments()` fallback，行为零变化
- `FileIngestionContext` 仍存在（继承自 `BaseIngestionContext`）
- Enhancer / Enricher 仍消费 `rawText` / `enhancedText`

---

### Phase 3：Enhancer / Enricher 迁移

**目标**：Enhancer 和 Enricher 向 `BaseIngestionContext` + segments 迁移。完成后删除 `FileIngestionContext` 和 `rawText`/`enhancedText` 过渡字段。

| 步骤 | 内容 | 说明 |
|------|------|------|
| 3.1 | `DocumentEnhancer` 接口签名改为 `enhance(BaseIngestionContext)` | §7.1 |
| 3.2 | `LlmDocumentEnhancer` 实现（消费 segments） | §7.3 |
| 3.3 | `NoopDocumentEnhancer` 适配 | §7.2 |
| 3.4 | `ChunkEnricher` 签名改为 `BaseIngestionContext` | §9.1 |
| 3.5 | `LlmContextualChunkEnricher` 改用 `resolveDocumentPrefix()` | §9.2 |
| 3.6 | 新增 `DocumentEnhancementResult` Transient DTO | §3.5 |
| 3.7 | 管线编排完整切换到 Context 继承体系 | §10.1, §10.2 |
| 3.8 | 删除 `FileIngestionContext`、`rawText`/`enhancedText` 过渡字段 | §4.5 |
| 3.9 | 删除 `ParseResult.text` 兼容字段 | `getFullText()` 改为从 segments 派生 |

**Phase 3 完成后的状态**：
- 管线货币完全切换到 `List<ParseSegment>`
- 所有旧兼容面已移除
- 代码与本文档描述的目标架构完全一致

---

### Phase 4：检索热路径

**目标**：落地 side table、Redis 双写、Reranker 信号注入。

| 步骤 | 内容 | 说明 |
|------|------|------|
| 4.1 | DDL：`knowledge_document_enhancement` 表 + Mapper | §12.1 |
| 4.2 | Ingestion 管线末尾持久化增强结果 | side table 写入 |
| 4.3 | Redis 双写 + Fail-open | §12.2 |
| 4.4 | 检索热路径接入：MGET 批量获取文档信号 | §12.3 |
| 4.5 | Reranker 消费文档信号（keywords/questions 前置拼接） | §12.3 |

**Phase 4 完成后的状态**：
- 文档级信号（keywords/questions）已参与 Rerank
- 热路径延迟 < 20ms（Redis MGET）
- 全链路目标架构落地完毕

---

### Phase 间依赖关系

```
Phase 1 (Parse) ──→ Phase 2 (Segment 兼容层) ──→ Phase 3 (Enhancer/Enricher) ──→ Phase 4 (检索热路径)
   │                                                      │
   │  可独立 merge                              可独立 merge  │
   │  回滚不影响下游                            回滚不影响 Phase 4
   └──────────────────────────────────────────────────────────┘
                    每期有明确的兼容面和退出点
```

---

## 16. 工程红线

### 全局红线（所有 Phase 均适用）

1. 决不允许 Session 会话管线触发文档级 Enhance。
2. 决不允许在多 PAGE segment 输入上执行 CONTEXT_ENHANCE（即使总字符数短）。CONTEXT_ENHANCE 仅限单 FULL segment。
3. 决不允许在 Rerank 热路径上使用 MySQL Join 查询文档级信号。
4. 决不允许将控制平面（warnings, contains_pii 等）混入送给 LLM 的正文 Chunks 中。
5. 决不允许无视 LLM 输出格式，必须在服务端对生成的列表与类型进行强校验和上限裁剪。
6. 决不允许在 `readBytes()` 之前跳过 `FileSizeGuard` 入口级硬拦截。OOM 防护必须在字节进堆之前。
7. 决不允许无限制地 join 全部 segments 为一个 String。任何需要全文的场景必须使用 `resolveDocumentPrefix(maxChars)` 限制累积长度。
8. 决不允许假设单个 segment 是小的。`splitToWindows()` 必须能物理切分超大 segment。
9. 决不允许在 Chunk 切片时丢弃源 ParseSegment 的 metadata（如页码）。Chunk metadata 必须继承源 segment 信息。
10. 现有 `StructureAwareMarkdownChunker` 和 `PlainTextChunker` 的内部逻辑不做修改，只通过 Router 委托调用。
11. 决不允许 Session 管线跳过质量检查。OCR_REQUIRED 和 REJECTED 必须在两条管线中均有处理。

### 分期兼容红线

12. **Phase 1-2 期间**：决不允许删除 `ParseResult.text` 字段。现有消费方依赖此字段，必须保留到 Phase 3 正式迁移。
13. **Phase 1-2 期间**：决不允许删除 `FileIngestionContext`。Phase 2 通过继承适配，Phase 3 才删除。
14. **Phase 1-2 期间**：决不允许删除 `rawText` / `enhancedText` 过渡字段。Enhancer/Enricher 在 Phase 3 之前仍消费这些字段。
15. **Phase 3 完成后**：决不允许在管线中持有 `String rawText` 作为核心流转对象。一切基于 `List<ParseSegment>`。（此条在 Phase 3 之前为目标，非强制）

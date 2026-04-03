# RAG 文件解析架构落地方案 (V1.2 - 最终工程闭环版)

## 1. 文档目标
本文档用于明确 ChatAgent 当前知识库 / 会话文件 RAG ingestion 的文件解析策略，并回答三个核心问题：

1. 当前项目大量格式默认走 Apache Tika，是否合理
2. 相比 Claude Code 的分层文件处理策略，ChatAgent 当前方案的优劣势在哪里
3. 面向企业级 RAG，文件解析层的推荐架构应该如何演进

本文档的目标不是替换现有 RAG 增强方案，而是为 `parse -> enhance -> chunk -> enrich -> index` 主链中的 **parse 层** 提供一份独立的架构基线。

---

## 2. 当前项目现状
（参见 V1.1 现状分析，主要指出了当前双路 Parser 和轻度 Tika 封装的局限性）

---

## 3. 架构对比总结：Tika 与 Claude Code 策略
我们评估了 Claude Code 的分层策略，主要借鉴其格式分层和显式拒绝机制的思路，但不采用其多模态交互路径（因为不适合后端批量 Ingestion）。
Tika 适合作为 RAG ingestion 的默认 parser backbone；而 Claude Code 风格更适合作为复杂格式分层识别、拒绝策略和降级设计的指导思想。

---

## 4. 最终架构决策 (MUST)
以下 8 条为企业级 RAG 解析的**强制架构决策**：

1. **MUST**: 保留 Tika 作为通用 Parser 兜底底座（支持 `.doc`, `.docx` 等基础文本提取）。
2. **MUST**: 保留 Markdown 专门 parser。
3. **MUST**: 将 PDF 提升为一等公民，从通用 Tika 路径中拆出独立的 `PdfDocumentParser`。
4. **MUST**: 实现基于 MimeType 和扩展名的动态路由注入机制，废弃管线中硬编码的 Parser Type 路由。
5. **MUST**: 实现解析结果结构化，废弃现有 2 字段的 record，采用基于 Builder 模式（允许部分缺省）的结构化 `ParseResult`。
6. **MUST**: 引入解析指标体系（Diagnostics），基于量化指标对复杂文档（如 PDF）进行 Quality Level 判定。
7. **MUST**: 针对扫描件 PDF，引入 **异步化 OCR 状态机**，严禁在同步主 Ingestion 链路中阻塞执行 OCR。
8. **MUST**: 针对大文件实现硬拦截阈值，规划 `parseSegments()` 流式解析接口。

---

## 5. 目标分层策略

### 5.1 PDF 专门层 (PdfDocumentParser)
**技术选型决策**：
- 第一阶段（Text-first）：采用 **Apache PDFBox** 作为核心引擎。原因：比 Tika 更底层，支持获取页级内容、坐标信息和精准的字符特征，便于计算质量指标。
- 第二阶段（OCR Fallback）：采用 **外部服务 API（如 PaddleOCR/云 API）** 或本地离线 OCR 模型。

**状态机与异步 OCR 集成机制**：
当 PDFBox 抽取文本判定为 `qualityLevel == LOW` 且存在图片特征（`ocr_candidate == true`）时：
1. `PdfDocumentParser` 返回异常或携带特殊标记的空 `ParseResult`（如 `extractionMode="OCR_REQUIRED"`）。
2. `KnowledgeDocumentIngestionServiceImpl` 捕获后，立即调用 `knowledgeDocumentStatusSseService` 和数据库，将 `parseStatus` 置为 `PARSING_OCR_PENDING`，随后**安全结束当前方法执行（Return）**。
3. 系统将该文档信息推入消息队列（如 RabbitMQ）。
4. 独立的 OCR Worker 节点消费消息，优先调用本地部署的 OCR 服务（如 PaddleOCR）完成提取并存入专门的文本存储。
   - **高可用兜底机制（HA Fallback）**：若本地 OCR 服务宕机、处理超时或资源耗尽，系统应捕获异常并自动降级调用外部云商 OCR API（如阿里/百度文档解析 API）完成处理，确保入库业务不中断。
5. Worker 更新 `parseStatus` 并触发一条“OCR 完成”事件，主节点监听到事件后，根据 OCR 输出的文件路径重新从 Enhance 阶段拉起整个 `chunk -> enrich -> index` 链路。

### 5.2 Notebook 专门层 (NotebookDocumentParser)
**优先级声明**：目前业务尚未明确提出 Jupyter Notebook 的入库需求。此模块作为预研。
**逻辑连续性约束（若实施）**：
- 解析出 Cell 后，Chunker 必须至少支持相邻 code cell 合并，或将 markdown cell 与其下方的 code cell 及 outputs 绑定为一个完整片段，不得“单 cell 单 chunk”。

### 5.3 二进制显式拒绝策略
**路由拦截层决策**：
在 `FileIngestionServiceImpl` 和 `KnowledgeDocumentIngestionServiceImpl` 进行解析之前，必须通过统一的 `FileTypeDetector`（结合扩展名和前部 Magic Number 嗅探）判断文件类型。
- 若命中黑名单（如 `.exe`, `.dll`, 图片/视频等非知识类二进制），直接更新状态为 `REJECTED` 并抛出异常退出，绝不进入后续 Parser。

---

## 6. ParseResult 结构化升级与兼容性

**决策**：废弃全参 Record 模式，采用 Builder 或 Class 实现，允许 Nullable，并分离质量与告警数据。

```java
@Data
@Builder
public class ParseResult {
    private String text;                  // 解析后的文本
    private String parserType;            // 使用的 Parser 名称 (e.g. "PDFBox")
    private String extractionMode;        // NATIVE_TEXT / OCR_FALLBACK
    
    // 质量分级（枚举：HIGH, MEDIUM, LOW, REJECTED）。默认为未评估(null/UNKNOWN)。
    private QualityLevel qualityLevel;    
    
    // 诊断指标（原始计算数值，如 printable_char_ratio）
    @Builder.Default
    private Map<String, Object> diagnostics = new HashMap<>();
    
    // 解析器告警信息（如 "未解析的图片", "密码保护"）
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    
    // 通用元数据（页数、标题等）
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    public static ParseResult ofText(String text) {
        return ParseResult.builder().text(text).build();
    }
}
```

### 6.1 警告与控制面边界 (红线)
- `warnings` 和 `diagnostics` **仅属于控制平面**。
- `DocumentEnhancer` 在拼接 LLM Prompt 时，**严禁读取 `warnings` 并混入正文**。这些字段只能用来写日志、推 SSE 事件或决定是否触发异步 OCR。

---

## 7. 解析质量分级 (Quality Assessment)

**判定责任决策**：
质量评分**不能强求所有 Parser 共用一套逻辑**。
- 对于 `MarkdownDocumentParser`，直接返回文本即可，`qualityLevel` 留空。
- 质量评级主要由 `PdfDocumentParser` 内部计算，或交由独立的 `ParseQualityAssessor` 执行。

**PDF 诊断核心指标与阈值计算 (示例)**：
- `text_length_chars` / `pages_detected` -> 若平均每页字符 < 50，极大概率是纯扫描件。
- `printable_char_ratio` -> 剔除空格后的有效字符比。低于 85% 可能是乱码。
若命中扫描件或乱码阈值，置 `qualityLevel = LOW` 并根据图片特征设置 `ocr_candidate = true`。

**消费者定义**：
- **OCR 状态机**：消费 `LOW` 触发异步降级。
- **DocumentEnhancer**：若 `qualityLevel == LOW`（即使不用 OCR 硬上），可以考虑跳过 `CONTEXT_ENHANCE` 防止因乱码导致 LLM 产生幻觉，仅回退使用 `rawText`。

---

## 8. 与 Document Enhancement 计划的接口同步

为避免本计划与 Enhancement 计划发生接口脱节，规定如下实施顺序与契约同步：

1. **先发执行 ParseResult 改造**：先扩展 `ParseResult` 结构（使用 Builder 保证老代码调用点不报错）。
2. **重构路由接口**：废弃业务管线中硬编码的 `ParserType` 判断，改为 `DocumentParserSelector.selectByMimeTypeOrExtension(...)` 动态加载，为新增 PDF Parser 扫清障碍。
3. **Context 映射明确化**：`FileIngestionContext` （在 Enhancement 计划中已被分为基类）负责完整承载 ParseResult 传递的 `warnings` 和 `qualityLevel`，但不允许混入送给大模型的正文段落。
4. **大文件防爆硬拦截**：在 `Files.readAllBytes` 之前（如 `documentStorageService.getFileSize()`），如果文件 > 30MB，直接报错抛出 `REJECTED` 异常，在 `parseSegments()` 流式特性开发完毕前硬性保护内存。
5. **最终对齐 Enhancer**：当上述底层基建稳固后，`DocumentEnhancer` 即可安全地消费更干净的 `rawText` 并利用 `qualityLevel` 进行容错。
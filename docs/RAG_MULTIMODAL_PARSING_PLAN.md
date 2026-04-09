# RAG 多模态解析与视觉文档解析（VDP）升级方案与落地现状（Phase 5）

## 1. 文档目的

本文档不再只描述 Phase 5 的目标态设计，也同步记录 **Phase 5a / 5b / 5c 当前代码落地现状**，用于对齐方案、实现与后续迭代边界。

当前代码已完成：

- Phase 5a：Knowledge 图片前置拦截、Image Parser、`VdpEngine` 抽象、`FIGURE` 段、Segment-Aware Chunker
- Phase 5b：PDF 逐页启发式路由、Visual-Track、超时与防反压、页级降级、视觉结果缝合
- Phase 5c：PDF 页缓存与图像去重缓存、字体感知结构恢复、Batch 模式基础设施、真实 MinerU 本地接入、配置与测试补齐

当前代码尚未完成：

- `Marker` 等第二批本地 Batch Engine 尚未接入
- `VlmVdpEngine` 仍受 Spring AI `Media` / VLM API 载荷约束，单图链路内部仍会 materialize 为 `byte[]`
- MinerU 参数调优、更多 golden 样本与端到端主链验收仍未收口

---

## 2. 当前总体架构

### 2.1 双管线准入

- `SESSION`：允许图片、PDF、Markdown、TXT、Word 文档
- `KNOWLEDGE`：拒绝 standalone image，只接受文档型输入

### 2.2 主要代码落点

- 文件类型探测：`chatagent/bootstrap/.../rag/parser/FileTypeDetector.java`
- Parser 选择：`chatagent/bootstrap/.../rag/parser/DocumentParserSelector.java`
- 单图解析：`chatagent/bootstrap/.../rag/parser/ImageDocumentParser.java`
- PDF 解析：`chatagent/bootstrap/.../rag/parser/PdfDocumentParser.java`
- VLM 视觉引擎：`chatagent/bootstrap/.../rag/parser/VlmVdpEngine.java`
- PDF 页缓存：`chatagent/bootstrap/.../rag/parser/VdpPageCacheService.java`
- 图像结果去重缓存：`chatagent/bootstrap/.../rag/parser/VdpResultCacheService.java`
- Session 分桶缓存：`chatagent/bootstrap/.../rag/parser/SessionScopedVdpCacheStore.java`
- 线程池配置：`chatagent/bootstrap/.../rag/parser/VdpExecutorConfig.java`
- Knowledge 准入第一道防线：`chatagent/bootstrap/.../knowledge/application/KnowledgeDocumentFacadeServiceImpl.java`
- Segment Chunker：`chatagent/bootstrap/.../rag/ingestion/SegmentAwareChunkerRouter.java`

### 2.3 当前运行模式

- 会话图片：直接走 `ImageDocumentParser -> VlmVdpEngine.parsePage`
- 会话 PDF：`PdfDocumentParser` 逐页路由，Fast-Track 保留原生文本，Visual-Track 渲染单页 PNG 后走 `parsePage`
- 知识库 PDF：同样走 `PdfDocumentParser`，但超时预算更宽，且支持 `PDF_PAGE_BATCH` 基础设施
- 知识库存储文档：在真正进入存储与异步 ingestion 前，先做 Knowledge 图片拦截

---

## 3. MUST 决策与当前实现映射

### 3.1 MUST 1：拥抱 VLM 与统一抽象

已落地：

- `VdpEngine` 作为统一视觉解析抽象
- `VdpOptions`、`VdpPageResult`、`VdpMode`、`VdpPageStatus` 已建立稳定契约
- `VlmVdpEngine` 为当前生产可用实现
- `NoopVdpEngine` 作为禁用场景的 fail-open fallback

当前状态：

- `parsePage(...)` 已在会话图片与 PDF Visual-Track 中投入使用
- `parsePages(...)` 已用于本地 `MinerU` knowledge batch 路径，真实本地 smoke 已通过

### 3.2 MUST 2：Markdown-First

已落地：

- `ImageDocumentParser` 输出 `FIGURE` 段，`text` 为 Markdown
- `PdfDocumentParser` Visual-Track 输出 Markdown 表格/结构文本
- Fast-Track 在 Phase 5c 已从“纯文本”升级为“带轻量结构恢复的文本”，可根据字体大小恢复标题 Markdown

当前状态：

- PDF Fast-Track 不做复杂版面重建，只做轻量 heading 恢复
- 标题恢复基于页级字体统计 + 行级字体采样，不引入跨页文档结构重组

### 3.3 MUST 3：内容与元数据边界清晰

已落地：

- `ParseSegment.text` 仅放可检索内容
- 解释性提示统一进入 `metadata.interpretiveNote`
- 降级时不再向索引注入 `[图像解析失败]` 之类占位文字
- 非 JSON 响应只有在能恢复出可检索 Markdown 时才进入 `markdown`

### 3.4 MUST 4：双重准入与前置拦截

已落地：

- 第一层：`KnowledgeDocumentFacadeServiceImpl.validateKnowledgeUpload(...)`
- 第二层：`FileTypeDetector.detect(..., PipelineSource)` + `DocumentParserSelector.selectParser(..., PipelineSource)`

与原始方案的差异：

- 第一层拦截实际落在 `FacadeService`，而不是 `Controller`
- 但它仍发生在存储前、MQ 前，因此功能效果满足“前置拒绝”的目标

### 3.5 MUST 5：外部 API 合规

已落地：

- `VlmVdpEngine` 仅接收单图输入
- `PdfDocumentParser` 对外部视觉解析只发送渲染后的单页 PNG

当前状态：

- 没有任何路径会把整份 PDF 发送给商业 VLM

### 3.6 MUST 6：受控内存足迹与 Stream-Only 契约

已落地：

- Ingestion Service 层改为通过 `Supplier<InputStream>` 从存储层反复开新流
- PDF stream 路径不再 `readAllBytes()`，而是使用 `RandomAccessReadBuffer(InputStream)`
- `PdfDocumentParser` 单页渲染后立即清零 `RenderedPageImage`
- `VlmVdpEngine` 中图片字节所有权移入 worker 线程，worker 完成后主动擦除

仍保留的过渡项：

- `VlmVdpEngine` 读取图片时仍需 `stream.readAllBytes()`，因为当前 Spring AI `Media` 仍依赖字节载荷
- `DocumentParser` default 的 stream bridge 仍会退回 byte[] 版本，尚未完全废除

### 3.7 MUST 7：按 5a / 5b / 5c 分步演进

已落地：

- 5a、5b、5c 的骨架与主要能力均已分阶段落入代码
- 5c 的“本地 Batch 引擎接入”目前完成的是接口、调度、缓存、配置和测试底座，未接具体模型

### 3.8 MUST 8：绝对防反压

已落地：

- `vdpExecutor`、`vdpPageDispatchExecutor`、`vdpBatchExecutor` 全部使用 `AbortPolicy`
- 会话单图 VLM 调用有硬超时
- PDF Visual-Track 有 per-page budget 与 per-document budget
- Batch 与 Page Dispatch 已拆分到不同线程池，避免重型任务挤占会话短任务

### 3.9 MUST 9：全局去重

已落地：

- 图像解析结果去重：`VdpResultCacheService`
- PDF 页缓存：`VdpPageCacheService`
- Session 缓存按 `sessionId` 分桶
- Knowledge 缓存使用 Redis
- Cache Key 包含 `engineId` 与 `promptVersion`
- Knowledge 文档在缺少 `contentHash` 时，会对存储文件流实时计算 `SHA-256`

---

## 4. 按页质量分流：当前实际算法

当前 `PdfDocumentParser.decideRoute(...)` 的路由规则不是最初设计稿中的单一阈值版本，而是以下更保守的实现：

1. 原生文本为空：`VISUAL_TRACK`
2. 对齐空白行数达到阈值：`VISUAL_TRACK`
3. 短文本页：
   - 若看起来像结构化短文本（如大量短 token + 数字）：`VISUAL_TRACK`
   - 否则：`FAST_TRACK`
4. 低字符密度页：
   - 若不像叙述性片段：`VISUAL_TRACK`
   - 若像短叙述：`FAST_TRACK`
5. 其余：`FAST_TRACK`

这套实现的目标是减少“章节尾页、短说明页”被误杀进 VLM，同时保留对表格页、扫描页、低密度结构页的视觉解析能力。

---

## 5. 当前 VDP 契约

```java
public interface VdpEngine {
    default List<VdpPageResult> parsePages(
            Supplier<InputStream> pdfStream,
            List<Integer> pageIndices,
            VdpOptions options
    ) {
        throw new UnsupportedOperationException("parsePages is not supported by this engine");
    }

    default VdpPageResult parsePage(
            Supplier<InputStream> imageStream,
            String imageFormat,
            VdpOptions options
    ) {
        throw new UnsupportedOperationException("parsePage is not supported by this engine");
    }

    default String engineId() {
        return "default";
    }

    default String promptVersion() {
        return "default";
    }

    EnumSet<VdpMode> supportedModes();
}
```

当前关键数据结构：

- `PipelineSource`：`SESSION | KNOWLEDGE`
- `VdpMode`：`PAGE_IMAGE | PDF_PAGE_BATCH`
- `VdpPageStatus`：`SUCCESS | FAILED | DEGRADED`
- `VdpOptions`：
  - `recognizeFormulas`
  - `languageHint`
  - `extra`

当前 `extra` 实际只保留最小信息集：

- `pipelineSource`
- `sessionId`（若存在）

PDF 页缓存上下文并不再依赖 `VdpOptions.extra()` 透传整份 ingestion options，而是由 `PdfDocumentParser` 显式从原始 `options` 取 `documentCacheKey`、`sessionId` 等字段。

---

## 6. 当前元数据契约

所有视觉相关段遵循以下元数据语义：

| 字段 | 含义 |
| :--- | :--- |
| `contentOrigin` | `NATIVE`、`VDP_TRANSCRIBED` |
| `visualType` | `TABLE`、`FORMULA`、`CHART`、`IMAGE` |
| `degraded` | 是否走了降级/兜底路径 |
| `interpretiveNote` | 仅供 LLM 参考，不进入 embedding 文本 |
| `engineId` | 产出该视觉结果的引擎标识 |
| `promptVersion` | 当前引擎使用的 prompt 版本 |
| `modelId` | 当前引擎使用的模型标识 |
| `pageNumber` | 当前段所属页码（1-based） |
| `pageRoute` | `FAST_TRACK` / `VISUAL_TRACK` |
| `pageRouteReason` | 路由原因 |
| `vdpStatus` | `SUCCESS` / `FAILED` / `DEGRADED` |
| `visualFallback` | `NATIVE_TEXT` / `EMPTY_PAGE` |
| `nativeCharCount` | 原生文本清理后的字符数 |
| `alignedWhitespaceLines` | 对齐空白行数 |
| `dominantFontSizePt` | 页内主导字号 |
| `maxFontSizePt` | 页内最大字号 |
| `minFontSizePt` | 页内最小字号 |
| `fontSampleCount` | 字体采样数 |
| `headingLikePage` | 是否像标题页 |
| `fontAwareStructureRestored` | 是否进行了字体驱动的结构恢复 |
| `restoredHeadingCount` | 恢复出的标题数 |

---

## 7. 当前降级与熔断策略

### 7.1 Session

- 会话图片与会话 PDF Visual-Track 使用短超时预算
- 线程池满载直接 `AbortPolicy`
- 超时、拒绝、执行异常统一降级为 `DEGRADED/FAILED`
- 若 PDF 页存在可用原生文本，优先回退原生文本，不污染索引

### 7.2 Knowledge

- 预算比 Session 更宽
- 当引擎支持 `PDF_PAGE_BATCH` 时，走 batch 调度线程池
- 若整批无可恢复结果且总字符极低，可判为 `OCR_REQUIRED`

### 7.3 非 JSON 响应

`VlmVdpEngine` 当前策略：

- JSON 正常：直接映射 `markdown + interpretiveNote + visualType`
- 非 JSON 但能恢复 Markdown 表格/结构文本：作为 `DEGRADED` 结果继续使用
- 纯助手废话：只写入 `interpretiveNote`，`markdown` 为空

---

## 8. Chunker 当前行为

`SegmentAwareChunkerRouter` 已按 `ParseSegment` 逐段处理：

- `FIGURE` 段独立切块
- `visualType = TABLE` 的 PAGE 段独立切块
- 普通 PAGE 段进入页缓冲区
- 单页自身超长时，会直接对该页执行 markdown/plain chunker 细切，不进入页缓冲区
- 当缓冲区累计后超长且包含 Markdown 结构时，才在 `flush` 阶段触发 markdown chunker 细切

这保证了：

- 单图、单表不会被混入普通页缓冲
- 超长单页不会因为等待缓冲区而丢失结构
- 视觉 Markdown 能保留结构
- 普通 PDF 正文仍维持页感知切块

---

## 9. Phase 5 分阶段落地情况

### 9.1 Phase 5a：基础设施与单图解析

已完成：

1. Knowledge standalone image 前置拒绝
2. `PipelineSource`、`DetectedFileType`、`FileTypeDetector`
3. `VdpEngine`、`VdpOptions`、`VdpPageResult`
4. `ImageDocumentParser`
5. `FIGURE` 段类型
6. `DocumentParserSelector` 按优先级选择 parser
7. `SegmentAwareChunkerRouter`

### 9.2 Phase 5b：PDF 逐页路由与单页视觉解析

已完成：

1. `PdfDocumentParser` 逐页预扫描
2. Fast-Track / Visual-Track 启发式路由
3. 单页 PNG 渲染
4. 会话/知识库差异化超时预算
5. Page Dispatcher 与 Batch Dispatcher 线程池隔离
6. 页级 `FAILED / DEGRADED / SUCCESS`
7. 原生文本回退与 `OCR_REQUIRED`
8. 视觉结果按页缝合回 `PAGE` 段

### 9.3 Phase 5c：缓存、结构恢复与 Batch 底座

已完成：

1. `parsePages(...)` 契约与 `PDF_PAGE_BATCH` 调度底座
2. `vdpBatchExecutor`
3. 图像结果去重缓存
4. PDF 页缓存与渲染前 cache hit
5. Session 分桶缓存与空闲 bucket 回收
6. 字体元数据采样与标题结构恢复
7. Knowledge 缺失 `contentHash` 时的内容摘要回退
8. `MinerUVdpEngine` 本地生产实现 + 本地 GPU / smoke 打通

未完成：

1. `Marker` 等第二本地 batch engine 适配
2. 多商业 API 适配器
3. MinerU 参数调优与更多 golden 验收

---

## 10. 当前配置项

主要配置位于 `chatagent/bootstrap/src/main/resources/application.yaml`：

- `chatagent.rag.vdp.cache.*`
- `chatagent.rag.vdp.char-density-threshold`
- `chatagent.rag.vdp.short-text-fast-track-threshold`
- `chatagent.rag.vdp.whitespace-alignment-line-threshold`
- `chatagent.rag.vdp.pdf-page-max-in-flight`
- `chatagent.rag.vdp.pdf-page-timeout-ms`
- `chatagent.rag.vdp.knowledge-document-timeout-ms`
- `chatagent.rag.vdp.pdf-page-dispatch-*`
- `chatagent.rag.vdp.pdf-batch-*`
- `chatagent.rag.vdp.pdf-render-dpi`
- `chatagent.rag.vdp.routing.*`
- `chatagent.rag.vdp.mineru.*`
- `chatagent.rag.vdp.vlm.*`

默认关键值：

- `pdf-render-dpi = 120`
- `pdf-page-max-in-flight = 2`
- `pdf-page-timeout-ms = 5000`
- `knowledge-document-timeout-ms = 300000`
- `pdf-batch-max-pool-size = 2`
- `pdf-batch-queue-capacity = 30`
- `vlm.timeout-ms = 5000`

---

## 11. 已知遗留与后续建议

### 11.1 当前遗留与消解方案

#### 遗留 1: VlmVdpEngine 仍需 readAllBytes() — 记为架构约束

**现状**：`VlmVdpEngine.parsePage()` 通过 `stream.readAllBytes()` 将图片载入内存，因为 Spring AI `Media.builder().data(imageBytes)` 只接受 `byte[]`。

**根因分析**：即使绕过 Spring AI 直接调用 ZhiPuAi REST API，VLM API 协议本身要求完整图片载荷（base64 或 binary multipart）。base64 编码虽然可以流式写入 `Base64.getEncoder().wrap(outputStream)`，但 ZhiPuAi 的请求体仍需完整 base64 字符串。因此 **heap materialization 是 VLM API 协议层的固有约束**，不是 Spring AI 的限制。

**处置**：不再列为待消解遗留，记为架构约束。当前已有的缓解措施足够：
- Worker 线程内构造 Prompt 并在 finally 中 wipeBytes（字节所有权隔离）
- `RenderedPageImage` 渲染后立即 clear
- `pdf-render-dpi = 120` 控制单页图片尺寸（120 DPI A4 页约 700KB PNG）
- 仅当 VLM API 未来支持 chunked upload 或 presigned URL 引用时，此约束才可消除

#### 遗留 2: DocumentParser default stream bridge — 已完成

**当前状态（2026-04-05）**：已完成消解。

**已落地结果**：
- `DocumentParser.parse(Supplier<InputStream>, ...)` 已去掉 default body，变为强制实现的抽象方法
- `DocumentParser.parse(byte[], ...)` 已标注 `@Deprecated(forRemoval = true)`，保留为兼容入口
- 4 个生产 parser 均保持 stream-native 实现
- 两条 ingestion service 调用链均继续使用 stream 版本

**结果**：stream-only 契约现在由编译期保证，后续新增 parser 若未实现 stream 版本会直接编译失败，而不是运行时回落到 `readAllBytes()` bridge。

#### 遗留 3: MinerU 已接入，但参数联调与主链验收未完成

**当前状态（2026-04-05）**：
- `VdpEngineRouter` / `VdpEngineRoutingProperties` 已接入
- `PdfDocumentParser` / `ImageDocumentParser` 已改为先 resolve engine，再按 resolved engine 构造 cache context
- `MinerUVdpEngine` + `MinerUProperties` 已完成本地官方 MinerU API 适配
- `VdpPageCacheService.putAll(...)` + Redis pipelining 已落地，batch 路径会缓存全量页结果并仅返回请求子集
- 本地 `MinerU` 服务已部署在 `http://127.0.0.1:8000`
- `MinerUVdpEngineSmoke` 已在本地真实服务下通过

**剩余工作**：MinerU batch 参数调优、golden 切换到 `mineru` 引擎的质量验收，以及 retrieval/rerank 主链的端到端验证。详见 11.2 Step 3 与 13.3。

---

### 11.2 Phase 5d — 剩余生产化工作

> 目标：将 Phase 5c 的接口底座升级为可用的生产能力。

#### Step 1: 收集金标验收样本（前置，不依赖代码）

**当前状态（2026-04-06）：已完成。基础设施 + 首批 4 份样本 + 4 份 snapshot + golden 验收全绿。**

在动手写 adapter 之前，先准备 4 类真实 PDF，每类 2-3 份。样本用于 adapter 开发期间反复对照调参（DPI、超时、batch size），避免仅靠 mock 上线后才发现格式不符。

**存放位置**：`chatagent/bootstrap/src/test/resources/golden-pdfs/`

**目录结构**：

```
golden-pdfs/
+-- scanned/           # 扫描件
|   +-- scanned-01.pdf
+-- tables/            # 表格型
|   +-- table-01.pdf
+-- headings/          # 标题层级
|   +-- heading-01.pdf
+-- mixed/             # 混合文本+图片
|   +-- mixed-01.pdf
+-- expected/          # 期望输出快照 (*.segments.json)
|   +-- scanned-01.segments.json
|   +-- table-01.segments.json
|   +-- heading-01.segments.json
|   +-- mixed-01.segments.json
|   +-- _example.segments.json
+-- README.md          # 样本要求、命名规范、JSON schema 说明
```

**验收重点**：

| 类别 | 验收重点 |
|---|---|
| 扫描件 | OCR_REQUIRED 判定、VDP 降级路径 |
| 表格型 PDF | 表格保真度、Markdown table 输出 |
| 标题层级明显文档 | 字体感知结构恢复、`##` / `###` 注入 |
| 混合文本 + 图片文档 | 按页路由正确性、visual/native 混排、chunk 质量 |

**期望输出快照格式**（`expected/*.segments.json`）：

```json
{
  "documentId": "table-01",
  "expectedSegmentCount": 3,
  "expectedExtractionMode": "PDF_VISUAL_ROUTED",
  "segments": [
    {
      "pageIndex": 0,
      "expectedRoute": "FAST_TRACK",
      "mustContain": ["Introduction"],
      "mustNotContain": ["[图像解析失败]"],
      "expectedVisualType": null
    },
    {
      "pageIndex": 1,
      "expectedRoute": "VISUAL_TRACK",
      "mustContain": ["|", "---"],
      "mustNotContain": [],
      "expectedVisualType": "TABLE"
    }
  ]
}
```

**JSON schema 字段说明**：

| 字段 | 必填 | 说明 |
|---|---|---|
| `documentId` | 是 | 与 PDF 文件名（去扩展名）一致 |
| `expectedSegmentCount` | 否 | 若设置则断言精确 segment 数量 |
| `expectedExtractionMode` | 否 | `NATIVE_TEXT` / `PDF_VISUAL_ROUTED` / `OCR_REQUIRED` |
| `segments[].pageIndex` | 是 | 0-based 页索引 |
| `segments[].expectedRoute` | 否 | `FAST_TRACK` 或 `VISUAL_TRACK` |
| `segments[].mustContain` | 否 | 页 segment 文本中必须出现的子串列表（忽略大小写） |
| `segments[].mustNotContain` | 否 | 页 segment 文本中不得出现的子串列表（忽略大小写） |
| `segments[].expectedVisualType` | 否 | `TABLE` / `CHART` / `FORMULA` / `IMAGE`（仅 visual-track 页） |

##### Step 1 已完成的代码落地

**1. 验收测试类**：`chatagent/bootstrap/src/test/java/com/yulong/chatagent/rag/parser/GoldenPdfValidationTest.java`

```java
@Tag("golden")
class GoldenPdfValidationTest {
    // @ParameterizedTest(name = "[{0}]") + @MethodSource("goldenPdfCases")
    // 自动发现 scanned/ tables/ headings/ mixed/ 下所有 *.pdf
    // 读取 expected/<basename>.segments.json 快照
    // 用 PdfDocumentParser(NoopVdpEngine) 解析 PDF
    // assertSoftly 做文档级 + 页级断言
    // 无样本时抛 IllegalStateException 提示添加
}
```

**测试断言覆盖**：

| 断言层级 | 断言内容 |
|---|---|
| 文档级 | `expectedSegmentCount`（精确 segment 数）、`expectedExtractionMode`（提取模式）、`parserType == "pdfbox"` |
| 页级 | `pageRoute` 匹配 `expectedRoute`、`mustContain` 子串存在（忽略大小写）、`mustNotContain` 子串不存在、`visualType` 匹配 |

**2. Surefire 排除配置**：`chatagent/bootstrap/pom.xml`

```xml
<!-- bootstrap/pom.xml -->
<properties>
    <surefire.excludedGroups>golden</surefire.excludedGroups>
</properties>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludedGroups>${surefire.excludedGroups}</excludedGroups>
    </configuration>
</plugin>
```

用 Maven property 间接引用，允许命令行 `-Dsurefire.excludedGroups=` 覆盖为空串来解除排除。

- 常规 `mvn test` 跳过 `@Tag("golden")`（已验证 232 tests pass，0 failure）
- 手动验收：`mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest`

**3. 存储策略**：方案 C（压缩子集 < 5MB，直接入仓），单文件上限 5MB，目录总量控制在 20MB 以内。

##### Step 1 待收口

| 待办 | 说明 | 负责方 |
|---|---|---|
| **填充真实 PDF 样本** | 已完成首批 4 份：`scanned-01`、`table-01`、`heading-01`、`mixed-01`；下一步扩到每类 2-3 份 | 已完成首批 |
| **编写对应 .segments.json** | 已完成首批 4 份 snapshot，对应 `expected/*.segments.json` | 已完成首批 |
| **首次 golden 验收通过** | 已完成首轮校验；执行命令见下方 | 已完成 |

**样本命名规范**：`<category>-<nn>.pdf` → `expected/<category>-<nn>.segments.json`，例如 `table-01.pdf` → `expected/table-01.segments.json`。

---

#### Step 2: VdpEngineRouter + MinerU Adapter 同步设计实现

**当前落地状态（2026-04-05）**：

- 已完成 `VdpEngineRouter` 与 `VdpEngineRoutingProperties`
- 已去掉 `VlmVdpEngine.@Primary` 与 `NoopVdpEngine.@ConditionalOnMissingBean`
- 已将 `PdfDocumentParser` / `ImageDocumentParser` 改为基于 router 选择引擎
- 已实现 `MinerUVdpEngine` + `MinerUProperties` 的 HTTP 提交 / 轮询 / cancel 骨架
- 已实现 batch 路径的“先 resolve engine，再构造 cache context”
- 已实现 `VdpPageCacheService.putAll(...)`，Knowledge 侧走 Redis pipelining

**本节剩余任务**：MinerU 参数联调、golden 验收与主链端到端验证，不再是适配协议本身。

**关键约束**：当前 `PdfDocumentParser` 只注入一个 `VdpEngine`（`@Primary` 为 `VlmVdpEngine`）。若先写 MinerU adapter 再加路由，`@Primary` 冲突无处注入。因此 **router 和 adapter 必须同步设计**。

##### 2a: VdpEngineRouter — 引擎选择器

Router 不实现 `VdpEngine`，而是作为引擎选择器，让 `PdfDocumentParser` 在调度前确定最优引擎。

**新建文件**：`chatagent/bootstrap/.../rag/parser/VdpEngineRouter.java`

```java
@Component
public class VdpEngineRouter {

    private final List<VdpEngine> engines;
    private final VdpEngineRoutingProperties properties;

    private final Map<String, VdpEngine> engineMap;  // engineId -> VdpEngine
    private final VdpEngineRoutingProperties properties;

    public VdpEngineRouter(List<VdpEngine> engines,
                           VdpEngineRoutingProperties properties) {
        // 按 engineId 去重建 Map，消除对 Spring bean 注入顺序的依赖
        this.engineMap = engines.stream()
                .collect(Collectors.toMap(
                        VdpEngine::engineId,
                        Function.identity(),
                        (a, b) -> a  // 同 id 取先注册者
                ));
        this.properties = properties;
    }

    /**
     * 为单页图片解析选择引擎。
     * 按 preferredPageImageEngine 配置查找，未配置或不可用时回退到任意 PAGE_IMAGE 引擎。
     */
    public VdpEngine resolveForPageImage(PipelineSource source) {
        // 1. 尝试 preferred
        String preferredId = properties.getPreferredPageImageEngine();
        if (preferredId != null) {
            VdpEngine preferred = engineMap.get(preferredId);
            if (preferred != null && isEnabled(preferred)
                    && preferred.supportedModes().contains(VdpMode.PAGE_IMAGE)) {
                return preferred;
            }
        }
        // 2. fallback: 任意可用 PAGE_IMAGE 引擎
        return engineMap.values().stream()
                .filter(e -> e.supportedModes().contains(VdpMode.PAGE_IMAGE))
                .filter(this::isEnabled)
                .findFirst()
                .orElseGet(NoopVdpEngine::new);
    }

    /**
     * 为 PDF 批量解析选择引擎。
     * SESSION 不走 batch。
     * KNOWLEDGE 根据 knowledgeBatchPreferred 决定是否优先 batch 引擎。
     * 无可用 batch 引擎时返回 null（调用方回退到逐页派发）。
     */
    public VdpEngine resolveForBatch(PipelineSource source) {
        if (source == PipelineSource.SESSION) {
            return null;
        }
        if (!properties.isKnowledgeBatchPreferred()) {
            return null;  // 配置明确关闭 batch 优先 → 强制走逐页
        }
        // 按 preferredBatchEngine 配置查找
        String preferredId = properties.getPreferredBatchEngine();
        if (preferredId != null) {
            VdpEngine preferred = engineMap.get(preferredId);
            if (preferred != null && isEnabled(preferred)
                    && preferred.supportedModes().contains(VdpMode.PDF_PAGE_BATCH)) {
                return preferred;
            }
        }
        // fallback: 任意可用 batch 引擎
        return engineMap.values().stream()
                .filter(e -> e.supportedModes().contains(VdpMode.PDF_PAGE_BATCH))
                .filter(this::isEnabled)
                .findFirst()
                .orElse(null);
    }

    public boolean supportsBatchDispatch(PipelineSource source) {
        return resolveForBatch(source) != null;
    }

    private boolean isEnabled(VdpEngine engine) {
        return !properties.getDisabledEngines().contains(engine.engineId());
    }
}
```

**新建文件**：`chatagent/bootstrap/.../rag/parser/VdpEngineRoutingProperties.java`

```java
@Component
@ConfigurationProperties(prefix = "chatagent.rag.vdp.routing")
@Data
public class VdpEngineRoutingProperties {
    /** 按 engineId 显式禁用特定引擎 */
    private Set<String> disabledEngines = Set.of();
    /** KNOWLEDGE 管线是否优先 batch 引擎（false 时强制走逐页） */
    private boolean knowledgeBatchPreferred = true;
    /** 指定 PAGE_IMAGE 首选引擎 engineId（null 时取任意可用） */
    private String preferredPageImageEngine;
    /** 指定 PDF_PAGE_BATCH 首选引擎 engineId（null 时取任意可用） */
    private String preferredBatchEngine;
}
```

**PdfDocumentParser 改造**：

| 当前代码 | 改为 |
|---|---|
| `private final VdpEngine vdpEngine` | `private final VdpEngineRouter engineRouter` |
| `vdpEngine.supportedModes().contains(PDF_PAGE_BATCH)` | `engineRouter.supportsBatchDispatch(pipelineSource)` |
| `vdpEngine.parsePages(...)` | `engineRouter.resolveForBatch(pipelineSource).parsePages(...)` |
| `vdpEngine.parsePage(...)` | `engineRouter.resolveForPageImage(pipelineSource).parsePage(...)` |
| `vdpEngine.engineId()` / `vdpEngine.promptVersion()` | 从 resolved engine 实例取 |

**⚠️ 缓存 key 时序约束（Critical）**：

缓存 key 包含 `engineId` + `promptVersion`，因此 **必须先 resolve engine，再构造 `PageCacheContext`**。否则在 resolve 之前构造的 cache context 会使用旧的 `vdpEngine.engineId()` 硬编码值，导致引擎切换后缓存串读。

正确时序（伪代码）：

```java
// ✅ 正确：先 resolve，再用 resolved engine 的 id 构造 cache context
VdpEngine batchEngine = engineRouter.resolveForBatch(pipelineSource);
VdpEngine pageEngine  = engineRouter.resolveForPageImage(pipelineSource);

PageCacheContext batchCacheCtx = buildPageCacheContext(options, batchEngine);
PageCacheContext pageCacheCtx  = buildPageCacheContext(options, pageEngine);

// ❌ 错误：构造 cache context 时引擎尚未 resolve
PageCacheContext cacheCtx = buildPageCacheContext(options, ???);
VdpEngine engine = engineRouter.resolveForBatch(pipelineSource);
```

`buildPageCacheContext` 签名需增加 `VdpEngine resolvedEngine` 参数，从中取 `engineId()` 和 `promptVersion()`。

**现有 bean 改动**：

| Bean | 改动 |
|---|---|
| `VlmVdpEngine` | 去掉 `@Primary`，保留 `@ConditionalOnProperty(vlm.enabled=true)` |
| `NoopVdpEngine` | 去掉 `@ConditionalOnMissingBean`，作为 router 内部 fallback |
| `ImageDocumentParser` | 改为注入 `VdpEngineRouter`，调用 `resolveForPageImage(SESSION)` |

##### 2b: MinerU Adapter

**部署模型**：MinerU 以 Python HTTP 服务运行（`magic-pdf` CLI 或 REST wrapper），本项目通过 HTTP 客户端调用。

> **⚠️ 关键约束：异步任务提交 + 轮询模式**
>
> MinerU 处理大 PDF（50+ 页扫描件）耗时可达数分钟。若采用同步 POST → 阻塞等待 → 返回结果的模式，会面临：
> - HTTP 连接超时（Nginx/网关默认 60s，调到 180s 仍不够）
> - 线程池被长连接独占，无法响应后续请求
> - 无法实现进度上报（SSE 推送解析进度给前端）
>
> 因此 MinerU adapter **必须采用异步任务提交 + 轮询模式**：
> 1. `POST /tasks` — 上传 PDF，返回 `taskId`
> 2. `GET /tasks/{taskId}/status` — 轮询任务状态（PENDING / PROCESSING / COMPLETED / FAILED）
> 3. `GET /tasks/{taskId}/result` — 任务完成后拉取结果

**⚠️ 远端任务取消与清理策略**：

本地轮询超时（`maxPollAttempts` 耗尽）只会停止本地等待，**不会**终止远端 MinerU 上正在运行的任务。若不做清理，超时风暴下会在 MinerU 服务端积累大量无人领取的僵尸任务，反过来拖垮 batch 通道。

**必须实现的清理机制**（按优先级）：

| 机制 | 实现位置 | 说明 |
|---|---|---|
| **本地超时后发送 cancel** | `MinerUVdpEngine.pollUntilComplete` 的 catch/finally | 轮询超时后立即调用 `DELETE /tasks/{taskId}`（best-effort，失败不阻塞） |
| **MinerU 服务端 TTL** | MinerU REST wrapper 配置 | 服务端对每个任务设置最大执行时间（如 10 分钟），超时自动清理结果和中间文件 |
| **结果过期清理** | MinerU REST wrapper 配置 | 已完成任务的结果在 N 分钟后自动删除（避免磁盘膨胀） |

```java
// MinerUVdpEngine.pollUntilComplete 伪代码（含 cancel）
private MinerUTaskResult pollUntilComplete(String taskId) {
    for (int i = 0; i < properties.getMaxPollAttempts(); i++) {
        Thread.sleep(properties.getPollIntervalMs());
        MinerUTaskStatus status = queryStatus(taskId);
        if (status == COMPLETED) return fetchResult(taskId);
        if (status == FAILED) throw new VdpEngineException("MinerU task failed: " + taskId);
    }
    // 超时：best-effort cancel 远端任务
    cancelTaskQuietly(taskId);
    throw new VdpEngineException("MinerU poll timeout after " + properties.getMaxPollAttempts() + " attempts");
}

private void cancelTaskQuietly(String taskId) {
    try {
        webClient.delete().uri("/tasks/{taskId}", taskId)
                .retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
    } catch (Exception e) {
        log.warn("Failed to cancel MinerU task {}: {}", taskId, e.getMessage());
    }
}
```

**MinerU REST wrapper 侧配置建议**（部署文档/运维手册）：
- `TASK_MAX_EXECUTION_SECONDS=600`（10 分钟硬上限）
- `TASK_RESULT_TTL_SECONDS=300`（完成后 5 分钟清理）
- 提供 `DELETE /tasks/{taskId}` 端点支持外部主动取消

**新建文件**：`chatagent/bootstrap/.../rag/parser/MinerUVdpEngine.java`

```java
@Component
@ConditionalOnProperty(prefix = "chatagent.rag.vdp.mineru", name = "enabled", havingValue = "true")
public class MinerUVdpEngine implements VdpEngine {

    private final WebClient webClient;
    private final MinerUProperties properties;

    @Override
    public List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream,
                                          List<Integer> pageIndices,
                                          VdpOptions options) {
        // --- 异步任务提交 + 轮询 ---
        // 1. POST /tasks: multipart 上传完整 PDF → 返回 taskId
        //    注意：不传 pageIndices 给 MinerU，它解析整份文档（见下方 Batch 策略）
        String taskId = submitTask(pdfStream);

        // 2. 轮询 GET /tasks/{taskId}/status
        //    间隔: pollIntervalMs（默认 2000ms）
        //    上限: maxPollAttempts（默认 150 次 = 5 分钟）
        //    每次轮询检查: COMPLETED → 进入步骤 3
        //                  FAILED → 抛 VdpEngineException
        //                  PENDING/PROCESSING → 继续等待
        MinerUTaskResult taskResult = pollUntilComplete(taskId);

        // 3. GET /tasks/{taskId}/result → 解析 JSON 响应
        // 4. 映射全量页结果，但只返回 pageIndices 请求的子集
        //    page.markdown -> VdpPageResult.markdown
        //    ⚠️ page.page_no (1-based) -> VdpPageResult.pageIndex (0-based)
        //       必须 page_no - 1！内部约定 pageIndex=0-based，
        //       metadata.pageNumber 才是 1-based（由上层 buildVisualSegment 写入）
        //       错位会导致缓存 key、结果缝合、页级 fallback 全部落错页
        //    有内容 -> SUCCESS, 空内容 -> DEGRADED
        //    metadata: engineId="mineru", contentOrigin="VDP_TRANSCRIBED"
        return filterRequestedPages(taskResult, pageIndices);
    }

    /**
     * 将 MinerU 返回的单页结果归一化为 VdpPageResult。
     * ⚠️ 关键：page_no (1-based, MinerU 外部协议) → pageIndex (0-based, 内部约定)
     */
    private VdpPageResult toVdpPageResult(MinerUPageDto page) {
        int pageIndex = page.getPageNo() - 1;  // 1-based → 0-based
        String markdown = page.getMarkdown();
        VdpPageStatus status = (markdown != null && !markdown.isBlank())
                ? VdpPageStatus.SUCCESS : VdpPageStatus.DEGRADED;
        Map<String, Object> metadata = Map.of(
                "engineId", engineId(),
                "contentOrigin", "VDP_TRANSCRIBED"
        );
        return new VdpPageResult(pageIndex, markdown, status, metadata);
    }

    @Override
    public VdpPageResult parsePage(...) {
        throw new UnsupportedOperationException("MinerU only supports batch PDF parsing");
    }

    @Override public String engineId() { return "mineru"; }
    @Override public String promptVersion() { return properties.getVersion(); }

    @Override
    public EnumSet<VdpMode> supportedModes() {
        return EnumSet.of(VdpMode.PDF_PAGE_BATCH);
    }
}
```

**⚠️ 线程池饿死风险（Critical）**：

`parsePages` 是同步阻塞方法 — 轮询期间（3-5 分钟）调用线程被挂起。该线程来自 `vdpBatchExecutor`，当前配置为 `core=1, max=1, queueCapacity=0`。这意味着：

- **1 个大 PDF 正在轮询** → 整个 Batch 通道满载
- **第 2 个知识库文档到达** → `RejectedExecutionException` → 文档 ingestion 失败

**必须配套的 Executor 调整**：

| 方案 | 改动 | 适用场景 |
|---|---|---|
| **A: 放大队列（推荐）** | `vdpBatchExecutor` 的 `pdf-batch-queue-capacity` 从 0 调至 **20-50**，允许后续文档排队等待 | 知识库文档 ingestion 不要求实时性，排队可接受 |
| **B: 增加线程数** | `pdf-batch-max-pool-size` 从 1 调至 **2-3**，允许多文档并发轮询 | MinerU 服务端有足够并发处理能力 |
| **C: Ingestion 侧重试（利用现有 Retry 机制）** | `RejectedExecutionException` 已被 `catch (Exception e)` 捕获 → `markFailure` + 抛 `RetryableKnowledgeDocumentIngestionException` → RabbitMQ 自动重投 | 无需新增 PENDING 状态，复用现有 FAILED + retryCount + MQ retry 语义 |

**⚠️ 方案 C 的前提约束**：

当前代码中 `RejectedExecutionException` 会走 `catch (Exception e)` 通用路径：`markFailure(FAILED)` → 抛 `RetryableKnowledgeDocumentIngestionException` → MQ 重投。这已经是可用的重试语义，**无需新增 PENDING 状态**。

但需注意：
- `markFailure` 会 `retryCount++`，需确保 MQ consumer 有 `maxRetryCount` 上限（否则无限重投）
- 前端显示 `FAILED` 时应区分"可重试中"与"终态失败"—— 可通过 `retryCount < maxRetry` 判断
- 不建议为此引入新的 `PENDING_RETRY` 状态流转，因为需要同步改 repository、SSE 推送、前端状态机，代价不成比例

**推荐组合**：A + B — 队列吸收短时突发（queue-capacity=30），适度增加并发（max-pool-size=2）。方案 C 作为兜底已由现有代码天然支持（FAILED + MQ retry），无需额外改动。Step 3 调参表中已添加对应参数。

**新建文件**：`chatagent/bootstrap/.../rag/parser/MinerUProperties.java`

```java
@Component
@ConfigurationProperties(prefix = "chatagent.rag.vdp.mineru")
@Data
public class MinerUProperties {
    private boolean enabled = false;
    private String baseUrl = "http://localhost:8765";
    private String version = "v1";
    private int maxPdfSizeMb = 50;

    // --- 异步轮询参数 ---
    /** 轮询间隔（毫秒），默认 2 秒 */
    private long pollIntervalMs = 2000L;
    /** 最大轮询次数，默认 150（= 2s × 150 = 5 分钟） */
    private int maxPollAttempts = 150;
    /** 任务提交 HTTP 超时（上传 PDF），默认 30 秒 */
    private long submitTimeoutMs = 30000L;
    /** 单次轮询 HTTP 超时，默认 5 秒 */
    private long pollTimeoutMs = 5000L;
}
```

**application.yaml 新增**：

```yaml
chatagent:
  rag:
    vdp:
      routing:
        disabled-engines: []
        knowledge-batch-preferred: true
        preferred-page-image-engine: ${CHATAGENT_RAG_VDP_PREFERRED_PAGE_IMAGE_ENGINE:}
        preferred-batch-engine: ${CHATAGENT_RAG_VDP_PREFERRED_BATCH_ENGINE:}
      mineru:
        enabled: ${CHATAGENT_RAG_VDP_MINERU_ENABLED:false}
        base-url: ${CHATAGENT_RAG_VDP_MINERU_BASE_URL:http://localhost:8765}
        bearer-token: ${CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN:}
        version: ${CHATAGENT_RAG_VDP_MINERU_VERSION:v1}
        max-pdf-size-mb: ${CHATAGENT_RAG_VDP_MINERU_MAX_PDF_SIZE_MB:50}
        poll-interval-ms: ${CHATAGENT_RAG_VDP_MINERU_POLL_INTERVAL_MS:2000}
        max-poll-attempts: ${CHATAGENT_RAG_VDP_MINERU_MAX_POLL_ATTEMPTS:150}
        submit-timeout-ms: ${CHATAGENT_RAG_VDP_MINERU_SUBMIT_TIMEOUT_MS:30000}
        poll-timeout-ms: ${CHATAGENT_RAG_VDP_MINERU_POLL_TIMEOUT_MS:5000}
```

##### 2c: Batch 调度策略 — 文档级全量派发 + 结果过滤

> **⚠️ 关键约束：MinerU 等 batch 引擎以整份 PDF 为输入单元**
>
> 与逐页 VLM 不同，batch 引擎（MinerU / Marker）接收完整 PDF 文件并返回全量页结果。
> 不可能在 HTTP 调用时指定"只解析第 3, 7, 12 页"，因为引擎内部依赖全局页间关系（表格跨页、目录结构等）。

**策略**：

1. **调度入口（`dispatchBatchVisualTrackPages`）**：
   - 收到 visual-track 页列表 `visualPageIndices` 后，**不对 pageIndices 做过滤**直接传给 batch engine
   - Batch engine 内部上传完整 PDF（`pdfStream` supplier 始终提供整份文档）
   - Engine 返回全量页结果 `List<VdpPageResult> allPages`

2. **结果过滤（调用方负责）**：
   - 从 allPages 中提取 `visualPageIndices` 请求的子集
   - **缓存全量**：将 allPages 中所有成功页结果写入 `VdpPageCacheService`，避免后续请求重复解析同一文档
   - 只将 `visualPageIndices` 子集返回给上层合并逻辑

3. **文档级缓存拦截（优化路径）**：
   - 在调度 batch engine 之前，检查 `visualPageIndices` 中的所有页是否已在 cache 中
   - 若全部命中 → 直接返回缓存结果，跳过 batch 调用
   - 若部分命中 → 仍需发送完整 PDF（batch engine 无法只处理缺失页），但可从缓存补全已有页结果

```java
// PdfDocumentParser.dispatchBatchVisualTrackPages 伪代码
// ━━ 时序红线：必须先 resolve engine，再构造 cacheCtx ━━
VdpEngine batchEngine = engineRouter.resolveForBatch(pipelineSource);
PageCacheContext cacheCtx = buildPageCacheContext(options, batchEngine);
//                                                        ^^^^^^^^^^^
//                          cacheCtx.engineId / promptVersion 来自 resolved engine

// 1. 文档级缓存拦截
List<VdpPageResult> cachedResults = lookupAllFromCache(visualPageIndices, cacheCtx);
if (cachedResults.size() == visualPageIndices.size()) {
    return cachedResults;  // 全部命中，跳过 batch
}

// 2. 部分缺失或全部缺失 → 发送完整 PDF
List<VdpPageResult> allPages = batchEngine.parsePages(pdfStream, visualPageIndices, vdpOptions);

// 3. 批量缓存全量结果（Redis pipelining，见下方说明）
cacheAllPagesPipelined(allPages, cacheCtx);

// 4. 返回请求子集
return filterByPageIndices(allPages, visualPageIndices);
```

**VdpEngine.parsePages 语义调整**：`pageIndices` 参数在 batch engine 实现中仅作为 **hint**（告知调用方关心哪些页），engine 实现可忽略并返回全量页。调用方始终按 `pageIndices` 过滤返回值。

**⚠️ 批量缓存写入性能约束**：

Batch engine 对 200 页扫描件可能返回 200+ 条 `VdpPageResult`，单条 Markdown + metadata 序列化后可达 10-25KB，总计 2-5MB。若逐条调用 `VdpPageCacheService.put()`（底层 Redis SET），会产生 200 次网络 RTT，形成瞬时 Redis 带宽尖峰，拖慢 Ingestion 链路。

**要求**：`VdpPageCacheService` 需提供批量写入方法，底层采用 **Redis Pipelining**（`RedisTemplate.executePipelined`）合并为单次网络往返：

```java
// VdpPageCacheService 新增方法
void putAll(List<VdpPageResult> results, PageCacheContext ctx) {
    redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
        for (VdpPageResult result : results) {
            String key = buildKey(ctx, result.pageIndex());
            byte[] value = serialize(result);
            connection.stringCommands().setEx(
                key.getBytes(), ttlSeconds, value
            );
        }
        return null;
    });
    // 同时批量写入 SessionScopedVdpCacheStore（内存操作，无需 pipeline）
    for (VdpPageResult result : results) {
        sessionStore.put(ctx.sessionId(), buildKeySuffix(ctx, result.pageIndex()), result);
    }
}
```

##### 2d: 测试策略

| 测试 | 类型 | 验证目标 |
|---|---|---|
| `VdpEngineRouterTest` | 单测 | preferredBatchEngine 命中、preferredPageImageEngine 命中、disabled fallback、no-batch→null、knowledgeBatchPreferred=false→强制逐页 |
| `MinerUVdpEngineTest` | 单测 (mock HTTP) | 异步提交+轮询：taskId 返回、轮询状态转移、超时后 maxPollAttempts 触发降级 + cancelTaskQuietly、FAILED 状态处理、空结果 DEGRADED |
| `MinerUVdpEngineTest` — page_no 归一化 | 单测 (mock HTTP) | MinerU 返回 page_no=1 → VdpPageResult.pageIndex=0；page_no=5 → pageIndex=4 |
| `MinerUVdpEngineTest` — 全量派发 | 单测 (mock HTTP) | 请求 pageIndices=[3,7] 但 engine 返回全量 10 页 → filterRequestedPages 只返回 2 页 |
| `PdfDocumentParserTest` 扩展 | 单测 | 注入 router 后 batch/page 路径分派正确 |
| `PdfDocumentParserTest` — cache key 时序 | 单测 | 验证 buildPageCacheContext 使用 resolved engine 的 engineId（非硬编码 "default"） |
| `PdfDocumentParserTest` — 文档级缓存拦截 | 单测 | 全部 visual 页已缓存 → 不调用 batchEngine.parsePages；部分缓存 → 仍调用 batch 但缓存全量返回 |
| `VdpPageCacheServiceTest` — 批量写入 | 单测 (mock Redis) | `putAll()` 调用 `executePipelined`，200 页只产生 1 次网络往返 |
| `PdfDocumentParserTest` — batch executor 满载 | 单测 | `vdpBatchExecutor` 满载时文档排队（queue-capacity > 0）而非直接 reject |

---

#### Step 3: 样本验收 + 调参

用 Step 1 收集的 4 类 PDF 跑通完整链路。

**执行方式**：

```bash
# 跑全部金标样本
mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest

# 跑单类
mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest
```

**调参项及初始值**：

| 参数（完整 key） | 初始值 | 调参依据 |
|---|---|---|
| `chatagent.rag.vdp.mineru.poll-interval-ms` | 2000 | 根据 MinerU 平均处理速度调整，太小增加请求压力，太大增加延迟 |
| `chatagent.rag.vdp.mineru.max-poll-attempts` | 150 | 2s × 150 = 5 分钟上限，根据样本中最大 PDF 的实际耗时 x1.5 |
| `chatagent.rag.vdp.mineru.submit-timeout-ms` | 30000 | PDF 上传超时，大文件（50MB）可能需要提高 |
| `chatagent.rag.vdp.pdf-batch-queue-capacity` | **30** | 原值 0，MinerU 轮询阻塞 3-5 分钟/文档，需队列吸收突发（见 Step 2b 线程池饿死分析） |
| `chatagent.rag.vdp.pdf-batch-max-pool-size` | **2** | 原值 1，允许 2 份 PDF 并发轮询（需确认 MinerU 服务端并发能力） |
| `chatagent.rag.vdp.pdf-render-dpi` | 120 | 若 MinerU 接管 Knowledge batch，Session VLM 可提高到 150 |
| `chatagent.rag.vdp.pdf-page-max-in-flight` | 2 | 若 MinerU 在 Knowledge 端分担，Session VLM 并发可适当提高 |
| `chatagent.rag.vdp.knowledge-document-timeout-ms` | 120000 | MinerU 接管后可能需要调至 300000+（因异步轮询本身耗时） |

**验收通过标准**：

- 扫描件：正确判定 `OCR_REQUIRED` 或 MinerU 成功提取文字
- 表格型：Markdown table 格式正确，列数/行数匹配
- 标题型：`fontAwareStructureRestored=true`，heading 注入位置合理
- 混合型：visual/native 分页路由无误，chunk 文本可读

---

#### Step 4: Micrometer 可观测性埋点

**当前状态（2026-04-05）**：本节已完成基础落地。当前代码已在 `VdpEngineRouter`、`VdpPageCacheService`、`VdpResultCacheService`、`PdfDocumentParser`、`VlmVdpEngine`、`MinerUVdpEngine` 接入 `MeterRegistry`，并补齐了对应单测。

**前置**：`spring-boot-starter-actuator` 与 `micrometer-registry-prometheus` 已在 pom.xml 中，Spring Boot AutoConfig 自动注册 `MeterRegistry` 并暴露 `/actuator/prometheus`。

**埋点位置**：指标桩点已覆盖 **router 层**（跨 engine 对比）、**cache 层**、**PDF 文档级调度层** 与 **engine 内部耗时**。

**VdpEngineRouter**（核心指标入口）：

```java
public VdpEngine resolveForPageImage(PipelineSource source) {
    VdpEngine engine = doResolve(...);
    meterRegistry.counter("vdp.engine.resolved",
            "engineId", engine.engineId(),
            "mode", "PAGE_IMAGE",
            "pipelineSource", source.name()
    ).increment();
    return engine;
}
```

**VdpPageCacheService / VdpResultCacheService**：

```java
public VdpPageResult get(...) {
    // ...existing logic...
    if (cached != null) {
        meterRegistry.counter("vdp.cache.hit", "layer", "page").increment();
        return cached;
    }
    meterRegistry.counter("vdp.cache.miss", "layer", "page").increment();
    return null;
}
```

**PdfDocumentParser**：

```java
private ParseResult extractPages(...) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // ...existing logic...
        return result;
    } finally {
        sample.stop(Timer.builder("vdp.document.parse.latency")
                .tag("pipelineSource", pipelineSource.name())
                .tag("extractionMode", extractionMode)
                .register(meterRegistry));
    }
}
```

**完整指标清单**：

| 指标名 | 类型 | Tags | 埋点位置 |
|---|---|---|---|
| `vdp.engine.resolved` | Counter | engineId, mode, pipelineSource | VdpEngineRouter |
| `vdp.engine.parsePage.latency` | Timer | engineId, status | VdpEngineRouter 包装层或各 engine 内部 |
| `vdp.engine.parsePages.latency` | Timer | engineId, status | 同上 |
| `vdp.cache.hit` | Counter | layer (page / image) | VdpPageCacheService / VdpResultCacheService |
| `vdp.cache.miss` | Counter | layer | 同上 |
| `vdp.page.success` | Counter | engineId | PdfDocumentParser.collectCompletedVisualPages / batch normalize |
| `vdp.page.timeout` | Counter | engineId | PdfDocumentParser.expireTimedOutVisualPages |
| `vdp.page.degraded` | Counter | engineId | PdfDocumentParser.collectCompletedVisualPages |
| `vdp.page.failed` | Counter | engineId | 同上 |
| `vdp.document.parse.latency` | Timer | pipelineSource, extractionMode | PdfDocumentParser.extractPages |
| `vdp.document.ocr_required` | Counter | pipelineSource | PdfDocumentParser.extractPages |

**暴露方式**（application.yaml 追加）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

生产环境通过 `/actuator/prometheus` 或 `/actuator/metrics/vdp.cache.hit` 查看。

---

#### 开放决策项

**1. Golden PDF 样本存储策略 — 已决策：方案 C**

`src/test/resources/golden-pdfs/` 存放真实 PDF 样本，采用 **方案 C（压缩子集入仓）**：

- 用 `pdftk` 抽取关键页，单文件 < 5MB，目录总量 < 20MB
- 直接 `git add` 入仓，无需 Git LFS
- CI 默认 `<excludedGroups>golden</excludedGroups>` 跳过，不影响日常构建速度

如果后续样本规模超过 20MB，可追加 Git LFS（方案 A）作为补充。

**2. DocumentParser.parse(byte[]) 废弃的 SPI 兼容影响 — 待执行**

删除 `parse(Supplier<InputStream>)` 的 default body 使其成为抽象方法，是一个 **SPI breaking change**——仓外如果有第三方扩展只实现了 `parse(byte[])` 而未实现 stream 版本，升级后会编译失败。

**建议**：
- 将此变更单独成一个 commit/PR，标题明确标注 `[BREAKING]`
- 变更前全局搜索 `implements DocumentParser` 和 `extends.*DocumentParser`，输出编译期清单
- 在 `CHANGELOG` 或 release notes 中说明迁移路径：实现 `parse(Supplier<InputStream>, String, Map)` 方法
- 如果确认仓外无扩展（当前为私有项目），可直接执行；但仍建议保留 `@Deprecated` 标注至少一个版本周期

---

## 12. 测试与验证现状

当前已覆盖的关键测试方向包括：

- Knowledge 图片拒绝
- Session 图片接受
- Parser 选择优先级
- Image VDP 成功路径
- VLM 异常、超时、非 JSON、缓存命中
- PDF Fast-Track / Visual-Track / timeout / fallback / OCR_REQUIRED
- PDF Batch dispatch 基础设施：
  - `shouldPreferBatchPdfDispatchWhenEngineSupportsPageBatchMode`
  - `shouldIsolateBatchDispatchFromSaturatedPageExecutor`
  - `shouldQueueSecondBatchDispatchInsteadOfRejectingWhenBatchExecutorHasCapacity`
- PDF 页缓存命中
- 字体感知结构恢复
- Knowledge `documentCacheKey` 内容摘要回退
- Session bucket 过期回收
- VdpEngineRouter 路由逻辑（preferred 引擎命中、knowledgeBatchPreferred=false 强制逐页、disabled fallback）
- MinerU 异步提交+轮询（taskId 返回、状态转移、FAILED 处理、executor 参与调度）
- MinerU page_no 归一化（1-based → 0-based pageIndex）
- MinerU 全量派发 + pageIndices 子集过滤
- Cache key 时序验证（resolved engine 的 engineId 绑定到 PageCacheContext）
- 文档级缓存拦截（全部/部分命中场景）
- VdpPageCacheService 批量写入（`putAll` + Redis pipelining，200 页 1 次 RTT）
- Micrometer 指标注册验证（router / cache / engine / document）

已通过的验收类测试：

- `@Tag(“golden”)` 的 Golden PDF 样本验收集 — **首轮验收通过**（4 份 PDF + 4 份 snapshot + 4 golden tests green），可按需扩充更多边界样本

当前已补齐的联调类测试：

- 真实本地 MinerU 服务 smoke 验证结果
  - `MinerUVdpEngineSmoke.java` 已在 `http://127.0.0.1:8000` 本地服务下通过
  - 当前仍保持 opt-in，不进入默认 CI

当前仍待补的验收类测试：

- MinerU 打开后的 golden 全量验收结果
- 本地 GPU 服务栈下的 retrieval → rerank → answer 端到端验收

文档应以本文件为准，不再以早期”纯规划态”表述推断当前代码行为。

---

## 13. Phase 5d 总体进度与收口清单

> 截至 2026-04-06 的完整进度快照。

### 13.1 已完成

| 序号 | 工作项 | 落地状态 | 关键文件 |
|---|---|---|---|
| S11-1 | `DocumentParser` stream contract + `ParserType` enum | 已完成 | `DocumentParser.java`, `ParserType.java` |
| S11-2 | `VdpEngineRouter` + `VdpEngineRoutingProperties` | 已完成（5 轮对抗审查通过） | `VdpEngineRouter.java`, `VdpEngineRoutingProperties.java` |
| S11-3 | `MinerUVdpEngine` + `MinerUProperties` HTTP 提交/轮询/cancel | 已完成（reactive Mono 链，无 Thread.sleep） | `MinerUVdpEngine.java`, `MinerUProperties.java` |
| S11-4 | `VdpPageCacheService` 页缓存 + Redis pipelining batch write | 已完成 | `VdpPageCacheService.java` |
| S11-5 | `VdpResultCacheService` 图像去重缓存 | 已完成 | `VdpResultCacheService.java` |
| S11-6 | `SessionScopedVdpCacheStore` Caffeine per-session bucket | 已完成（MAX_ACTIVE_SESSION_BUCKETS=1000） | `SessionScopedVdpCacheStore.java` |
| S11-7 | `PdfDocumentParser` 重构：router 注入、batch async、SizeLimitedInputStream、cache overwrite fix | 已完成 | `PdfDocumentParser.java` |
| S11-8 | `ImageDocumentParser` MIME subtype cleanup | 已完成 | `ImageDocumentParser.java` |
| S11-9 | Micrometer 可观测性埋点（Step 4） | 已完成（11 指标 + VdpMetricsSupport fail-open helper） | `VdpMetricsSupport.java`, 各 engine/cache/parser |
| S11-10 | Golden PDF 验证基础设施 + 首批 4 份样本（Step 1 完整收口） | 已完成（4 golden tests green） | `GoldenPdfValidationTest.java`, `golden-pdfs/`, 4 PDF + 4 `.segments.json` |
| S11-11 | Surefire golden 执行入口修正 | 已完成 | `pom.xml`（`${surefire.excludedGroups}` property 间接引用）、`README.md`、`GoldenPdfValidationTest.java` javadoc |
| S11-12 | 回归测试覆盖 | 232 unit tests + 4 golden tests pass, 0 failure | 所有 `*Test.java` |
| S11-13 | MinerU 真实服务 smoke | 已完成（opt-in，本地 `127.0.0.1:8000` 已通过） | `MinerUVdpEngineSmoke.java`, `MinerUProperties.java`, `MinerUVdpEngine.java` |
| S11-14 | ChatAgent 本地 GPU Spring profile + 后端启动入口 | 已完成 | `application-local-gpu.yaml`, `start-local-gpu-backend.ps1` |
| S11-15 | 本地 GPU 运行文档 | 已完成 | `docs/LOCAL_GPU_RUNTIME.md`, `tools/bge-reranker-server/README.md`, `tools/mineru/README.md`, `README.md` |

### 13.2 对抗审查修复追踪

> Section 11 代码经过 5 轮对抗性审查，共发现 22 个问题，全部已修复或确认为已知边界。

| 轮次 | 发现数 | P0 | P1 | P2 | 全部已修复 |
|---|---|---|---|---|---|
| Round 1（Section 11 初版） | 12 | 3 | 5 | 4 | 是 |
| Round 2（Round 1 修复验证） | 4 | 0 | 3 | 1 | 是（P1-B 确认为已知边界） |
| Round 3（Round 2 修复验证） | 0 | 0 | 0 | 0 | — |
| Round 4（Step 4 Metrics） | 4 | 0 | 2 | 2 | 是 |
| Round 5（Round 4 修复验证） | 0 | 0 | 0 | 0 | — |

**P0 修复项**：MinerU InputStream race → ByteArrayResource、Thread.sleep blocking → reactive Mono.delay、Cache overwrite SUCCESS→FAILED → putIfAbsent guard

**P1 修复项**：Map.copyOf loses order → unmodifiableMap(LinkedHashMap)、Set.of() immutable → new HashSet<>()、Stream no size limit → SizeLimitedInputStream、No taskId validation → TASK_ID_PATTERN、MIME subtype dirty → split(“[+;]”)、int overflow readPdfBytes → long arithmetic、executor ignored by MinerU → Schedulers.fromExecutor、Batch still calling sync → parsePagesAsync、Metrics exception covers business → try-catch fail-open、Timer.Sample includes queue time → Mono.defer

**P2 修复项**：ocr_required missing pipelineSource tag → added、No SUCCESS counter → added vdp.page.success

### 13.3 接下来要做的工作

按优先级顺序：

#### 近期（Step 3 参数调优 + 样本扩充）

> Step 1 已完整收口（2026-04-06）。N-1 ~ N-3 全部完成。

| 序号 | 工作项 | 状态 | 说明 |
|---|---|---|---|
| ~~N-1~~ | 填充 golden PDF 样本 | **已完成** | 首批 4 份：`scanned-01`、`table-01`、`heading-01`、`mixed-01` |
| ~~N-2~~ | 编写对应 `.segments.json` | **已完成** | 4 份 snapshot 已就位 |
| ~~N-3~~ | 首次 golden 验收全绿 | **已完成** | 4 golden tests green |
| **N-4** | Step 3 参数调优 | 待执行 | 调整 `char-density-threshold`、`pdf-render-dpi`、`short-text-fast-track-threshold` 等参数使 4 类样本全部达到验收标准 |
| **N-5** | 样本扩充至每类 2-3 份 | 可选 | 在 MinerU 联调前扩充更多边界样本（大表格、多列布局、中英混排等） |

**golden 执行命令**（surefire `excludedGroups` 需显式清空才能激活 golden tag）：

```bash
mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest
```

**样本扩充指南**（N-5 用）：

```bash
# 用 pdftk 抽取关键页（示例：抽第 1,3,5 页）
pdftk original.pdf cat 1 3 5 output golden-pdfs/tables/table-02.pdf

# 检查文件大小（应 < 5MB）
ls -lh golden-pdfs/tables/table-02.pdf

# 获取 baseline 输出后编写 expected/table-02.segments.json
```

#### 中期（MinerU 参数联调与主链验收）

| 序号 | 工作项 | 前置条件 | 预期产出 |
|---|---|---|---|
| ~~M-1~~ | 本地 MinerU 部署与健康检查 | **已完成** | `http://127.0.0.1:8000/health` / `/docs` 可用 |
| ~~M-2~~ | 协议字段校准 + 本地 smoke | **已完成** | `MinerUVdpEngine` 已对齐真实本地 API，`MinerUVdpEngineSmoke` 通过 |
| **M-3** | 超时参数调优 | N-3 | 用 golden 样本跑 `mineru.enabled=true`，调整 `poll-interval-ms`、`max-poll-attempts`、`submit-timeout-ms` |
| **M-4** | Batch executor 参数确认 | M-3 | 根据 MinerU 并发能力决定 `pdf-batch-queue-capacity`（建议 30）和 `pdf-batch-max-pool-size`（建议 2） |
| **M-5** | MinerU golden 验收 | M-3, N-3 | 在 golden 测试中切换 engine 到 mineru，验证表格保真度、扫描件 OCR 质量 |
| **M-6** | 本地 GPU 主链端到端验收 | M-3 | 跑通 retrieval → rerank → answer，确认 embedding / reranker / mineru 均走本地 GPU 服务 |

**MinerU smoke 执行命令**（本地真实服务已验证通过）：

```bash
CHATAGENT_RAG_VDP_MINERU_SMOKE=true \
CHATAGENT_RAG_VDP_MINERU_BASE_URL=http://127.0.0.1:8000 \
mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MinerUVdpEngineSmoke test
```

#### 远期（SPI 清理）

| 序号 | 工作项 | 前置条件 | 预期产出 |
|---|---|---|---|
| **F-1** | `DocumentParser.parse(byte[])` 废弃清理 | 所有调用方已迁移到 stream 版本 | 单独 `[BREAKING]` PR：删除 `@Deprecated` default 方法体，`parse(Supplier<InputStream>)` 成为唯一抽象方法 |
| **F-2** | 全局编译清单确认 | F-1 PR 前 | `grep -r “implements DocumentParser” --include=”*.java”` + `grep -r “extends.*DocumentParser”` 确保无仓外扩展遗漏 |

### 13.4 关键文件索引

| 文件 | 用途 |
|---|---|
| `bootstrap/src/test/java/.../rag/parser/GoldenPdfValidationTest.java` | Golden PDF 参数化验收测试（`@Tag(“golden”)`） |
| `bootstrap/src/test/resources/golden-pdfs/README.md` | 样本要求、命名规范、JSON schema 说明 |
| `bootstrap/src/test/resources/golden-pdfs/expected/_example.segments.json` | JSON schema 示例文件 |
| `bootstrap/src/test/resources/golden-pdfs/expected/{scanned,table,heading,mixed}-01.segments.json` | 首批 4 份期望输出快照 |
| `bootstrap/src/test/resources/golden-pdfs/{scanned,tables,headings,mixed}/*-01.pdf` | 首批 4 份 golden PDF 样本 |
| `bootstrap/pom.xml` | surefire `${surefire.excludedGroups}` property 间接排除 golden tag |
| `bootstrap/src/main/java/.../rag/parser/VdpMetricsSupport.java` | Micrometer 共享 helper（fail-open） |
| `bootstrap/src/main/java/.../rag/parser/MinerUVdpEngine.java` | MinerU batch engine（reactive async polling） |
| `bootstrap/src/test/java/.../rag/parser/MinerUVdpEngineSmoke.java` | 真实 MinerU 环境 opt-in smoke test |
| `bootstrap/src/main/java/.../rag/parser/VdpEngineRouter.java` | 引擎选择路由器 |
| `bootstrap/src/main/java/.../rag/parser/PdfDocumentParser.java` | PDF 核心解析器（~1500 行） |
| `bootstrap/src/main/resources/application-local-gpu.yaml` | ChatAgent 本地 GPU profile（embedding / reranker / mineru） |
| `start-local-gpu-backend.ps1` | 一键以 `local-gpu` profile 启动后端 |
| `docs/LOCAL_GPU_RUNTIME.md` | 本地 GPU 服务栈运行手册 |
| `tools/bge-reranker-server/README.md` | 本地 reranker 服务启动与验证说明 |

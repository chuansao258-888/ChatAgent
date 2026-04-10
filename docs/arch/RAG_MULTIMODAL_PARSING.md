# RAG 多模态解析 (VDP) 架构实现文档

> 对应计划：`docs/plans/RAG_MULTIMODAL_PARSING_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

用 VLM（视觉语言模型）替代传统纯文本 OCR，实现 PDF、图片中表格、图表、扫描内容的 Markdown 优先输出。覆盖单页图片解析、PDF 逐页路由、批量处理、全局去重和缓存。

### 1.2 核心设计决策

1. **统一 VdpEngine 抽象**：支持单页图片（VLM）和批量 PDF（MinerU）两种引擎。
2. **Markdown 优先输出**：所有视觉解析结果以 Markdown 格式输出（表格 → Markdown 表格，图表 → 拓扑描述）。
3. **商业 VLM 只接收单页 PNG**：绝不发送完整 PDF 到外部 API。
4. **受控内存占用**：`Supplier<InputStream>` 流式契约，AbortPolicy 线程池。
5. **全局去重**：图片 SHA-256 缓存 + PDF 页级缓存 + Session 级桶。
6. **双门禁准入**：Knowledge 独立图片拒绝；二进制类型在 FacadeService 层拒绝。
7. **分阶段交付**：5a（基建+单图）→ 5b（PDF 逐页路由）→ 5c（缓存+结构恢复）→ 5d（MinerU 集成）。

## 2. 整体架构

### 2.1 VDP 引擎架构

```
┌───────────────────────────────────────────────────────┐
│               VdpEngineRouter                         │
│  preferred engine + fallback logic                    │
│                                                       │
│  ┌─────────────────┐    ┌──────────────────┐         │
│  │  VlmVdpEngine   │    │  MinerUVdpEngine  │         │
│  │  (单页图片)      │    │  (批量 PDF)       │         │
│  │  gpt-4o-mini /  │    │  本地 HTTP API    │         │
│  │  Qwen-VL        │    │  提交 → 轮询 → 取消│         │
│  └─────────────────┘    └──────────────────┘         │
│                                                       │
│  ┌─────────────────┐                                 │
│  │  NoopVdpEngine  │  (fail-open 回退)               │
│  └─────────────────┘                                 │
└───────────────────────────────────────────────────────┘
         │                    │
         ▼                    ▼
┌──────────────┐    ┌──────────────────┐
│ VdpResult    │    │ VdpPageCache     │
│ CacheService │    │ Service          │
│ (图片去重)   │    │ (PDF 页级缓存)   │
│ SHA-256      │    │ Redis + Session  │
└──────────────┘    └──────────────────┘
```

### 2.2 PDF 逐页路由流程

```
PdfDocumentParser 收到 PDF
    │
    ├── 逐页提取文本密度
    │
    ├── 文本密度高 → Fast-Track (原生提取)
    │
    ├── 文本密度低/扫描 → Visual-Track
    │   ├── 渲染单页 PNG (PDFBox)
    │   ├── VdpEngineRouter 选择引擎
    │   │   ├── 短页 → VlmVdpEngine (VLM 单页)
    │   │   └── 长文档/批量 → MinerUVdpEngine (批量提交)
    │   └── 输出 Markdown 文本
    │
    └── 组装 ParseResult (含 PAGE segments + QualityLevel)
```

## 3. 文件清单

### 3.1 后端代码

| 文件路径 | 职责 |
|---|---|
| **VDP 核心抽象** | |
| `rag/parser/VdpEngine.java` | 统一视觉解析引擎接口 |
| `rag/parser/VlmVdpEngine.java` | VLM 引擎（gpt-4o-mini/Qwen-VL） |
| `rag/parser/MinerUVdpEngine.java` | MinerU 批量引擎（响应式异步轮询） |
| `rag/parser/NoopVdpEngine.java` | Fail-open 空回退 |
| `rag/parser/VdpEngineRouter.java` | 引擎选择路由器（preferred + fallback） |
| **VDP 模型与配置** | |
| `rag/parser/VdpOptions.java` | VDP 选项（recognizeFormulas, languageHint） |
| `rag/parser/VdpPageResult.java` | 单页解析结果 |
| `rag/parser/VdpMode.java` | 枚举：PAGE_IMAGE / PDF_PAGE_BATCH |
| `rag/parser/VdpEngineRoutingProperties.java` | 路由配置 |
| `rag/parser/MinerUProperties.java` | MinerU 配置（轮询间隔、最大尝试、超时） |
| `rag/parser/VlmVdpProperties.java` | VLM 引擎配置 |
| `rag/parser/VdpCacheProperties.java` | 缓存配置 |
| **缓存与去重** | |
| `rag/parser/VdpResultCacheService.java` | 图片去重缓存（SHA-256） |
| `rag/parser/VdpPageCacheService.java` | PDF 页级缓存（Redis + Session 桶） |
| `rag/parser/SessionScopedVdpCacheStore.java` | Caffeine per-session 缓存桶 |
| **基础设施** | |
| `rag/parser/VdpMetricsSupport.java` | Micrometer 指标（11 个指标，fail-open） |
| `rag/parser/VdpExecutorConfig.java` | 线程池配置（dispatch, batch, page executors） |
| **使用 VDP 的解析器** | |
| `rag/parser/PdfDocumentParser.java` | PDF 解析器（含 Fast/Visual Track 路由） |
| `rag/parser/ImageDocumentParser.java` | 图片解析器（路由到 VLM） |

### 3.2 工具脚本

| 文件路径 | 说明 |
|---|---|
| `tools/mineru/start-mineru-api.ps1` | MinerU API 启动脚本 |
| `tools/mineru/README.md` | MinerU 本地部署文档 |

### 3.3 配置文件

| 配置项 | 说明 |
|---|---|
| `chatagent.vdp.routing.*` | VDP 引擎路由配置（preferred/disabled engines） |
| `chatagent.vdp.mineru.*` | MinerU 连接和超时配置 |
| `chatagent.vdp.vlm.*` | VLM 引擎配置 |
| `chatagent.vdp.cache.*` | 缓存策略 |

## 4. 核心功能实现

### 4.1 VdpEngineRouter — 引擎选择

**实现位置：** `rag/parser/VdpEngineRouter.java`

**实现逻辑：**

1. 检查 VDP 是否全局禁用
2. 按 mode（PAGE_IMAGE / PDF_PAGE_BATCH）和路由配置选择 preferred engine
3. preferred engine 失败时 fallback 到备选引擎
4. 所有引擎不可用时回退 NoopVdpEngine（fail-open）

### 4.2 MinerUVdpEngine — 批量 PDF 处理

**实现位置：** `rag/parser/MinerUVdpEngine.java`

**实现逻辑：** 响应式异步 HTTP 提交-轮询-取消模式。

```
submit(PDF) → 获取 taskId
    ↓
poll(taskId, interval, maxAttempts) → 获取结果
    ↓
cancel(taskId) → 超时或失败时取消
```

**关键约束：**
- 使用 `Supplier<InputStream>` 流式契约，不在内存中保持完整 PDF
- AbortPolicy 线程池，防止积压导致 OOM
- 超时后主动调用 cancel API

### 4.3 VdpPageCacheService — PDF 页级缓存

**实现位置：** `rag/parser/VdpPageCacheService.java`

**实现逻辑：**
- Redis 存储 PDF 页级解析结果
- Session 级桶隔离（session 过期时自动清理）
- Redis pipelined batch write 优化批量写入

### 4.4 Micrometer 可观测性

**实现位置：** `rag/parser/VdpMetricsSupport.java`

**已实现的 11 个指标：**
- 解析成功/失败/降级计数
- 解析延迟分布
- 缓存命中/未命中
- 引擎选择分布

**设计特点：** `MeterRegistry` 不存在时全部 no-op，不改变启动要求。

## 5. 分阶段完成状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 5a | 基建 + 单图解析 + Knowledge 图片拒绝 | 已完成 |
| Phase 5b | PDF 逐页路由（Fast/Visual Track）+ 单页 PNG 渲染 | 已完成 |
| Phase 5c | VdpEngineRouter + MinerU 集成 + 页缓存 + 结构恢复 + Micrometer | 已完成 |
| Phase 5d | MinerU 冒烟测试 + 参数调优 + Golden 验证 | 部分完成 |

## 6. 已知限制与后续规划

- **MinerU 调优未完成**：本地冒烟测试通过，但生产参数调优和 Golden PDF 验证待完成。
- **VLM 成本控制**：外部 VLM API 调用成本需要监控和预算限制。
- **结构恢复精度**：字体感知的结构恢复对复杂排版的支持有限。

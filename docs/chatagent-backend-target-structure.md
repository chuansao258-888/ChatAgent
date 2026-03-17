# ChatAgent 后端目标结构与迁移顺序

这份文档基于当前 `app / framework / infra / agent / rag / biz` 六模块现状，参考 `ragent` 的模块边界整理。

目标不是“照抄 ragent”，而是把 `ChatAgent` 从“已经拆模块名的原型工程”升级成“职责边界清晰的模块化单体”。

## 一、先讲结论

当前真正需要学习的不是 `ragent` 的功能数量，而是它的职责边界：

- `ragent/framework` 对应你未来更完整的 `framework`
- `ragent/infra-ai` 对应你未来的 `infra`
- `ragent/bootstrap` 对应你未来的 `biz + agent + rag` 协作层
- `ragent/mcp-server` 是你后续可选扩展，不是第一阶段重点

你现在最核心的问题不是“模块不够多”，而是：

- `biz` 仍然过重，且主要按技术层组织
- `agent` 还是单类 orchestration
- `rag` 还是 service 级实现，不是 pipeline
- `infra` 还是客户端注册，不是 AI 能力网关
- `framework` 还没有成为真正的基础设施底座

## 二、六模块目标职责

### 1. `app`

只保留启动职责：

- Spring Boot 启动类
- profile / 配置装配
- 组件扫描与模块装配

禁止放入：

- controller
- service
- mapper
- 业务配置

### 2. `framework`

变成真正的基础设施底座，承接所有横切能力。

目标包结构：

```text
framework
`- src/main/java/com/yulong/chatagent/framework
   |- config
   |- context
   |- error
   |- trace
   |- web
   `- sse
```

建议承接的内容：

- 统一返回体 `Result/ApiResponse`
- 错误码与异常体系
- 全局异常处理
- Trace 上下文
- 用户上下文
- 线程上下文透传工具
- 底层 SSE sender 封装
- 幂等、限流注解的公共基建

你当前已有的：

- `ApiResponse`
- `BizException`
- `GlobalExceptionHandler`
- `AsyncConfig`
- `CorsConfig`

下一步应迁入：

- `biz/service/impl/SseServiceImpl` 的底层 emitter 发送逻辑
- 通用错误码
- TraceContext

### 3. `infra`

从“配置两个模型客户端”升级成“外部系统与 AI 能力网关层”。

目标包结构：

```text
infra
`- src/main/java/com/yulong/chatagent/infra
   |- llm
   |  |- client
   |  |- routing
   |  |- health
   |  `- model
   |- embedding
   |- rerank
   |- storage
   |- mail
   `- config
```

建议承接的内容：

- LLM 统一服务接口
- 多 provider 客户端适配
- 模型候选选择
- 模型健康状态
- 超时与降级策略
- embedding 接口
- rerank 接口
- 邮件客户端
- 文件存储客户端

你当前已有的：

- `ChatClientRegistry`
- `MultiChatClientConfig`
- `EmailService`

下一步新增：

- `LLMService`
- `EmbeddingService`
- `RerankService`
- `ModelSelector`
- `ModelHealthStore`
- `RoutingLLMService`

### 4. `agent`

只保留 Agent runtime 与工具编排，不碰持久化细节。

目标包结构：

```text
agent
`- src/main/java/com/yulong/chatagent/agent
   |- orchestration
   |- planning
   |- runtime
   |- tool
   |- bridge
   `- model
```

建议承接的内容：

- `AgentOrchestrator`
- `PlanningService`
- `ToolExecutionService`
- `AgentSession`
- `ToolRegistry`
- `ToolResolver`
- `AgentMessageBridge` 接口

你当前的 `ChatAgent` 需要拆成：

- `AgentOrchestrator`：控制 step loop
- `PlanningService`：负责 think
- `ToolExecutionService`：负责 execute
- `ConversationRuntime`：持有 memory / session state
- `AgentTerminationPolicy`：控制停止条件

### 5. `rag`

从“检索 service + markdown 入库”升级成“RAG pipeline 模块”。

目标包结构：

```text
rag
`- src/main/java/com/yulong/chatagent/rag
   |- ingestion
   |  |- fetcher
   |  |- parser
   |  |- chunk
   |  `- index
   |- retrieve
   |  |- channel
   |  `- postprocessor
   |- rewrite
   |- prompt
   |- memory
   |- repository
   `- model
```

建议承接的内容：

- 文档抓取与存储抽象
- parser selector
- chunker
- indexer
- query rewrite
- retrieval engine
- search channel
- post processor
- prompt builder
- conversation memory

你当前已有的：

- `DocumentStorageService`
- `MarkdownParserService`
- `RagService`
- `DocumentIngestionService`
- repository port

下一步新增：

- `DocumentParserSelector`
- `Chunker`
- `Indexer`
- `QueryRewriteService`
- `RetrievalEngine`
- `SearchChannel`
- `SearchResultPostProcessor`
- `ConversationMemoryService`
- `PromptService`

### 6. `biz`

`biz` 未来应该是应用层，而不是技术分层垃圾桶。

目标包结构：

```text
biz
`- src/main/java/com/yulong/chatagent/biz
   |- conversation
   |  |- controller
   |  |- application
   |  `- adapter
   |- knowledge
   |  |- controller
   |  |- application
   |  `- adapter
   |- agentadmin
   |  |- controller
   |  |- application
   |  `- adapter
   `- support
      |- converter
      |- mapper
      |- entity
      `- typehandler
```

核心原则：

- `biz` 对外暴露 controller
- `biz` 编排 use case
- `biz` 负责事务边界
- `biz` 暂时承接 MyBatis adapter
- `biz` 不再放 Agent 核心算法
- `biz` 不再放 RAG 核心流程

## 三、模块依赖规则

建议最终依赖方向：

```text
app -> framework, infra, agent, rag, biz
biz -> framework, infra, agent, rag
agent -> framework, infra, rag
rag -> framework, infra
infra -> framework
framework -> none
```

强约束：

- 不允许 `agent` 依赖 `biz`
- 不允许 `rag` 依赖 `biz`
- 不允许 `infra` 依赖 `agent/rag/biz`
- 不允许 `framework` 依赖任何业务模块

## 四、现有类怎么迁

### 第一批：优先收敛横切能力

- `biz/service/impl/SseServiceImpl` -> 保留会话管理在 `biz`，但抽出底层 `SseEmitterSender` 到 `framework`
- `framework/model/common/ApiResponse` -> 升级为统一 `Result`
- `framework/exception/BizException` -> 拆成 `ClientException/ServiceException/RemoteException`

### 第二批：把 `infra` 做实

- `infra/config/ChatClientRegistry` -> 退化为底层 registry，不再直接给业务用
- `infra/config/MultiChatClientConfig` -> 继续保留 provider bean 装配
- 新增 `infra/llm/RoutingLLMService`
- 新增 `infra/embedding/EmbeddingService`
- 新增 `infra/rerank/RerankService`

### 第三批：把 `rag` 做成两条链

- `rag/service/impl/RagServiceImpl` -> 拆成 `EmbeddingService` 调用 + `RetrievalEngine`
- `rag/service/impl/MarkdownParserServiceImpl` -> 升级为 parser selector 的一个实现
- `rag/service/impl/DocumentStorageServiceImpl` -> 作为 ingestion 基础设施继续保留
- `rag/service/impl/DocumentIngestionServiceImpl` -> 拆成 parser/chunker/indexer 三段
- `biz/rag/repository/MyBatisKnowledgeChunkSearchRepository` -> 继续作为 repository adapter 保留在 `biz`

### 第四批：重构 Agent runtime

- `agent/ChatAgent` -> 拆成 orchestration / planning / execution
- `biz/agent/ChatAgentFactory` -> 改成 `AgentRuntimeAssembler`
- `biz/agent/AgentMessageBridgeImpl` -> 保留在 `biz`，实现 `agent` 定义的 bridge port

### 第五批：把 `biz` 从技术分层改成业务纵切

- `controller/service/model/converter/mapper` 目录结构逐步淡出
- 先改为 `conversation`、`knowledge`、`agentadmin`
- 每个业务域内部再放 `controller / application / adapter`

## 五、推荐迁移顺序

### Phase 1：边界收紧

目标：

- 先让 `framework` 和 `infra` 不再是空心模块
- 让 `biz` 不再承接底层共性能力

做法：

- 增加错误码、trace、sse sender
- 配置全部环境变量化
- 去掉硬编码 provider 依赖入口

### Phase 2：RAG pipeline 骨架

目标：

- 从 service 升级为 pipeline

做法：

- 先补 parser selector
- 再补 chunker/indexer
- 再补 retrieval engine / search channel / postprocessor

### Phase 3：Agent orchestration 重构

目标：

- 把 `ChatAgent` 从单类状态机拆开

做法：

- 先拆 planning
- 再拆 tool execution
- 最后收敛 memory/runtime/session

### Phase 4：`biz` 业务纵切

目标：

- 让 `biz` 变成应用层

做法：

- 优先重构 `conversation`
- 再重构 `knowledge`
- 最后重构 `agentadmin`

### Phase 5：平台化能力

目标：

- 补齐企业化能力

做法：

- 鉴权
- 限流
- trace
- 管理后台
- MCP

## 六、最值得马上动手的三件事

如果只做最小闭环，我建议马上做这三件：

1. 先把 `framework` 补成真正的横切底座，优先做 `error + trace + sse`
2. 先把 `rag` 从 `RagServiceImpl` 拆出 `RetrievalEngine`
3. 先把 `ChatAgent` 拆成 `orchestrator + planning + execution`

这三步做完，整个后端就会从“拆了模块名”进入“开始有稳定架构”的阶段。

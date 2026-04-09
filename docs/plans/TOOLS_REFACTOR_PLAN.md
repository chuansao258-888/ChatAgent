# ChatAgent 混合工具架构与 MCP 接入计划 (V3.0 - 基于现有工具链基座的无损加固版)

## 1. 背景与目标

本计划旨在通过引入 **MCP (Model Context Protocol)** 协议，使 `ChatAgent` 能够安全、动态地接入外部第三方工具（如全网搜索、天气、GitHub 等），同时确保系统核心能力的稳固与治理体系的统一。

经过多轮对抗性审查，V3.0 版本确立了**“基座不动、插件适配、治理统一”**的核心原则。

---

## 2. 现状盘点与核心工具基准 (Actual Baseline)

重构必须基于真实的运行时现状。以下是当前系统中真正受管且作为 Spring Bean 存在的核心原生工具（Local Tools）基准：

1. **`SessionFileTools` (RAG 核心检索)**: 负责直连 VDP、向量检索与 Reranker。产生巨大 Context 流转，必须保留在本地进程。
2. **`TerminateTool`**: 状态为 **FIXED** 的 Spring Bean。作为 Agent 停止思考循环的核心元信号，必须原样保留在运行时基座中。
3. **`DataBaseTools` (OPTIONAL)**: 探查业务库执行 SQL。出于核心数据隔离、连接池复用与性能要求，保留在本地。
4. **`EmailTools` (OPTIONAL)**: 执行侧工具，涉及内部 SMTP 配置与限流，保留在本地。

---

## 3. MCP 工具治理面与身份模型 (Governance)

为了防止 MCP 工具游离于体系之外，必须将其深度集成进现有的管理与权限模型中。

### 3.1 动态发现与全局唯一命名 (防止模型侧碰撞)
1. **`t_mcp_server`**: 存储外部 Server 的 SSE/HTTP 端点、认证信息及状态。
2. **`t_mcp_tool_catalog`**: 存储同步回来的工具元数据。
3. **防碰撞策略**: 当前运行时按 `ToolDefinition.name()` 去重。若两个 Server 均提供 `get_weather`，会导致静默覆盖。
   * **落地方案**：入库时自动生成**全局唯一模型函数名**（如 `mcp_server1_get_weather`），以此覆写原始 Schema 喂给大模型。
   * **RPC 映射**：`McpToolCallbackAdapter` 在发起真实调用前，负责将唯一名还原回远端识别的原始名。

### 3.2 治理面集成 (`ToolFacadeService` 改造)
管理端接口 `/api/tools` 的数据源目前仅来自 `List<Tool>` 注入。
* **重构逻辑**：改造 `ToolFacadeServiceImpl`。不仅依赖 Spring Beans，还要注入动态加载逻辑，从 DB 查出启用的 MCP 工具并封装为 `McpToolWrapper`（实现 `Tool` 接口，标记为 `OPTIONAL`）。
* **结果**：MCP 工具将自动出现在 Intent Tree 的 `allowedTools` 勾选框中，与本地工具在治理逻辑上完全对等。

### 3.3 ToolContext 的生产与透传 (核心补丁)
目前 `AgentToolExecutionEngine` 触发工具执行时并未携带上下文。
*   **改造点**：修改 `AgentToolExecutionEngine.execute()`。在构建 `Prompt` 时，通过 `ChatOptions.builder().toolContext(...)` 注入包含 `userId` 和 `sessionId` 的 `ToolContext`。
*   **作用**：这确保了上下文能一路流转到 `McpToolCallbackAdapter.call()` 方法中，供远端 RPC 调用时进行身份识别或鉴权。

### 3.4 传输层决策 (Transport Decision)
为了保持项目轻量级并避免引入不成熟的第三方 SDK，V1 决定采用 **“自研轻量级 JSON-RPC over HTTP/SSE 客户端”**。
*   利用 Spring 自带的 `WebClient` 或 `RestTemplate` 实现标准的 MCP `tools/list` 和 `tools/call` 协议。
*   封装基础的 MCP 消息报文对象（Request/Response/Notification）。

---

## 4. 运行时适配层 (The Adapter Contract)

必须严格遵循 Spring AI 的 `ToolCallback` 契约，确保上下文（User/Session Context）能正确透传。

```java
public class McpToolCallbackAdapter implements ToolCallback {
    private final String mcpServerId;
    private final String remoteOriginalName; // 远端真实的函数名
    private final ToolDefinition schema;     // 持有覆写后的全局唯一名

    public McpToolCallbackAdapter(String mcpServerId, String remoteOriginalName, ToolDefinition schema) {
        this.mcpServerId = mcpServerId;
        this.remoteOriginalName = remoteOriginalName;
        this.schema = schema;
    }

    @Override
    public ToolDefinition getToolDefinition() { return this.schema; }

    @Override
    public String call(String jsonArguments, ToolContext context) {
        // 使用 remoteOriginalName 发起 RPC，确保远端 Server 认得这个函数
        return executeRemoteCall(this.remoteOriginalName, jsonArguments, context);
    }
    // ...
}
```

---

## 5. 失败语义与稳定性矩阵 (Resilience)

远程 RPC 是脆弱的，不能让单个外部工具故障摧毁整轮会话。

1. **执行边界隔离**: Adapter 的 `call` 方法内部必须捕获所有 RPC 异常（超时、熔断、认证失败）。
2. **工具级报错 (Non-Breaking)**: 不要直接抛出未受检异常导致整轮进入 `ChatEventProcessor.publishFailure`。应当返回标准化的**错误 JSON 报文**（如 `{"error": "Remote tool timeout"}`）给模型。
3. **模型自主恢复**: 这种方式允许大模型在 ReAct 循环中感知到“这个工具暂时不可用”，从而选择使用其他工具（如 RAG 兜底）或直接告知用户，实现局部平滑降级。
4. **性能保护**: 强制挂载 Resilience4j 断路器，防止某个已崩溃的公网 MCP Server 持续阻塞 Agent 的主执行线程。

---

## 6. 实施分期 (Phases)

**Phase 1: 管理面升级与上下文生产端改造**
- 数据库建表，改造 `ToolFacadeService` 聚合动态工具。
- 修改 `AgentToolExecutionEngine`，实现 `ToolContext` 的创建与注入（生产端落地）。
- 实现 `/api/admin/mcp-servers` 同步与全局唯一名生成逻辑。

**Phase 2: ToolCallback 适配器与上下文透传**
- 编写符合契约的 `McpToolCallbackAdapter`。
- 修改 `AgentToolCallbackFactory`，确保能正确加载并注入动态生成的适配器。

**Phase 3: 客户端传输层实现与熔断容错**
- 基于 `WebClient` 实现轻量级 JSON-RPC over HTTP/SSE 传输层（Transport）。
- 实现基于 `remoteOriginalName` 的远程工具调用。
- 引入断路器与超时保护，验证远程工具失败时的局部降级行为。

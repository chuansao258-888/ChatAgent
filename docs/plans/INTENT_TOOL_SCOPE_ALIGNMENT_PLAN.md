# ChatAgent Tool Scope 对齐 KB 语义的小型重构方案

## 1. 背景

当前 `ChatAgent` 对 **KB** 和 **Tool** 采用了两套不同的运行时语义：

1. **KB**：Agent 自带默认知识库；命中 `KB` intent 时优先使用 `scopedKbIds`，并可按 `scopePolicy` 回退到 Agent 默认知识库。
2. **Tool**：只有命中 `TOOL` intent 时，`optional tools` 才会暴露给模型；否则只保留 `FIXED` 工具。

这导致一个明显的产品割裂：

1. MCP 工具即使已经 `test/sync` 成功，也不会被真实聊天使用。
2. 管理员必须再补一棵 `intent tree`，否则工具对模型完全不可见。
3. 与 KB 的"默认可用 + intent 收窄"心智模型不一致。

用户侧的自然预期通常是：

1. Agent 配置了哪些工具，这些工具默认就应可用。
2. Intent Tree 的作用应更接近"收窄边界、优化路由"，而不是"没配就完全禁用能力"。

---

## 2. 目标

本次重构的目标不是推翻当前 `intent tree` 设计，而是把 **Tool 的运行时语义对齐到 KB 的继承式模型**：

1. **`allowedTools` 为空时自动继承全部可选工具**（含 MCP），无需手动配置。
2. **`allowedTools` 非空时作为白名单**，仅暴露列表中的工具。
3. **Intent Tree 仅负责收窄工具边界，不再作为 optional tools 的硬门禁**。
4. **MCP 工具在 `test/sync` 后，只要 rollout policy 允许，即可参与真实聊天**。
5. 保留 `TOOL` intent 的价值，但其角色从"能力开关"降级为"工具作用域约束器"。

---

## 3. 当前实现现状

### 3.1 运行时硬门禁

当前 `AgentToolCallbackFactory` 的核心逻辑是：

1. 先放入 `FIXED` 工具。
2. 如果 `intentResolution == null`，则按 `agent.allowedTools` 暴露 Agent 默认 optional 工具池。
3. 如果 `intentResolution != null && intentResolution.kind() != TOOL`，直接返回，仅保留 `FIXED` 工具。
4. 只有命中 `TOOL` intent 时，才会从 `agent.allowedTools` 中追加 `OPTIONAL` 工具。

这意味着：

1. 只要进入 `intent` 路由且结果不是 `TOOL`，optional tools 就会被整体挡掉。
2. MCP 工具虽然被同步进目录，但一旦命中 `KB` 或普通业务 intent，运行时仍对模型不可见。
3. **`allowedTools` 为空时，不加载任何 optional 工具**，导致管理员必须手动配置才能让 MCP 工具生效。

### 3.2 Intent Tree 对 tools 的写入语义

`IntentTreeFacadeServiceImpl` 中：

1. 只有 `TOPIC + TOOL` 节点才允许保存 `allowedTools`。
2. 非 `TOOL` 节点会被强制写成空列表。

这个约束本身没有问题，但与当前运行时硬门禁组合后，等价于：

1. **不走 `TOOL` intent，就不可能使用 optional tools**。

---

## 4. 目标语义

重构后的运行时规则定义如下：

### 4.1 基础规则

1. `FIXED` 工具始终保留。
2. Agent 的 `allowedTools` 作为**默认可选工具池**；**为空时自动继承全部可选工具**（含 MCP）。
3. `SessionFileSearchTool` 的现有特殊过滤**保持不变**：仅 `KB` intent 可见；非 `KB` intent 仍过滤掉该工具。
4. 若没有命中 intent，则使用全部可选工具。
5. 若命中的是 `KB` intent 或普通业务 intent，也仍然使用全部可选工具。
6. `intent` 对 tool 的作用仅限于 **收窄**，不得越权授予 Agent 默认池中不存在的工具。
7. 若命中的是 `TOOL` intent 且节点配置了 `allowedTools`，则对 Agent 默认工具池做 **交集收窄**。
8. 若命中的是 `TOOL` intent 但 `allowedTools` 为空，则 **继承全部可选工具**。
9. `SYSTEM` intent 仍保持当前行为，直接走模板/系统直答，不进入工具决策链。

### 4.2 运行时效果

| 场景 | 现状 | 重构后 |
|------|------|--------|
| 未命中 intent | `FIXED` + Agent 默认工具池 | `FIXED` + 全部可选工具（含 MCP） |
| 命中 `KB` intent | `FIXED` + `SessionFileSearchTool` | `FIXED` + `SessionFileSearchTool` + 全部可选工具 |
| 命中普通业务 intent | 仅 `FIXED` 工具 | `FIXED` + 全部可选工具（`SessionFileSearchTool` 被过滤） |
| 命中 `TOOL` intent，且配置工具子集 | `FIXED` + 子集 | `FIXED` + Agent 默认工具池与子集的交集 |
| 命中 `TOOL` intent，但未配置 `allowedTools` | `FIXED` | `FIXED` + 全部可选工具 |
| 命中 `SYSTEM` intent | 不进入 Agent tool loop | 保持不变 |

注：

1. `allowedTools` 为空时表示"全部可用"，这是本次重构的核心语义变化。
2. 这意味着 MCP 工具 sync 后无需管理员手动配置即可被模型使用。
3. 管理员仅需在需要**收窄**工具范围时才填写 `allowedTools`。

---

## 5. 非目标

本次重构刻意保持轻量，不做以下扩展：

1. **不改 DB 表结构**。
2. **不新增 `tool_scope_policy` 字段**。
3. **不允许非 `TOOL` 节点显式配置 `allowedTools`**。
4. **不改变 MCP 管理面、Tool Catalog、McpToolWrapper、McpToolCallbackAdapter 的现有结构**。
5. **不改变 KB 作用域逻辑**。
6. **不新增 DB 字段或治理面字段**。

---

## 6. 具体改动点

### 6.1 `AgentToolCallbackFactory`

改造原则：

1. 先构建 `baseRuntimeTools = FIXED + 全部可选工具`。
2. 保留现有 `SessionFileSearchTool` 的特殊过滤：非 `KB` intent 仍移除该工具。
3. `appendOptionalTools` 核心语义变化：`allowedTools` 为空时从"无工具"变为"全部工具"。
4. 仅当 `intentResolution.kind() == TOOL` 且 `intentResolution.allowedTools()` 非空时，再对 Agent 默认工具池做交集收窄。
5. 移除"`intent.kind != TOOL` 就只返回 FIXED"的硬门禁。
6. 移除 `AliasToolCallback` 内部类（此前造成 `SessionFileSearchToolTool` 双 Tool 后缀）。

`appendOptionalTools` 核心逻辑：

```java
Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
        .stream()
        .collect(Collectors.toMap(Tool::getName, Function.identity()));

// Empty allowedTools means "inherit all"
Collection<String> effectiveNames = (allowedToolNames == null || allowedToolNames.isEmpty())
        ? optionalToolMap.keySet()
        : allowedToolNames;

for (String toolName : effectiveNames) {
    Tool tool = optionalToolMap.get(toolName);
    if (tool != null && isRuntimeAllowed(tool, agentConfig)) {
        runtimeTools.add(tool);
    }
}
```

`intersectAllowedTools` 的目标语义：

```java
if (intentAllowedTools == null || intentAllowedTools.isEmpty()) {
    return List.of();
}
if (agentAllowedTools == null || agentAllowedTools.isEmpty()) {
    return List.of();
}
return agentAllowedTools.stream()
        .filter(intentAllowedTools::contains)
        .toList();
```

### 6.2 `DefaultAgentRuntimeContextLoader`

改造要求：

1. `[Intent Routing Context]` 中对 `Allowed business tools` 的文案要从"绝对唯一边界"调整为"intent 收窄后的工具边界"。
2. 当 intent 未显式收窄 tools 时，不要误导模型以为"没有 allowed tools 就不能调工具"。
3. 硬编码的边界约束句改为按收窄条件分支输出。

文案矩阵：

1. `allowedTools` 非空 → 输出 `Intent-narrowed tools` + "Do not call tools outside the resolved intent boundary"
2. `scopedKbIds` 非空、`allowedTools` 为空 → 输出 `Scoped knowledge bases` + "Prioritize retrieval within the resolved KB scope"
3. `scopedKbIds` 与 `allowedTools` 都为空 → 不输出额外的边界硬约束句

### 6.3 Admin / UI 文案

1. `Allowed tools` 的含义改为"可选收窄范围"。
2. 留空不再表示"禁用全部工具"，而表示"继承全部可选工具"。

### 6.4 Intent Tree 配套交互修复

#### A. `Examples` 当前几乎不可用（P1）

- 改为 `Input.TextArea`，约定一行一个 example。
- 保存时把多行文本拆成 `string[]`。

#### B. `Intent Tree` 视觉层级与协调性不足（P2）

- 强化层级缩进与连接线。
- 收窄选中节点的背景高亮范围。
- 调整标签密度和描述间距。

### 6.5 删除模拟工具

删除 `agent/tools/test/` 目录下的 `WeatherTool`、`CityTool`、`DateTool`。它们是 `FIXED` 类型的硬编码 mock，模型每次都优先调用它们，导致 MCP 工具无法被选中。

### 6.6 跨 Session 数据泄漏修复

1. `ChatAgent.run()` finally 中补充 `CurrentIntentResolutionHolder.clear()`，防止上一个 session 的 KB scope 残留。
2. `CurrentTurnCitationHolder` 新增 `clearBySession()` 方法，作为安全网在 `ChatEventProcessor` finally 中调用。
3. 前端 `AgentChatView` 新增 `useEffect([chatSessionId])` 在 session 切换时清空消息状态。
4. 前端 `EmptyAgentChatView` 复用缓存 session ID 前验证是否仍存在于 session 列表。

---

## 7. 兼容性与风险

通过 feature flag `chatagent.intent.tool-scope-mode` 控制灰度：

| 值 | 行为 |
|---|---|
| `STRICT_TOOL_ONLY` | 完全保留旧行为（硬门禁 + `allowedTools` 空值 = 无工具） |
| `AGENT_DEFAULT_WITH_INTENT_NARROWING` | 新行为（`allowedTools` 空值 = 全部工具，Intent 仅收窄） |

默认值已切换为 `AGENT_DEFAULT_WITH_INTENT_NARROWING`。

主要风险：

1. **工具暴露面变宽**——更多场景下模型能看到 MCP 工具。
2. 缓解措施：MCP rollout policy 仍按 `chatagent.mcp.rollout.mode` 控制哪些 Agent 可用 MCP；`SessionFileSearchTool` 仍仅对 KB intent 可见。

---

## 8. 测试清单

### 8.1 单测

围绕 `AgentToolCallbackFactory` 新增或调整以下测试：

1. 无 intent 时，返回 `FIXED + agent.allowedTools`。
2. `KB` intent 时，返回 `FIXED + SessionFileSearchTool + agent.allowedTools`。
3. 非 `KB` intent 下，`SessionFileSearchTool` 仍被过滤。
4. `TOOL` intent 且 `allowedTools` 非空时，返回交集后的 optional tools。
5. `TOOL` intent 且 `allowedTools` 为空时，继承 Agent 默认工具池。
6. Agent 默认池为空 + `TOOL` intent 声明了工具时，返回仅 `FIXED`，intent 不得越权授予。
7. 交集语义：Agent 默认池 = `[a,b,c]`，`TOOL` intent = `[b,d]`，结果必须为 `[b]`。
8. rollout policy 不允许 MCP 时，MCP tool 仍被正确挡掉。
9. feature flag = `STRICT_TOOL_ONLY` 时，行为与当前实现完全一致。
10. prompt 文案矩阵验证。

### 8.2 集成验证

1. 不配置 `intent tree` 的情况下，只给 Agent sync 一个 MCP weather tool，验证真实聊天可调用。
2. 配置 `TOOL` intent 收窄到 weather 子集，验证只暴露被选中的工具。
3. 命中 `SYSTEM` intent 时，验证仍不会进入工具链。
4. `KB` intent 命中时，weather 等 MCP tool 默认可见，但模型仍优先走知识库检索。
5. `Examples` 输入控件可稳定录入、编辑和回显多条示例。
6. `Intent Tree` 页面三层结构下保持清晰层级。
7. 新 session 不会出现旧 session 的 Sources 或工具调用记录。

---

## 9. 推荐实施顺序

1. 改 `appendOptionalTools` 的空值语义（空 = 全部）。
2. 改 `AgentToolCallbackFactory` 运行时分发逻辑。
3. 修正 `intersectAllowedTools` 的越权授予语义。
4. 改 `DefaultAgentRuntimeContextLoader` 提示词文案。
5. 修复 `ChatAgent.run()` finally 中 `CurrentIntentResolutionHolder` 泄漏。
6. 新增 `CurrentTurnCitationHolder.clearBySession()` 安全网。
7. 修复前端 `AgentChatView` 跨 session 消息残留。
8. 修复前端 `EmptyAgentChatView` 缓存 session 验证。
9. 删除模拟工具（WeatherTool、CityTool、DateTool）。
10. 移除 `AliasToolCallback` 双 Tool 后缀。
11. 同步修复 `IntentNodeEditDrawer` 的 `Examples` 录入交互。
12. 补充单测与 MCP 冒烟用例。
13. 调整 `IntentTreeViewer` 和相关 admin UI 文案。
14. 切换 `application.yaml` 默认值为 `AGENT_DEFAULT_WITH_INTENT_NARROWING`。

---

## 10. 预期收益

完成后，这套系统的能力模型会变得统一且自动化：

1. **KB**：Agent 默认可用，intent 负责收窄。
2. **Tool**：Agent 默认可用（`allowedTools` 为空 = 全部），intent 负责收窄。
3. **MCP**：sync 后自动对模型可见，无需手动配置 `allowedTools`。

直接收益：

1. MCP 工具接入后的首次可用性显著提升——sync 后即用。
2. `intent tree` 更接近"执行策略收窄器"，而不是工具硬开关。
3. 管理员的认知负担降低，不再需要"手动配 allowedTools 才能让工具出现"。
4. 跨 session 数据泄漏已修复（ThreadLocal 清理、Citation 安全网、前端状态隔离）。

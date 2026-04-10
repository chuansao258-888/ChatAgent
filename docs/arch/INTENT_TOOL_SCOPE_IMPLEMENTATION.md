# Tool Scope 继承式对齐 架构实现文档

> 对应计划：`docs/plans/INTENT_TOOL_SCOPE_ALIGNMENT_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

将 Tool 的运行时语义对齐到"默认全部可见 + Intent 可选收窄"的继承式模型，使 MCP 工具 sync 后即用。

- **改造前：** Agent 需手动配置 `allowedTools`，为空则无可选工具；只有命中 `TOOL` intent 时 optional tools 才会暴露。
- **改造后：** `allowedTools` 为空时**自动继承全部可选工具**（含 MCP）；Intent 仅负责可选收窄，不再作为硬门禁。

### 1.2 核心设计决策

1. **`allowedTools` 空值语义反转**：空 = 全部可用（而非无工具）。
2. **Feature Flag 灰度**：`chatagent.intent.tool-scope-mode` 控制新旧行为切换。
3. **Intent 仅收窄不授予**：Intent 不得越权授予 Agent 默认池中不存在的工具。
4. **跨 Session 泄漏修复**：ThreadLocal 和 Citation 在 finally 中清理。

---

## 1. Feature Flag 与枚举

### 1.1 `IntentToolScopeMode` 枚举（新增文件）

```
chatagent/bootstrap/src/main/java/com/yulong/chatagent/intent/model/IntentToolScopeMode.java
```

```java
public enum IntentToolScopeMode {
    STRICT_TOOL_ONLY,
    AGENT_DEFAULT_WITH_INTENT_NARROWING
}
```

### 1.2 `application.yaml` 配置绑定

```yaml
chatagent:
  intent:
    tool-scope-mode: ${CHATAGENT_INTENT_TOOL_SCOPE_MODE:AGENT_DEFAULT_WITH_INTENT_NARROWING}
```

默认值已从 `STRICT_TOOL_ONLY` 切换为 `AGENT_DEFAULT_WITH_INTENT_NARROWING`。

`AgentToolCallbackFactory` 构造函数通过 `@Value` 注入：

```java
public AgentToolCallbackFactory(ToolFacadeService toolFacadeService,
                                McpRolloutPolicy rolloutPolicy,
                                @Value("${chatagent.intent.tool-scope-mode:AGENT_DEFAULT_WITH_INTENT_NARROWING}")
                                IntentToolScopeMode toolScopeMode)
```

---

## 2. `AgentToolCallbackFactory` 运行时逻辑

```
chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/AgentToolCallbackFactory.java
```

### 2.1 入口方法：`resolveRuntimeTools`

```
resolveRuntimeTools(agentConfig, intentResolution)
  │
  ├─ runtimeTools = FIXED 工具（来自 ToolFacadeService）
  │
  ├─ SessionFileSearchTool 过滤：
  │    intentResolution != null 且 kind != KB 时移除 SessionFileSearchTool
  │
  ├─ allowedToolNames = agentConfig.getAllowedTools()
  │
  ├─ if STRICT_TOOL_ONLY
  │    → resolveRuntimeToolsStrict(...)
  │
  └─ if AGENT_DEFAULT_WITH_INTENT_NARROWING
       → resolveRuntimeToolsWithIntentNarrowing(...)
```

### 2.2 `appendOptionalTools` 核心语义变化

**关键改动：`allowedTools` 为空时从"无工具"变为"全部工具"。**

```java
private void appendOptionalTools(List<Tool> runtimeTools,
                                 List<String> allowedToolNames,
                                 AgentDTO agentConfig) {
    Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
            .stream()
            .collect(Collectors.toMap(Tool::getName, Function.identity()));

    // Empty allowedTools means "inherit all" — every optional tool is visible by default.
    // A non-empty list narrows the pool to the named subset only.
    Collection<String> effectiveNames = (allowedToolNames == null || allowedToolNames.isEmpty())
            ? optionalToolMap.keySet()
            : allowedToolNames;

    for (String toolName : effectiveNames) {
        Tool tool = optionalToolMap.get(toolName);
        if (tool != null && isRuntimeAllowed(tool, agentConfig)) {
            runtimeTools.add(tool);
        }
    }
}
```

| `allowedTools` 值 | 旧行为 | 新行为 |
|---|---|---|
| `null` 或 `[]` | 不加载任何 optional 工具 | **加载全部** optional 工具（含 MCP） |
| `["toolA", "toolB"]` | 仅加载 A、B | 仅加载 A、B（不变） |

这意味着：
- MCP 工具 sync 成功后自动对模型可见，**无需手动配置 Agent 的 `allowedTools`**。
- 管理员仅在需要**收窄**工具范围时才填写 `allowedTools`。

### 2.3 AGENT_DEFAULT_WITH_INTENT_NARROWING 模式（`resolveRuntimeToolsWithIntentNarrowing`）

| 条件 | 行为 |
|------|------|
| `intentResolution == null` | 追加全部可选工具 |
| 任何非 TOOL intent（KB、CLARIFY 等） | 追加全部可选工具 |
| TOOL intent + `allowedTools` 非空 | 通过 `intersectAllowedTools` 做**交集收窄** |
| TOOL intent + `allowedTools` 为空 | 追加全部可选工具（继承默认池） |

### 2.4 运行时效果矩阵（新模式）

| 场景 | 返回的工具集 |
|------|-------------|
| 未命中 intent | FIXED + 全部可选工具 |
| 命中 KB intent | FIXED + SessionFileSearchTool + 全部可选工具 |
| 命中普通业务 intent（CLARIFY 等） | FIXED + 全部可选工具（SessionFileSearchTool 被过滤） |
| 命中 TOOL intent，且配置了工具子集 | FIXED +（Agent 默认池 ∩ 子集） |
| 命中 TOOL intent，但 `allowedTools` 为空 | FIXED + 全部可选工具 |
| 命中 SYSTEM intent | 不进入此工厂（上游已拦截） |

### 2.5 已移除的代码

以下代码在本次重构中已被删除：

- **`AliasToolCallback` 内部类**：此前为以 "Tool" 结尾的工具名创建 `NameToolTool` 格式的别名（如 `SessionFileSearchToolTool`），造成模型混淆，已移除。
- **`registerCallback` 中的别名逻辑**：移除 `if (name.endsWith("Tool"))` 分支，现在只注册原始工具名。
- **测试中 `callbackNames` 的 `ToolTool` 过滤**：`filter(name -> !name.endsWith("ToolTool"))` 已移除。

---

## 3. 跨 Session 数据泄漏修复

### 3.1 `CurrentIntentResolutionHolder` 未在 `ChatAgent.run()` finally 中清理

**文件：** `ChatAgent.java`

之前 finally 块只清了 `CurrentTurnKnowledgeHitHolder`、`CurrentTurnHolder`、`CurrentChatSessionHolder`，漏了 `CurrentIntentResolutionHolder`。如果 `ChatAgent.run()` 抛异常，上一个 session 的 intent resolution（含 `scopedKbIds`）会残留到同一线程的下一个请求。

修复：在 finally 中加入 `CurrentIntentResolutionHolder.clear()`。

### 3.2 `CurrentTurnCitationHolder` 添加 session 级清理

**文件：** `CurrentTurnCitationHolder.java` + `ChatEventProcessor.java`

单例 `ConcurrentHashMap`（`citationsByTurn`）此前只按 turn 清理。如果 agent 运行异常退出，对应 turn 的 citations 永远残留。

新增 `clearBySession(String sessionId)` 方法：

```java
public void clearBySession(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
        return;
    }
    String prefix = sessionId.trim() + "::";
    citationsByTurn.keySet().removeIf(k -> k.startsWith(prefix));
}
```

在 `ChatEventProcessor` 的 finally 中作为安全网调用。

### 3.3 前端跨 Session 消息残留

**文件：** `AgentChatView.tsx`

`AgentChatView` 是同一组件实例跨越 `/chat` 和 `/chat/:id` 路由，`messages` 状态在 `chatSessionId` 变为 `undefined`（回到主页）时未被清空。切换到新 session 时 `mergeRealtimeMessages` 会把新消息合并到旧消息上。

修复：新增 `useEffect([chatSessionId])` 在 session 切换时立即清空 `messages`、`sessionFiles`、`persistentErrorText`、`retryMessage`。

### 3.4 前端 `pendingSessionIdRef` 缓存残留

**文件：** `EmptyAgentChatView.tsx`

`pendingSessionIdRef` 缓存创建过的 session ID，用户在侧边栏删除该 session 后 ref 没有清除，下次提问时复用已删除的 session ID。

修复：复用缓存 ID 前检查 `chatSessions` 列表中是否仍存在，不存在则清除 ref。

---

## 4. 删除的模拟工具

```
chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/tools/test/  （整个目录已删除）
├── WeatherTool.java   — 硬编码天气 mock，与 MCP weather tool 冲突
├── CityTool.java      — 硬编码返回 "Shenzhen"
└── DateTool.java      — 返回当前日期
```

三个均为 `ToolType.FIXED`，模型每次对话都能看到并优先调用，导致 MCP 工具虽然已 sync 但实际上没机会被选中。删除后模型转而使用 MCP 提供的真实工具。

---

## 5. `DefaultAgentRuntimeContextLoader` 提示词组装

```
chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/DefaultAgentRuntimeContextLoader.java
```

### 5.1 `[Intent Routing Context]` 区块

Intent 上下文区块改为条件式组装：

```
if (intentResolution != null) {
    输出: "- Intent kind: {kind}"
    输出: "- Intent path: {pathLabel}"  （如果存在）

    if (scopedKbIds 非空)
        输出: "- Scoped knowledge bases: {ids}"

    if (allowedTools 非空)
        输出: "- Intent-narrowed tools: {tools}"

    if (rewrittenInput 存在)
        输出: "- Search hint: {rewrittenInput}"

    appendIntentBoundaryInstructions(hasScopedKb, hasNarrowedTools)
}
```

### 5.2 边界约束指令矩阵（`appendIntentBoundaryInstructions`）

| 条件 | 输出内容 |
|------|----------|
| `hasNarrowedTools = true` | "Do not call tools outside the resolved intent boundary." + 可选 KB 提示 |
| `hasNarrowedTools = false`，`hasScopedKb = true` | "Prioritize retrieval within the resolved knowledge-base boundary." |
| 两者都为 false | **不输出**任何边界约束语句 |

---

## 6. UI 改动

### 6.1 `IntentNodeEditDrawer` — Examples 录入交互（P1）

**改造前：** `Select mode="tags"` — 交互体验差。

**改造后：** `Input.TextArea`，一行一条示例。

- 表单字段：`examplesText`（string 类型，非 string[]）
- 加载时：`(node?.examples ?? []).join("\n")`
- 保存时：`sanitizeExamplesText(value)` — 按 `\n` 拆分，trim 后过滤空行

### 6.2 `IntentNodeEditDrawer` — Allowed tools 文案

```
extra="Narrows which optional tools are exposed when this intent fires.
       Leave empty to inherit the agent's default tool pool."
```

### 6.3 `IntentTreePage` — 节点详情面板

| 状态 | 显示文案 |
|------|----------|
| 列表为空 | "Inherits the agent's default tool pool (no narrowing)." |
| 列表非空 | "Narrows the agent default pool to the intersection below." + 金色标签列表 |

### 6.4 `IntentTreeViewer` — 视觉层级优化（P2）

CSS 实现细节：

- **连接线：** `border-left: 1px dashed rgba(255,255,255,0.14)`
- **层级颜色：** DOMAIN 蓝、CATEGORY 紫、TOPIC 灰（`::before` 伪元素）
- **选中态：** `rgba(99,179,237,0.10)` 浅蓝背景（非整行铺满）

---

## 7. 测试覆盖

### 7.1 `AgentToolCallbackFactoryTest`

| 测试用例 | 模式 | 验证内容 |
|---------|------|---------|
| `shouldPreserveLegacyGrantSemanticsWhenScopeModeIsStrictToolOnly` | STRICT | 向后兼容：Agent 池空 + TOOL intent 可授予 intent 工具 |
| `shouldPreserveStrictToolOnlyBehaviorWhenKbIntentIsResolved` | STRICT | KB intent 仅 FIXED + SessionFileSearchTool |
| `shouldInheritAgentDefaultPoolWhenIntentIsAbsent` | NARROW | 无 intent → FIXED + Agent 默认池 |
| `shouldExposeAgentDefaultToolsForKbIntent` | NARROW | KB intent → FIXED + SessionFileSearchTool + Agent 默认池 |
| `shouldFilterSessionFileSearchToolOutsideKbIntent` | NARROW | 非 KB intent 移除 SessionFileSearchTool |
| `shouldNarrowToolIntentToIntersectionWithoutGrantingNewTools` | NARROW | Agent [A,B,C] ∩ Intent [B,D] = [B] |
| `shouldInheritAgentDefaultToolsWhenToolIntentLeavesAllowedToolsEmpty` | NARROW | TOOL intent + 空 allowedTools → 继承默认池 |
| `shouldNotGrantIntentToolsWhenAgentDefaultPoolIsEmpty` | NARROW | Agent 池空 → intent 不可越权授予 |
| `shouldHideMcpToolsWhenAgentIsOutsideRolloutAllowlist` | NARROW | Rollout policy 拦截 MCP |

### 7.2 `DefaultAgentRuntimeContextLoaderTest`

| 测试用例 | 验证内容 |
|---------|---------|
| `shouldInjectHistoricalSummaryBeforeIntentContext` | 区块顺序 |
| `shouldAppendMcpSafetyInstructionsWhenRuntimeIncludesMcpTools` | MCP 安全提示 |
| `shouldDescribeIntentNarrowedToolsWhenToolBoundaryExists` | "Intent-narrowed tools" 输出 |
| `shouldPreferKnowledgeBaseBoundaryMessageWhenOnlyKbScopeExists` | 仅 KB 收窄时文案 |
| `shouldOmitHardBoundaryInstructionWhenIntentDoesNotNarrowKbOrTools` | 无收窄时不输出约束 |

---

## 8. 文件变更清单

| 文件 | 变更类型 |
|------|---------|
| `intent/model/IntentToolScopeMode.java` | **新增** — 作用域模式枚举 |
| `agent/runtime/AgentToolCallbackFactory.java` | **修改** — 双模式分发、`allowedTools` 空值语义反转、移除 `AliasToolCallback` |
| `agent/runtime/CurrentTurnCitationHolder.java` | **修改** — 新增 `clearBySession()` 方法 |
| `agent/runtime/CurrentIntentResolutionHolder.java` | **未改** — 在 `ChatAgent.run()` finally 中加入清理调用 |
| `agent/ChatAgent.java` | **修改** — finally 中补充 `CurrentIntentResolutionHolder.clear()` |
| `agent/DefaultAgentRuntimeContextLoader.java` | **修改** — 条件式提示词组装 |
| `conversation/event/ChatEventProcessor.java` | **修改** — finally 中加入 `clearBySession()` 安全网 |
| `bootstrap/src/main/resources/application.yaml` | **修改** — 默认值改为 `AGENT_DEFAULT_WITH_INTENT_NARROWING` |
| `agent/tools/test/WeatherTool.java` | **删除** — 模拟天气工具，与 MCP 冲突 |
| `agent/tools/test/CityTool.java` | **删除** — 模拟城市工具 |
| `agent/tools/test/DateTool.java` | **删除** — 模拟日期工具 |
| `ui/.../AgentChatView.tsx` | **修改** — session 切换时清空消息状态 |
| `ui/.../EmptyAgentChatView.tsx` | **修改** — 复用缓存 session 前验证是否仍存在 |
| `ui/.../IntentNodeEditDrawer.tsx` | **修改** — Examples 改为 TextArea，文案对齐 |
| `ui/.../IntentTreeViewer.tsx` | **修改** — 树视觉层级 |
| `ui/.../IntentTreePage.tsx` | **修改** — 详情面板文案 |
| `ui/src/index.css` | **修改** — Intent Tree CSS 样式 |
| `test/.../AgentToolCallbackFactoryTest.java` | **修改** — 9 个测试用例 + 移除 ToolTool 过滤 |
| `test/.../DefaultAgentRuntimeContextLoaderTest.java` | **修改** — 5 个测试用例 |
| `test/.../McpEndToEndIntegrationTest.java` | **修改** — 使用 STRICT 模式验证向后兼容 |

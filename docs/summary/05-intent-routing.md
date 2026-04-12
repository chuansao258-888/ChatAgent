# 意图路由模块 (com.yulong.chatagent.intent)

## 模块概述

意图路由模块实现了**树状层级意图分类系统**，将用户的自然语言输入映射到预定义的意图树中，从而确定应该使用哪些知识库、工具或系统响应。这是编排层和执行层之间的**唯一数据契约**，决定了 Agent 的行为边界。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/intent/`

---

## 1. 总体架构

```
用户输入
    │
    ▼
ConversationTurnPreparationService.prepare()
    │
    ├── 检查待澄清状态 (Redis, 5分钟 TTL)
    │   └── ClarificationResolver.resolve() → 匹配用户回复
    │
    ├── IntentRouter.route() → 层级路由
    │   │
    │   ├── 加载快照 (Redis → DB, DefaultIntentTreeCacheManager)
    │   │
    │   └── 逐级下钻: DOMAIN → CATEGORY → TOPIC
    │       │
    │       ├── 启发式评分 (bigram Jaccard + 子串匹配)
    │       │   └── 高置信度直接通过 (score ≥ 1.2, gap > 0.5)
    │       │
    │       └── LLM 回退分类
    │           └── 返回 node ID / NONE / AMBIGUOUS
    │
    ├── 需要澄清 → PendingIntentResolutionStore.save()
    │   └── ClarificationResponseBuilder → 返回选项列表
    │
    ├── SYSTEM 意图 → SystemIntentResponseRenderer.render()
    │
    └── KB/TOOL 意图 → QueryRewriter.rewrite()
        └── TurnPreparationResult.dispatch(resolution, rewrittenInput)
```

---

## 2. 意图树结构

### 2.1 三级固定层级

```
DOMAIN (领域) ─── 最顶层分类，如 "人事管理"
    │
    ├── CATEGORY (类别) ─── 子分类，如 "请假制度"
    │       │
    │       ├── TOPIC (主题) ─── 叶子节点，承载意图元数据
    │       │   ├── intentKind: KB / TOOL / SYSTEM
    │       │   ├── scopePolicy: STRICT / FALLBACK_ALLOWED
    │       │   ├── allowedTools: [工具ID列表] (仅TOOL意图)
    │       │   ├── systemPromptOverride: 模板 (仅SYSTEM意图)
    │       │   └── 知识库绑定 (仅KB意图)
    │       │
    │       └── TOPIC ...
    │
    └── CATEGORY ...
```

**关键约束：**
- 只有 TOPIC 节点携带意图元数据
- DOMAIN 和 CATEGORY 是纯结构路由中间节点
- TOPIC 不能有子节点

### 2.2 版本化快照

| 版本 | 用途 |
|------|------|
| 版本 0 (DRAFT) | 管理员编辑区，所有增删改在版本0操作 |
| 版本 N (PUBLISHED) | 发布后的不可变快照，路由使用此版本 |

发布流程：深拷贝所有草稿节点到新版本号 → 重映射 parentId → 拷贝知识库绑定 → 更新 assistant 的 activeIntentVersion → 刷新缓存。

---

## 3. IntentRouter — 核心路由引擎

**文件：** `application/IntentRouter.java`

### 3.1 层级路由算法 (route, 第50-112行)

```java
route(agentId, query, selectedNodeId):
    1. 加载活跃快照
    2. 如果有 selectedNodeId (澄清恢复)：
       → 找到节点，计算路径，设为 current
    3. while(true) 逐级下钻：
       a. 获取当前层的候选兄弟节点
       b. 无子节点 → 叶子，返回结果
       c. 只有一个候选且无 intentKind → 自动下钻
       d. 多个候选 → select(query, candidates, pathLabel)
```

### 3.2 两阶段分类策略 (select, 第134-191行)

#### 阶段一：启发式评分 (score, 第241-276行)

评分函数考虑四个信号：

| 信号 | 权重 | 计算方式 |
|------|------|---------|
| 节点名称精确/子串匹配 | 1.2 | 包含关系 |
| 节点名称 Token 重叠 | 0.7 | bigram Jaccard |
| 节点描述 Token 重叠 | 0.4 | bigram Jaccard |
| 最佳匹配样例 | 1.0/0.6 | 子串/重叠 |

**bigram Jaccard 相似度 (overlapScore, 第278-292行)：**
- `splitUnits` 生成词级 token + 字符 bigram
- Jaccard = |A∩B| / |A∪B|

**高置信度快捷通道 (第150行)：**
- 最佳分数 ≥ 1.2 且与第二名差距 > 0.5 → 直接采用，不调用 LLM

#### 阶段二：LLM 回退 (callLlmClassifier, 第203-239行)

当启发式不确定时，调用 LLM 分类器：
- Prompt 包含候选列表（ID、名称、描述）+ 用户查询
- LLM 返回：特定节点 ID / "NONE" / "AMBIGUOUS"
- LLM 失败 → 纯启发式阈值判断

**纯启发式回退参数：**
- `minimumScore` = 0.45 (可配置 `chatagent.intent.minimum-score`)
- `ambiguityGap` = 0.2 (可配置 `chatagent.intent.ambiguity-gap`)

---

## 4. 澄清系统

### 4.1 澄清触发

当路由结果为 ambiguous（前两名分数接近）时：
1. 收集最多 `clarificationCandidateCount`（默认2，最小2）个候选
2. 保存 `PendingIntentResolution` 到 Redis（key: `chatagent:intent:pending:{sessionId}`, TTL: 5分钟）
3. 通过 `ClarificationResponseBuilder` 生成中文澄清提示

### 4.2 ClarificationResponseBuilder

**文件：** `application/ClarificationResponseBuilder.java`

```
构建的中文提示：
- 无候选项 → "刚才的候选项已失效，请重新描述一下你的问题。"
- 重试 → "我还没有识别出你想选哪一项，请直接回复序号或名称。"
- 首次 → "我需要先确认一下你的问题属于哪一类。"
  + 父路径上下文
  + 编号候选列表（名称 + 描述）
```

### 4.3 ClarificationResolver — 解析用户澄清回复

**文件：** `application/ClarificationResolver.java`

```
resolve(userInput, candidates):
    1. 标准化：去除中文前缀 ("选择", "选", "我要", "我选")，小写
    2. 序号解析：
       - 阿拉伯数字: regex (\d+)
       - 中文序号: "一"至"十"，支持 "第X", "第X个", "X个" 格式
    3. 名称匹配：标准化文本包含候选名称
```

---

## 5. 查询改写 (QueryRewriter)

**文件：** `application/QueryRewriter.java`

仅对 KB 意图生效（TOOL 和 SYSTEM 返回原始查询）。

```
rewrite(query, pathLabel):
    1. 构建 Prompt，指导 LLM：
       - 使用意图路径上下文扩展代词
       - 保留领域术语
       - 补充省略细节
    2. LLM 调用成功 → 返回改写后的查询
    3. LLM 调用失败 → 回退："{pathLabel} | {originalQuery}"
```

---

## 6. 意图解析契约 (IntentResolution)

**文件：** `application/IntentResolution.java`

这是**编排层和执行层之间的唯一数据契约**：

```java
public record IntentResolution(
    IntentKind kind,              // KB, TOOL, SYSTEM, CLARIFY
    List<IntentNodeDTO> path,     // 从根到叶的完整路径
    List<String> scopedKbIds,     // KB 意图绑定的知识库 ID
    ScopePolicy scopePolicy,      // STRICT 或 FALLBACK_ALLOWED
    List<String> allowedTools,    // TOOL 意图允许的工具
    String systemPromptOverride   // SYSTEM 意图的模板
)
```

| IntentKind | Agent 行为 |
|-----------|-----------|
| `KB` | 搜索知识库 + 生成回答 |
| `TOOL` | 执行指定工具集 |
| `SYSTEM` | 直接返回模板响应，不创建 Agent |
| `CLARIFY` | 返回澄清选项，等待用户回复 |

**ScopePolicy 语义：**
- `STRICT`：只使用意图绑定的知识库
- `FALLBACK_ALLOWED`：如果范围 KB 为空，扩展到助手级别的 KB

---

## 7. ConversationTurnPreparationService — 编排入口

**文件：** `application/ConversationTurnPreparationService.java`

`prepare(agentId, sessionId, userInput)` 是意图子系统的统一入口：

```
Step 1: 加载活跃快照 → 空则删除待澄清状态，返回 passthrough
Step 2: 检查待澄清状态
    → 候选项过期 → 返回过期提示
    → ClarificationResolver.resolve() → 匹配成功
        → 删除待澄清状态，从选中节点继续路由
    → 匹配失败 → 返回重试提示
Step 3: 无待澄清 → 从根路由
Step 4: 需要澄清 → 保存 PendingIntentResolution，返回澄清提示
Step 5: 无匹配 → dispatch(null, originalQuery)
Step 6: SYSTEM 意图 → SystemIntentResponseRenderer，返回直接回复
Step 7: KB/TOOL → QueryRewriter.rewrite() → dispatch(resolution, rewritten)
```

---

## 8. 缓存管理 (IntentTreeCacheManager)

**文件：** `application/DefaultIntentTreeCacheManager.java`

两层缓存（Redis + 数据库）：

```
loadActiveSnapshot(agentId):
    1. 获取 assistant 的 activeIntentVersion
    2. 检查 Redis 缓存 (chatagent:intent:tree:{agentId}:active)
    3. 版本匹配 → 返回缓存快照
    4. 版本不匹配或无缓存 → 从持久化加载：
       a. 查询 (agentId, version) 的 PUBLISHED + enabled 节点
       b. 加载知识库绑定
       c. 构建 IntentTreeSnapshot
       d. 写入 Redis (TTL: chatagent.intent.cache-ttl-minutes, 默认30分钟)
```

**版本号自动失效：** 缓存存储版本号，每次读取时校验。发布新版本后版本号变化，缓存自动失效。

---

## 9. Admin API

**IntentTreeController** (`/api/admin/assistant/intent-tree`, `@RequireRole(ADMIN)`)

| 方法 | 端点 | 操作 |
|------|------|------|
| GET | `/` | 获取草稿树 + 版本列表 |
| POST | `/nodes` | 创建草稿节点 |
| PATCH | `/nodes/{nodeId}` | 更新草稿节点 |
| DELETE | `/nodes/{nodeId}` | 删除草稿节点 + 子树 |
| PUT | `/nodes/{nodeId}/knowledge-bases` | 绑定知识库 |
| POST | `/publish` | 发布草稿为新版本 |
| GET | `/versions` | 列出已发布版本 |
| PUT | `/versions/{version}/activate` | 切换活跃版本 |

**树结构验证 (validateNodePlacement)：**
- 根节点必须为 DOMAIN 级别
- DOMAIN 子节点必须为 CATEGORY
- CATEGORY 子节点必须为 TOPIC
- TOPIC 不能有子节点
- 循环引用检查：不能移动到自己的后代下

---

## 10. 技术亮点总结

### 分层分类架构
- **三级固定层级：** DOMAIN → CATEGORY → TOPIC，避免扁平枚举
- **启发式优先：** bigram Jaccard + 子串匹配，高置信度不调用 LLM
- **LLM 回退：** 启发式不确定时才调用 LLM，节省成本和延迟

### 澄清系统
- **Redis 临时状态：** 5分钟 TTL，自动过期清理
- **多模态解析：** 阿拉伯数字、中文序号、名称匹配
- **上下文感知：** 追踪父路径，支持中继恢复

### 版本化快照
- **不可变发布：** 发布后快照不可修改，保证路由一致性
- **原子切换：** 版本切换通过更新 assistant 的 activeIntentVersion 实现
- **自动缓存失效：** 版本号校验机制

### 查询改写
- **意图上下文注入：** 利用意图路径扩展代词、补充省略信息
- **确定性回退：** LLM 失败时简单拼接路径和原始查询

### 唯一契约设计
- **IntentResolution** 是编排层和执行层之间唯一的共享数据结构
- 解耦两个子系统的实现细节
- 清晰的 IntentKind 分类决定 Agent 行为

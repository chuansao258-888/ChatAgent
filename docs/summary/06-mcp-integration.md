# MCP 集成模块 (com.yulong.chatagent.mcp)

## 模块概述

MCP (Model Context Protocol) 模块实现了对外部第三方工具的安全动态集成。它覆盖了从 MCP 服务器的管理、发现、传输、运行时保护到可观测性的完整生命周期。MCP 工具在运行时通过 `McpToolCallbackAdapter` 桥接到 Spring AI 的 `ToolCallback` 接口，与内置工具统一治理。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/mcp/`

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Admin 管理层                              │
│  McpServerAdminFacadeService + McpServerCrudHelper          │
│  McpEndpointValidator (SSRF防护) + McpCredentialCipher      │
│  McpServerStatusMachine (状态机) + McpAlertService           │
└──────────────────────────┬──────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
┌─────────────────┐ ┌──────────────┐ ┌──────────────────┐
│ Transport 层    │ │ Schema Drift │ │ Application 层   │
│ WebClientMcp    │ │ Detector     │ │ CatalogSync      │
│ TransportClient │ │ Scheduler    │ │ Service          │
│ (HTTP/SSE 双协议)│ │ (定时检测)    │ │                  │
└────────┬────────┘ └──────────────┘ └──────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────┐
│                    Runtime 运行时层                            │
│                                                              │
│  ┌───────────────────────┐  ┌────────────────────────────┐  │
│  │ McpRuntimeToolRegistry│  │ McpToolCallbackAdapter     │  │
│  │ (Caffeine 30s 缓存)   │  │ (Spring AI ToolCallback 桥接)│  │
│  └───────────────────────┘  └─────────┬──────────────────┘  │
│                                       │                      │
│  ┌──────────────────┐  ┌──────────────┴───────────────────┐ │
│  │ McpRolloutPolicy │  │ 运行时保护                        │ │
│  │ (ALL/NONE/       │  │ McpServerCircuitBreaker (熔断器)  │ │
│  │  AGENT_ALLOWLIST)│  │ McpServerRateLimiter (令牌桶)     │ │
│  └──────────────────┘  │ McpToolResponseSanitizer (64KB)   │ │
│                         └──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. MCP 服务器管理

### 2.1 McpServerAdminFacadeService

**文件：** `admin/application/McpServerAdminFacadeServiceImpl.java`

| 操作 | 说明 |
|------|------|
| getServers | 列出所有服务器 |
| getServer | 获取详情（含工具目录） |
| createServer | 创建：slug 验证 + endpoint 验证 + 凭据加密 + 状态机 |
| updateServer | 更新：敏感字段变更 → 状态转为 STALE |
| deleteServer | 删除：引用检查 + 强制删除选项 + 目录清理 |
| testServer | 探测：发现工具 + 测试连通性 |
| syncServer | 同步：发现并持久化工具目录 |

### 2.2 McpServerCrudHelper (CRUD 辅助)

**文件：** `admin/application/McpServerCrudHelper.java`

- **slug 验证：** `^[a-z0-9_]+$`
- **endpoint URL 规范化**
- **凭据加密：** 通过 `McpCredentialCipher` (AES-256-GCM)
- **状态机转换**
- **slug 唯一性：** DataIntegrityViolationException 翻译

### 2.3 McpEndpointValidator — SSRF 防护

**文件：** `admin/application/McpEndpointValidator.java`

安全校验 MCP 端点 URL：

| 防护项 | 说明 |
|--------|------|
| 元数据端点 | 阻止 169.254.169.254 等云元数据地址 |
| 私有/回环地址 | 阻止私有网络、回环、链路本地地址 |
| 内部主机名 | 阻止 .local, .internal, .corp 后缀 |
| 嵌入凭据 | 阻止 URL 中嵌入的用户名密码 |
| HTTP 限制 | 仅 dev/test profile 允许 localhost/127.0.0.1 的 HTTP |

### 2.4 McpCredentialCipher — AES-256-GCM 加密

**文件：** `application/McpCredentialCipher.java`

```
加密：
    1. 生成随机 12 字节 IV
    2. AES/GCM/NoPadding 加密 (128-bit auth tag)
    3. IV + 密文 → Base64
    4. 返回 EncryptedCredential(ciphertext, keyVersion)

解密：
    1. 校验 keyVersion 匹配
    2. 解码 Base64 → IV + 密文
    3. AES/GCM 解密
```

密钥长度验证：仅接受 128、192 或 256 位。

### 2.5 McpServerStatusMachine — 状态机

**文件：** `application/McpServerStatusMachine.java`

```
初始状态: DISABLED
敏感配置变更 → STALE (不管当前状态)
连续失败 >= 3次 → FAILED
连接成功 → ACTIVE
显式禁用 → DISABLED
```

---

## 3. 传输层 (Transport)

### 3.1 WebClientMcpTransportClient

**文件：** `transport/WebClientMcpTransportClient.java` (737行)

支持双协议的完整 MCP 协议实现：

#### HTTP (Streamable) 协议
```
discover():
    1. POST / initialize → 获取协议版本、服务器信息
    2. POST / notifications/initialized → 通知初始化完成
    3. POST / tools/list → 获取工具列表
```

#### Legacy SSE 协议
```
discover():
    1. 打开 SSE 会话
    2. 等待 "endpoint" 事件 → 发现 POST 端点
    3. POST initialize (通过 SSE 会话的 POST 端点)
    4. POST notifications/initialized
    5. POST tools/list
```

### 3.2 认证支持

| 类型 | 实现 |
|------|------|
| `API_KEY` | X-API-Key Header |
| `BEARER_TOKEN` | Authorization: Bearer Header |
| OAuth2 | 未实现 |

### 3.3 McpHandshakeCache — 单次握手保护

**文件：** `transport/McpHandshakeCache.java`

使用 `ReentrantLock` 防止同一服务器的并发握手：
- 请求到来时加锁
- 无线程排队时自动移除锁
- 防止重复 initialize 和 tools/list 调用

### 3.4 传输配置

| 配置 | 默认值 |
|------|--------|
| connect timeout | 2s |
| response timeout | 10s |
| request timeout | 15s |
| maxInMemorySize | 128KB |
| SSE max reconnects | 5 |
| HTTP protocol version | 2025-06-18 |
| SSE protocol version | 2024-11-05 |

---

## 4. Schema 漂移检测

### 4.1 McpSchemaDriftDetector

**文件：** `application/McpSchemaDriftDetector.java`

```
detect(McpServerDTO):
    1. 传输层 tools/list 发现 → 获取远程工具列表
    2. 与持久化目录逐个比较：
       - description 变更
       - 暴露名称变更
       - schema hash (SHA-256) 变更
       - schema JSON 变更
    3. 变更的工具 → upsert 状态为 STALE (不是 ENABLED)
    4. 缺失的远程工具 → 也标记 STALE
    5. 检测到漂移 → 服务器标记 STALE + 记录指标 + 发送告警
    6. runtimeToolRegistry.invalidate() → 强制缓存清除
```

### 4.2 McpSchemaDriftScheduler

定时检测（默认 10 分钟间隔），只探测 ACTIVE 状态的服务器。

---

## 5. 运行时保护层

### 5.1 McpServerCircuitBreaker — MCP 熔断器

**文件：** `runtime/McpServerCircuitBreaker.java`

轻量级三态熔断器：

| 参数 | 默认值 |
|------|--------|
| 滑动窗口大小 | 10 |
| 失败阈值 | 5 |
| 失败率阈值 | 50% |
| 最低请求数 | 10 |
| 开启持续时间 | 30s |
| 半开探测数 | 3 |
| 慢调用阈值 | 10s |
| 慢调用率阈值 | 80% |

**双阈值评估：** 失败率和慢调用率任一超标即触发熔断。

### 5.2 McpServerRateLimiter — 令牌桶限流

**文件：** `runtime/McpServerRateLimiter.java`

每个服务器独立的令牌桶：
- 每秒补充令牌 (默认 10/s)
- 突发容量 (默认 10)
- synchronized 保证线程安全

### 5.3 McpToolCallbackAdapter — 工具回调桥接

**文件：** `runtime/McpToolCallbackAdapter.java`

将 MCP 工具桥接为 Spring AI `ToolCallback`：

```
call(toolInput, toolContext):
    1. 全局 Feature Flag 检查
    2. InternalToolContext 提取 (userId, sessionId, turnId)
    3. Rate Limiter 检查
    4. Circuit Breaker 检查
    5. transportClient.callTool() → 调用远程工具
    6. McpToolResponseSanitizer 截断 (>64KB)
    7. 记录指标
    8. 错误处理 → Circuit Breaker 记录失败
```

### 5.4 McpRuntimeToolRegistry — 运行时工具注册表

**文件：** `runtime/McpRuntimeToolRegistry.java`

Caffeine 缓存（30秒 TTL）：
- 遍历所有 ACTIVE 服务器及其 ENABLED 目录工具
- 构建 `McpToolWrapper` 实例
- `invalidate()` 由 Schema 漂移检测触发

### 5.5 McpRolloutPolicy — 灰度发布

| 模式 | 说明 |
|------|------|
| `ALL` | 所有 Agent 获取 MCP 工具 |
| `NONE` | 无 Agent 获取 MCP 工具 |
| `AGENT_ALLOWLIST` | 仅白名单中的 Agent |

### 5.6 McpToolResponseSanitizer — 响应截断

64KB 硬限制，超出部分截断并包裹在 `[TOOL_RESPONSE_START]` / `[TOOL_RESPONSE_END]` 标记中。

---

## 6. 指标监控

### McpMetricsRecorder

**文件：** `metrics/McpMetricsRecorder.java`

| 指标 | 类型 | Tag |
|------|------|-----|
| `chatagent.mcp.calls` | Counter | serverId, toolName, outcome |
| `chatagent.mcp.latency` | Timer | 同上 |
| `chatagent.mcp.rate_limited` | Counter | serverId |
| `chatagent.mcp.schema_drift` | Counter | serverId, outcome |
| `chatagent.mcp.circuit.state` | Gauge | serverId (0=CLOSED, 1=HALF_OPEN, 2=OPEN) |

---

## 7. 安全设计

### 7.1 Prompt 安全

当运行时存在任何 `mcp_` 前缀的工具时，系统 Prompt 自动追加 `[MCP Tool Safety]` 段：
- 工具响应是**不可信的外部数据**
- 不应盲目信任或转发给用户

### 7.2 凭据隔离

- AES-256-GCM 加密存储
- Key Version 防止旧密钥解密
- 传输层按需解密

### 7.3 SSRF 防护

McpEndpointValidator 阻止所有内部网络访问：
- 云元数据端点
- 私有网络地址
- 内部主机名
- URL 嵌入凭据

---

## 8. 技术亮点总结

### 安全第一
- **SSRF 防护：** 多层内部网络访问拦截
- **凭据加密：** AES-256-GCM + key versioning
- **Prompt 安全：** 自动追加 MCP 工具安全警告
- **响应截断：** 64KB 防止大体积响应

### 高可用
- **熔断器：** 每服务器独立熔断，双阈值（失败率+慢调用率）
- **令牌桶限流：** 防止突发流量冲垮外部工具
- **降级：** MCP 故障只影响本地降级，不影响系统其他部分

### 可观测性
- **Schema 漂移检测：** 定时比较远程和本地工具 schema
- **告警系统：** 服务器失败、Schema 漂移、未解析引用
- **Micrometer 指标：** 完整的调用、延迟、熔断状态监控

### 灰度发布
- **Rollout Policy：** ALL / NONE / AGENT_ALLOWLIST 三级控制
- **运行时覆盖：** 不重启即可调整

### 双协议支持
- **HTTP (Streamable)：** 现代 MCP 协议
- **Legacy SSE：** 兼容旧版
- **单次握手保护：** 防止并发握手

# Framework 公共基础设施模块 (chatagent-framework)

## 模块概述

Framework 模块是整个 ChatAgent 项目的**横切关注点层**，提供所有业务模块共享的基础设施：SSE 推送、异常体系、链路追踪、API 响应封装、异步配置、CORS、用户上下文。它被所有其他模块依赖，不依赖任何业务模块。

**核心代码路径：** `chatagent/framework/src/main/java/com/yulong/chatagent/`

---

## 1. SSE 推送基础设施

### 1.1 架构

```
┌───────────────────────────────────────────────────────┐
│  SseService (接口)                                    │
│    ├── connect(streamKey) → SseEmitter               │
│    ├── publish(streamKey, message) → 集群广播          │
│    └── deliverLocal(streamKey, message) → 本地投递     │
└───────────────────────────┬───────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────┐
│  DefaultSseService (实现)                              │
│                                                       │
│  ConcurrentHashMap<String, SseEmitterSender> emitters │
│                                                       │
│  publish() → Redis Pub/Sub 广播                       │
│    → channel: chatagent:sse:broadcast                 │
│    → payload: {streamKey, message} (JSON)             │
│                                                       │
│  deliverLocal() → 本地 emitters.get(key).sendEvent()  │
└───────────────────────────────────────────────────────┘
```

### 1.2 DefaultSseService

**文件：** `sse/DefaultSseService.java`

- **SSE 超时：** 30 分钟 (`SSE_TIMEOUT_MILLIS`)
- **connect()：** 创建 `SseEmitter`，包装为 `SseEmitterSender`，发送 "init" 事件
- **自动清理：** `onCompletion`/`onTimeout`/`onError` 回调自动从 Map 移除
- **重连支持：** 同一 streamKey 的新连接替换旧连接

### 1.3 SseEmitterSender

**文件：** `sse/SseEmitterSender.java`

- `AtomicBoolean closed` 防止 double-close/double-send
- `sendEvent(eventName, data)` → 命名 SSE 事件
- `complete()` / `fail()` → `compareAndSet(false, true)` 安全调用一次

### 1.4 RedisSseConfiguration — 集群广播

**文件：** `sse/RedisSseConfiguration.java`

- 订阅 `chatagent:sse:broadcast` Redis 频道
- `SseMessageReceiver` 反序列化 JSON → 调用 `sseService.deliverLocal()`
- **反序列化错误隔离：** 不影响其他消息的处理

### 1.5 集群 SSE 工作原理

```
实例 A publish():
    → Redis Pub/Sub 广播
    → 所有实例收到消息
    → 每个实例检查本地 emitters Map
    → 有匹配的连接 → deliverLocal()

多实例部署：
    用户 SSE 连接到实例 A
    Agent 在实例 B 运行
    → publish() 通过 Redis 广播
    → 实例 A 收到 → deliverLocal() → 推送给用户
```

---

## 2. 异常体系

### 2.1 层次结构

```
RuntimeException
    └── AbstractException (errorCode + errorMessage)
        ├── ClientException (400) → 客户端错误
        │   └── BizException → 业务逻辑违反
        │   └── SessionConflictException (409) → 会话并发冲突
        ├── ServiceException (500) → 内部服务错误
        └── RemoteException (502) → 上游远程服务错误
```

### 2.2 IErrorCode — 错误码契约

| 错误码 | HTTP 状态 | 说明 |
|--------|----------|------|
| SUCCESS | 200 | 成功 |
| CLIENT_ERROR | 400 | 客户端错误 |
| CONFLICT | 409 | 冲突（并发） |
| FORBIDDEN | 403 | 禁止访问 |
| NOT_FOUND | 404 | 未找到 |
| SERVICE_ERROR | 500 | 服务内部错误 |
| REMOTE_ERROR | 502 | 远程服务错误 |

### 2.3 GlobalExceptionHandler — 全局异常处理

**文件：** `exception/GlobalExceptionHandler.java`

| Handler | 处理 |
|---------|------|
| `handleAbstractException` | 日志 warn(traceId) → 返回对应 HTTP 状态 + ApiResponse.error |
| `handle404` | NoResourceFoundException → 404 |
| `handleMaxUploadSizeExceeded` | 文件大小超限 → 400 |
| `handleException` | 兜底 → 500 + 通用消息 |

---

## 3. 链路追踪

### 3.1 TraceContext

**文件：** `trace/TraceContext.java`

- `ThreadLocal<String>` 存储当前请求的 traceId
- `setTraceId()` 同时写入 SLF4J MDC → 日志自动关联 traceId
- `clear()` 同时清除 ThreadLocal 和 MDC

### 3.2 TraceIdFilter

**文件：** `trace/TraceIdFilter.java`

```
doFilterInternal():
    1. 读取 X-Trace-Id 请求 Header
    2. 缺少 → 生成 UUID（去连字符）
    3. TraceContext.setTraceId()
    4. 设置响应 Header X-Trace-Id
    5. finally → TraceContext.clear()
```

---

## 4. ApiResponse — 统一 API 响应

**文件：** `model/common/ApiResponse.java`

```java
public class ApiResponse<T> {
    int code;
    String message;
    T data;
    String traceId;    // 自动从 TraceContext 获取
}
```

**工厂方法：**
- `success(data)` / `success()` / `success(data, message)`
- `error(errorCode)` / `error(errorCode, message)` / `error(message)`

所有响应自动携带当前 traceId，支持链路追踪。

---

## 5. 异步配置

### 5.1 AsyncConfig

**文件：** `config/AsyncConfig.java`

三个线程池：

| 线程池 | core/max/queue | 前缀 | 拒绝策略 |
|--------|----------------|------|---------|
| `taskExecutor` | 4/10/100 | async-event- | 默认 AbortPolicy |
| `summaryExecutor` | 1/2/8 | summary-task- | DiscardOldestPolicy |
| `modelStreamExecutor` | 20/100/200 | LlmStream- | AbortPolicy |

### 5.2 TraceTaskDecorator

**关键设计：** 异步线程继承父线程的 traceId：

```java
decorate(runnable):
    1. 捕获当前 traceId
    2. 包装 runnable：设置 traceId → 执行 → 恢复之前的 traceId
```

支持嵌套异步场景的 traceId 传播。

---

## 6. 用户上下文

### 6.1 UserContext

**文件：** `context/UserContext.java`

`ThreadLocal<LoginUser>` 持有当前认证用户：
- `set()` / `get()` / `requireUser()` (无用户抛异常) / `clear()`

### 6.2 LoginUser

**文件：** `context/LoginUser.java`

```java
@Data @Builder
public class LoginUser {
    String userId;
    String username;
    String role;
    String avatar;
    String status;
}
```

---

## 7. CORS 配置

**文件：** `config/CorsConfig.java`

双层 CORS（MVC + Servlet Filter）：
- 允许 `http://localhost:*` 和 `http://127.0.0.1:*`
- 允许凭据
- 允许所有方法和 Header
- max-age 3600s

---

## 8. 技术亮点总结

### SSE 集群广播
- **Redis Pub/Sub：** 多实例部署下跨节点消息推送
- **失败隔离：** 单个消息反序列化错误不影响其他消息
- **自动清理：** SSE 连接超时/完成/错误时自动从 Map 移除

### 链路追踪
- **TraceContext + MDC：** 请求全链路 traceId 自动关联日志
- **异步传播：** TraceTaskDecorator 确保异步线程继承 traceId

### 统一异常处理
- **层次化异常：** 区分客户端/服务端/远程错误
- **自动 traceId：** 错误响应自动携带 traceId
- **文件大小保护：** 全局拦截文件上传超限

### 线程池隔离
- **三个独立线程池：** 事件处理 / 摘要生成 / LLM 流式，互不影响
- **差异化拒绝策略：** 摘要用 DiscardOldestPolicy，其他用 AbortPolicy

# 用户认证模块 (com.yulong.chatagent.user)

## 模块概述

用户认证模块实现了完整的认证与授权系统，采用 **双 JWT 架构**（Access Token + Refresh Token），支持用户注册/登录、Token 刷新、RBAC 角色控制和用户管理。该模块遵循六边形架构，通过端口-适配器模式隔离基础设施依赖。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/user/`

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────┐
│                  Controller 层                          │
│  AuthController (/api/auth/*)                          │
│  UserProfileController (/api/user/profile)             │
│  UserAdminController (/api/admin/users)                │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  拦截器层                                │
│  JwtAuthenticationInterceptor → 解析JWT + 加载用户快照   │
│  RequireRoleInterceptor → RBAC 角色检查                  │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Application 层                         │
│  AuthService (注册/登录/刷新/登出)                       │
│  JwtTokenService (JWT 生成/解析/验证)                    │
│  BCryptPasswordService (密码哈希)                        │
│  UserProfileService (用户画像)                           │
│  AuthenticatedUserSnapshotCache (Caffeine 5s TTL)       │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Port 层 (接口)                          │
│  UserRepository                                        │
│  UserProfileRepository                                 │
│  RefreshTokenStore                                     │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                  Infrastructure 层                      │
│  RedisRefreshTokenStore (Redis + Lua 脚本)              │
│  MyBatis UserRepository Adapter                        │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 双 JWT 认证架构

### 2.1 Token 设计

| Token | 类型 | 存储 | 传输方式 | 用途 |
|-------|------|------|---------|------|
| Access Token | JWT | 响应体 | `Authorization: Bearer` header 或 `access_token` 查询参数 | 接口认证 |
| Refresh Token | 随机字符串 (SHA-256 哈希) | Redis | HttpOnly Cookie (SameSite=Lax) | 无感刷新 |

**设计理由：**
- Access Token 是 JWT，无状态，快速验证，但有有效期
- Refresh Token 是不透明字符串，哈希后存储在 Redis，支持即时撤销
- HttpOnly Cookie 防止 XSS 窃取，SameSite=Lax 防止 CSRF

### 2.2 JwtTokenService

**文件：** `application/JwtTokenService.java`

```
generateAccessToken(user):
    → JWT: subject=userId, claims={username, role}, issuer, iat, exp
    → 密钥: auth.jwt.secret
    → 有效期: auth.jwt.access-ttl-minutes

parseAccessToken(token):
    → 验证签名和过期时间
    → 映射 claims → JwtClaims(userId, username, role)

isAccessTokenValid(token):
    → try-catch 包装，任何异常返回 false
```

### 2.3 RefreshTokenCookieManager

**文件：** `web/RefreshTokenCookieManager.java`

```
写入 Cookie:
    - 名称: refreshToken
    - HttpOnly: true
    - SameSite: Lax
    - Path: /api/auth (限定范围)
    - TTL: 匹配 Refresh Token 有效期

清除 Cookie:
    - maxAge: 0 (立即删除)
```

---

## 3. 认证流程

### 3.1 注册 (register)

**文件：** `application/AuthServiceImpl.java` (第61-94行)

```
1. 验证用户名唯一性
2. BCrypt 哈希密码
3. 分配 USER 角色 + ACTIVE 状态
4. 保存到数据库
5. 立即生成 Access Token + Refresh Token
6. 保存 Refresh Token (Redis)
7. 返回 LoginResponse (含 Token)
```

### 3.2 登录 (login)

**文件：** `application/AuthServiceImpl.java` (第97-114行)

```
1. 按用户名查找用户
2. BCrypt 验证密码
3. assertUserCanAuthenticate() → 检查 deleted/DISABLED 状态
4. 清除旧的 Refresh Token (单会话策略)
5. 生成新的 Access Token + Refresh Token
6. 保存 Refresh Token (Redis)
7. 返回 LoginResponse
```

### 3.3 Token 刷新 (refresh)

**文件：** `application/AuthServiceImpl.java` (第117-146行)

```
1. 验证 Refresh Token 有效（从 Redis 查找）
2. 加载用户，检查状态
3. 生成新的 Access Token
4. 轮换 Refresh Token（保存新的，删除旧的）
5. 返回新的 LoginResponse
```

### 3.4 登出 (logout)

```
1. 撤销 Refresh Token（从 Redis 删除）
2. Access Token 保持有效直到自然过期
```

### 3.5 assertUserCanAuthenticate (状态检查)

```
如果用户 deleted 或 DISABLED：
    1. 失效快照缓存
    2. 撤销所有 Refresh Token
    3. 抛出 ClientException
```

---

## 4. 请求认证流程

### 4.1 JwtAuthenticationInterceptor

**文件：** `config/JwtAuthenticationInterceptor.java`

```
preHandle(request):
    1. 清除 UserContext (防止线程复用导致的状态残留)
    2. 解析 Access Token:
       - 优先 Authorization: Bearer header
       - 回退 access_token 查询参数 (SSE 连接使用)
    3. 缺少 Token → 返回 401
    4. 验证 Token 有效性
    5. 解析 claims → JwtClaims
    6. 从快照缓存加载用户 (不信任 Token 中的 role)
       → Caffeine 5s TTL, 10000 max entries
    7. 检查用户 deleted/DISABLED 状态
    8. 设置 UserContext (LoginUser)
    9. 返回 true

afterCompletion():
    → 清除 UserContext (保证清理)
```

**关键设计：** 从数据库加载用户的最新角色和状态，不信任 JWT 中的 role claim，防止角色变更后旧 Token 仍有权限。

### 4.2 SSE 认证

由于 `EventSource` API 无法设置自定义 Header，SSE 连接通过 URL 查询参数传递 Access Token：
```
GET /api/sse/connect/{sessionId}?access_token=xxx
```

---

## 5. Refresh Token 存储

### 5.1 RedisRefreshTokenStore

**文件：** `infrastructure/cache/RedisRefreshTokenStore.java`

**双重索引：**
```
正向索引: auth:refresh:{tokenHash} → userId (TTL: refresh token 有效期)
反向索引: auth:user:{userId}:refresh-tokens → Set<tokenHash> (用于批量撤销)
```

**Token 哈希：** SHA-256 哈希后存储，不存储原始 Token。

### 5.2 批量撤销 (deleteByUserId)

使用 Lua 脚本**原子操作**：
```lua
-- 获取用户的所有 token hash
local tokens = redis.call('smembers', KEYS[2])
-- 逐个删除正向索引
for _, token in ipairs(tokens) do
    redis.call('del', token)
end
-- 删除反向索引
redis.call('del', KEYS[2])
```

**触发场景：** 角色变更、状态变更、密码重置、用户删除。

---

## 6. RBAC 角色控制

### 6.1 UserRole

| 角色 | 说明 |
|------|------|
| `ADMIN` | 管理员，可访问所有管理接口 |
| `USER` | 普通用户，只能操作自己的资源 |

### 6.2 @RequireRole 注解

```java
@RequireRole(UserRole.ADMIN) // 放在 Controller 类或方法上
```

### 6.3 RequireRoleInterceptor

```
preHandle():
    1. 解析方法上的 @RequireRole（优先）或类上的
    2. UserContext.requireUser() → 获取当前用户
    3. UserRole.matchesAny(user.role, requiredRoles)
    4. 不匹配 → ClientException(FORBIDDEN)
```

### 6.4 ResourceAccessGuard (资源级权限)

| 方法 | 检查 |
|------|------|
| `assertCanReadSession(user, sessionId)` | 会话 userId == user.userId |
| `assertCanManageKnowledgeBase(user, kbId)` | user.role == ADMIN + KB 存在 |

---

## 7. 用户管理 (Admin)

### 7.1 UserAdminFacadeService

**文件：** `admin/application/UserAdminFacadeServiceImpl.java`

| 操作 | 说明 |
|------|------|
| getUsers | 分页查询，支持 keyword(用户名模糊) + status 过滤 |
| createUser | 生成16位随机初始密码，BCrypt 哈希，返回一次 |
| updateUser | 更新角色/头像，防护自我修改 |
| updateUserStatus | 启用/禁用，防护自我操作 |
| resetPassword | 重置为新的随机密码，返回一次 |
| deleteUser | 软删除 (deleted=true + DISABLED) |

### 7.2 安全防护

**防止自我操作 (assertCanModifyOtherAdmin)：** 管理员不能修改自己的角色、禁用自己、删除自己。

**最后管理员保护 (assertNotRemovingLastActiveAdmin)：**
```java
findActiveAdminsForUpdate() // SELECT ... FOR UPDATE (事务锁)
→ 如果只剩一个活跃管理员 → 抛异常
```

### 7.3 数据模型语义

| 状态 | deleted | status |
|------|---------|--------|
| 正常账户 | false | ACTIVE |
| 临时禁用 | false | DISABLED |
| 已删除 | true | DISABLED |

**用户名唯一性：** 软删除后用户名仍被占用，不可重用。

### 7.4 快照缓存失效

以下操作后失效 `AuthenticatedUserSnapshotCache`：
- 角色变更
- 状态变更
- 密码重置
- 用户删除

---

## 8. 技术亮点总结

### 安全设计
- **双 JWT 架构：** Access Token (JWT) + Refresh Token (不透明)
- **HttpOnly Cookie：** 防止 XSS 窃取 Refresh Token
- **Token 哈希存储：** Redis 不存储原始 Refresh Token
- **不信任 Token 内角色：** 每次从数据库加载最新状态
- **BCrypt 密码哈希：** 自适应工作量因子

### 高可用
- **快照缓存：** Caffeine 5s TTL 减少 DB 负载
- **Fail-Open：** SSE 查询参数回退确保 EventSource 可用
- **Lua 原子撤销：** 批量删除 Token 无竞态条件

### RBAC
- **注解式鉴权：** `@RequireRole` 声明式控制
- **资源级权限：** ResourceAccessGuard 细粒度检查
- **最后管理员保护：** SELECT FOR UPDATE 事务锁

### 会话管理
- **单会话策略：** 登录时清除旧 Refresh Token
- **Token 轮换：** 每次刷新生成新的 Refresh Token
- **即时撤销：** 密码重置/角色变更后立即撤销所有 Token

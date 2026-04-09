# ChatAgent 用户管理功能落地方案 (V1.0)

## 1. 背景与设计目标

随着 ChatAgent 系统的不断完善，我们需要一个简单的后台用户管理功能（类似于 `ragent` 的 Admin 模块），以便系统管理员能够对内部用户进行 CRUD 管理、权限分配以及状态管控。

**核心目标：**
- 提供轻量、直观的用户列表与检索能力。
- 支持管理员手动创建用户、重置密码。
- 支持更改用户角色（如 `USER` 提权至 `ADMIN`）与账户状态（启用/禁用）。
- **无缝集成现有的双 JWT (Dual-JWT) 架构**，确保权限变更和账户禁用能够安全、**及时地**生效。
- **保护最后一名管理员**：建立安全护栏，防止管理员因误操作导致系统失去控制权。

---

## 2. 当前认证架构约束分析 (Dual-JWT 适配)

当前系统使用了 **Access Token + Refresh Token** 的双重 JWT 体系：
- **Access Token (短效)**：基于 JJWT 生成，无状态，通过 Response Body 返回前端，包含 `userId`, `username`, `role`。
- **Refresh Token (长效)**：作为不透明字符串（Opaque Token）存储在服务端的 Redis 中，并通过浏览器的 `HttpOnly` Cookie 返回前端。

**在设计用户管理时必须解决的安全问题：**
当管理员“禁用”某用户或“修改”其角色时，如何让其立即下线或权限立刻生效？
如果在请求期仅仅信任 Access Token 里的 Claims，被禁用或降权的用户在 Access Token 过期前仍可继续访问受保护接口，这会产生权限滞后窗口。

**我们的决断（安全、及时生效）：**
1. **服务端会话彻底撤销（强阻断）**：发生状态变更、密码重置或角色变更时，服务端立刻调用 `RefreshTokenStore.deleteByUserId(userId)`，按 `userId` 清除该用户在 Redis 中的**所有**有效 Refresh Token，切断其后续换取新 Access Token 的能力。注意：这里必须操作服务端的 Token 存储层，而不是调用 Cookie Manager 清理本次响应的 Cookie。
2. **Access Token 认证期回源校验（即时生效）**：在现有的 `JwtAuthenticationInterceptor` 中，验证完 Access Token 签名后，**不要直接信任 Token 里的 role 和 status**。而是根据解析出的 `userId` 回源查主库（V1 默认查主库以保证强一致性）获取当前用户的最新快照。若快照显示该用户已被禁用（`DISABLED`）或删除（`deleted = true`），统一返回 401（这会让前端立刻清除 Token 并退出登录态），从而消除了 Access Token 的存活期窗口。

---

## 3. 核心功能与 API 设计

管理员接口统一放在 `/api/admin/users` 路径下，通过现有的 `@RequireRole(UserRole.ADMIN)` 进行授权保护。

### 3.1 用户列表 (分页与检索)
- **Endpoint**: `GET /api/admin/users`
- **Query Params**:
  - `page`: 当前页码 (默认 1)
  - `size`: 每页大小 (默认 10)
  - `keyword`: 按用户名模糊查询 (可选)
  - `status`: 按状态筛选 (ACTIVE/DISABLED) (可选)
- **Response**: 返回带分页信息的 `UserVO` 列表。注意：查询时需默认过滤掉 `deleted = true` 的用户。

### 3.2 创建用户 (Admin 手动建号)
- **Endpoint**: `POST /api/admin/users`
- **Body**: 
  - `username`: 登录名
  - `password`: 初始密码（也可选择由后端随机生成并返回给管理员）
  - `role`: 角色（ADMIN/USER）
- **逻辑**: 校验用户名防重，执行标准的加盐 Hash 密码存储逻辑。

### 3.3 更新用户信息与角色
- **Endpoint**: `PUT /api/admin/users/{userId}`
- **Body**:
  - `role`: 角色（ADMIN/USER）
- **副作用 (会话失效)**：如果 `role` 发生变化，需调用 `RefreshTokenStore.deleteByUserId(userId)` 强制其重新登录。

### 3.4 修改用户状态 (启用/禁用)
- **Endpoint**: `PUT /api/admin/users/{userId}/status`
- **Body**: 
  - `status`: 枚举值 `ACTIVE` 或 `DISABLED`
- **副作用 (会话失效)**：若状态变更为 `DISABLED`，立刻调用 `RefreshTokenStore.deleteByUserId(userId)` 删除该用户所有 Refresh Token。配合 `JwtAuthenticationInterceptor` 的回源校验，该用户将瞬间失去访问权限。

### 3.5 管理员重置密码
- **Endpoint**: `PUT /api/admin/users/{userId}/password/reset`
- **Body**:
  - `newPassword`: 新密码 (若为空可由系统生成默认密码如 `Abc@123456`)
- **副作用 (会话失效)**：密码重置后，立刻调用 `RefreshTokenStore.deleteByUserId(userId)` 迫使其重新登录。

---

## 4. 账户状态语义与数据模型

在现有的用户模型中，我们需要厘清已有的 `deleted` 字段与将要引入的 `status` 字段的职责边界。

- **`deleted` (Boolean)**: 表示软删除。**语义**：不可逆（或通常不开放恢复入口），等同于物理删除。在所有用户列表查询、登录验证中必须被过滤。
- **`status` (Enum)**: 表示可恢复的停用。**枚举值**：`ACTIVE` / `DISABLED`。**语义**：用户仍然存在于系统中，可以展示在管理列表中，但处于被临时封禁的状态，禁止登录和访问 API，可以被管理员随时重新启用。

**持久化扩展：**
在现有的 `t_user` 表中增加 PostgreSQL 兼容的 DB Migration 脚本：
```sql
ALTER TABLE t_user ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;
COMMENT ON COLUMN t_user.status IS '账户状态: ACTIVE, DISABLED';
```
在 `User.java` (Domain Entity) 中补充：
```java
public enum UserStatus {
    ACTIVE,
    DISABLED
}
```

---

## 5. 权限控制 (RBAC) 拦截策略

为了保持系统的职责分离与扩展性，必须严格区分“认证”与“授权”两个步骤，**拒绝将角色判断和路径前缀校验耦合进认证拦截器中**。

**推荐落地架构（认证回源 + 注解式授权）：**

1. **认证层 (`JwtAuthenticationInterceptor`)**：只负责解决“你是谁”以及“你是否还有资格访问”。
   - 验证 Token 签名后，根据 `userId` 提取用户快照。
   - 若用户不存在、`deleted == true` 或 `status == DISABLED`，统一拦截并抛出 401（触发前端退出逻辑）；真正的角色权限不足才由授权层抛出 403。
   - 若校验通过，将最新的用户上下文注入（不要依赖 Token 中可能过期的 `role`）。
2. **授权层 (`RequireRoleInterceptor` + `@RequireRole`)**：只负责解决“你有没有权限执行该操作”。
   - 管理员控制器（`AdminUserController`）在类或方法上统一打上项目现有的 `@RequireRole(UserRole.ADMIN)` 注解。
   - 角色校验继续由已在 `UserWebMvcConfig` 中全局注册的 `RequireRoleInterceptor` 负责。

---

## 6. 安全护栏 (Safety Guards)

由于本项目规模较小，管理员权限极为重要，必须在业务层（Service 层）加入防“自杀”机制：

1. **禁止操作自己**：明确禁止管理员删除、禁用或降权自己。
2. **保护最后一名管理员**：在执行降权、禁用、删除操作前，检查系统中是否仅剩这一名活跃的 `ADMIN`。如果是，则阻断操作并抛出异常，防止系统因失误陷入无人可管的绝境。

---

## 7. 前端 UI 规划 (模仿 ragent)

前端 (基于 Vite + React + TailwindCSS) 需要增加：
1. **Admin Layout 菜单**：当 `authContext` 中获取到当前 currentUser 的 role 为 `ADMIN` 时，在左侧导航栏暴露出 **"用户管理"** 菜单。
2. **User Table 页面**：
   - 顶部搜索框 (`keyword`)。
   - 数据表格显示：`ID`, `用户名`, `角色(Tag显示)`, `状态(Switch/Toggle 控件)`, `创建时间`, `操作`。
3. **编辑/创建弹窗 (AuthDialog 的变体)**：
   - 收集基础信息，并提供 Role 的 Select 选择器。
4. **重置密码确认弹窗**：
   - 二次确认防止误触。

---

## 8. 实施分期步骤 (Phases)

**Phase 1: 底层模型、认证回源与授权规范**
- 明确 `deleted` 与 `status` 的语义边界，并在数据库中增加 `status` 字段。
- 修改 `User` 实体、Mapper 及 `AuthServiceImpl.login` 校验逻辑，拒绝 `DISABLED` 或 `deleted=true` 的用户。
- 改造 `JwtAuthenticationInterceptor`，不再信任 Token 的 Claims，而是根据 `userId` 回源加载最新状态，实现状态与角色变更即时生效。

**Phase 2: 后端管理接口与会话撤销**
- 实现 `UserAdminController` 以及对应的 `UserService`，加上 `@RequireRole(UserRole.ADMIN)` 注解。
- 引入安全护栏（禁止删除自己/最后的 Admin）。
- 实现与 `RefreshTokenStore` 的联动：当发生禁用、改权限、重置密码时，调用 `deleteByUserId(userId)` 撤销服务端会话。

**Phase 3: 前端页面落地**
- 封装 `api/admin.ts` 网络请求。
- 绘制 UserTable 与弹窗组件，完成前后端联调。
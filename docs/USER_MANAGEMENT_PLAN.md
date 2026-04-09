# ChatAgent 用户管理功能落地方案 (V1.1 - 安全加固版)

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

**在设计用户管理时必须解决的 P0 级安全问题：**
当管理员“禁用”某用户或“修改”其角色时，如何让其立即下线或权限立刻生效？

**我们的决断（强一致与及时生效）：**

1. **服务端会话彻底撤销（原子性强阻断）**：发生状态变更、密码重置或角色变更时，服务端必须调用 `RefreshTokenStore.deleteByUserId(userId)`，清除该用户在 Redis 中的所有有效 Refresh Token。
   - *P1 修复*：由于原生的 `deleteByUserId` 采用“先查询 Set，再循环 Delete，最后清理 Set”的非原子操作，极易产生竞态（如在循环期间用户刚好并发调用 refresh 导致新 Token 逃逸）。**实现时必须使用 Redis Lua 脚本或 MULTI/EXEC 管道重构该方法，保证会话撤销的原子性。**
   - *P1 修复*：该步骤必须在包裹了 `@Transactional` 的 Service 方法的 **`afterCommit`** 钩子中执行，或确保 Redis 异常不破坏 DB 事务，保证状态变更与会话撤销的最终一致性。

2. **Refresh 阶段补漏拦截（堵住 /api/auth 绕过）**：
   - *P0 修复*：现有的 `UserWebMvcConfig` 将 `/api/auth/**` 排除在 `JwtAuthenticationInterceptor` 之外。如果在 Redis 会话撤销完成前的极短竞态窗口内，受限用户发起 `/api/auth/refresh` 请求，原逻辑的 `selectById` 既不过滤 `deleted` 也不判断 `status`，将直接签发带有旧权限的非法 Access Token。
   - **强制要求**：必须在 `AuthServiceImpl.refresh()` 内部显式增加校验。通过 `userId` 查出实体后，判断 `deleted == true` 或 `status == DISABLED`，若是则直接抛出 `BizException` 阻断续签。

3. **Access Token 认证期回源校验（折中性能的即时生效）**：在现有的 `JwtAuthenticationInterceptor` 中，验证完 Access Token 签名后，**不要直接信任 Token 里的 role 和 status**。而是根据解析出的 `userId` 回源获取当前用户的最新快照。若快照显示该用户已被禁用（`DISABLED`）或删除（`deleted = true`），**统一返回 401**（触发前端立刻清除 Token 并退出登录态）。
   - *P1 修复*：为防止 SSE 长连接或高频聊天接口引发主库查库风暴，**强烈建议引入 `Caffeine` 5~10 秒短效 TTL 缓存，或在 Access Token 生命周期的最后 N 秒才强制查库**，实现性能与安全性的动态平衡。

---

## 3. 核心功能与 API 设计

管理员接口统一放在 `/api/admin/users` 路径下，通过现有的 `@RequireRole(UserRole.ADMIN)` 进行授权保护。**所有管理行为必须写入审计日志（至少 `log.info` 级别记录 "who did what to whom at when"）。**

### 3.1 用户列表 (分页与检索)
- **Endpoint**: `GET /api/admin/users`
- **Query Params**:
  - `page`: 当前页码 (默认 1)
  - `size`: 每页大小 (默认 10)
  - `keyword`: **仅按用户名模糊查询** (由于实体中尚无 nickname 字段，V1 收敛范围) (可选)
  - `status`: 按状态筛选 (ACTIVE/DISABLED) (可选)
- **Response**: 返回带分页信息的 `UserVO` 列表。注意：查询时需默认过滤掉 `deleted = true` 的用户。

### 3.2 创建用户 (Admin 手动建号)
- **Endpoint**: `POST /api/admin/users`
- **Body**: 
  - `username`: 登录名
  - `role`: 角色（ADMIN/USER）
- **逻辑**: 
  - 校验用户名防重，DB 层面**必须有 `UNIQUE` 索引**作为并发创建防线。
  - *P1 修复*：绝不允许使用硬编码默认密码（如 `Abc@123456`）。系统必须每次生成**随机强密码**并仅在本次 Response 中明文返回一次，不提供默认口令。

### 3.3 更新用户信息与角色
- **Endpoint**: `PUT /api/admin/users/{userId}`
- **Body**:
  - `role`: 角色（ADMIN/USER）
- **副作用 (原子会话失效)**：需调用原子化的 `RefreshTokenStore.deleteByUserId(userId)` 强制其重新登录。

### 3.4 修改用户状态 (启用/禁用)
- **Endpoint**: `PUT /api/admin/users/{userId}/status`
- **Body**: 
  - `status`: 枚举值 `ACTIVE` 或 `DISABLED`
- **副作用 (原子会话失效)**：立刻调用原子化的 `deleteByUserId(userId)`，配合拦截器/Refresh校验使权限剥夺瞬间生效。

### 3.5 管理员重置密码
- **Endpoint**: `PUT /api/admin/users/{userId}/password/reset`
- **Body** (空):
- **逻辑**: 不接收 `newPassword`。每次由系统安全生成随机密码并返回。
- **副作用 (原子会话失效)**：立刻调用原子化 `deleteByUserId(userId)`。

---

## 4. 账户状态语义与数据模型深度扩展

在现有的用户模型中，我们需要厘清已有的 `deleted` 字段与将要引入的 `status` 字段的职责边界。

- **`deleted` (Boolean)**: 表示软删除。**语义**：不可逆（或通常不开放恢复入口），等同于物理删除。在所有用户列表查询、登录验证中必须被过滤。**特别注意：现有 `UserMapper.selectById` 没有过滤 `deleted=false`，因此在 `refresh()` 和 拦截器中查询后必须在 Java 业务层显式判断。**
- **`status` (Enum)**: 表示可恢复的停用。**枚举值**：`ACTIVE` / `DISABLED`。**语义**：用户仍然存在于系统中，禁止登录和访问 API。

**数据模型与持久化深度扩展 (P2 修复)：**
引入 `status` 需要自底向上进行全面贯穿，而不仅仅是改个实体类：

1. **DB Migration (PostgreSQL 标准语法)**: 
   ```sql
   ALTER TABLE t_user ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;
   COMMENT ON COLUMN t_user.status IS '账户状态: ACTIVE, DISABLED';
   ```
2. **Entity & Enum**: `User.java` (Domain Entity) 补充 `status` 字段；新增枚举 `UserStatus { ACTIVE, DISABLED }`。
3. **DTO/VO/Context**: `UserDTO`、`UserProfileDTO`、`LoginUserVO`、`LoginUser` (Context 对象，供拦截器写入) 均需增加 `status` 字段。
4. **Mapper SQL**: `UserMapper.xml` 中的 `<resultMap>`、`Base_Column_List`、`insert`、`selectById`、`selectByUsername`、`updateById` 全部需要补充对 `status` 字段的映射与更新。
5. **Converter**: `UserConverter` 负责处理 `status` 的相互映射（尤其是 String 到 Enum 的互转）。

---

## 5. 权限控制 (RBAC) 拦截策略

为了保持系统的职责分离与扩展性，必须严格区分“认证”与“授权”两个步骤，**拒绝将角色判断和路径前缀校验耦合进认证拦截器中**。

**推荐落地架构（认证回源 + 注解式授权）：**

1. **认证层 (`JwtAuthenticationInterceptor`)**：只负责解决“你是谁”以及“你是否还有资格访问”。
   - 验证 Token 签名后，根据 `userId` 提取用户快照。
   - 若用户不存在、`deleted == true` 或 `status == DISABLED`，**统一拦截并抛出 401（触发前端退出逻辑）**；真正的角色权限不足才由授权层抛出 403。
   - *(注：SSE 端点通过 Query Parameter 传递的 Token 同样受此 401 拦截规则保护，即使在服务器 Access Log 中存在残留，其效力也被回源阻断)*
   - 若校验通过，将最新的用户上下文注入 `UserContext`（不要依赖 Token 中可能过期的 `role`）。
2. **授权层 (`RequireRoleInterceptor` + `@RequireRole`)**：只负责解决“你有没有权限执行该操作”。
   - 管理员控制器在类或方法上统一打上 `@RequireRole(UserRole.ADMIN)` 注解。
   - 角色校验继续由现有的 `RequireRoleInterceptor` 负责。

---

## 6. 安全护栏 (Safety Guards - TOCTOU 竞态修复)

1. **禁止操作自己**：明确禁止管理员删除、禁用或降权自己。
2. **保护最后一名管理员的并发漏洞防范**：
   - *P0 修复*：在执行降权、禁用、删除操作前检查最后一名 `ADMIN` 时存在严重的 TOCTOU (Time-of-Check to Time-of-Use) 竞态问题（两个 Admin 可能互相降权导致系统瘫痪）。
   - **强制实现方案**：必须使用**数据库悲观锁 (`SELECT COUNT(*) ... FOR UPDATE`)** 或基于部分唯一索引（Partial Unique Index）结合事务来保证原子性。如果在加锁统计下发现仅剩该活跃 `ADMIN`，则抛出 `BizException` 阻断操作。

---

## 7. 前端 UI 规划 (模仿 ragent)

前端 (基于 Vite + React + TailwindCSS) 需要增加：
1. **Admin Layout 菜单**：当 `authContext` 中获取到当前 currentUser 的 role 为 `ADMIN` 时，在左侧导航栏暴露出 **"用户管理"** 菜单。
2. **User Table 页面**：
   - 顶部搜索框 (`keyword` 仅查 username)。
   - 数据表格显示：`ID`, `用户名`, `角色(Tag显示)`, `状态(Switch/Toggle 控件)`, `创建时间`, `操作`。
3. **编辑/创建弹窗 (AuthDialog 的变体)**：
   - 收集基础信息，提供 Role Select 选择器，密码由系统生成并展示提示框。

---

## 8. 实施分期步骤 (Phases)

**Phase 1: 底层模型、并发护栏与认证回源修复**
- 数据库增加 `status` 字段 (PostgreSQL 语法) 及 `UNIQUE` 约束。
- 深度改造 Entity/DTO/Mapper XML，贯穿 `status` 字段的存取。
- 修复 `AuthServiceImpl.login()` 并在 `refresh()` 补充 `deleted / status` 校验以堵住绕过窗口。
- 改造 `JwtAuthenticationInterceptor`，引入短效 Cache（如 5s）回源加载最新状态，返回标准 401 触发下线。

**Phase 2: 后端管理接口与原子化会话撤销**
- 利用 Redis Lua 或管道重构 `deleteByUserId` 确保其为原子操作，并在事务提交后的钩子中调用。
- 实现 `UserAdminController` 及 `UserService` (`@RequireRole(UserRole.ADMIN)`)，并补齐**操作审计日志**。
- 引入解决 TOCTOU 竞态的最后一名 Admin 保护锁机制及生成随机初始密码策略。

**Phase 3: 前端页面落地**
- 封装 `api/admin.ts` 网络请求。
- 绘制 UserTable 与弹窗组件，完成前后端联调。

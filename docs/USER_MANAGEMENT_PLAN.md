# ChatAgent 用户管理功能实现说明

本文档以当前仓库中已经落地的代码为准，描述用户管理 V1 的实际行为与边界，不再使用未实现的设计稿口径。

## 1. 当前范围

当前版本已经支持以下能力：

- 管理员分页查看用户列表
- 按 `username` 模糊搜索
- 按 `status` (`ACTIVE` / `DISABLED`) 筛选
- 管理员手动创建用户
- 编辑用户角色与头像
- 启用 / 禁用用户
- 管理员重置用户密码
- 软删除用户
- 权限变更、禁用、重置密码、删除后的会话撤销
- 最后一名活跃管理员保护

当前版本未实现的内容：

- 批量操作
- 物理删除
- 删除后用户名复用
- 更细粒度角色体系

## 2. 认证、鉴权与会话策略

系统继续沿用当前的 Dual-JWT 架构：

- Access Token：JWT，放在响应体中返回前端
- Refresh Token：不透明字符串，存储在 Redis，并通过 `HttpOnly` Cookie 发送

### 2.1 请求期认证

`JwtAuthenticationInterceptor` 的当前实现如下：

- 先校验 Access Token 签名
- 再根据 `userId` 加载最新用户快照，而不是直接信任 Token 内的 `role`
- 若用户不存在、`deleted = true` 或 `status = DISABLED`，统一返回 `401`
- 若校验通过，将最新用户快照写入 `UserContext`

为减少高频接口直接查库带来的压力，当前代码已经落地了一个本地短效缓存：

- 组件：`AuthenticatedUserSnapshotCache`
- 实现：`Caffeine`
- TTL：5 秒
- 最大条目数：10000

管理员侧发生角色、状态、密码、删除等变更后，服务层会主动失效该用户的本地快照缓存；Refresh Token 的撤销则在事务提交后执行。

### 2.2 Refresh 阶段补漏

`/api/auth/**` 不经过 `JwtAuthenticationInterceptor`，因此 `AuthServiceImpl.refresh()` 当前会显式检查：

- 用户是否存在
- `deleted` 是否为 `true`
- `status` 是否为 `DISABLED`

只要账户已失效，就会阻断续签。

### 2.3 授权策略

当前 RBAC 采用“认证回源 + 注解式授权”：

- 认证：`JwtAuthenticationInterceptor`
- 授权：`@RequireRole(UserRole.ADMIN)` + `RequireRoleInterceptor`

管理员接口统一挂在 `/api/admin/users` 下，并通过 `@RequireRole(UserRole.ADMIN)` 保护。

### 2.4 Refresh Token 撤销

`RefreshTokenStore.deleteByUserId(userId)` 的当前实现已经改为 Redis Lua 脚本，用于一次性删除：

- 用户的 Refresh Token 索引集合
- 集合中关联的所有 Refresh Token Key

当前会在以下操作完成后撤销目标用户的 Refresh Token：

- 角色变更
- 状态变更
- 密码重置
- 软删除

## 3. 后端 API

### 3.1 获取用户列表

- Endpoint：`GET /api/admin/users`
- Query Params：
  - `page`
  - `size`
  - `keyword`：仅按 `username` 模糊查询
  - `status`

查询默认过滤 `deleted = true` 的用户。

### 3.2 创建用户

- Endpoint：`POST /api/admin/users`
- Body：
  - `username`
  - `role`
  - `avatar`（可选）

当前行为：

- 校验用户名是否重复
- 生成 16 位随机初始密码
- 仅在本次响应中返回明文初始密码一次
- 新建用户默认写入：
  - `status = ACTIVE`
  - `deleted = false`

### 3.3 更新用户

- Endpoint：`PUT /api/admin/users/{userId}`
- Body：
  - `role`（可选）
  - `avatar`（可选）

当前实现支持更新角色和头像。

### 3.4 更新用户状态

- Endpoint：`PUT /api/admin/users/{userId}/status`
- Body：
  - `status`：`ACTIVE` 或 `DISABLED`

### 3.5 重置密码

- Endpoint：`PUT /api/admin/users/{userId}/password/reset`
- Body：空

当前实现不会接收调用方传入的新密码，而是：

- 重新生成随机密码
- 返回一次性明文密码
- 让目标用户重新登录

### 3.6 软删除用户

- Endpoint：`DELETE /api/admin/users/{userId}`

当前删除是软删除，不做物理删除。删除时会同时写入：

- `deleted = true`
- `status = DISABLED`

删除后的用户：

- 不出现在用户列表中
- 无法登录
- 无法 refresh
- 无法通过请求期鉴权

## 4. 数据模型与状态语义

### 4.1 `deleted` 与 `status` 的职责

当前代码中的账户状态语义已经收敛为：

- 正常账户：`deleted = false` 且 `status = ACTIVE`
- 临时停用账户：`deleted = false` 且 `status = DISABLED`
- 已删除账户：`deleted = true` 且 `status = DISABLED`

规则如下：

- 用户列表默认过滤 `deleted = true`
- 登录、refresh、请求期鉴权只要命中 `deleted = true` 或 `status = DISABLED`，都会拒绝
- 删除操作会同步把 `status` 置为 `DISABLED`

### 4.2 当前持久化落地

数据库当前已通过迁移新增 `t_user.status` 字段，并约束取值为：

- `ACTIVE`
- `DISABLED`

`status` 已贯穿到以下对象中：

- `User`
- `UserDTO`
- `LoginUser`
- `LoginUserVO`
- `LoginResponse`
- `UserProfileDTO`

### 4.3 用户名唯一性

当前数据库仍保留 `t_user.username` 的唯一约束。

这意味着：

- 即使用户被软删除，用户名当前也默认不可复用
- 若未来需要支持“删除后用户名复用”，需要额外调整唯一约束与查询策略

## 5. 安全护栏

当前实现已经包含以下护栏：

- 禁止管理员给自己降权
- 禁止管理员禁用自己的管理员账户
- 禁止管理员删除自己的账户
- 禁止移除最后一名活跃管理员

“活跃管理员”在当前代码中的定义为：

- `role = admin`
- `deleted = false`
- `status != DISABLED`

最后一名管理员保护当前通过数据库查询 `findActiveAdminsForUpdate()` 配合事务实现。

## 6. 审计日志

当前管理员用户管理流程已经接入审计日志。

成功操作会记录 `log.info`，包括：

- 列表查询
- 创建用户
- 更新用户
- 更新状态
- 重置密码
- 删除用户

被安全护栏拦截的拒绝操作也会记录 `log.warn`，例如：

- 重复用户名
- 删除自己
- 禁用自己
- 降权自己
- 删除 / 禁用 / 降权最后一名管理员

## 7. 前端页面现状

当前前端入口为：

- 菜单：Admin Side Nav 中的 `Users`
- 路由：`/admin/users`

### 7.1 页面行为

当前用户管理页已实现：

- 一个顶部 `New user` 按钮
- 用户名搜索框
- 状态筛选下拉框
- 用户表格
- 创建 / 编辑弹窗
- 一次性密码展示弹窗

当前表格列为：

- `User`
- `Role`
- `Status`
- `Created`
- `Updated`
- `Action`

当前 UI 不显示 `ID` 列。

### 7.2 行为细节

当前页面支持以下操作：

- 编辑用户
- 通过 `Switch` 启用 / 禁用用户
- 重置密码
- 删除用户

创建 / 编辑弹窗当前字段为：

- `Username`（仅创建时展示）
- `Role`
- `Avatar URL`

### 7.3 重复用户名提示

当前前端对“重复用户名”做了字段级反馈：

- 创建用户请求采用静默错误模式，不再弹通用失败 toast
- 若后端返回 `Username already exists`，错误会直接展示在 `Username` 输入框下方

### 7.4 管理台语言

当前 Admin Layout 单独使用 `antd` 的英文 locale，因此分页文案等后台组件文本显示为英文，不继承站点根部的中文 locale。

## 8. 已完成验证

当前已经完成的主要验证包括：

- 后端定向测试：
  - `UserAdminFacadeServiceImplTest`
  - `JwtAuthenticationInterceptorTest`
- 前端构建：
  - `npm run build`

这些验证已覆盖以下关键路径：

- 创建用户
- 禁用失效用户
- 使用最新用户快照鉴权
- 最后一名管理员保护
- 软删除与会话撤销
- 不能删除自己的管理员账户

## 9. 当前已知边界

当前实现仍然保持以下边界：

- 不支持批量导入 / 批量删除 / 批量禁用
- 不支持物理删除
- 不支持软删除后用户名复用
- 不支持比 `admin` / `user` 更细粒度的角色模型

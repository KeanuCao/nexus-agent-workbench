# task.2 地基搭建：多租户 + 用户权限（阶段1）

> 任务级别：L1 阶段级（跨模块、影响架构） ｜ 状态：🚧 **代码已交付（2026-09-13），待实机联调**
> 设计文档：`docs/design/01-多租户与认证.md` ｜ 测试案例：`docs/test-cases/TC-01.md`

## 任务目标
跑通登录

## 执行要求
本任务为 L1 阶段级任务：先产出设计文档并经确认后再编码；完成后更新 `docs/核心任务.md` 进度标记并生成 TC-01。

## 子任务拆解

### 1.1 首条补丁内容规划

`/db-patch/202609031000_初始化多租户基础表.sql`（时间戳落地时按实际生成时间调整）：

1. `CREATE TABLE IF NOT EXISTS t_db_patch (...)` —— 与引擎引导建表互为冗余保险，让记录表自身也有补丁溯源；
2. `CREATE EXTENSION IF NOT EXISTS vector;` —— pgvector 扩展启用（官方镜像默认 superuser，有权限）；
3. 多租户基础表 `t_tenant`（tenant_id BIGSERIAL PK、tenant_name、status、created_at、updated_at 最小集）—— 字段级详细设计归阶段1（task.2），此处只落最小可运行集，避免越界设计。


### 1.1 后端基础框架（父 POM + 统一返回/异常体系）
- **输入**：task.1 的 0.4 骨架
- **输出**：父 POM（Spring Boot 3.3.x、`spring-boot-starter-web`、`mybatis-plus-boot-starter`、`jjwt`）、nexus-common 的 `Result<T>`、`BusinessException` / `SystemException`、`@RestControllerAdvice` 全局异常处理
- **依赖**：0.4
- **验收标准**：
  - 所有 API 返回 `Result<T>`（code / msg / data）
  - 全局异常不向返回堆栈；业务异常前端可展示，系统异常记日志并返回通用错误
  - 无 `*` 导入、无 Python 风格命名

### 1.2 JWT 认证过滤器
- **输入**：1.1
- **输出**：`JwtUtil` + 认证过滤器（OncePerRequestFilter）：从请求头取 Token → 解析 userId / tenantId 写入上下文，登录态存 Redis
- **依赖**：1.1
- **验收标准**：
  - 无 Token / 过期 / 伪造 Token 返回 401 统一格式
  - 合法 Token 放行，后续接口可取到当前用户与租户
  - Token 与 Redis 联动，登出即失效

### 1.3 多租户隔离
- **输入**：1.1、task.1 的 0.2
- **输出**：`t_tenant` 表（db-patch 补丁）、用户表关联 `tenant_id`、MyBatis-Plus `TenantLineHandler` 自动注入 + `@IgnoreTenant` 注解
- **依赖**：1.1、0.2
- **验收标准**：
  - 任何查询自动带 `tenant_id` 条件（SQL 日志可见）
  - 两个租户的数据互不可见
  - `@IgnoreTenant` 场景（如登录按 username 查用户）可正确跳过

### 1.4 登录接口
- **输入**：1.2、1.3
- **输出**：`/api/auth/login`（`@PostMapping`，显式声明 produces / consumes）
- **依赖**：1.2、1.3
- **验收标准**：
  - 正确账号密码返回 Token
  - 密码错误返回业务错误码（BusinessException），不抛堆栈
  - 返回的 Token 可访问受保护接口

### 1.5 前端登录页与鉴权骨架
- **输入**：task.1 的 0.4 前端骨架
- **输出**：登录页、Pinia 存 Token、Axios 拦截器（自动携带 Token、401 跳登录）、路由守卫
- **依赖**：1.4
- **验收标准**：登录成功进入控制台；无 Token 访问受保护页跳转登录；401 自动登出

## 🚩 阶段交付物
`/api/auth/login` 接口 + 前端登录页

## ✅ 阶段验收标准
使用预设租户账号登录成功拿到 Token，前端进入控制台

## 完成状态

> 2026-09-13 交付。**勾选口径与阶段0 一致：「已实现」不等于「已验收」** —— 未通过实机验收的一律不勾。

- [x] 1.1 后端基础框架 —— ✅ 已完成（父 POM 与统一返回/异常体系在阶段0 已就位；本轮补 `UnauthorizedException` 与 6 个认证错误码）
- [x] 1.2 JWT 认证过滤器 —— ✅ 已完成（`JwtUtil` / `JwtAuthenticationFilter` / `TokenStore`；单测 9 + 9 通过）
- [x] 1.3 多租户隔离 —— ✅ 已完成（`TenantContext` / `TenantLineHandlerImpl` fail-closed / `@IgnoreTenant` + 切面；单测 7 通过）
- [x] 1.4 登录接口 —— ✅ 已完成（`/api/auth/login` + `logout` + `me`；单测 12 通过）
- [x] 1.5 前端登录页与鉴权骨架 —— ✅ 已完成（登录页 / Pinia store / 拦截器 / 路由守卫；`npm run type-check` 零错误）

### 📌 实现现状（2026-09-13）

**已验证**（在 builder 镜像内对本地未提交源码实跑，非推断）：

1. ✅ `mvn clean test-compile` → BUILD SUCCESS（主代码 + 测试代码全部通过）
2. ✅ `mvn test` → **37 个用例全绿**：JwtUtil 9 / JwtAuthenticationFilter 9 / TenantContext 7 / AuthServiceImpl 12
3. ✅ `npm run type-check`（vue-tsc --noEmit）→ exit 0、零类型错误（主会话独立复跑）
4. ✅ `openapi.yaml` YAML 解析通过，4 个 path 齐全（login/logout/me/health）

**未验证**（勾选阶段验收前必须补，全部是实机项）：

1. 登录接口真跑：`admin/admin123` 与 `demo/demo123` 能否拿到 token
2. **SQL 日志里 `tenant_id` 条件的实际形态**（1.3-1 的验收判据是"SQL 日志可见"）
3. 两租户数据互不可见（1.3-2）：用 A 的 token 查不到 B 的用户
4. 401 三态端到端（无 token / 伪造 / 过期）与**登出即失效**（1.2-3）
5. 前端三条交互验收（1.5-1/2/3）—— 其中 1.5-3 有个陷阱：`/api/health` 是白名单接口，
   改坏 localStorage 的 token 后**必须刷新页面**才会触发 `/me` → 401 → 自动登出

**文档债已清**（2026-09-13）：`docs/test-cases/` 目录已建立，TC-00（18 个用例）与 TC-01（21 个用例）均已生成。
TC-00 的展开依据 `docs/design/00-环境与部署.md` §7 修订后才展开 —— 修正了 3 行失效判据、补了 4 行新架构验收点。

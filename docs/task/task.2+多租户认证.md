# task.2 地基搭建：多租户 + 用户权限（阶段1）

> 任务级别：L1 阶段级（跨模块、影响架构） ｜ 状态：⏳ 待开始
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
- [ ] 1.1 后端基础框架
- [ ] 1.2 JWT 认证过滤器
- [ ] 1.3 多租户隔离
- [ ] 1.4 登录接口
- [ ] 1.5 前端登录页与鉴权骨架

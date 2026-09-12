# nexus-infrastructure

基础设施模块：**MyBatis-Plus 多租户 / JWT / db-patch 引擎**。

## 阶段0 交付范围

- 依赖就位（MyBatis-Plus、JDBC/Hikari、PostgreSQL 驱动），**可编译的骨架**；
- 包结构占位（`tenant` / `security` / `dbpatch`，各含 `package-info.java` 说明规划）；
- 不写多租户拦截器、JWT、db-patch 实现 —— 分别属阶段1 与子任务 0.2 的范围。

## 依赖归属说明

| 依赖 | 作用域 | 为什么在本模块 |
| --- | --- | --- |
| `nexus-common` | compile | 依赖方向：infrastructure → common |
| `mybatis-plus-spring-boot3-starter` | compile | Boot 3 专用 starter，ORM 归属本模块 |
| `spring-boot-starter-jdbc` | compile | 显式声明（MP 只是间接带入）：JDBC 与连接池 HikariCP 的实际归属方 |
| `org.postgresql:postgresql` | runtime | JDBC 驱动仅运行期需要；`nexus-start` 经传递依赖获得，无需重复声明 |

版本全部由父 POM 的 `<dependencyManagement>` 管理（MyBatis-Plus 选型理由见父 POM 注释）。

## 计划内容

| 包 | 阶段 | 内容 |
| --- | --- | --- |
| `tenant` | 阶段1 | `TenantLineHandler` 自动注入 `tenant_id`、`@IgnoreTenant` 跳过注解、租户上下文持有者 |
| `security` | 阶段1 | JWT 签发/校验、认证过滤器、Redis 会话缓存 |
| `dbpatch` | 子任务 0.2 | `PatchCli`：扫补丁 → 排序 → 事务执行 + SHA-256 checksum 幂等防篡改 |

## 约束

- 应用启动**不得**依赖外部服务可用：数据源连接池由 `nexus-start` 的 `application.yml` 配置为
  `initialization-fail-timeout: -1`（不做初始连接尝试），DB 不可达时应用照常启动。
- 多租户强制隔离：任何查询都必须带租户条件（除 `@IgnoreTenant`）—— 阶段1 落地时不得留下绕过口子。

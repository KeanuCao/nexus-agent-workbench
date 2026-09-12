# nexus-module-system

系统模块：**用户 / 角色 / 菜单**。阶段0 为空壳模块，阶段1（`docs/task/task.2+多租户认证.md`）填充。

## 阶段0 交付范围

- 仅包结构占位（`com.nexus.module.system`，见 `src/main/java/.../package-info.java`）与依赖链路；
- 不写任何业务代码 —— 目的是让 `mvn clean install -DskipTests` 的全模块链路先跑通。

## 依赖

- `nexus-common`、`nexus-infrastructure`（版本由父 POM 统一管理，本模块不写 `<version>`）
- 禁止反向依赖，禁止与 `nexus-module-ai` 平级互依（依赖方向见父 POM 顶部注释与设计文档 §5.1）

## 阶段1 计划内容

| 内容 | 说明 |
| --- | --- |
| `controller` | `/api/auth/login` 登录接口 |
| `entity` / `mapper` | `t_tenant`、用户表（含 `tenant_id`）等 |
| `service` | 登录校验、Token 签发编排 |

包分层约定：`controller` / `service`（+ `impl`）/ `mapper` / `entity` / `model`。

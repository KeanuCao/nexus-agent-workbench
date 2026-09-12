# backend —— nexus 统一 AI 网关（Maven 多模块）

Java 17 + Spring Boot 3.3.x + MyBatis-Plus 3.5.x，构建产物为可执行 fat jar。

## 模块与依赖方向

```
nexus-common          Result / 异常体系 / 工具类（零第三方依赖）
nexus-infrastructure  MyBatis-Plus 多租户 / JWT / db-patch 引擎
nexus-module-system   用户 / 角色 / 菜单        （阶段1 填充，阶段0 空壳）
nexus-module-ai       网关 / RAG / Agent 编排   （阶段2-4 填充，阶段0 空壳）
nexus-start           Application 主类 + application.yml + 健康检查（唯一可执行 jar）
```

依赖规则（硬约束）：**只允许上层依赖下层，禁止反向/平级互依**。

```
infrastructure → common
module-system  → common + infrastructure
module-ai      → common + infrastructure
nexus-start    → module-system + module-ai + infrastructure
```

## 构建与运行

```bash
# 全模块编译 + 安装到本地仓库（父 POM 用于统一管理版本）
mvn clean install -DskipTests

# 仅启动模块（含其依赖模块）
mvn -pl nexus-start -am spring-boot:run

# 打包产物（仅 nexus-start 产出可执行 jar）
mvn -DskipTests package && java -jar nexus-start/target/nexus-start-0.1.0.jar
```

容器化构建见 `docker-compose/backend/Dockerfile`（stage 1 = `nexus-builder`，stage 2 = JRE 瘦身镜像，容器内端口固定 8089）。

## 打包约定（与 Dockerfile 的耦合点）

- **仅 `nexus-start` 声明 `spring-boot-maven-plugin`**，其余模块为普通 jar；
- `nexus-start/target/` 下必须只有一个 `*.jar`：Dockerfile 用
  `COPY --from=build /build/nexus-start/target/*.jar /app/app.jar` 通配拷贝。
  故全项目**不启用** `maven-source-plugin` / `maven-javadoc-plugin`
  （会产生 `-sources.jar` 使通配匹配到多个文件而构建失败）。
- 本项目无 `<build>` 级插件绑定，编译参数（`-parameters`）、资源过滤（`application*.yml`
  使用 `@..@` 分隔符）等均由 `spring-boot-starter-parent` 提供。

## 版本选型

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.3.13 | CLAUDE.md 锁定 3.3.x，取该线最后一个补丁版（已核对 Maven Central） |
| Java | 17 | 与构建/运行镜像的 Temurin 17 一致 |
| MyBatis-Plus | 3.5.9 | `mybatis-plus-spring-boot3-starter`，其内置 BOM 为 Boot 3.3.4 —— 与 3.3.x 同代（选型对比见父 POM 注释） |

## 编码规范要点（CLAUDE.md 宪法）

- 禁止 `import xxx.*;`、禁止 `_` 作变量名、禁止滥用 `var`；类名大驼峰、方法与变量小驼峰；
- Controller 用 `@GetMapping` / `@PostMapping` 等组合注解，**必须显式声明** `produces` / `consumes`；
- 所有接口返回 `Result<T>`；异常分 `BusinessException`（可展示）与 `SystemException`（只记日志）；
- 关键流程（探活、大模型调用、向量入库）打 `log.info`，便于展示调用链路。

## 文档

- 接口契约：[`../docs/api/README.md`](../docs/api/README.md)（速查）、[`../docs/api/openapi.yaml`](../docs/api/openapi.yaml)（OpenAPI 3.0.3）
- 环境与部署设计：[`../docs/design/00-环境与部署.md`](../docs/design/00-环境与部署.md)

# nexus-start

启动模块：**Application 主类 + application.yml + 健康检查**。全项目唯一的可执行 jar 出口。

## 目录结构

```
src/main/java/com/nexus/start/
├── NexusApplication.java          # @SpringBootApplication(scanBasePackages = "com.nexus")
├── config/NexusHealthProperties   # nexus.health.* 配置绑定
├── controller/HealthController    # GET /api/health
├── service/HealthService(+Impl)   # 汇总探活结果、组装报告
├── probe/                         # 依赖真实连通性探测（策略化，一个依赖一个实现）
│   ├── DependencyProbe            #   接口：name() + check()
│   ├── DependencyStatus           #   UP / DOWN（枚举名即 JSON 取值）
│   ├── PostgresProbe              #   JDBC SELECT 1
│   ├── RedisProbe                 #   PING / PONG
│   └── OllamaProbe                #   GET /api/version（RestClient）
├── model/                         # HealthReport / HealthChecks（响应体 data 的定义）
└── handler/GlobalExceptionHandler # @RestControllerAdvice：业务 / 系统 / 404 / 兜底
src/main/resources/application.yml
```

## 关键设计（面试问答备料）

1. **健康检查不造假**：`checks` 的每一项都是真实交互（`SELECT 1` / `PING` / `/api/version`），
   而非"配置是否存在"的间接判断 —— 这是演示链路的第一步。
2. **探活失败 ≠ 启动失败**：Hikari `initialization-fail-timeout: -1` 让连接池不做初始连接尝试，
   DB/Redis 不可达时应用照常启动，只把对应 `checks` 标 `DOWN` 并让 `/api/health` 返回 503。
3. **两层健康**：`/api/health`（业务可用性，含 AI 依赖，给前端与演示用）；`/actuator/health`
   （容器级，给 compose healthcheck 用）。Ollama 刻意**不**进容器级判据 —— 模型拉取耗时长，
   纳入会阻塞前端启动。
4. **契约收窄**：`UP`/`DOWN` 两态（不复用 Spring `HealthStatus` 的 4 态），前端无需处理未定义分支。
5. **探活实现可插拔**：新增依赖只需实现 `DependencyProbe` 并注册 Bean；
   但响应体结构由 `HealthChecks` record 显式固定 —— 契约稳定性优先于扩展便利性。

## 本地（Windows 宿主机）直跑

容器内默认指向 compose 服务名（`postgres` / `redis` / `ollama`），本地直跑时用环境变量覆盖：

```bash
# PowerShell 示例（先确保 PG / Redis / Ollama 已在 WSL 容器中起来并映射到本机端口）
$env:POSTGRES_HOST="localhost"; $env:REDIS_HOST="localhost"
$env:OLLAMA_BASE_URL="http://localhost:11434"
mvn -pl nexus-start -am spring-boot:run
# 或运行已打包的 fat jar
java -jar nexus-start/target/nexus-start-0.1.0.jar
```

启动后：`curl http://localhost:8089/api/health`（依赖全通时 HTTP 200，否则 503）。

> 注：本模块不提供 `settings.xml`；Maven 镜像源（阿里云）由构建容器侧注入，属 devops-engineer 范围。

## 打包与产物约束

- `nexus-start/target/` 下**只允许一个 `*.jar`** —— `docker-compose/backend/Dockerfile` 以
  `COPY --from=build /build/nexus-start/target/*.jar /app/app.jar` 通配拷贝；
  `repackage` 产生的 `*.jar.original` 不命中通配（无害），但 `-sources.jar` 会命中并导致构建失败，
  故本模块禁止启用 `maven-source-plugin` / `maven-javadoc-plugin`。
- 产物为可执行 fat jar（`Main-Class: JarLauncher`，`Start-Class: com.nexus.start.NexusApplication`），
  运行镜像 `eclipse-temurin:17-jre-alpine` 内无构建工具链，靠它独立启动。

# nexus-agent-workbench — AI 数字员工平台（演示项目）

Java 全栈求职面试展示项目：用 **Java 17 + Spring Boot 3.3 + Vue 3 (script setup) + TypeScript** 复刻「AI 数字员工平台」的核心能力 —— 统一 AI 网关、RAG 知识库问答、多租户与 Agent 编排。数据库（PostgreSQL + pgvector）、缓存（Redis）、本地大模型（Ollama）与前后端全部容器化，一条命令拉起整套环境。

> **当前进度（阶段0 进行中）**：环境编排、前后端骨架、健康检查与运维脚本已交付；db-patch 补丁工作流待落地。多租户登录（阶段1）、统一 AI 网关（阶段2）、RAG 知识库（阶段3）、Agent 编排（阶段4）均为**规划中**。进度以 [`docs/核心任务.md`](docs/核心任务.md) 为准。

## 目录

- [一键启动](#一键启动)
- [三个脚本怎么区分](#三个脚本怎么区分)
- [服务与端口](#服务与端口)
- [本地开发（前后端分别启动）](#本地开发前后端分别启动)
- [常用验证命令](#常用验证命令)
- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [文档索引](#文档索引)

## 一键启动

> 所有 docker / docker compose 都在 **WSL 发行版 `nexus-agent-workbench`** 内运行，Windows 侧不直接跑 docker。
> 前置条件：Windows 11 + WSL2，且该发行版内已装好 Docker 与 compose 插件；仓库位于 `C:\wp\nexus-agent-workbench`（仓库换了位置时，同步改下面命令里的路径）。

```bash
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/up.sh"
```

`up.sh` 依次执行九步：
① 前置自检（FAIL 即中止）→ ② 构建打包容器 `builder` → ③ 拉起 postgres / redis / ollama → ④ 执行 db-patch 迁移（当前条件跳过）→ ⑤ 多阶段构建前后端镜像 → ⑥ 拉起 backend / frontend（同时由一次性容器 `nexus-ollama-init` 拉模型）→ ⑦ 等两个模型就绪（约 5GB，超时 30min 只告警不中止）→ ⑧ 健康检查（容器级 + 业务级）→ ⑨ 打印服务清单与常用命令。

首次执行要拉取基础镜像（约 9GB，含 Ollama fat 镜像）并下载两个模型（约 5GB），耗时较长属正常；模型落在命名卷 `ollama-models` 里，之后重建容器不会重新下载。

也可以分步执行（在 WSL 内，任一步都可单独重跑）：

```bash
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/check-env.sh"
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/check-health.sh"
```

> 阶段0 的脚本与骨架已交付，实机验收仍在进行中 —— 逐项验收状态见 [`docs/task/task.1+环境准备.md`](docs/task/task.1+环境准备.md)。

## 三个脚本怎么区分

三个脚本各管一段，**定位与退出码语义不同，别混用**：

| 脚本 | 定位 | 何时运行 | 失败语义 |
|------|------|----------|----------|
| `scripts/check-env.sh` | **启动前置自检（门禁）**：问"这台机器能不能把环境拉起来" | 环境起来**之前**（`up.sh` 第 1 步会调用） | 任一 `[FAIL]` → 退出码非 0 → **`up.sh` 中止**。判据必须在"一个容器都没起"的机器上可复现（所以"模型就绪"在这里只判 WARN） |
| `scripts/up.sh` | **一键拉起**：九步全流程 | 首次启动 / 重置环境 | 自检 FAIL、构建失败、依赖不健康 → 中止；模型拉取超时 → 只告警不中止 |
| `scripts/check-health.sh` | **运行期巡检**：对"此刻"的环境做快照报告 | 环境起来**之后**，任何时刻可重跑 | `[FAIL]` **仅报告，不中止任何流程**；退出码只表达报告结论。容器一个都没起时也会正常跑完，并提示"若尚未启动，请先执行 up.sh" |

三者共用同一套探针判据（`scripts/lib/probe.sh`），巡检分三层：容器级（容器状态 + compose healthcheck）→ 业务级（`GET /api/health` 的 `checks`）→ 模型级（`/api/tags`）。

## 服务与端口

> 端口唯一真源是 [`docker-compose/.env`](docker-compose/.env)（下表为当前默认值）；冲突时只改 `.env` 一处。
> **宿主端口**供 Windows 侧访问；**容器内端口固定**不随宿主端口漂移（健康检查与 nginx 反代都按容器内端口固定写死）。

| 服务 | 容器名 | 宿主端口（`.env` 变量） | 容器内端口 | 说明 |
|------|--------|------------------------|-----------|------|
| PostgreSQL 16 + pgvector | `nexus-postgres` | 5432（`PG_PORT`） | 5432 | 库 / 用户 `nexus`，dev 凭据见 `.env` |
| Redis 7 | `nexus-redis` | 6379（`REDIS_PORT`） | 6379 | dev 不设密码 |
| Ollama（本地大模型） | `nexus-ollama` | 11434（`OLLAMA_PORT`） | 11434 | CPU 推理；模型落卷 `ollama-models` |
| 后端 API | `nexus-backend` | **8089**（`BACKEND_PORT`） | **8089** | Spring Boot；`GET /api/health` 在此 |
| 前端页面（nginx） | `nexus-frontend` | **8088**（`FRONTEND_PORT`） | **80** | 静态托管 + 反代 `/api → nexus-backend:8089` |

另有两个非常驻容器：`nexus-ollama-init`（一次性拉模型，`restart: "no"`，**退出码 0 = 两个模型拉取成功**）、`nexus-builder`（打包 / db-patch 迁移用，`profiles: ["build"]` 隔离，运行期不常驻）。容器间互访走 compose 网络 `nexus-net` 的服务名，不依赖宿主 DNS。

Windows 侧访问：前端页面 `http://localhost:8088`，后端接口 `http://localhost:8089/api/health`。

## 本地开发（前后端分别启动）

后端（在 `backend/` 目录执行）：

```bash
mvn clean install -DskipTests && java -jar nexus-start/target/*.jar
```

- PowerShell 下通配符不会被展开，请直接写实际文件名：`java -jar nexus-start/target/nexus-start-0.1.0.jar`（WSL / Git Bash 中通配符可用）。
- 本地直跑时数据源 / Redis / Ollama 地址默认是容器服务名（`postgres` / `redis` / `ollama`），需用环境变量覆盖为 `localhost`（依赖容器仍由 compose 提供），详见 [`docs/api/README.md`](docs/api/README.md) §3。

前端（在 `frontend/` 目录执行）：

```bash
npm install && npm run dev
```

访问 `http://localhost:5173`。Vite dev server 已配置代理 `/api → http://localhost:8089`，页面上的健康检查面板可直接调通后端（后端需已启动）。

## 常用验证命令

> Windows PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，请用 `curl.exe`；WSL / Git Bash 内直接用 `curl`。

```bash
# ① 后端健康检查（业务级契约：Result<T>，checks 三项应均为 UP）
curl http://localhost:8089/api/health
# → {"code":0,"msg":"success","data":{"status":"UP","service":"nexus-start","version":"0.1.0",
#    "timestamp":"...","checks":{"postgres":"UP","redis":"UP","ollama":"UP"}}}

# ② 经前端 nginx 反代的健康检查（验证前端容器与 /api 代理链路）
curl http://localhost:8088/api/health
# → 与 ① 相同的 JSON

# ③ 前端页面可达
curl -I http://localhost:8088
# → HTTP/1.1 200 OK
```

任一依赖不可用时 `/api/health` 返回 **HTTP 503** 且 `code=20000`，body 仍是完整 JSON、`data.checks` 会点名 DOWN 的依赖（如 `"ollama":"DOWN"`）。取数请用 `curl -s`（**不加 `-f`**，否则 503 时 body 会丢）。走前端端口只能查 `/api/health`，`/api/actuator/health` 必然 404。

```bash
# ④ 容器状态（WSL 内执行；必须带 -a —— 否则看不到已退出的 nexus-ollama-init）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose ps -a"

# ⑤ 运行期巡检（三层判据 + 失败项的下一步指引）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/check-health.sh"
```

## 项目结构

```
nexus-agent-workbench/
├── backend/                     # Spring Boot 3.3 后端（Maven 多模块，仅 nexus-start 产出可执行 jar）
│   ├── nexus-common             #   Result<T> 统一返回体 / 异常体系 / 工具类
│   ├── nexus-infrastructure     #   多租户拦截器 / JWT / db-patch 引擎
│   ├── nexus-module-system      #   用户 / 角色 / 菜单（阶段1 填充，当前空壳）
│   ├── nexus-module-ai          #   AI 网关 / RAG / Agent 编排（阶段2-4 填充，当前空壳）
│   └── nexus-start              #   主类 + application.yml + 健康检查（GET /api/health）
├── frontend/                    # Vue 3 (script setup) + TypeScript（Vite）
│   └── src/                     #   api/ 接口封装 · views/ 页面 · components/ 组件 · router/ · stores/
├── docker-compose/              # 环境编排：docker-compose.yml + .env + 各服务 Dockerfile
├── scripts/                     # check-env.sh / up.sh / check-health.sh / lib/probe.sh
├── db-patch/                    # 数据库补丁 YYYYMMDDHHmm_描述.sql（0.2 落地，目录尚未创建）
├── docs/                        # design/ 设计文档 · api/ 接口契约 · task/ 任务拆解 · agent-log/ 工作日志
├── wsl.abc.md                   # WSL 排查手册（四段式：现象 → 日志证据 → 官方文档 → 结论）
└── CLAUDE.md                    # 项目"宪法"：编码规范、技术选型、协作约定
```

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.3.13 · MyBatis-Plus 3.5.9 · Maven 多模块 |
| 数据库 | PostgreSQL 16 + pgvector（向量检索） |
| 缓存 | Redis 7 |
| AI 能力 | Ollama 本地 CPU 推理：`qwen2.5:7b`（对话）+ `nomic-embed-text`（向量化）；统一网关后续规划接入云端模型（DeepSeek） |
| 前端 | Vue 3 (script setup) + TypeScript · Vite 5 · Element Plus · Pinia · Vue Router · Axios |
| 部署 | Docker Compose（WSL2 内运行）· 多阶段构建（前后端共用 `builder` 构建阶段）· nginx 反代 |
| 测试（规划中） | JUnit 5 + Mockito（后端）· Vitest + Playwright（前端） |

## 文档索引

| 文档 | 内容 |
|------|------|
| [`CLAUDE.md`](CLAUDE.md) | 项目宪法：编码规范、技术选型、Agent 协作约定 |
| [`docs/核心任务.md`](docs/核心任务.md) | 作战地图：阶段划分与当前进度 |
| [`docs/design/00-环境与部署.md`](docs/design/00-环境与部署.md) | 阶段0 设计文档：架构、端口、镜像加速、脚本、健康检查 |
| [`docs/api/README.md`](docs/api/README.md) | 后端 API 契约：`Result<T>`、错误码、健康检查判据 |
| [`docs/task/task.1+环境准备.md`](docs/task/task.1+环境准备.md) | 阶段0 任务拆解、验收标准与验收现状 |
| [`backend/README.md`](backend/README.md) | 后端模块说明、打包约定与版本选型 |
| [`wsl.abc.md`](wsl.abc.md) | WSL 排查手册：实测记录 + 常见问题预案 |

## License

MIT（见 [LICENSE](LICENSE)）

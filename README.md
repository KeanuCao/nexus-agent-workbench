# nexus-agent-workbench — AI 数字员工平台（演示项目）

Java 全栈求职面试展示项目：用 **Java 17 + Spring Boot 3.3 + Vue 3 (script setup) + TypeScript** 复刻「AI 数字员工平台」的核心能力 —— 统一 AI 网关、RAG 知识库问答、多租户与 Agent 编排。数据库（PostgreSQL + pgvector）、缓存（Redis）、本地大模型（Ollama）与前后端全部容器化，一条命令拉起整套环境。

---

> ### 📁 想跑这个项目？请克隆到 `C:\wp\nexus-agent-workbench`
>
> **这是省事的做法，不是硬性要求。** 请把仓库放在 `C:\wp\` 下 —— 本项目的**文档、脚本与验收步骤里大量出现 `/mnt/c/wp/nexus-agent-workbench` 这个绝对路径**，而那是「命令必须可直接复制粘贴执行」这条纪律的产物（见 [`docs/核心任务.md`](docs/核心任务.md) 的**测试案例约定**）。
>
> 克隆到别处会怎样，说清楚：
>
> | | 换位置后 |
> |---|---|
> | **脚本**（`scripts/`、`qa/scripts/`） | ✅ **照常能跑** —— 仓库根一律从 `BASH_SOURCE` 派生（`scripts/sh/lib/probe.sh`），没有任何一处写死 |
> | **少数提示文案** | ⚠️ 脚本**打印出来**的建议命令会指错地方（如 `check-env.sh` 的"下一步执行"），脚本本身不受影响 |
> | **文档里的命令**（README / `docs/test-cases/` / `docs/design/`） | ❌ **全部指错地方** —— 你得在脑子里逐条换算成自己的路径 |
>
> 放在 `C:\wp\` 下，上面第三类可以**原样粘贴执行**，省掉这一层。
> （文中提到的 `/mnt/c/...` 是 **WSL 视角**；Windows 视角是 `C:\...`；builder 容器内是 `/workspace/repo`。三个视角的换算见 [`qa/README.md`](qa/README.md)。）

---

> **当前进度（阶段0 进行中）**：环境编排、前后端骨架、健康检查与运维脚本已交付；db-patch 补丁工作流**代码已交付、待实机验收**。多租户登录（阶段1）、统一 AI 网关（阶段2）、RAG 知识库（阶段3）、Agent 编排（阶段4）均为**规划中**。进度以 [`docs/核心任务.md`](docs/核心任务.md) 为准。
>
> ⚠️ **2026-09-13 构建链路重构**：`builder` 容器改为**手工启停**，不再挂载宿主源码（容器内 `git pull`），产物写到共享卷 `build-artifacts` 供前后端容器挂载取用。
> **由此产生一条硬约束：改完必须先 `commit` + `push`**，否则容器里拉到的还是旧代码。详见 [`docs/design/01-多租户与认证.md`](docs/design/01-多租户与认证.md) §2。

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

**动手之前先确认一件事**（否则会构建出旧代码，而且不容易发现）：

**代码合并并推送到 `main` 了吗？** builder 容器是从**远端**拉代码的，宿主本地未 `push` 的提交它看不到。

> **本项目约定的工作流是「先合并再构建」**：功能分支开发完 → 合并进 `main` → push → 再跑 `up.sh`。
> 因此 `docker-compose/.env` 的 `NEXUS_REPO_BRANCH` **保持默认的 `main` 即可**；
> 只有确需构建某个**尚未合并**的分支时，才临时改成那个分支名。

```bash
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/sh/up.sh"
```

`up.sh` 依次执行九步：
① 前置自检（FAIL 即中止）→ ② 构建并启动打包容器 `builder` → ③ 拉起 postgres / redis / ollama → ④ 同步源码（容器内 git pull，**只能拿到已 push 的提交**）→ ⑤ 执行 db-patch 迁移 → ⑥ 前后端打包到共享目录 `build-artifacts` → ⑦ 构建运行镜像并拉起 backend / frontend（同时由一次性容器 `nexus-ollama-init` 拉模型）→ ⑧ 等两个模型就绪（约 5GB，超时 30min 只告警不中止）→ ⑨ 健康检查（容器级 + 业务级）与服务清单。

首次执行要拉取基础镜像（约 9GB，含 Ollama fat 镜像）、下载两个模型（约 5GB），并让 builder 下载 Maven / npm 依赖（约 150MB+），耗时较长属正常。三者都落在命名卷里（`ollama-models` / `builder-m2` / `builder-npm`），之后重建容器不会重新下载。

> **源码有两份，别搞混**：宿主仓库 `C:\wp\nexus-agent-workbench` 是**开发真源**；builder 容器内 `/workspace/repo` 那份是**构建用的克隆副本**，只读不写、每次 `git-sync` 同步到远端分支的最新提交。所以"改了没生效"的第一排查方向是——推没推。

也可以分步执行（在 WSL 内，任一步都可单独重跑）：

```bash
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/sh/check-env.sh"
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/sh/check-health.sh"
```

> 阶段0 的脚本与骨架已交付，实机验收仍在进行中 —— 逐项验收状态见 [`docs/task/task.1+环境准备.md`](docs/task/task.1+环境准备.md)。

## 三个脚本怎么区分

三个脚本各管一段，**定位与退出码语义不同，别混用**：

| 脚本 | 定位 | 何时运行 | 失败语义 |
|------|------|----------|----------|
| `scripts/sh/check-env.sh` | **启动前置自检（门禁）**：问"这台机器能不能把环境拉起来" | 环境起来**之前**（`up.sh` 第 1 步会调用） | 任一 `[FAIL]` → 退出码非 0 → **`up.sh` 中止**。判据必须在"一个容器都没起"的机器上可复现（所以"模型就绪"在这里只判 WARN） |
| `scripts/sh/up.sh` | **一键拉起 + 构建**：九步全流程（同步源码 → 迁移 → 打包 → 起容器）。2026-09-13 起构建也由它承担 | 首次启动 / 改完代码要重建 | 自检 FAIL、构建失败、依赖不健康 → 中止；模型拉取超时 → 只告警不中止 |
| `scripts/sh/check-health.sh` | **运行期巡检**：对"此刻"的环境做快照报告 | 环境起来**之后**，任何时刻可重跑 | `[FAIL]` **仅报告，不中止任何流程**；退出码只表达报告结论。容器一个都没起时也会正常跑完，并提示"若尚未启动，请先执行 up.sh" |

三者共用同一套探针判据（`scripts/sh/lib/probe.sh`），巡检分三层：容器级（容器状态 + compose healthcheck）→ 业务级（`GET /api/health` 的 `checks`）→ 模型级（`/api/tags`）。

## 服务与端口

> 配置唯一真源是 [`docker-compose/.env`](docker-compose/.env)（下表为当前默认值）；冲突时只改 `.env` 一处。
> **宿主端口**供 Windows 侧访问；**容器内端口固定**不随宿主端口漂移（健康检查与 nginx 反代都按容器内端口固定写死）。
> `.env` 里还有两项 builder 配置：`NEXUS_REPO_URL`（仓库地址）、`NEXUS_REPO_BRANCH`（构建哪个分支）。

| 服务 | 容器名 | 宿主端口（`.env` 变量） | 容器内端口 | 说明 |
|------|--------|------------------------|-----------|------|
| PostgreSQL 16 + pgvector | `nexus-postgres` | 5432（`PG_PORT`） | 5432 | 库 / 用户 `nexus`，dev 凭据见 `.env` |
| Redis 7 | `nexus-redis` | 6379（`REDIS_PORT`） | 6379 | dev 不设密码 |
| Ollama（本地大模型） | `nexus-ollama` | 11434（`OLLAMA_PORT`） | 11434 | CPU 推理；模型落卷 `ollama-models` |
| 后端 API | `nexus-backend` | **8089**（`BACKEND_PORT`） | **8089** | Spring Boot；`GET /api/health` 在此 |
| 前端页面（nginx） | `nexus-frontend` | **8088**（`FRONTEND_PORT`） | **80** | 静态托管 + 反代 `/api → nexus-backend:8089` |

另有：`nexus-ollama-init`（一次性拉模型，`restart: "no"`，**退出码 0 = 两个模型拉取成功**）；`nexus-builder`（打包 / db-patch 迁移用，**手工启停的常驻容器**，`profiles: ["build"]` 隔离 —— 裸 `docker compose up -d` 不会拉起它，要显式 `up -d builder`）。
容器间互访走 compose 网络 `nexus-net` 的服务名，不依赖宿主 DNS。

**builder 的手工用法**（需要打包时启动，用完停掉）：

```bash
cd /mnt/c/wp/nexus-agent-workbench/docker-compose
docker compose up -d builder                          # 启动
docker compose exec builder git-sync                  # 拉取已 push 的源码
docker compose exec builder build-all                 # 前后端打包 → /artifacts
docker compose exec builder db-patch-migrate          # 执行数据库补丁迁移
docker compose stop builder                           # 用完停掉
```

产物落在命名卷 `build-artifacts`（`/artifacts/backend/app.jar`、`/artifacts/frontend/**`），前后端容器只读挂载同一卷。
想查看卷里有什么：`docker run --rm -v build-artifacts:/a alpine ls -lR /a`

> **⚠️ 运行镜像不再自包含** —— 这带来两个具体后果，是 2026-09-13 架构变更最需要记住的一点：
> ① **裸 `docker compose up -d` 不再是可靠入口**（产物还没生成时后端会启动失败）。请走 `up.sh`，它保证顺序；
> ② 想只更新应用代码，**不必重建任何镜像**：`docker compose exec builder build-all` 之后 `docker compose restart nexus-backend` 即可。
> 后端产物缺失时入口脚本会打印三步修复命令；前端则是 nginx 照常启动、**全部 404 + 容器 unhealthy**（别把 404 当配置写错）。

Windows 侧访问：前端页面 `http://localhost:8088`，后端接口 `http://localhost:8089/api/health`。

## 本地开发（前后端分别启动）

后端（在 `backend/` 目录执行）：

```bash
mvn clean install -DskipTests && java -jar nexus-start/target/*.jar
```

- PowerShell 下通配符不会被展开，需写出实际文件名（`nexus-start/target/nexus-start-<版本号>.jar`，版本号取 `backend/pom.xml` 的 `<revision>`）；WSL / Git Bash 中可直接用通配 `nexus-start/target/*.jar`。
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
# → {"code":0,"msg":"success","data":{"status":"UP","service":"nexus-start","version":"0.2.0",
#    "timestamp":"...","checks":{"postgres":"UP","redis":"UP","ollama":"UP"}}}

# ② 经前端 nginx 反代的健康检查（验证前端容器与 /api 代理链路）
curl http://localhost:8088/api/health
# → 与 ① 相同的 JSON

# ③ 前端页面可达
curl -I http://localhost:8088
# → HTTP/1.1 200 OK
```

> ① 的响应里 `"version": "0.2.0"` 是**示例值，非真源；实际以 `/api/health` 的实际返回为准** ——
> 真源是 `backend/pom.xml` 的 `<revision>`（发版只改这一行，构建期经资源过滤注入）。

任一依赖不可用时 `/api/health` 返回 **HTTP 503** 且 `code=20000`，body 仍是完整 JSON、`data.checks` 会点名 DOWN 的依赖（如 `"ollama":"DOWN"`）。取数请用 `curl -s`（**不加 `-f`**，否则 503 时 body 会丢）。走前端端口只能查 `/api/health`，`/api/actuator/health` 必然 404。

```bash
# ④ 容器状态（WSL 内执行；必须带 -a —— 否则看不到已退出的 nexus-ollama-init）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose ps -a"

# ⑤ 运行期巡检（三层判据 + 失败项的下一步指引）
wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/sh/check-health.sh"
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
│   ├── builder/                 #   builder 镜像 + 5 个入口脚本（git-sync / build-* / db-patch-migrate）
│   ├── backend/                 #   后端运行镜像（JRE + entrypoint.sh，不含应用产物）
│   └── frontend/                #   前端运行镜像（nginx + nginx.conf，不含静态产物）
├── scripts/                     # check-env.sh / up.sh / check-health.sh / lib/probe.sh
├── db-patch/                    # 数据库补丁 YYYYMMDDHHmm_描述.sql（仓库根目录；已执行过的补丁禁改）
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
| AI 能力 | Ollama 本地 CPU 推理：`qwen2.5:7b`（对话）+ `bge-m3`（向量化，2026-09-22 由 `nomic-embed-text` 更换 —— 起因是中文检索召回不足，见 `docs/design/03-RAG知识库.md` §0.3）；统一网关后续规划接入云端模型（DeepSeek） |
| 前端 | Vue 3 (script setup) + TypeScript · Vite 5 · Element Plus · Pinia · Vue Router · Axios |
| 部署 | Docker Compose（WSL2 内运行）· `builder` 容器内构建 → 产物落共享卷 `build-artifacts` → 前后端挂载消费 · nginx 反代 |
| 测试（规划中） | JUnit 5 + Mockito（后端）· Vitest + Playwright（前端） |

## 文档索引

| 文档 | 内容 |
|------|------|
| [`CLAUDE.md`](CLAUDE.md) | 项目宪法：编码规范、技术选型、Agent 协作约定 |
| [`docs/核心任务.md`](docs/核心任务.md) | 作战地图：阶段划分与当前进度 |
| [`docs/design/00-环境与部署.md`](docs/design/00-环境与部署.md) | 阶段0 设计文档：架构、端口、镜像加速、脚本、健康检查（**顶部有 2026-09-13 修订块，列出因 builder 重构而失效的章节**） |
| [`docs/design/01-多租户与认证.md`](docs/design/01-多租户与认证.md) | 阶段1 + 0.2 设计文档：**新 builder 架构（§2）**、多租户与认证决策 D1~D9、接口契约、错误码 |
| [`docs/api/README.md`](docs/api/README.md) | 后端 API 契约：`Result<T>`、错误码、健康检查判据 |
| [`docs/task/task.1+环境准备.md`](docs/task/task.1+环境准备.md) | 阶段0 任务拆解、验收标准与验收现状 |
| [`backend/README.md`](backend/README.md) | 后端模块说明、打包约定与版本选型 |
| [`wsl.abc.md`](wsl.abc.md) | WSL 排查手册：实测记录 + 常见问题预案 |

## License

MIT（见 [LICENSE](LICENSE)）

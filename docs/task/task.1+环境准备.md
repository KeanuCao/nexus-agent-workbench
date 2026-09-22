# task.1 环境准备与检查（阶段0）

> 任务级别：L1 阶段级（跨模块、影响架构） ｜ 状态：🚧 进行中（0.1 ✅ 已实测；0.3.4 ✅ 已完成；**0.2 代码已交付（2026-09-13）、待实机验收**；0.3 的 0.3.1/0.3.2/0.3.3/0.3.5 脚本已实现、待实机验收；0.4 代码已交付、待实机验收）
> ⚠️ 2026-09-13：`CLAUDE.md` 的 builder 条目被改写（容器内 git pull + 手工启停 + 产物落共享目录），
> 0.1 / 0.2 / 0.4 的**构建链路整体重构**。本文件中原「多阶段构建」相关表述已由 `docs/design/00-环境与部署.md` 顶部修订块统一说明。
> 设计文档：`docs/design/00-环境与部署.md` ｜ 测试案例：[`docs/test-cases/TC-00.md`](../test-cases/TC-00.md)（18 个用例，2026-09-13 生成）

## 任务目标
一键拉起整套环境

## 执行要求
本任务为 L1 阶段级任务：先产出设计文档并经确认后再编码；完成后更新 `docs/核心任务.md` 进度标记并生成 TC-00。

## 子任务拆解

### 0.1 基础设施编排（docker-compose.yml）— 第一步交付物
- **输入**：技术选型约束（PostgreSQL 16 + pgvector、Redis 7、Ollama）
- **输出**：`docker-compose/docker-compose.yml`
- **依赖**：无
- **验收标准**：
  - 配置国内镜像加速（registry-mirrors）
  - 一次 `docker compose up -d` 拉起 PG / Redis / Ollama
  - Ollama 就绪并拉取 `qwen2.5:7b`、`nomic-embed-text`
  - 后端、前端独立容器；
  - 单独的专门用来打包的独立容器: builder 构建前后端包、运行期不常驻（前后端打包共用构建阶段，酌情拆分）

### 0.2 db-patch 数据库补丁工作流
- **输入**：补丁命名规则 `YYYYMMDDHHmm_描述.sql`
- **输出**：补丁记录表 `t_db_patch` + 启动扫描执行逻辑 + 首条补丁
- **依赖**：0.1
- **验收标准**：
  - 未执行补丁按文件名排序依次应用
  - 已执行补丁再次执行必须报错终止（记录表 + checksum 幂等保护）
  - 涉及表结构变更一律新增补丁，严禁修改已发布历史补丁

#### 📌 0.2 实现现状（2026-09-13 交付，**待实机验收**）

> 验收标准第 2 条的**语义已订正**（design 00 §3.3 早已细化，此处与之一致）：
> 「已执行补丁再次执行」的准确含义是 **checksum 一致 → 静默跳过（退出码 0）**；
> **只有当已应用补丁的内容被改动（checksum 不一致）时才报错终止**。
> 即"篡改只能被检出、不能被重放"，而不是"重跑迁移就报错"。

| 交付物 | 位置 | 状态 |
|---|---|---|
| 迁移引擎 `PatchCli` | `backend/nexus-infrastructure/.../dbpatch/PatchCli.java` | ✅ 已实现；`javac -Xlint:all` 零告警通过；**待实机跑** |
| 补丁目录与首条补丁 | `db-patch/202609131000_初始化多租户基础表.sql`、`..1010_初始化用户表.sql` | ✅ 已交付；**待实机执行** |
| 迁移入口 | builder 镜像内 `/usr/local/bin/db-patch-migrate` | ✅ 已实现；**待实机验证** |
| 执行入口调用 | `scripts/up.sh` 第 5 步（`compose exec builder db-patch-migrate`） | ✅ 已改写；**待端到端实跑** |

⚠️ **架构变更（同日）**：`CLAUDE.md` 的 builder 条目被改写为「容器内 git pull + 手工启停 + 产物落共享目录」，
0.2 的迁移入口随之从 `docker compose run --rm builder …` 改为 `docker compose exec builder …`。
设计留档见 `docs/design/01-多租户与认证.md` §2，受影响的旧章节见 `docs/design/00-环境与部署.md` 顶部修订块。

### 0.3 环境检查与一键启动脚本

> 拆解来源：草稿 `docs/drafts/环境检查脚本.md`（**未复核草稿**；其中 8 处判据错误已在本节订正，草稿原文按要求保持不动、不删、不改）。
> 规格依据：`docs/design/00-环境与部署.md` §4.1 ~ §4.4
> 判据依据：**`docs/api/README.md` §4** —— 它是 `check-env.sh` / `check-health.sh` / up.sh 第 7~8 步的**唯一判据来源**（§4 已把接口契约逐条转为可执行判据）。`§2.2` / `§2.3` / `§2.5` 只用于**理解接口本身的契约**（响应字段定义、503 语义、两个探活口的覆盖面差异），**不要**据 §2.x 反推脚本判定规则
> 分工：脚本实现 → **devops-engineer**；健康判据规格 → **backend-engineer**（规格已落在 `docs/api/README.md`，作为 devops 的输入）
> 判据纪律：凡判据涉及**运行时状态**（容器状态、退出码、端口响应、探活结果），一律标注「需实测确认」；未实测的观察不得写成验收标准。

- **输入**：0.1 的 `docker-compose/docker-compose.yml` + `.env`（宿主端口/凭据的唯一来源）；0.4 的 `GET /api/health` 契约；0.2 的 `db-patch-migrate` 命令（**尚未实现**，故 up.sh 中该步按「存在才执行」编写）
- **输出**：`scripts/check-env.sh`、`scripts/up.sh`、`scripts/check-health.sh`、`scripts/lib/probe.sh`（见 0.3.5）、`wsl.abc.md`、README 一键启动章节
- **依赖**：0.1（0.4 提供判据，可并行；0.2 只影响 up.sh 第 4 步何时可用）

#### 📌 0.3 实现与验收现状（2026-09-12 对账）

> **对账结论**：0.3 的四个脚本此前文档仍记「未开工」，属**事实性漂移** —— 实际已全部落盘且语法通过。
> 但**「已实现」不等于「已验收」**：下表把「已实现 / 已验证（只读实跑）/ 待验收」三者分开记录。**未通过实机验收的子项一律不勾选**（记法与 0.4 一致）。

| 子任务 | 产物（行数） | 已实现 | 已验证（只读实跑 / 静态复核） | 待验收（完成前不勾选） |
|---|---|---|---|---|
| 0.3.1 | `scripts/check-env.sh`（375） | ✅ 2026-09-11 | ✅ `bash -n` 通过；实跑 `PASS 10 / WARN 0 / FAIL 0`，exit 0 | 负路径未逐项触发（设计文档 §7 的验证方式是「逐项触发 PASS/WARN/FAIL 各路径」，实跑只覆盖了全通过路径）；第 4 项双侧探测的实跑输出未留档（见 0.3.1 需实测确认） |
| 0.3.2 | `scripts/up.sh`（298） | ✅ 2026-09-11 | ⚠️ 仅 `bash -n` 通过，**未实跑** | **九步端到端实跑**（唯一能验它的场景 = 干净机器全流程，含首次约 5GB 模型下载耗时） |
| 0.3.3 | `scripts/check-health.sh`（585） | ✅ 2026-09-12 | ✅ `bash -n` 通过；实跑 `PASS 19 / WARN 1 / FAIL 0`，exit 0，幂等复跑一致；零容器场景 → 8 项 FAIL + 提示先跑 `up.sh` | `WARN 1`（前端假故障）需在前端容器重建后复跑**归零**；后端 `healthy → unhealthy` 的实际耗时未实测 |
| 0.3.4 | `wsl.abc.md` | ✅ 2026-09-03（与 0.1 同批交付） | ✅ 四段式齐备（环境约定 / 四段式方法论 / 6 条实测记录 / 常见问题预案），已写明「AI 文档可能过期，以日志 + 官方文档交叉验证」 | **README 一键启动章节未做** —— 0.3 里唯一的真·未开工项；另有 1 条待追加记录（Windows 本地 Node v24 vs builder 容器 Node 20 的版本漂移，来源 `docs/agent-log/20260911-frontend骨架.md`） |
| 0.3.5 | `scripts/lib/probe.sh`（641） | ✅ 2026-09-11，2026-09-12 扩展 | ✅ `bash -n` 通过；已被 0.3.2 / 0.3.3 `source`；静态复核：两个调用方的**判定逻辑**未内联探针命令、未出现宿主端口/模型名字面量（仅人读提示文案里出现容器内端口 8089 / 80，属说明性内容） | 无独立验收项（随调用方一起验收：`up.sh` 的轮询路径待端到端实跑） |

**本轮修复（2026-09-12，改动均在 `docker-compose/docker-compose.yml`）**：

| # | 缺陷 | 修法 | 生效状态 |
|---|------|------|---------|
| 1 | `nexus-frontend` 恒 `unhealthy`（**假故障**：服务本身正常，宿主 `curl :8088` 与反代 `/api/health` 均 200） | healthcheck 探活地址 `wget -qO- localhost` → `wget -qO- 127.0.0.1`（根因：容器内 `localhost` 解析到 `::1`，而 nginx 监听的是 IPv4） | ⚠️ **尚未生效**：需 `docker compose up -d` 重建前端容器后才可见（重建后复跑 `check-health.sh`，`WARN 1` 应归零） |
| 2 | `ollama-init` 幂等判断失效（`nomic-embed-text` 每次都被判缺失、重复 pull） | `awk '{print $1}'` → `awk 'NR>1 {sub(/:latest$/,"",$1); print $1}'`（`ollama list` 对未指定 tag 的模型显示 `name:latest`，与不带 tag 的 `model` 变量整行比**必然不等**） | ✅ 已用模拟 `ollama list` 输出跑对照**实证**（修复前判缺失、修复后正确跳过）；容器侧待下次 `up -d` 生效 |

#### 0.3.1 `scripts/check-env.sh` —— 启动前置自检（在环境**起来之前**执行；FAIL 即中止 up.sh）

- **输入**：设计文档 §4.2 八项清单
- **输出**：`scripts/check-env.sh`，逐项输出 `[PASS]/[WARN]/[FAIL]`；存在 FAIL 时退出码非 0
- **依赖**：0.1
- **验收标准**（判据已逐条对照仓库订正）：
  1. Docker daemon：`docker version` 退出码 0；失败时给出 `sudo service docker start` 排查路径
  2. compose 插件：`docker compose version` 可用
  3. WSL 环境：`uname -r` 含 microsoft 关键字
  4. 端口占用（WSL 侧 `ss -ltnp` + Windows 侧 `powershell.exe Get-NetTCPConnection`）：宿主端口取 `.env` 实际值 —— **后端 8089、前端 8088**、postgres 5432、redis 6379、ollama 11434；冲突时提示改 `.env` 一处
  5. 镜像前缀：`docker compose config` 输出与 Dockerfile `FROM` 均含 `docker.m.daocloud.io`；**不得提示修改 daemon.json**
  6. compose 配置合法性：`docker compose config --quiet` 通过（不通过报错终止）
  7. 资源：可用内存 < 8GB 给 WARN（7B 模型推理需 5GB+）
  8. 模型就绪：**只能判 WARN，不得判 FAIL**。理由：前置阶段 ollama 容器通常尚未启动（干净机器首次运行必然如此），判 FAIL 会让**第一次 up.sh 永远无法通过前置门禁**，与 §4.3「由 ollama-init 在 up 过程中拉模型」的设计自相矛盾
- **需实测确认**：第 4 项双侧端口探测在 WSL2 NAT 下的实际表现（Windows 侧命令可用性）
  - 2026-09-12 部分核实：`powershell.exe` 在 WSL 内**可用**（`/mnt/c/Windows/System32/WindowsPowerShell/v1.0/`），实跑第 4 项给出 PASS。
  - ⚠️ 但**未能据 PASS 判定「双侧都查过」**：脚本对「双侧已查且无冲突」与「仅 WSL 侧已查（`powershell.exe` 不可用）」给的是**两条不同的 PASS 文案**，WARN 0 无法区分二者 —— 需留档当次输出原文才能闭合本条。

#### 0.3.2 `scripts/up.sh` —— 一键拉起（设计文档 §4.3 九步）

- **输入**：0.3.1；0.3.5 的探针函数；0.1 的 compose；0.2 的迁移命令（条件可用）
- **输出**：`scripts/up.sh`
- **依赖**：0.3.1、0.3.5、0.1
- **验收标准**：
  - 九步流程按 §4.3 顺序落地；第 1 步调用 `check-env.sh`，任一 FAIL 即中止
  - 第 2 步**必须显式点名服务**：`docker compose build builder`（不带服务名会把尚未就绪的应用镜像一并拉进构建；`builder` 虽有 `profiles` 隔离，显式点名即激活其 profile）
  - 第 4 步（db-patch 迁移）**必须写成条件执行**：`/db-patch` 目录不存在、或 `db-patch-migrate` 命令未实现时，打印 WARN 并跳过。已核实：`/db-patch` 目录当前不存在、`backend/nexus-infrastructure/.../dbpatch/` 仅有 `package-info.java` 占位（0.2 未开工），无条件执行该步会直接失败/卡死
  - 第 7 步模型就绪：轮询 `http://localhost:11434/api/tags` 校验 `qwen2.5:7b` 与 `nomic-embed-text` 两个模型（宿主端口取 `.env`；**含 `:latest` 归一化**，判据见 §4.5 与 0.3.5 的 `probe_models_ready`）；超时默认 30min 可配，超时给出断点续拉指引（不中止）
  - 第 8 步健康判据**分两层，两个探活口不可混用**：
    - **容器级**：各容器状态 + compose healthcheck（后端打 `/actuator/health`，覆盖 Boot 内建 db/redis/diskSpace，**不含 ollama**）
    - **业务级**：`curl http://localhost:8089/api/health` → HTTP 200 且 `data.checks.{postgres,redis,ollama}` 全为 UP；任一 DOWN 时 HTTP **503**，body 的 `data.checks.*` 用于定位到具体依赖（字段契约见 `docs/api/README.md` §2.2 / §2.3；**判定规则见 §4.2**）
    - **前端**：`curl -I http://localhost:8088` 返回 200（**8088 是宿主端口，80 是容器内端口**）
  - 第 8 步前端反代判据：只能查 `http://localhost:8088/api/health`；`/api/actuator/health` **必然 404**（`docker-compose/frontend/nginx.conf` 的 `location /api/` → `proxy_pass http://nexus-backend:8089` 无尾路径 = 保留 `/api` 前缀，而 Actuator 路径不带 `/api`）
  - 「容器 running」不得作为「依赖连通」的判据：`application.yml` 的 Hikari `initialization-fail-timeout: -1`，依赖不可达时应用照常启动 —— 脚本必须分「容器级 / 依赖级」两层
  - 一次性容器 `nexus-ollama-init` 纳入检查清单（其余常驻容器为 postgres / redis / ollama / backend / frontend 共 5 个）：它 `restart: "no"`，**退出码是「模型是否拉取成功」的唯一权威判据**；注意 `docker compose ps` 默认不列已退出容器，需 `ps -a` / `docker inspect`
  - `nexus-builder` **不纳入**运行期检查清单（`profiles` 隔离、运行期不常驻）
- **需实测确认**：全流程在干净环境的耗时与各终态（首次约 5GB 模型下载）；`nexus-ollama-init` 退出码的读取方式

#### 0.3.3 `scripts/check-health.sh` —— 运行期巡检（环境**起来之后**执行；失败**仅报告、不中止任何流程**）

> **决策记录（2026-09-11 已定，选 (a)：新增独立脚本）**：草稿把「运行期容器巡检」写成了 `check-env.sh` 的一部分，而 `check-env.sh` 是**启动前置门禁**（FAIL 即中止 up.sh），运行期轮询属 §4.3 的 step 7~9。**两者语义相反** —— 混进一个脚本，会让「容器还没起」这个**完全正常的场景**被前置门禁判 FAIL（与草稿中「模型就绪判 FAIL」同源，已在 0.3.1 第 8 项订正为 WARN）。更硬的分离理由是**退出码契约不同**：一个是门禁（FAIL = 中止流程），一个是报告（FAIL = 仅打印）。故拆为独立脚本，不做 `--runtime` 模式。
> 备查：(b) 方案「给 `check-env.sh` 加 `--runtime` 模式」被否决 —— 单脚本承担两种相反语义，参数分支使测试路径翻倍，且默认无参数时必须保持前置语义，否则 up.sh 第 1 步的调用会被污染。
> 说明：决策同时新增 0.3.5（探针共享函数）。0.3 细化到 5 个原子任务不受「阶段内 ≤5 个子任务」限制 —— 那条约束作用于 `0.1`~`0.4` 这一层（本层仍是 4 个）。

- **输入**：0.3.5 的探针函数（容器状态 / HTTP 探活 / 模型就绪）；`docker-compose/.env`（宿主端口唯一真源）；判据一律来自 `docs/api/README.md` §4（**不是** §2.x —— §2.x 描述接口本身，§4 才把判定规则逐条转成可执行判据）
- **输出**：`scripts/check-health.sh`；逐项 `[PASS]/[WARN]/[FAIL]` 清单 + 末尾汇总 + 失败项的下一步指引
- **依赖**：0.3.5、0.1（**不依赖 0.3.2**：任何时刻均可独立重跑。up.sh 第 7~8 步是「等到就绪」的轮询，本脚本是「此刻快照」的报告，两者判据同源、行为不同，因此不能合并）
- **验收标准**：
  1. **语义分野写进脚本头部注释与 `--help`**：本脚本是**运行期报告**，不是门禁。**禁止在 up.sh 中把它当门禁调用**；脚本必须能在「容器一个都没起」时正常跑完并输出报告（全 FAIL + 提示「若尚未启动，请先执行 `./scripts/up.sh`」），而不是报环境错误或直接退出
  2. **退出码契约（与 0.3.1 相反）**：全部通过（含 WARN）→ 0；存在 FAIL → 非 0。该退出码**只表达报告结论**，任何调用方都不得据此中止流程；`check-env.sh` 的「FAIL 即中止」语义**不得**出现在本脚本中
  3. **只读、幂等**：只做探测，不 start / stop / restart 任何容器、不写文件、不改 `.env`；连续两次运行结论一致（依赖抖动除外）
  4. **检查对象 = 5 个常驻容器 + 1 个一次性容器**：
     - 常驻（服务名 / 容器名）：`postgres` → `nexus-postgres`、`redis` → `nexus-redis`、`ollama` → `nexus-ollama`、`nexus-backend` → `nexus-backend`、`nexus-frontend` → `nexus-frontend`
     - 一次性：**`ollama-init` → `nexus-ollama-init`（`restart: "no"`）必须纳入** —— 它的**退出码是「模型是否拉取成功」的唯一权威判据**（`nexus-ollama` 的 healthcheck 是 `ollama list`，而零模型时该命令退出码仍为 0，**不能**用来判模型）。注意 `docker compose ps` **默认不列已退出容器**，必须用 `ps -a` 或 `docker inspect` 读 `State.ExitCode`
     - **不纳入**：`builder` → `nexus-builder`（`profiles: ["build"]` 隔离、运行期不常驻）
  5. **两级判据不可混用（判据来源 `docs/api/README.md` §4.1）**：
     - **L1 容器级**：各容器 `State` / `Health`（`docker inspect`）+ compose healthcheck —— 后端打 `/actuator/health`（容器内 8089），覆盖 Boot 内建 **db / redis / diskSpace**，**不含 ollama**。此级只回答「进程起没起来、要不要重启」
     - **L2 业务级**：`curl -s --max-time 10 http://localhost:${BACKEND_PORT}/api/health`（`BACKEND_PORT` 从 `.env` 读、**不硬编码**；**不加 `-f`** —— 503 时也要拿到 body 才能定位）。判据 = HTTP 200 且 `code=0` 且 `data.checks.{postgres,redis,ollama}` **全为 UP**；任一 DOWN 时 HTTP **503** / `code=20000`，用 `data.checks.*` **定位到具体依赖**，并打印 `msg`（`依赖服务不可用：<依赖名>`，全角冒号/顿号）与最后一次 `data.checks`
     - **L3 模型级**：`GET /api/tags` 校验 `qwen2.5:7b` 与 `nomic-embed-text` 均在（§4.5：**ollama 健康 ≠ 模型就绪**，两个探活口同时绿灯时模型可能一个都没拉下来；注意 `:latest` 归一化陷阱）。未就绪 → **WARN + 给补拉命令**（`docker exec nexus-ollama ollama pull <model>`），**不判 FAIL**（「正在拉」不是「环境坏了」）
     - **为什么必须两级**（活教材）：停 ollama → 后端仍 `healthy`，但 `/api/health` 立刻 503；停 postgres/redis → 后端约 2 分钟后转 `unhealthy`（推算，**未实测**）且不触发重启 —— **只查容器状态的脚本会完全漏掉 AI 依赖故障**
  6. **字段级校验按 §4.2.2 全表执行**：`data.service` = `nexus-start`；`data.checks` 键数**恰好 3 个**（多出未知键 → 报告契约变更，而非静默通过）；取值域只有 `UP` / `DOWN`（出现第三值 → 报契约破坏）；`data.timestamp` 匹配秒级无小数秒的正则且**不断言时区**；`data.version` 断言 `^[0-9]+\.[0-9]+\.[0-9]+$` 且显式拒绝 `^@.*@$`（资源过滤失效属**构建层缺陷**，提示查 `maven-resources-plugin`，别让用户误以为容器没起好）—— 该条同时是 0.4 遗留验证项 6 的收口手段
  7. **前端**：`curl -I http://localhost:${FRONTEND_PORT}` 返回 200（**8088 是宿主端口、80 是容器内端口**）；反代只查 `http://localhost:${FRONTEND_PORT}/api/health` 并校验拿到完整 `Result` JSON（`/api/actuator/health` 必然 404；而走前端端口查 `/actuator/*` 会命中 SPA 回退，得到 **200 + HTML 的假通过**，见 §4.6）
  8. **抖动免疫**：巡检为单次采样；命中 503 时 **5s 后复采一次确认**，复采仍 503 才判 FAIL（§4.3：连接池重建期「一个 200 紧跟一个 503」是常见现象）。注意这与 up.sh「连续 2 次 200 才算就绪」是同源判据的**不同用途**，不要互相套用
  9. **失败处理**：跑完全部检查项再汇总（不提前退出），FAIL 项要给「下一步查什么」的具体命令（如 `docker logs --tail 100 nexus-backend`）
  10. **明确不做**（沿用 §4.6，防止后续 agent 照草稿重新生成）：不查 `pg_extension` 已安装（现阶段必 FAIL，改查 `pg_available_extensions`）、不查 `t_db_patch` / 业务表 `tenant_id`（0.2 / 阶段1 才有）、不做 GPU 检查（已定 CPU 推理）、不调 `/api/ai/ping`（阶段2 才有）
- **需实测确认**：① ✅ 已实测（2026-09-12：`PASS 19 / WARN 1 / FAIL 0`，exit 0，约 2s，幂等复跑一致；`WARN 1` = 前端假故障，故「全绿」尚未达成，待修复生效后复跑）；② `nexus-ollama-init` 退出码的读取方式（`ps -a` vs `docker inspect`，与 0.3.2 同一待验项）；③ ✅ 已实测：WSL 内**无 `jq`** → `probe.sh` 的 sed/grep 子串兜底路径**就是实际执行路径**（「有 jq 更精确」那条分支从未跑过，改判据时必须保证兜底路径也对）；④ 后端由 `healthy` 转 `unhealthy` 的实际耗时（§4.1 为推算值，仍未实测）

#### 0.3.4 `wsl.abc.md` + README 一键启动章节

- **输入**：设计文档 §4.4 结构规划；§4.1 的 Windows 侧触发方式
- **输出**：`wsl.abc.md`；`README.md` 的「一键启动」章节
- **依赖**：无（可与 0.3.1 / 0.3.2 并行）
- **验收标准**：
  - `wsl.abc.md` 四段式齐备：环境约定 / 排查方法论（每条固定「现象 → 日志证据 → 官方文档出处 → 结论」）/ 踩坑记录表 / 常见问题预案；明确「AI 文档可能过期，一律以日志 + 官方文档交叉验证，不照抄」
  - README 含 Windows 侧一键触发命令（`wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/up.sh"`）与前后端启动命令（`mvn clean install -DskipTests && java -jar nexus-start/target/*.jar`、`npm install && npm run dev`）
- **状态（2026-09-12 对账）**：**拆两半看** ——
  - `wsl.abc.md`：✅ **已交付**（2026-09-03，与 0.1 同批，见 `docs/agent-log/20260903-step10-wrapup.md`），四段式齐备、6 条实测踩坑记录、已写明「AI 文档可能过期，一律以日志 + 官方文档交叉验证」；
  - `README.md` 一键启动章节：❌ **未开工**（仓库根目录无 `README.md`）—— 这是 0.3 里唯一仍有未开工内容的子任务。
  - 另有 1 条待追加进 `wsl.abc.md` 的记录（闸门：`wsl.abc.md` 的验收要求是「随实践持续追加」）：Windows 本地 **Node v24** vs builder 容器 **Node 20** 的版本漂移（与设计文档 §5.2「两者同大版本」的意图相悖，来源 `docs/agent-log/20260911-frontend骨架.md`）。

#### 0.3.5 `scripts/lib/probe.sh` —— 探针共享函数（0.3.2 与 0.3.3 的共同依赖）

- **定位**：把「容器状态 / HTTP 探活 / 模型就绪」三类探针与判据常量（期望模型名、单次超时、端口读取方式）收敛到**一处**，供 `up.sh`（轮询）与 `check-health.sh`（快照）共同 `source`。
  **Why**：两处各写一份判据必然漂移 —— 本项目已出过真实事故（`:latest` 归一化陷阱、`ollama list` 退出码恒 0 被误当模型判据，见 `docs/agent-log/20260911-ollama-init诊断.md`）。判据治一处，漏洞才不会以同一形态复发。
- **输入**：`docs/api/README.md` §4（判据）、`docker-compose/.env`（端口真源）
- **输出**：`scripts/lib/probe.sh`，固定函数清单（**只做探测、不做输出格式决策**，格式由调用方决定）。
  **函数名以实现的 `nexus_*` 为准**（本节原规格名为 `probe_*`）—— **规格名 ↔ 实现名对照表写在 `probe.sh` 文件头注释里，评审以该对照表为准**（不留暗偏差）：
  - `nexus_container_status <容器名>` → 输出 `<State>|<Health>`（规格名 `probe_container_state`；容器级判据要 health，单给 state 不够）
  - `nexus_oneshot_state` → 读一次性容器 `nexus-ollama-init` 的 `<state>|<exitcode>`（规格名 `probe_ollama_init_exit`；必须区分「没跑过 / 正在跑 / 已退出」，**只给退出码分不出「还没跑」这个正常状态**）
  - `nexus_probe_api_health` → 取 `/api/health` 的 HTTP + body（`curl -s --max-time 10`，**不加 `-f`**；同义，仅加前缀）
  - `nexus_probe_actuator_health` → 取 `/actuator/health`（`--max-time 5`；同义，仅加前缀）
  - `nexus_probe_models` → 校验 `/api/tags` 两模型（**含 `:latest` 归一化**；不返回布尔的 0/1，而是写 `NEXUS_MODELS_*` 全局 —— 就绪判据要能说清「缺哪几个」「是不是 ollama 不可达」）
  - `nexus_env_value <键> <兜底>` → 从 `.env` 读值（规格名 `probe_port_of`；泛化为读任意键，凭据同样来自 `.env`；端口读取方式未变。**硬编码端口正是草稿被订正的 8 处错误之一**）
  - **决策：保留 `nexus_` 现名，不做改名对齐**（2026-09-12 已定）。理由：改名需同步改 `up.sh` / `check-env.sh` 两处**已交付**脚本，行为收益为零，却会让已评审的文件重新变成未评审；且规格名装不下实际语义（如 `nexus_oneshot_state` 的三态）。替代方案即上面这张对照表。
- **依赖**：0.1（服务名 / 容器名 / `.env` 端口真源）
- **验收标准**：
  - 判据常量**只在真源出现一次**：期望模型名的**唯一真源是 `docker-compose/docker-compose.yml` 的 `ollama-init` 命令**
    （`scripts/lib/probe.sh` 的 `nexus_expected_models()` 从那里取、三个消费者脚本再调它 —— 2026-09-22 实测"只改 compose 一行即全线跟上"）；
    单次超时（10s / 5s）、端口读取方式同理只应有一处定义。
    ⚠️ **2026-09-22 订正**：本条原写"只在本文件出现一次：期望模型名（`qwen2.5:7b`、`nomic-embed-text`）"，
    既不再是唯一真源、模型名也变了（`nomic-embed-text` → `bge-m3`，1024 维，理由见 `docs/design/03-RAG知识库.md` §0.3）
  - `source` 本文件**无副作用**：不执行任何探测、不打印、不改调用方的退出码与 `set -e` 行为（`check-env.sh` 的门禁语义不得被库污染）
  - `bash -n` 语法检查通过；`jq` 缺失时自动走 §4.2.3 的子串兜底路径 —— ✅ 2026-09-12 实测：WSL 内**确实无 `jq`**，即**兜底路径就是实际执行路径**，「有 jq」分支从未跑过，此后改判据必须保证兜底路径也对
  - `up.sh` 与 `check-health.sh` 中**不得再出现内联的探针命令与端口/模型字面量**（评审时按此检查）—— ✅ 2026-09-12 静态复核：两个调用方的**判定逻辑**均未内联探针命令、未出现宿主端口与模型名字面量（仅人读提示文案里出现容器内端口 `8089` / `80`，属说明性内容，不参与判定）
- **落地顺序**：与 0.3.2 同批（先写函数、再由 `up.sh` 调用，避免「先造库、无人用」的空转）；0.3.3 复用同一组函数。
- **返回约定（2026-09-12 已定，写在本文件头注释，不再是待确认项）**：纯探针函数（`nexus_probe_*`）**只读环境、不打印装饰性输出、不 `exit`**；需要返回多值时写 `NEXUS_*` 全局，**因此必须在当前 shell 调用、不要放进 `$( )`**；等待类函数（`nexus_wait_*`）可打印进度并返回 0/1。「严重性由调用方决定」—— 探针只回答「形态对不对」，报 PASS 还是 FAIL 由报告脚本定

#### 草稿判据订正记录（8 处，均已对照仓库核实）

| # | 草稿原文 | 订正后（以仓库现状为准） | 证据 |
|---|---------|------------------------|------|
| 1 | 后端探活 `curl localhost:8080/actuator/health` | 宿主端口 **8089**（容器内亦为 8089） | `docker-compose/.env:20` `BACKEND_PORT=8089`；`backend/nexus-start/src/main/resources/application.yml:17` `port: ${SERVER_PORT:8089}` |
| 2 | 前端 `curl -I localhost`（称「80 端口可达」） | 宿主是 **8088**（80 是容器内端口） | `.env:21` `FRONTEND_PORT=8088`；compose 端口映射 `${FRONTEND_PORT:-8088}:80` |
| 3 | `curl localhost/api/actuator/health` | **必然 404**；走反代只能查 `localhost:8088/api/health` | `docker-compose/frontend/nginx.conf:25-26`：`location /api/` → `proxy_pass http://nexus-backend:8089`（无尾路径 = 保留 `/api` 前缀），Actuator 路径不带 `/api` |
| 4 | 「nexus-frontend 没配 healthcheck，要不要补一个」 | **已有，且无需新增任务**（2026-09-12 修正的是它的**探活地址**，不是「补一个 healthcheck」，见「本轮修复」表） | compose 前端服务段：`test: ["CMD-SHELL","wget -qO- 127.0.0.1"]`，interval 10s / retries 6 / start_period 10s（原写法 `localhost` 致容器**恒 unhealthy** 的假故障）；探活口覆盖见 0.3.2 |
| 5 | 查 `pg_extension` 确认 pgvector **已安装** | 镜像里扩展**可用**，但本库**从未执行 `CREATE EXTENSION`** → 该检查**必然 FAIL**；现阶段只能查 `pg_available_extensions` | 全仓库 `CREATE EXTENSION` 仅出现在 `docs/task/task.2+多租户认证.md:19`（阶段1 计划步骤）；compose 的 postgres 只挂 `pg-data` 卷、无 initdb 挂载 |
| 6 | 深度项：查 `t_db_patch` 记录数、抽查业务表 `tenant_id` | **两者都还不存在**，现阶段无从检查 | `/db-patch` 目录不存在；`backend/nexus-infrastructure/.../dbpatch/package-info.java` 仅为占位（0.2 未开工）；多租户属阶段1（task.2） |
| 7 | 深度项：GPU 是否启用（`nvidia-smi`） | **不做该检查**：项目 2026-09-03 已确定 **CPU 推理**，GPU 直通为预留项 | `docs/design/00-环境与部署.md` §2.1 / §8 项 3；compose 中 GPU 段为注释保留 |
| 8 | 深度项：调测试接口（如 `/api/ai/ping`） | **该接口不存在**（统一 AI 网关属阶段2） | `docs/api/README.md`：阶段0 只有 `GET /api/health`；网关任务见 `docs/task/task.3+统一AI网关.md` |

**由订正引出的「明确不做」清单**（防止后续 agent 按草稿重新生成）：
- 不给 `nexus-frontend` **补** healthcheck（已有；2026-09-12 修正的是其探活地址，见「本轮修复」表）；
- 不查 `pg_extension`（改查 `pg_available_extensions`，并说明「可用 ≠ 已启用」）；
- 不查 `t_db_patch` / 业务表 `tenant_id`（阶段1 才有）；
- 不做 GPU 检查（已定 CPU 推理）；
- 不调 `/api/ai/ping`（阶段2 才有）。

**方法论提醒**：草稿称前端容器「只是 `Started`，不是 `Healthy`」，该观察**来源不明**（当时贴出的是 `docker images` 镜像列表，**无健康状态列**），且正是第 4 条错误的源头。凡涉及运行时状态的判据，一律标注「需实测确认」。

### 0.4 前后端项目骨架
- **输入**：目录结构约定（backend 五模块 / frontend）
- **输出**：backend Maven 多模块骨架（含可启动的 nexus-start）+ frontend Vite 骨架（Vue 3 setup + Element Plus + Pinia）
- **依赖**：无（可与 0.1 并行）
- **验收标准**：
  - `mvn clean install -DskipTests` 通过
  - 后端健康检查接口返回 `Result<T>` 统一格式
  - `npm run dev` 可启动

## 🚩 阶段交付物
`docker-compose.yml`、db-patch 工作流、`check-env.sh` / `up.sh` / `check-health.sh`（+ `scripts/lib/probe.sh`）、`wsl.abc.md`、前后端容器

> 📎 **阶段外工程沉淀（不计入本任务验收）**：`devops-engineer` 的 agent 定义新增「🔍 排查环境问题」章节（三条铁律 + 6 步诊断阶梯 + 10 条已踩陷阱 + 「是否真的修好了」判定标准，见 `.claude/agents/devops-engineer/CLAUDE.md`）。它不在 0.3 的验收范围内，**故不进「完成状态」勾选清单**；在此记一笔是因为它与脚本头部警示同一个思路 —— 把已证伪的做法固化在**离现场最近的地方**，后续阶段排障会直接引用。

## ✅ 阶段验收标准
拿到仓库后执行 `./scripts/up.sh` 即可拉起全部环境，后端健康检查、前端页面均可达
（可执行判据：`curl http://localhost:8089/api/health` 返回 UP；浏览器 `http://localhost:8088` 页面可达）

## ⚠️ 待确认事项

| # | 事项 | 现状（已实测） | 选项 —— **未拍板，此处不代定** |
|---|------|--------------|-------------------------------|
| 1 | `data.timestamp` 的时区写法 | `GET /api/health` 实际返回 `2026-09-12T12:40:06Z`（`Z` = ISO-8601 对 UTC 的标准写法）；而 `docs/api/README.md` §4.2.2 的字段正则要求 `[+-]hh:mm`，设计文档 §5.3 的示例写 `+08:00` | (a) **改文档/示例**：接受 `Z`；(b) **改后端**：固定输出偏移形式。<br>两侧各改一处即可，**改哪侧由 backend-engineer 定**（本轮只如实记录，不代拍板）<br>→ `check-health.sh` 当前按「**不断言时区**」处理（`Z` 与 `+08:00` 都接受，只在报告里附一句提示），故本项**不阻塞** 0.3 的脚本验收，但**阻塞契约文档（README / 设计文档）与实现的一致性** |

## 完成状态
- [x] 0.1 基础设施编排
- [ ] 0.2 db-patch 补丁工作流 —— ✅ **用例全跑通（2026-09-17，TC-00 §2）**；仅剩 ⑥ up.sh 第 5 步端到端待验（归 0.3-7）
  - **已验证**：
    - `PatchCli.java` 以 `javac -Xlint:all` 独立编译零告警通过（在 builder 容器内单文件编译）
    - ① 首次迁移执行成功、`t_db_patch` 落记录；② 幂等复跑全跳过且退出码 0；
      ③ **篡改已应用补丁 → 退出码非 0 并打印新旧 checksum**（2026-09-17 起：篡改对象用 `qa/fixtures/db-patch/` 的探针补丁，不碰业务补丁）；
      ④ 乱序保护（时间戳回退 → 非 0）；⑤ 排序（**一次性沙箱库**从零重放真实补丁目录，比对 `file_name` 与目录字典序；原「清库重跑」口径已废 —— 见 `docs/design/00-环境与部署.md` §7）
    - 逐条实测记录见 `docs/test-cases/TC-00.md` §2（含 0.2-6 首轮判据缺陷的订正过程：注入 `README.md` 触发不了 WARN 分支）
  - **未验证**（勾选前必须补）：⑥ up.sh 第 5 步端到端 —— 归 `TC-00-0.3-7` 的九步跑
- [ ] 0.3 环境检查与启动脚本 —— 🚧 **4 个脚本已实现，待实机验收**（逐项状态见 0.3 的「实现与验收现状」表）
  - 已验证：四个脚本 `bash -n` 通过；`check-env.sh` 实跑 `PASS 10 / WARN 0 / FAIL 0`（exit 0）；`check-health.sh` 实跑 `PASS 19 / WARN 1 / FAIL 0`（exit 0，幂等复跑一致）
  - **未验证**（勾选前必须补）：
    1. `up.sh` 九步端到端实跑（干净环境，含首次约 5GB 模型下载耗时）
    2. `check-env.sh` 负路径：8 个检查项逐项触发 `WARN` / `FAIL`（设计文档 §7 的验证方式）
    3. `check-env.sh` 第 4 项双侧端口探测的**实跑输出留档**（「双侧已查」与「仅 WSL 侧已查」是两条不同的 PASS 文案）
    4. 前端容器重建（`docker compose up -d`）后复跑 `check-health.sh`：`WARN 1` → `0`（前端 healthcheck 修复生效的判据）
    5. 后端 `healthy → unhealthy` 的实际耗时（§4.1 为推算值）
  - [ ] 0.3.1 `check-env.sh`（启动前置自检）—— 已实现、已实跑；**待补负路径验证**
  - [ ] 0.3.2 `up.sh`（一键拉起，九步）—— 已实现；**端到端未实跑**
  - [ ] 0.3.3 `check-health.sh`（运行期巡检，失败仅报告、不中止任何流程）—— 已实现、已实跑；待前端修复生效后复跑
  - [x] 0.3.4 `wsl.abc.md` + README 一键启动章节 —— **已完成（2026-09-12）**
    - `wsl.abc.md`：四段式齐备，7 条实测记录（含 2026-09-12 追加的「Node 版本漂移」§3.7）
    - `README.md`：已建，含 Windows 侧一键触发命令、前后端本地启动命令、三脚本定位对照表、服务与端口表、常用验证命令
    - 两条验收标准（「`wsl.abc.md` 四段式齐备」「README 含一键触发与前后端启动命令」）均已逐项核对通过
  - [ ] 0.3.5 `scripts/lib/probe.sh`（探针共享函数，0.3.2 / 0.3.3 共同依赖）—— 已实现；命名偏差已用对照表消解
- [ ] 0.4 前后端项目骨架 —— 🚧 **代码已交付（2026-09-11），待实机验收**
  - **已验证**（2026-09-12 实测补充）：
    1. ✅ `mvn clean install -DskipTests` 通过 —— 以 `nexus-backend:dev` 镜像构建成功为证（在 builder 容器内执行；首次因传输截断失败一次，重跑通过）
    2. ✅ `nexus-start/target/*.jar` 只命中一个文件 —— 构建成功即证明（该 COPY 通配命中 0 个或 ≥2 个都会让构建失败）
    3. ✅ `curl localhost:8089/api/health` 返回 §5.3 契约 JSON，`checks` 三项全 UP（"不造假"已验证）
    4. ✅ `data.version` = `0.1.0`（**非**字面量 `@project.version@`）—— `spring-boot-starter-parent` 的资源过滤确实生效
    5. ✅ `npm run type-check` 通过（exit 0，零诊断）—— 由主会话独立复跑确认
    6. ✅ 经 nginx 反代的 `localhost:8088/api/health` 同样返回 200 + 完整 Result
    7. ✅ 契约前后端逐字段核对一致（`checks.{postgres,redis,ollama}` / `UP|DOWN`）
  - **未验证**（勾选前必须补）：
    1. `npm run dev` 可启动且能经 Vite 代理调通 `/api/health`（需 Windows 侧手工跑，两个 agent 均被禁止启动 dev server）
    2. 停掉 ollama 后 `check-health.sh` 能否 `[FAIL]` 并点名 ollama（真实 503 路径未在真机验过）

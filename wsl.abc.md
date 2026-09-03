# wsl.abc.md — WSL 环境排查手册（nexus 项目实测经验库）

> 本文件记录 nexus 项目在 Windows 11 + WSL 开发环境下的实测排查经验（四段结构见 `docs/design/00-环境与部署.md` §4.4）。
> **立身之本：AI 文档可能过期或错误，一切结论以日志 + 官方文档交叉验证为准，不照抄。**

## 1. 环境约定（浓缩自设计文档 §1）

| 项 | 约定 |
|----|------|
| WSL 发行版 | **`nexus-agent-workbench`**（docker / docker compose 全部在其中运行，Windows 侧不直接跑 docker） |
| 路径映射 | `C:\wp\nexus-agent-workbench` ↔ WSL 内 `/mnt/c/wp/nexus-agent-workbench` |
| 执行边界 | Windows：写代码、`npm run dev`、经 `wsl -d` 触发；WSL：docker、compose、`.sh` 脚本（只在 WSL 内执行） |
| compose 工作目录 | 固定 `/mnt/c/wp/nexus-agent-workbench/docker-compose`（compose 自动发现同目录 `.env`） |
| 镜像加速 | `docker.m.daocloud.io` 前缀直写 Dockerfile `FROM` / compose `image:`；官方镜像走 `/library/` 路径；**禁止改 `/etc/docker/daemon.json`** |
| 触发命令 | `wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench/docker-compose && docker compose up -d postgres redis ollama"`（显式列服务名，勿裸 `up -d` 连带构建 backend/frontend） |
| 执行纪律 | docker 变更类命令（up/pull/build/down/restart）执行前报批；数据一律命名卷（WSL ext4），避开 /mnt/c 9p 性能坑 |

## 2. 排查方法论：四段式记录法

每条踩坑记录固定四段式，缺一不记：

| 段落 | 写什么 |
|------|--------|
| **现象** | 一句话描述遇到的问题/要验证的假设 |
| **日志/证据** | 实测命令输出、耗时、退出码、digest——一切结论的根据 |
| **官方文档出处** | 官方仓库/文档入口（Docker 官方文档、ollama GitHub 仓库、Microsoft WSL 文档等）；**官方文档与实测冲突时以实测为准并注明** |
| **结论** | 沉淀为规则：今后怎么干 / 怎么绕开 |

## 3. 踩坑记录

> 索引表（详细记录见下方各条目）：
>
> | 日期 | 主题 | 结论速记 |
> |------|------|----------|
> | 2026-09-03 | DaoCloud 镜像前缀可用性 | `/library/` 只加官方镜像；hello-world 实测 2.9s，前缀方案生效 |
> | 2026-09-03 | ollama 镜像内无 curl/wget | 健康检查改 `["CMD","ollama","list"]` |
> | 2026-09-03 | ollama latest = 8.45GB fat 镜像 | CPU 项目注意体积；无官方 CPU-only 小 tag |
> | 2026-09-03 | 模型下载不走镜像加速 | 直连 registry.ollama.ai，24MB/s ≈2min/5GB |
> | 2026-09-03 | 双层 shell `$var` 展开丢失 | 用显式命令或传脚本体，不用内层变量 |
> | 2026-09-03 | `docker inspect -f` 模板取空值 | 验证状态统一用 `docker compose ps` |

### 3.1 DaoCloud 镜像前缀可用性（`docker.m.daocloud.io`）

- **现象**：国内拉取 Docker 官方镜像需加速；需验证 `docker.m.daocloud.io` 前缀写法（官方镜像是否必须加 `/library/`、非官方镜像路径映射）是否可用，再定 compose/Dockerfile 全部镜像引用写法。
- **日志/证据**：`docker pull docker.m.daocloud.io/library/hello-world` real 2.872s、退出码 0；`docker manifest inspect`（零下载）验证 `pgvector/pgvector:pg16`、`ollama/ollama`（无 `/library/`，非官方命名空间）均 OK；`docker compose pull postgres redis ollama` 一次成功 real 4m17.9s 无重试（三镜像共约 9.2GB）。
- **官方文档出处**：DaoCloud 公共镜像加速文档（docs.daocloud.io）；Docker Hub 命名空间约定（`library/` 为 Docker 官方镜像命名空间）。以本机实测退出码/耗时为准。
- **结论**：compose `image:` / Dockerfile `FROM` 直写 `docker.m.daocloud.io` 前缀；**官方镜像加 `/library/`，非官方镜像不加**；不改 daemon.json（加速配置随仓库走、任何干净机器可复现）。

### 3.2 ollama 镜像内无 curl/wget（0.33.2）→ 健康检查用 `ollama list`

- **现象**：设计文档 §2.1 初版 ollama 健康检查用 `curl -fsS localhost:11434/api/version`；若镜像内无 curl，healthcheck 永远失败，且依赖 `ollama(healthy)` 的 ollama-init 会卡死在 waiting——必须在 up 前确认。
- **日志/证据**：`docker run --rm --entrypoint /bin/sh <ollama镜像> -c 'command -v curl; command -v wget; ollama --version'` → curl、wget 均 not found；ollama --version = 0.33.2。
- **官方文档出处**：ollama 官方 Dockerfile（github.com/ollama/ollama 仓库）——运行镜像仅携带服务所需最小依赖；以镜像内实测为准。
- **结论**：健康检查改为 `["CMD","ollama","list"]`（CLI 连本机 serve 探活，成功即退出码 0）；实测每 10s 一次 `GET /api/tags` 200、healthy 正常。compose 与设计文档 §2.1 已同步。

### 3.3 ollama latest 是 8.45GB CUDA fat 镜像（v0.6+ 起）

- **现象**：计划预期 ollama 镜像 0.6-1.5GB，实测 latest 高达 8.45GB，远超预期。
- **日志/证据**：`docker compose pull` 实测 `ollama/ollama:latest` = 8.45GB（对比 pgvector/pgvector:pg16 = 621MB、redis:7-alpine = 57.8MB）；`/api/version` 返回 0.33.2。
- **官方文档出处**：ollama GitHub Releases（v0.6.x 起官方 Docker 镜像捆绑 CUDA 运行时，体积大幅增加）；以实测尺寸为准。
- **结论**：CPU 推理项目注意镜像体积（8.45GB 只下一次，磁盘门槛需预留）；探测官方 CPU-only 更小 tag（`0.33.2-cpu` / `cpu-0.33.2` / `latest-cpu`）**均不存在**（manifest inspect 全部 NOT-FOUND）——amd64 仅此 fat 镜像。故 `OLLAMA_TAG` 固定 `0.33.2`：实测与 latest 同 manifest，重建零下载。

### 3.4 模型下载走 registry.ollama.ai 直连，不走 docker 镜像加速

- **现象**：以为 qwen2.5:7b + nomic-embed-text（共约 5GB）会随 docker 镜像加速源下载；实际由 ollama 客户端直连官方 registry 拉取，与镜像加速无关。
- **日志/证据**：ollama-init 实测拉取速率 24MB/s，5GB 约 2 分钟完成；`docker exec nexus-ollama du -sh /root/.ollama` = 4.7G；`curl localhost:11434/api/tags` 含 qwen2.5:7b（4.68GB）+ nomic-embed-text:latest（274MB）。
- **官方文档出处**：ollama 官方 README / FAQ（模型存储位置与 `OLLAMA_MODELS` 环境变量；模型由 ollama 客户端从 registry.ollama.ai 拉取）。
- **结论**：docker 镜像加速只覆盖镜像层，模型流量不受其影响；模型落命名卷 `ollama-models`，一次下载永久复用（容器重建/重启后仍在，已实测）。

### 3.5 双层 shell（Windows bash → wsl -d -- bash -c）内层 `$var` 展开丢失

- **现象**：Windows 侧 bash 调用 `wsl -d <发行版> -- bash -c "..."` 双层嵌套时，内层 shell 变量（如 for 循环变量）展开为空，且外层转义规则难以预判。
- **日志/证据**：本轮回测 `for t in 0.33.2-cpu cpu-0.33.2 latest-cpu; do ... "\$t" ...` 输出 `===  ===`（变量为空），探测结果无效，需拆成 3 条独立命令重跑。
- **官方文档出处**：Microsoft WSL 文档（learn.microsoft.com 的 wsl.exe 命令行语法——参数拼接为命令行后由 WSL 侧 shell 再解析，引号/转义不可靠）；以实测为准。
- **结论**：复杂逻辑**不用内层变量**——改用「显式命令」（每个候选一条独立命令）或把脚本写成 `.sh` 文件后 `wsl -d nexus-agent-workbench -- bash /path/to/script.sh` 执行。

### 3.6 `docker inspect -f '{{...}}'` 经双层引号转义取空值

- **现象**：`docker inspect -f '{{.State...}}'` 的 Go 模板经双层 shell 引号转义后，输出为空值。
- **日志/证据**：检查点 H 实测时 `-f '{{...}}'` 模板输出为空；改用 `docker compose ps`（或去掉 `-f` 的普通 inspect）后信息完整。
- **官方文档出处**：Docker CLI 参考（docs.docker.com 的 `docker inspect --format` Go 模板语法）；空值问题属本环境双层 shell 叠加所致，以实测为准。
- **结论**：验证容器/健康状态统一用 `docker compose ps`；确需 `-f` 模板时经脚本文件执行，避免内层 `'{{...}}'` 引号被剥。

## 4. 常见问题预案（占位，遇到实际问题后填充）

| 问题 | 预案思路 |
|------|----------|
| docker 未启动 | `docker version --format '{{.Server.Version}}'` 失败时提示 `sudo service docker start` |
| 端口冲突（5432/6379/11434/8089/8088） | 改 `.env` 端口变量一处生效；WSL 侧 `ss -ltnp` + Windows 侧 `Get-NetTCPConnection` 双侧排查 |
| /mnt/c 9p 文件系统性能 | 运行数据一律命名卷（WSL ext4）；构建上下文用 `.dockerignore` 收紧 |
| 内存 OOM（7B 模型推理需 5GB+） | check-env.sh 内存告警；`.wslconfig` 调整由用户自行操作 |
| 镜像拉取超时 | 前缀源为仓库内常量，失效时改一处全量替换；备用源探测后报批再改 |
| Ollama CPU 推理慢 | 属预期（无 GPU）；可降级更小模型演示 |

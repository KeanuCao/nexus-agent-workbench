---
name: project-mirror-daocloud-verified
description: 2026-09-03 实测 docker.m.daocloud.io/library/ 写法可用（hello-world 2.9s 成功），检查点 B/C/D/F/G/H 全部通过
metadata:
  type: project
---

2026-09-03 检查点 B 通过：主探针 `docker pull docker.m.daocloud.io/library/hello-world` 在 WSL `nexus-agent-workbench` 内实测成功——real 2.872s、退出码 0、digest sha256:5dd0d3e6...203243cc，无需降级阶梯。

2026-09-03 检查点 C 通过：三镜像 `docker manifest inspect` 全部 OK（零下载），compose 现状无需改文件——`docker.m.daocloud.io/pgvector/pgvector:pg16`、`docker.m.daocloud.io/library/redis:7-alpine`、`docker.m.daocloud.io/ollama/ollama:latest`。ollama:latest 为 OCI image index（amd64 manifest digest sha256:9e7d782e99880c70f9563c51633da875ca605518a8f8d95c2532bda70a027b7a，arm64 为 sha256:8818bcd5...ec6fbab）。

**Why:** 该探测结论决定 compose/Dockerfile 全部镜像引用的写法（官方镜像带 `/library/` 路径）。之前只做过 compose config 静态校验，未实机拉取。

2026-09-03 检查点 D 通过：`docker compose pull postgres redis ollama` 一次成功，real 4m17.9s、无重试。实测尺寸：pgvector/pgvector:pg16 = 621MB (ccc6e83d6e35)、library/redis:7-alpine = 57.8MB (ff02b58f971e)、ollama/ollama:latest = **8.45GB** (020e4134285e)。ollama latest 远大于计划预期的 0.6-1.5GB（官方 latest 自 v0.6.x 起改为捆绑 CUDA 的"fat"镜像）；磁盘不受影响（WSL `/` 剩 951GB），但 Step 10 固定 OLLAMA_TAG 时需决策：CPU 推理场景是否改用更小的 CPU-only tag（备选方案，届时报批）。

**How to apply:** 后续 compose/Dockerfile 官方镜像统一写 `docker.m.daocloud.io/library/<镜像>`；镜像源可用性已实测，无需再探。docker 命令仍经 `wsl -d nexus-agent-workbench --` 执行，相关纪律见 [[feedback-docker-runtime-restrictions]]。

2026-09-03 Step 10 收尾完成（0.1 验收闭环）：① `OLLAMA_TAG` 已固定 `0.33.2`——manifest inspect 实测 0.33.2 与 latest 的 amd64 manifest digest 完全相同（sha256:9e7d782e...027b7a），按批准执行 `docker compose up -d ollama` 重建（零层下载，healthy，`ollama list` 两模型仍在卷内）；CPU-only 候选 tag（0.33.2-cpu / cpu-0.33.2 / latest-cpu）全部 NOT-FOUND，官方无更小 tag。② `wsl.abc.md` 已建（四段结构 + 6 条四段式实测记录）。③ 设计文档 §2.1 ollama 行已同步（tag 0.33.2、健康检查 `ollama list`）。④ task.1 完成状态 0.1 已勾选；`docs/核心任务.md` 无 0.1 单独标记未动（阶段0 整体状态待 0.2-0.4 完成后再更新）。

2026-09-03 检查点 F 通过（Step 5）：`docker compose up -d postgres redis ollama` 三件套拉起成功，首次轮询即全部 `Up (healthy)`。ollama 健康检查已按批准改为 `["CMD", "ollama", "list"]`（原 curl /api/version），实测通过（GIN 日志每 10s 见 `GET /api/tags` 200）；容器实测挂载 `[CMD ollama list] interval=10s timeout=5s retries=10`。PG "ready to accept connections"、Redis "Ready to accept connections tcp"、ollama 监听 11434。下一步 Step 6 ollama-init 拉模型（≈5GB）待用户确认后执行。

2026-09-03 检查点 G 通过（Step 6）：`docker compose up -d ollama-init` 一次性拉取成功，约 2 分钟内完成。证据三件套：① `docker compose ps -a` 显示 `Exited (0)`；② `curl localhost:11434/api/tags` 含 qwen2.5:7b（4.68GB，Q4_K_M，digest 845dbda0...697e）与 nomic-embed-text:latest（274MB，F16，digest 0a109f42...e59f）；③ `docker exec nexus-ollama du -sh /root/.ollama` = 4.7G（卷内落盘）。拉取速率实测 24 MB/s。init 容器日志末尾 `[ollama-init] 模型就绪：qwen2.5:7b / nomic-embed-text`。注意 ollama-init 服务的 image 实际是 `docker.m.daocloud.io/ollama/ollama:latest` + `/bin/sh -c 'set -e ...'` 拉取脚本（非独立镜像）。

2026-09-03 检查点 H 通过（Step 7）：四项探测全绿——`pg_isready -U nexus -d nexus` = accepting connections；`redis-cli ping` = PONG；`/api/version` = ollama 0.33.2；`/api/tags` 含两模型；pgvector 加分项 `SELECT name FROM pg_available_extensions WHERE name='vector'` 返回 1 行。卷持久化实测：`docker compose restart ollama` 后 38s 恢复 healthy，`ollama list` 两模型仍在（nomic-embed-text 274MB / qwen2.5:7b 4.7GB）。`docker compose ps` 三件套全部 `(healthy)`。§7 验收对照表 0.1 前三条打勾，第四条（builder 不常驻）留待 Step 8（`ps -a` 无 builder 行即通过）。注意：经 `bash -c` 双层引号转义时 `docker inspect -f '{{...}}'` 模板会取到空值，用 `docker compose ps` 或去掉 `-f` 直接 inspect 更稳。

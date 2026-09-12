---
name: project-health-probe-findings
description: 健康检查实测偏差的证据链与修复状态（前端 healthcheck / ollama-init 已在 compose 修复、待 up -d 生效；data.timestamp 的 Z 仍未闭环）+ 0.3.3 交付时的命名/探针决策
metadata:
  type: project
---

对**正在运行**的环境做只读实测（`curl /api/health`、`docker inspect`、容器内 `wget`）得到的偏差。

> **维护约定（2026-09-12 补）：本文件登记「当前仍未修复 / 未闭环」的项；任何一条修复后必须立即订正，且写清三件事 —— 修复落在哪个文件、是否已生效、生效判据是什么。**
> 教训：本文件曾把"两条都还没修"写死在第 2 行，而两条实际已在 `docker-compose/docker-compose.yml` 修好 —— **实测结论在事实变化后，反而成了最像证据的过期信息**（越像证据，越容易被后人直接引用而不再复核）。凡"状态类"陈述都要带**复核日期**与**生效判据**。

**2026-09-12：以下两条已修复，变更均落在 `docker-compose/docker-compose.yml`（配置已改、容器未重建 → 均待下次 `docker compose up -d` 生效）：**

1. **`nexus-frontend` 恒 `running/unhealthy`（假故障）—— 已修复，尚未生效。** 根因不是服务问题，是探活命令写错。**证据链**（2026-09-12 实测，三条一起看才闭合）：
   - 容器内 `/etc/hosts` 同时有 `127.0.0.1 localhost` 与 `::1 localhost`；
   - 容器内 `wget -qO- localhost` → exit 1、`127.0.0.1` → exit 0、`[::1]` → exit 1（**IPv6 上没有任何监听**）；FailingStreak 曾达 184，是活计数器；
   - 仓库 `docker-compose/frontend/nginx.conf` 是 `listen 80;`（只 IPv4）；
   - healthcheck 报错原文：`wget: can't connect to remote host: Connection refused`。
   修复：`test: ["CMD-SHELL","wget -qO- 127.0.0.1"]`。**生效状态：配置哈希已变、容器未重建 → 下次 `up -d` 重建前端容器后生效；生效判据 = 复跑 `check-health.sh`，`WARN 1` 归零。**
2. **`ollama-init` 幂等判断失效（`nomic-embed-text` 每次都被判缺失、重复 pull）—— 已修复，尚未生效（已用模拟输出实证）。** `ollama list` 的 NAME 列对未指定 tag 的模型显示 `name:latest`（如 `nomic-embed-text:latest`），原写法 `awk '{print $1}' | grep -qx "${model}"` 要求整行相等，**必然匹配不上**。修复：`awk 'NR>1 {sub(/:latest$/,"",$1); print $1}'`（列表侧剥离 `:latest`、`NR>1` 顺带跳过表头；对自带 tag 的 `qwen2.5:7b` 无影响）。**已用模拟 `ollama list` 输出跑对照实证：修复前 `nomic-embed-text` 判缺失、修复后正确跳过；容器侧待下次 `up -d` 生效。**

**仍未闭环的一条（不是 compose 能修的）：**

3. **`GET /api/health` 的 `data.timestamp` 实为 `2026-09-12T12:40:06Z`**（UTC，`Z` 后缀），而 `docs/api/README.md` §4.2.2 的正则写的是 `[+-]hh:mm` 形式 → 照文档硬判会把正常服务判成契约破坏。脚本按"接受 `Z`、只提示"处理；改文档还是改代码由 backend-engineer 定（`docs/task/task.1+环境准备.md`「待确认事项」#1 在跟踪）。

**Why:** 这些都是"文档/配置写的是一回事、运行起来是另一回事"，只有实机对照才发现；判据照文档硬编码就会把正常环境判成故障。

**How to apply:** 碰 compose healthcheck 或健康契约时先看这几条；`docker compose ps` 里前端 unhealthy 是**已知假故障**（且即将随容器重建消失），不要当服务异常排查。相关：[[project-wsl-tooling-facts]]、[[project-no-internet-verify-empirically]]

**0.3.3 交付时的两个决策（2026-09-12，别被后人"顺手改回去"）：**

- **probe.sh 保留 `nexus_` 前缀，不做改名对齐** 0.3.5 规格名（`probe_container_state` / `probe_ollama_init_exit` / `probe_api_health` / `probe_actuator_health` / `probe_models_ready` / `probe_port_of`）。理由：改名要同步改 up.sh / check-env.sh 两处**已交付**脚本，行为收益为零；且 `nexus_oneshot_state` / `nexus_probe_models` 返回的信息比规格名暗示的**多**（"没跑过/正在跑/已退出"三态、缺失模型清单），布尔/单值返回装不下。规格名 ↔ 实际名的对照表写在 `scripts/lib/probe.sh` 头注释里，评审可直接对照。
- **`nexus_probe_actuator_health` 已实现**（上一轮曾以"容器级判据读 compose healthcheck 即可"为由不实现）。它只在 **L1/L2 结论对不上时**当解释器调用：① 容器 healthy 但 `/api/health` 503；② 容器 unhealthy 却 `/api/health` 全 UP（**只有它能说清是哪个 Boot indicator 挂的，且 diskSpace 只有这里覆盖**）；③ 需要 healthcheck 报错原文对照。正常路径（healthy + UP）不调用，省一次 HTTP。

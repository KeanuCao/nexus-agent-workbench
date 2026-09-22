---
name: no-internet-verify-empirically
description: 本环境（Windows + WSL nexus-agent-workbench）访问外网全部超时/重置、无 proxy，查不了在线文档——只能靠本机实测与 --help 文本验证
metadata:
  type: project
---

# 本环境无外网（2026-09-11 实测）

Windows 侧与 WSL 内 `curl` 访问 `raw.githubusercontent.com`、`docs.docker.com` 均超时（exit 28）或 connection reset；`env` 无 proxy 变量。

**Why:** 2026-09-11 定位 compose 构建报错时，任务要求"给官方文档原文 URL"——实际网络不可达，只能改用本机 compose/buildx 实测取证（好在结论比引文档更贴合本机版本）。

**How to apply:** 涉及"某工具行为规则"的判断，别承诺查文档、别凭记忆把文档当权威：① 优先用 `--help`（如 `docker compose build --help`、`docker buildx bake --help`）确认命令语义；② 设计不触发真实操作的最小实验（如 `-f -` 从 stdin 喂最小 compose + `--print` 类命令）把结论建立在本机输出上；③ 二进制内嵌字符串可用 `grep -a` 查（但格式串可能是拼接的，未必命中）。无法验证的部分要在交付物里明确标注"未验证的推断"。相关：[[compose-build-path-resolution]]

## ⚠️ 2026-09-22 重要细化：**"无外网"只对 WSL 宿主成立，容器是能出网的**（分层，别一律当"断网"）

实测分层（同一时刻）：

| 层 | DNS / 出网 | 证据 |
| --- | --- | --- |
| **Windows 宿主** | ✅ 有 | `curl https://github.com` → **200** |
| **容器内**（nexus-builder / nexus-ollama / nexus-backend） | ✅ 能出网（**经 Docker Desktop 的解析器**，非 WSL 宿主） | 容器 `/etc/resolv.conf` = `nameserver 127.0.0.11`，注释里 `ExtServers: [host(192.168.65.7)]`；`docker exec nexus-builder getent hosts github.com` → `140.82.112.4`；`ollama pull bge-m3` 成功；`git-sync` 从 github 拉到提交；backend → `api.deepseek.com` 调通 |
| **WSL 宿主自身** | ❌ 无 DNS | `/etc/resolv.conf` = `nameserver 10.255.255.254`，`getent hosts github.com` 失败、`curl --max-time 8` → **exit 28 Resolving timed out**（github / maven.aliyun 都一样） |

⇒ **推论**：① "拉不动镜像/依赖"这类判断要区分**在哪一层**执行；builder 容器里的 mvn/aliyun、git、ollama 都可能通。
② 宿主的 `curl` 测试**不能**用来预测容器能否出网（我 2026-09-22 就据此误判过 git-sync 会失败）。

**⚠️ 而且容器那条路是"间歇性"的**：同一天出现两次**自愈型**解析失败，重试即好 ——
① `git-sync` 首次报 `fatal: unable to access … Could not resolve host: github.com`，**7 秒后重试成功**；
② backend 一次问答报 `调用 DeepSeek 失败：ResourceAccessException … I/O error on POST … api.deepseek.com` → 业务码 20100 → HTTP 503，**几分钟后重试成功**。
⇒ **遇到容器内 DNS/连接失败，先重试一次再定性**；别急着改配置或归因代码（那两次都只是解析抖动）。


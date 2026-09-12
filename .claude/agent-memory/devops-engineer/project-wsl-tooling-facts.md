---
name: project-wsl-tooling-facts
description: WSL 发行版 nexus-agent-workbench 内实际装了什么（jq 缺失！python3 可用、docker 29.5.3/compose 5.1.4），写脚本前的假设清单
metadata:
  type: project
---

2026-09-12 在 `nexus-agent-workbench` 发行版内实测的工具清单（写脚本/给命令前先按这个来，别假设）：

| 工具 | 状态 | 影响 |
| --- | --- | --- |
| `jq` | **缺失** | `scripts/lib/probe.sh` 的 **sed/grep 子串兜底路径才是实际执行路径**；"有 jq 会更精确"那条分支目前从没跑过。改判据时必须保证兜底路径也对 |
| `curl` / `ss` / `timeout` / `free` | 有（`/usr/bin/*`） | — |
| `python3` | **有（3.12.3）** | 适合做本地 stub server 来测 HTTP 分支，不必改 compose |
| `powershell.exe` | 可用（`/mnt/c/Windows/System32/WindowsPowerShell/v1.0/`） | 端口双侧检查走它 |
| `docker` | 29.5.3；compose 插件 5.1.4 | 只读探测可用 |
| `bash` | 5.2.21 | 库里仍按 bash 3.2 语法写（无关联数组/间接展开） |
| `shellcheck` | 缺失 | 静态检查只能靠 `bash -n` + 实跑分支 |

**另一条实测教训（与工具无关，但同源）**：Windows 侧经 `wsl -d <distro> -- bash -c "长脚本"` 传参时，**内层双引号会被吞掉**（实测出现 `for` 变量为空、`{{if` 被当命令执行）。可靠写法是 **heredoc 喂 stdin**：

```bash
MSYS_NO_PATHCONV=1 wsl -d nexus-agent-workbench -- bash -s <<'REMOTE'
  ...脚本正文，双引号安全...
REMOTE
```

**Why:** 脚本判据若假设"有 jq 会解析好"就会在真机上悄悄走兜底；长脚本传参引号被吞会让你以为"命令没输出=环境没问题"。

**How to apply:** 写 `scripts/` 下的解析逻辑时，**用无 jq 的路径自测**；在 Windows 侧驱动 WSL 跑脚本一律用 heredoc + `bash -s`。相关：[[project-health-probe-findings]]、[[feedback-docker-runtime-restrictions]]

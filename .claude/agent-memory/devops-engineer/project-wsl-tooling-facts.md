---
name: project-wsl-tooling-facts
description: WSL 发行版 nexus-agent-workbench 内实际装了什么（jq 缺失！python3 可用、docker 29.5.3/compose 5.1.4），写脚本前的假设清单
metadata:
  type: project
---

2026-09-12 在 `nexus-agent-workbench` 发行版内实测的工具清单（写脚本/给命令前先按这个来，别假设）：

| 工具 | 状态 | 影响 |
| --- | --- | --- |
| `jq` | **缺失** | `scripts/sh/lib/probe.sh` 的 **sed/grep 子串兜底路径才是实际执行路径**；"有 jq 会更精确"那条分支目前从没跑过。改判据时必须保证兜底路径也对 |
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

---

**2026-09-22 补充（阶段3 验收实测出来的三条新姿势）：**

1. **`python3` 是首选机械探针器**：3.12.3 + `urllib` 调 Ollama(`/api/embed`)、`subprocess` 调 `docker exec -i ... psql`，一次跑完"取向量 → 排序 → 算前缀一致性"，不需要 jq、不需要临时表。今天用它证明了「入库向量确实带 `search_document: ` 前缀」（`cos=1.000000`）与「答案块排名 27/587」——这类**要拿数字说话**的判定，别用 shell 拼。
2. **SQL 走文件，别内联**：`wsl -- bash -c "... psql -c \"...\$\$...\""` 里 `$$`（SQL 里的美元引用/`$$`）会被 WSL 与宿主两层 shell 吃掉，实测报 `trailing junk after numeric literal at or near "3579nexus_patch_probe3579"`。可靠写法：SQL 落成 `.tmp/main/*.sql`，再 `docker exec -i nexus-postgres psql -U nexus -d nexus -f - < /mnt/c/.../x.sql`。
3. **含 `docker exec -i` 的脚本必须落盘执行，不能用 `bash -s <`**：脚本与 `docker exec -i` 会争同一个 stdin，`-i` 把脚本正文吃光后命令就会静默空转（阶段3 验收任务书里专门点名了这条）。今天所有探针都落成 `.tmp/main/*.sh` / `*.py` + `MSYS_NO_PATHCONV=1 wsl -d ... -- bash <绝对路径>` 执行，零故障。
4. **`api.deepseek.com` 是通的**（订正"整机无外网"的粗判）：`docker inspect nexus-backend` 显示容器**只注入了 `DEEPSEEK_API_KEY`、没注入 `DEEPSEEK_BASE_URL`** ⇒ base-url 取 `application.yml` 默认的 `https://api.deepseek.com`，而知识库问答实测 **595ms 返回 completion** ⇒ 容器确有到该域名的出网。**"无外网"只对 `docs.docker.com`/`github.com` 这类站点成立**（取文档这条路），别据此推断"云端模型不可用"。

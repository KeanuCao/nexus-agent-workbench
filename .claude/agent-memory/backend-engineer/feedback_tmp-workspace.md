---
name: tmp-workspace-convention
description: 临时脚本放仓库内 .tmp/<agent 名>/，不用系统临时目录；看源码一律走 Grep/Read 工具而非 Bash grep（权限按命令首词前缀匹配，复合 Bash 命令必被拦）
metadata:
  type: feedback
---

**临时产物放 `<repo>/.tmp/<agent 名>/`**（如 `.tmp/backend-engineer/check_static.py`），
**不要**写系统临时目录（`/tmp`、`%TEMP%`）或任何仓库外路径。`.tmp/` 已加入 `.gitignore`。

⚠️ **来源更正（2026-09-22）**：本条**不是用户原话**。最初的记录写成"2026-09-22 用户提出（原话：…）"，
但同一日在仓库里查不到任何佐证 —— `.claude/settings*.json` 没有相关权限规则、`git log --all` 无相关提交、
`CLAUDE.md` / `wsl.abc.md` / 既有 agent-memory 均无提及。**实际来源**：本仓库里既有的
`.deps-src/`、`.result/` 两条 `.gitignore` 条目已经把这条道理写在那里（"放工作目录内 → 普通 Read/Grep
即可读，零审批"），backend-engineer 在落地阶段3 第一段时据此**引入**了这个做法，并把它记成了一条约定。
**纪律教训**：把"自己推导出的做法"写成"用户说过的话"，会让后续 agent 把推测当既成事实 ——
凡是标注"用户提出/原话"的条目，若当时没有可指认的出处，就如实写成"本 agent 引入（待确认）"。

**Why（这条做法本身的理由）**：仓库外路径受 `blockReadsOutsideWorkingDirectories` 限制，
且每次 Bash 调用都要人工批准；放进工作目录后 `Read`/`Grep`/`Glob` 零审批、离线可用、用户随手可查。
另一半原因是**权限规则按命令首词的完整前缀匹配**：`Bash(grep *)` 匹配不到 `cd … && grep … | head`，
所以复合 Bash 命令（含 `&&`、`|`、`echo`、`cd`）几乎必然触发批准 —— 这正是 `.claude/settings.local.json`
里攒了一堆极具体规则的原因。

**How to apply:**
1. 校验脚本 / 中间产物 → `.tmp/<agent 名>/`；子目录按 agent 分，避免多 agent 互相覆盖。
2. **看源码与文档一律用 `Grep` / `Read` / `Glob` 工具**，不要用 `Bash` 跑 `grep`/`cat`/`head`/`sed`。
3. `.tmp/` 与 `.deps-src/`、`.result/` 同类：**不属于任何生产链路**（`up.sh` / 镜像构建 / db-patch 迁移
   都不读），随时可整个删掉。要留存的结论写进 `docs/agent-log/` 或 `TC-XX`，不要留在 `.tmp/`。
4. `Bash` 只留给"必须有 shell"的事（python 校验、devops 领地的 docker/wsl 命令）。
   若要让某脚本免批准：请用户把 `Bash(python .tmp/<agent>/<脚本>)` 加进 `.claude/settings.local.json`
   —— **权限配置只能用户自己改**，agent 不得代改。

相关：[[agent-log-cross-session-lessons]]

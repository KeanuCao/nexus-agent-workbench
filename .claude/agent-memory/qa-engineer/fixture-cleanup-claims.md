---
name: fixture-cleanup-claims
description: 夹具脚本里每句「异常退出也会清理」都必须是验过的行为；bash 信号/trap 与 curl -o 的三个实测坑
metadata:
  type: feedback
---

**规则**：夹具脚本里每一句「异常退出（Ctrl-C / 失败）也会清理」**都必须是真验过的行为**，不能是"我以为 bash 会这样"。

**Why**：2026-09-17 写 `qa/scripts/tc01-two-tenant-me.sh` / `tc01-cross-tenant-token.sh` 时，第一稿注释写着
「Ctrl-C / 中途失败由 EXIT trap 兜底」—— 实测（WSL bash 5.2）撞到三件事：

1. **未被 trap 的信号直接终止 bash，EXIT trap 根本不执行**（实测：SIGTERM 后日志里只有 STARTED）。
   解法：`trap 'exit 130' INT` / `trap 'exit 143' TERM` 把信号转成一次正常退出，再让 EXIT trap 去清理，退出码保持 130/143。
2. **`… &` 起在后台的 bash 会继承 SIGINT 的忽略态**，而"入口即被忽略的信号无法再被 trap" ——
   这同时意味着**用「后台作业 + `kill -INT`」的干跑手法验不出 Ctrl-C 行为**：得用 python 的
   `preexec_fn=lambda: signal.signal(SIGINT, SIG_DFL)` 复位后再 exec，才测得准。
3. **`curl -s -o file` 在连不上时不会创建 `file`**，后面直接 `cat` 会被 `set -e` 吞成退出码 1、
   把真正的 `exit 3` 顶掉（现象：诊断信息打了、退出码却不对）。夹具里打印响应体一律走
   「文件不存在也说清楚」的封装（如 `show_body`）。

**How to apply**：
- 写"异常退出也会清理"这类承诺前，先**真发一次信号**验证；验不了就明确标注"未实测"，别写成既成事实。
- 有副作用的夹具**先取跑前指纹、再做写操作**：前置能力（如取 Redis 指纹的 `docker exec`）不成立时以独立退出码停下，**一个键都不写**。
- 干跑是低成本高回报的：本轮用「桩后端 + 桩 docker」（全部落在系统临时目录，不碰仓库、不碰真实环境）
  跑了 6 个用例，抓出上面第 2、3 条的两个真 bug，并顺带验证了退出码 0/3/4/130/143 与清理路径。

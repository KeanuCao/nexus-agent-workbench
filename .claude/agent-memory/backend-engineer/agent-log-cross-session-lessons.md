---
name: agent-log-cross-session-lessons
description: docs/agent-log/ 是本项目跨会话教训留档（含"报告自身错误"段），采用草稿/子代理结论前先查它并自行裁定
metadata:
  type: reference
---

`C:\wp\nexus-agent-workbench\docs\agent-log\` 每份留档含"**遇到的坑（含本报告自身的错误）**"与"**下次怎么改进**"两段，是本项目跨会话的经验入口。`docs/drafts/` 下则是**未经复核的原始草稿/转述**（例：`环境检查脚本.md` 事实有误——端口 8080→实为 8089、`/api/actuator/health` 必然 404、断言"前端没配 healthcheck"而 compose 里已配、pgvector 扩展未启用、`t_db_patch` 与 `tenant_id` 尚不存在）。

**How to apply:** 拿到草稿、子代理报告或"某文件已写明"的转述，**先对仓库实测核对再采用**；同一事实出现互相矛盾的结论时，用一条只读命令自行裁定，不采信"更晚返回的那份"。核对后用 `[[backend-first-build-transient-truncation]]` 那类结论条目沉淀。

**Why:** 2026-09-11 两起实例——(1) agent-log 记录同源两个子代理对同一现象给出相反结论；(2) 环境检查脚本草稿的 6 处判据错误若直接变成任务，会产出"改一个不存在的问题""必然 FAIL 的验收项"。写进项目文件的结论必须与原始证据对账。

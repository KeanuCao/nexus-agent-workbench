# 子代理任务留档（agent-log）

> 由 `.claude/hooks/agent-archive-check.js`（Stop hook）驱动：子代理任务耗时 ≥ 2 分钟时，主会话自动在此目录为其留档，积累可复用的工程经验。

## 文件命名

`YYYYMMDD-<任务简称>.md`（如 `20260903-design-doc-task1.md`）

## 留档格式（每篇固定四段）

```markdown
# <任务简称>

- **Agent**：devops-engineer ｜ **耗时**：约 X 分钟 ｜ **task-id**：<id>

## 做了什么
（1-5 条，只写交付物与结论）

## 遇到的坑
（如实记录：现象 → 原因 → 当时的处理）

## 下次怎么改进
（可执行的改进点，下次同类任务直接照做）
```

## 说明
- 只留档"耗时 ≥ 2 分钟且已完成"的任务；被杀停的任务不留档。
- 状态跟踪文件：`.claude/hooks/agent-archive-state.json`（记录已提醒的 task-id，防重复提醒）。
- 留档完成后无需回写状态文件（hook 在提醒时即标记，同一任务不会再次提醒）。

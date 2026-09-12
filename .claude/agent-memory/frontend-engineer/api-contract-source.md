---
name: api-contract-source
description: 前端接口契约的唯一事实源已是 docs/api/README.md（阶段0 起）；附 503 降级链路的前端边界与 timestamp 格式分歧的处理态度
metadata:
  type: project
---

**契约来源已迁移（2026-09-12 核对确认）**：`docs/api/README.md` + `openapi.yaml` 由 backend-engineer 产出，是该文件自述的"唯一事实源"（协议变更先改文档再改代码）。阶段0 早期"以 `docs/design/00-环境与部署.md` §5.3 当契约"是当时的临时口径，**此后不再适用** —— 接到接口相关任务先读 `docs/api/`。

阶段0 现有接口只有 `GET /api/health`：
- `GET /api/health` 的线上契约在 §2。**200 与 503 的响应体结构完全相同**：503 时 HTTP=503、`code=20000`、`msg` 形如「依赖服务不可用：redis」（全角冒号，多个依赖用顿号连接），**`data` 仍是完整 HealthReport**（`checks.{postgres,redis,ollama}` 各为 `UP`/`DOWN`，取值域只有这两个）。前端据此才能逐项标红。
- §4 是面向 devops（`check-env.sh` / `check-health.sh`）的判据来源，**不是前端规则**，只读不改。
- 契约红线：不改 `docs/api/`、不改 CORS、不 Mock 数据。跨域由 Nginx 反代解决。

**已知且不由前端处理的分歧**：`data.timestamp` 实测形如 `2026-09-11T15:31:54Z`（UTC，`Z` 后缀），而 §2.2/§4.2.2 写的是 `+00:00` / 正则 `[+-]hh:mm` —— 照文档硬校验会把**正常响应判成契约破坏**。用户 2026-09-12 明确：该分歧已在后端侧记录，**前端不要写严格时区正则、不要去改文档或契约**，原样展示即可。（devops 侧已同步记在同名 agent 记忆里。）

**Why:** 用户 2026-09-12 修复 503 契约缺口时指定「§4 是这条链路的唯一权威判据，只读它不要改它」，并强调"降级时看不到哪个依赖挂了"正是最该被展示的时刻 —— 健康面板逐项展示 DOWN 是面试演示里"探活是真的"的活证据，属于要保住的产品意图，不要为了简化 UI 把它合并成一句笼统提示。

**How to apply:** 前端任务开工第一步扫 `docs/api/`；文档与实机不一致时**如实报告差异**而非自行修正（改文档属 backend-engineer / devops 领地）。相关：[[dependency-version-baseline]]

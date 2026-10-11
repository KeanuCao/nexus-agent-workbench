---
name: project-report-pipeline-delivery
description: 5.5 报告/结果链路已交付（finalize-run.py + 结果表 + report-nginx 站点）；**workflow 未接线是已知缺口**；真卷验证要等下一次部署/M7
metadata:
  type: project
---

2026-10-10 交付（阶段5 最后一个 AI 子任务；真源 = `docs/design/05-自动化测试.md` **§7.4**，此处只留跨会话要点）：

- **收尾脚本 `scripts/py/finalize-run.py`**（一条命令：Allure 生成 → 发布进报告卷 → 结果落库）——
  幂等、可原样重放、`--dry-run` / `--volume <备用卷>` 可预演（**真卷 `nexus-test-report` 当时有红线不许动**，
  全链是在临时备用卷上验完并删掉的；结果库只留首跑那 1 行真实数据）。
- **已知缺口（会挡住 M7）**：`.github/workflows/e2e-smoke.yml` **还没调用 finalize-run.py**（5.5 的改动范围不含 `.github/`）
  ⇒ M7 前必须先补那一步（`if: always()` + `--expect-run-id "$E2E_RUN_ID"`；口径见 §7.4.6）。只跑 workflow 是不会出报告/不进库的。
- **测试宿主上已有 Allure CLI 缓存**：`~/.cache/nexus-e2e/allure/2.46.1`（~30MB，不再重新下载；每次运行会 sha256 校验）。
- **结果库**：`nexus-test-postgres-results` 的 `nexus_test_results`，表 `t_e2e_run` / `t_e2e_case` + 3 个 `v_e2e_*` 视图；
  DDL 是 `docker-compose/postgres-results-init/02-results-schema.sql`（**存量卷不会跑 initdb** ⇒ 靠收尾脚本每次重放收敛）。
- **改 report-nginx / compose 后要等"下一次部署"**：容器只在 `up -d` 检测到配置变化时重建（本轮的 healthcheck 改 `/healthz` 同理）。

**Why:** 这套链路跨"宿主脚本 / 命名卷 / 结果库 / compose / workflow"五处，光看单文件看不出"还差哪一步"；
M6/M7 开工时最容易踩的就是"workflow 没接线却去找报告为什么没有"。

**How to apply:** 涉及报告/结果的问题先读 §7.4；重启验证前先确认 workflow 接线是否已补；
在测试宿主上做验证时优先"备用卷 + --volume"套路（见 [[project-test-distro-host-tooling]]）。

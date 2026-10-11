---
name: project-report-pipeline-delivery
description: 报告/结果链路已交付并两轮演进（5.5 单快照 → 2026-10-11 按 run_id 并存 + 邮件/Step Summary 带报告地址 + 自愈式旧布局迁移）；真卷首跑即自动迁移（尚未在真卷上执行）
metadata:
  type: project
---

交付史（真源 = `docs/design/05-自动化测试.md` **§7.4**，此处只留跨会话要点）：

- **5.5（2026-10-10）**：`scripts/py/finalize-run.py`（一条命令：Allure 生成 → 发布进报告卷 → 结果落库）——
  幂等、可原样重放、`--dry-run` / `--volume <备用卷>` 可预演；结果库 = `nexus-test-postgres-results` 的
  `nexus_test_results`（`t_e2e_run`/`t_e2e_case` + 3 视图；存量卷靠收尾脚本每次重放 DDL 收敛）。
- **workflow 接线已补**（2026-10-10，commit `2ca5971`）：步骤 5「收尾」= `finalize-run.py --expect-run-id "$E2E_RUN_ID"`，
  `if: always()`。2026-10-11 已有真实 CI 运行产物（`ci-38139815278-3`）。
- **2026-10-11（本批，未 commit，交主会话）**：发布契约改为**按 run_id 并存** —— 卷内 `runs/<run_id>/`
  各一次运行一棵树（落地页/Allure/allure-results/playwright/两份 json），**卷根** = 索引页 `index.html`
  （扫 `runs/*/run-results.json`，最新在前）+ `run-results.json`（最新一次）；只动本次 run 目录，
  全新卷 / 旧布局首清一次（见下条）。`notify-mail.py` 正文加「报告」块（根索引/本次运行页/Allure 直链），
  host 取 `REPORT_BASE_URL`（缺 = 按 `.env.test` 的 `REPORT_PORT` 拼 localhost），缺可选输入时降级不崩。
  自测全在备用卷（已删卷）+ 结果库自测行已清，真卷/真容器未动。
- ⚠️ **真卷 `nexus-test-report` 仍是旧布局**（卷根直放落地页/报告，`runs/` 里只有平铺 `<run_id>.json`，
  2026-10-11 12:59 由 `ci-38139815278-3` 写入）。**自愈式迁移已拍板并实现（2026-10-11，用户）**：
  首清判据 = 卷根没有 `runs/` **或** `runs/` 下**无 `<run_id>/` 子目录**（旧布局）⇒ 首跑新脚本即整树清一次、
  自动迁到新布局（旧内容随之消失 —— 固有代价：真卷首跑后 `ci-38139815278-3` 的卷内报告不复存在、DB 行仍在）；
  已在备用卷以"手工造旧布局"实测。**真卷上的首次执行仍未发生**（等下一次部署/冒烟）。
- **端口口径单一实现**：`docker-compose/.env.test` 的 `REPORT_PORT` 是唯一真源；读法 = 共享模块
  `scripts/py/report_env.py`（finalize-run / notify-mail / workflow 步骤 6 三方共用）；脚本与 workflow 里
  **不再有 `12008` 字面量**。workflow 步骤 6 的 Step Summary 现在给报告三行（索引/本次运行页/Allure 直链）。
- **run_id 收口**：`RUN_ID_PATTERN` 已拒 `.`/`..`（负向前瞻）；`/` 等非法字符本就拒绝（写操作前收口）。
- 测试宿主上已有 Allure CLI 缓存：`~/.cache/nexus-e2e/allure/2.46.1`（每次运行 sha256 校验，不重下）。
- 改 report-nginx / compose 后要等"下一次部署"：容器只在 `up -d` 检测到配置变化时重建。

**Why:** 这套链路跨"宿主脚本 / 命名卷 / 结果库 / compose / workflow"五处，光看单文件看不出"还差哪一步"。

**How to apply:** 涉及报告/结果的问题先读 §7.4（布局真源 = §7.4.2）；在测试宿主上做验证优先
"备用卷 + `--volume`"套路；从 Windows 驱动取证用 `bash -s` + heredoc（见 [[project-wsl-tooling-facts]] 2026-10-11 条）。

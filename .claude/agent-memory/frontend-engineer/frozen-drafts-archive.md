---
name: frozen-drafts-archive
description: docs/drafts/ 是冻结存档（原文不动）—— 里面 tid="password" 这类被拍板推翻的旧写法不要"顺手修"
metadata:
  type: project
---

`docs/drafts/` 下是**冻结的存档草稿**（task.6 明文「存档区原文不动」），不是待维护的文档。

**Why**：阶段链路是「草稿 → 拍板 → 落地」，草稿原文要留着做对照（拍板到底改了什么）。
实例：草稿 12 写 `tid="password"`，而 2026-10-09 用户拍板的前端定位属性是 `data-tid`（禁混用）——
草稿里保留旧写法是**史料**，改了就无法对照。

**How to apply**：任何"全仓统一 / 禁止混用"的核查，命中 `docs/drafts/` 的一律**排除**并上报，不要动手改。
2026-10-09（5.3）实例：`grep -rn 'tid='` 全仓唯一命中就是 `docs/drafts/自动化测试.md:24`，按此纪律保留并上报。

关联：定位契约真源 = `docs/design/05-自动化测试.md` §7.2（收窄到 task.6 5.3 表格的 16 个值 / 5 个文件）；
落层结论的取法沿用 [[library-behavior-verify-by-source]]（读 element-plus 编译产物定 `$attrs` 落层）。

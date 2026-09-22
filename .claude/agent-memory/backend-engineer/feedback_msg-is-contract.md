---
name: msg-is-contract
description: 改 main 源码里任何文本前先分类：Javadoc/注释可直接改；msg/用户可见文案属契约，必须连带同步 docs/api/README.md + openapi.yaml 并点名上报
metadata:
  type: feedback
---

订正 main 源码里的文案/数字之前，**先分清两类**：

1. **纯 Javadoc / 注释** → 直接订正即可；
2. **`msg` / 用户可见文案**（会进 `Result<T>` 响应体、被前端直接展示）→ 属**契约**：同一次改动里
   必须同步 `docs/api/README.md` 的错误码/文案表**和** `docs/api/openapi.yaml` 里对应的描述，
   并在报告里**点名列出改了哪几处契约**（只改代码不改契约 = 契约与实现漂移）。

**Why:** `msg` 是前端失败分支唯一的展示来源（`docs/design/03` §5.2 明写"直接用后端 msg"），
两处不同步就制造出本项目最忌讳的那类矛盾 —— 契约文档与实现各说一套，等到联调/验收才以"理解不一致"爆出来。
已发生过一次真实的同构事故：`docs/api/README.md` §5.2 的 `tokenType` 说明自相矛盾，靠执行者主动上报才没沉进代码。

**How to apply:** 判断顺序 = 先看这段文字会不会进响应体；**会有"故意含糊"的文案**（不想给用户精确数字）时，
保持含糊、只把明显错误的量级改掉，并把理由写进报告（用户已授权这种判断，但要讲理由）。
同类纪律（谁的文件谁改）见 [[rag-upload-timing]]；"有单一真源的值怎么写"见 [[doc-single-source]]。

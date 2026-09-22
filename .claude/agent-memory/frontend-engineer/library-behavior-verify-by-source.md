---
name: library-behavior-verify-by-source
description: 第三方库行为的断言（尤其被写进注释/文档当"铁律"的）必须读 node_modules 源码 + 跑 Node 侧探针实证，并贴原始输出；"已证实、别重新推导"的前提也要复核
metadata:
  type: feedback
---

**规则**：凡是把第三方库（axios 等）的**运行期行为**写进代码注释或文档当"铁律"的，落笔前必须
① 读本机 `node_modules` 里**这个版本**的源码定位到行、② 跑一次 Node 侧探针拿原始输出。原始输出要
贴进回报（用户明确要过"把那三次的原始输出贴给我"）。

**Why:** 2026-09-22 上传 415（`code 40002`）事故就是一条**没被实证的设计断言**导致的 ——
"axios 1.x 在 data 是 FormData 时会主动删掉 Content-Type" 被写进了 `docs/design/03-RAG知识库.md`
§5.1-2 与 `kb.ts` 注释，于是 `uploadDocument` **刻意不加** headers；实测真相是 axios 1.20 的
`defaults/index.js:57` 见请求头含 `application/json` 就把 FormData 转成字符串 `{"file":{}}`
（文件字节一个都没发），且 data 到适配器时已不是 FormData、`resolveConfig.js:65-73` 的"摘头"分支
**永不触发** ⇒ 415。断言自己没错、只是漏了前置条件，而代价是一次走查失败 + 一整轮返工。

**How to apply:**
- 接到"根因已证实、不要重新推导"的任务时，**仍要复核那半句前提**：本次任务给的前提里，
  "自己拼 `; boundary=…` 是唯一真会丢 boundary 的写法"这半句**实测不成立**（本版 axios 会把带
  boundary 的头一并摘掉，所以拼死它只是"侥幸不炸"）。照实上报、按可验证的口径落笔，
  不照抄 —— 这正是 CLAUDE.md「指出矛盾不等于可以停工」的用法：先做，同时把偏差说清。
- 探针技巧（非显然，值得复用）：axios 的 `platform.hasStandardBrowserEnv` 是**模块求值时**由
  `window`/`document` 存在与否算出的，Node 里恒为 false ⇒ "浏览器环境"那半边逻辑观察不到。
  **在 import axios 之前**桩好 `globalThis.window = { location: { href: 'http://localhost/' } }`
  与 `globalThis.document = { cookie: '' }`，才能让 `resolveConfig` 走浏览器的删头分支。
  还要注意：用 `transformData.call(config, defaults.transformRequest)` 复现 `dispatchRequest.js:46`
  的真实调用形态，比直接调 `transformRequest` 更忠实。
- 探针写到**仓库外的临时目录**（本次是 `%TEMP%`），不要为了验证在 `frontend/` 里落临时文件 ——
  用户对"只准改哪几个文件"是当红线的。

**相关**：[[dependency-version-baseline]]（换版本时这类行为断言会整体失效，要重跑探针）、
[[api-contract-source]]（契约与设计文档的分工 —— 本次 `docs/api/README.md` §7.1 已被 backend-engineer
订正，而 `docs/design/03-RAG知识库.md` §5.1-2 还留着旧说法，两份文档互相矛盾，属要上报的缺口）。

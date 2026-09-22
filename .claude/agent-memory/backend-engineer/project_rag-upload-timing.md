---
name: rag-upload-timing
description: RAG 上传耗时的权威口径（131.9 s / 3.6 s/批 / 唯一硬上界 300 s）+ 哪些旧数字绝对不能就地订正（db-patch checksum、设计 §0.3、agent-log）
metadata:
  type: project
---

上传耗时的**权威口径**（2026-09-22 经 8088 端到端实测，两次一致）：
夹具 `公司年报.pdf`（1.68MB → 263,910 字 → **587 块** → **37 批**）：**131.9 s**（同日更早 129.75 s）；
向量化 ≈ **3.6 s/批**（bge-m3 / CPU / 无 GPU）。派生的两个数：`max-chunks-per-document` **3000 块 = 188 批 ≈ 11 分钟**（> 前端 300 s ⇒ 走"超时 ≠ 失败"，服务端继续入库）；
2 万块 TXT（10MB 量级）= **1250 批 ≈ 75 分钟**（这是"**没有 10204 这道闸**"的**反事实** —— 超限文件压根不进向量化，别把它读成耗时预期）。

**Why:** 设计初版按 2.1 s/批 估的"80~90 秒"来自宿主直连 Ollama，实测偏慢 1.5 倍 ⇒ 2026-09-22 定案：
**耗时只当参考记录值、不当硬判据；唯一硬上界 `< 300 s`（与前端上传超时同源）**。`docs/api/README.md` §7.1 第 3 条
（"超时 ≠ 失败"）与设计 §5.1-4 / §9 风险 14 是这条口径的契约侧落点。

**How to apply:** 再写任何与上传耗时有关的判据、文案、注释时用这套数；**算式在代码里只写一处** =
`ResultCode#KB_CONTENT_TOO_LARGE` 的 Javadoc（RagProperties / KbDocumentServiceImpl / application.yml 只留结论 + 指针），
避免"两处各写一份、早晚漂成两个数"。

⚠️ **这些地方的同款旧数字（如 "≈ 90 秒" / "10~60s" / "几分钟"）不可就地订正**：
- `db-patch/*.sql` 历史补丁 —— **硬红线**：`PatchCli` 会对已应用补丁比对 checksum，内容一变就
  `EXIT_CHECKSUM_MISMATCH` **终止迁移（exit 2）⇒ 后端起不来**。补丁注释里的作废数字只上报、只留原样。
- `docs/design/03-RAG知识库.md` §0.3（"验收夹具约 90 秒"）与 `docs/agent-log/**` —— 分别归主会话与历史留档，
  按各自主人的决定处理，我这一侧只改 main 源码（后端 java/yml）。
- `frontend/`（`api/kb.ts` 等）已由前端侧订正，别重复改。见 [[msg-is-contract]]。

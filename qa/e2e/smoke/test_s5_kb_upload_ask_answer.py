"""S5 知识库：上传夹具 → 提问 → 出现答案与引用（冒烟集合第 5 条，**最慢**）。

出处：`TC-03-3.5-1`（前端完整闭环的界面侧）+ `TC-03-3.1-3` ③（内容判据的兜底口径：
「答案里出现 客户成功部；措辞没带上时以 `sources[].content` 里找得到为准」）。
定位：`kb-upload` / `kb-question` / `kb-ask` / `kb-answer`（设计 §7.2）；
      上传的**文件框**是 `kb-upload` 内部的隐藏 `input[type=file]`（§7.2.2 的 el-upload 行）。

**作用域声明**（Test Harmlessness）：本用例会写两样东西 ——
  ① 一次真登录 ⇒ Redis 白名单 1 个键（TTL 7200s），由 `cleanup_login` 登出并自证；
  ② **本用例自建的一份文档**（夹具 `知识库说明.txt`，3 块），由下头的 `kb_doc_registry`
     在用例内删掉，并用**跑前/跑后列表指纹**自证回到了原样。
为什么非写不可：本条的验收对象就是"上传→入库→检索→出答案"，只读观察拿不到等价证据。
还有谁在消费它：知识库列表与检索（当前租户）—— 跑到一半被打断的最坏情况 = 多一份 3KB 的
测试文档，列表里带文件名、可手工删（命令见 README），不影响任何后续用例。
"""

from __future__ import annotations

import re
from typing import Any, Dict, Iterator

import pytest
from playwright.sync_api import expect

from lib import api, kb, ui

pytestmark = pytest.mark.tc("S5", "知识库：上传夹具 → 提问 → 出现答案与引用", "TC-03-3.5-1")

# 夹具里埋的"只有它才有的答案"（见 qa/fixtures/rag/README.md）
QUESTION = "星桥计划的试点部门是哪个部门"
ANSWER_KEYWORD = "客户成功部"

# 引用卡片的标题形态：`文件名 · 第 N 段 · 相似度 x.xx`（chunkIndex 0 起 ⇒ 显示时 +1）
CITATION_TITLE = re.compile(r"知识库说明\.txt · 第 \d+ 段 · 相似度 \d")

# 环境前置（2026-10-09 已修复）：测试档把 RAG 回答模型指向本地 Ollama
# （`.env.test` 的 NEXUS_AI_RAG_ANSWER_MODEL_TYPE + 测试 compose 的 backend environment 透传）。
# 故 code=20100（上游回答模型不可达）不再是"环境没配"，**按失败处理**（记要见 qa/e2e/README.md）。


@pytest.fixture
def kb_doc_registry(
    page,
    e2e_settings,
    logged_in,
    record_property,
) -> Iterator[Dict[str, Any]]:
    """一次性作用域：记下跑前指纹，收尾时删掉本用例自建的那一份并自证。"""
    base_url, token = e2e_settings.base_url, logged_in.token
    before = kb.fingerprint(page, base_url, token)
    registry: Dict[str, Any] = {"document_id": None, "before": before}
    yield registry

    document_id = registry.get("document_id")
    if document_id is None:
        record_property("cleanup.kb.status", "none")
        record_property("cleanup.kb.detail", "本次没有自建文档（上传未成功），无需清理")
        return

    try:
        status, payload = kb.delete_document(page, base_url, token, int(document_id))
        after = kb.fingerprint(page, base_url, token)
    except Exception as exc:  # 连列表都取不到时如实记 warn（不把通过的用例炸成 error）
        record_property("cleanup.kb.status", "warn")
        record_property(
            "cleanup.kb.detail",
            f"收尾没走完（{type(exc).__name__}: {exc}）—— 自建文档 documentId={document_id} "
            f"可能还在库里，手工删：DELETE /api/kb/documents/{document_id}",
        )
        return

    code = (payload or {}).get("code")
    detail = (
        f"删除自建文档 documentId={document_id} → HTTP {status} / code={code}"
        f"（200/0 或 10202 都表示「已经没有了」）；"
        f"列表指纹：跑前 {len(before)} 份 / 跑后 {len(after)} 份"
    )
    if after == before:
        record_property("cleanup.kb.status", "ok")
        record_property("cleanup.kb.detail", detail)
        return

    residue = [item for item in after if item not in before]
    record_property("cleanup.kb.status", "warn")
    record_property(
        "cleanup.kb.detail",
        detail + f"；⚠️ 未回到跑前，残留={residue}（手工删：DELETE /api/kb/documents/{document_id}）",
    )


def test_s5_kb_upload_then_ask_shows_answer_with_citations(
    page, e2e_settings, logged_in, kb_doc_registry
):
    fixture_path = e2e_settings.kb_fixture
    assert fixture_path.is_file(), f"缺少夹具：{fixture_path}（见 qa/fixtures/rag/README.md）"

    page.goto("/knowledge")
    expect(page.locator(ui.KB_UPLOAD)).to_be_visible(timeout=e2e_settings.ui_timeout_ms)

    # ── ① 上传（同步口径：响应回来时已解析、分块、向量化、入库）────────────────
    with page.expect_response(
        lambda r: r.url.endswith("/api/kb/documents") and r.request.method == "POST",
        timeout=e2e_settings.llm_timeout_ms,
    ) as upload_info:
        page.locator(ui.KB_UPLOAD_FILE_INPUT).set_input_files(str(fixture_path))

    upload_response = upload_info.value
    upload_body = api.payload_of(upload_response) or {}
    document = upload_body.get("data") or {}
    assert upload_response.status == 200 and upload_body.get("code") == 0, (
        f"上传没有成功：HTTP {upload_response.status} / body={str(upload_body)[:300]}"
        f"（若见 413，是 nginx 的体量墙；3KB 的夹具不该撞上它）"
    )
    kb_doc_registry["document_id"] = document.get("documentId")
    assert int(document.get("chunkCount") or 0) >= 1, f"上传成功但分块数为 0：{document}"

    # 列表里出现它（页面判据；重名不去重，故用 .first 兜住"上一次失败留下的同名行"）
    expect(page.get_by_text(fixture_path.name, exact=True).first).to_be_visible(
        timeout=e2e_settings.ui_timeout_ms
    )

    # ── ② 提问（就那份夹具里才有的答案问）────────────────────────────────────
    with page.expect_response(
        lambda r: r.url.endswith("/api/kb/ask") and r.request.method == "POST",
        timeout=e2e_settings.llm_timeout_ms,
    ) as ask_info:
        page.locator(ui.KB_QUESTION).fill(QUESTION)
        page.locator(ui.KB_ASK).click()

    ask_response = ask_info.value
    ask_body = api.payload_of(ask_response) or {}
    assert ask_body.get("code") == 0, (
        f"问答失败：HTTP {ask_response.status} / body={str(ask_body)[:300]}；"
        f"code=20100 = 上游回答模型不可达 —— 测试档应走本地 Ollama，检查 "
        f"NEXUS_AI_RAG_ANSWER_MODEL_TYPE 是否透传进容器；"
        f"后端日志分流：「未配置 DEEPSEEK_API_KEY」= 配置没进容器 /「上游超时或报错」= 本地模型真挂了"
    )

    answer = ask_body.get("data") or {}
    assert answer.get("grounded") is True, (
        f"grounded=false：检索没命中（那份夹具应当在库里）。answer={str(answer)[:300]}"
    )

    # ── ③ 页面判据：答案块出现 + 正文非空 + 引用卡片标题（文件名 · 第 N 段 · 相似度）──
    expect(page.locator(ui.KB_ANSWER)).to_be_visible(timeout=e2e_settings.ui_timeout_ms)
    expect(page.locator(ui.KB_ANSWER_TEXT)).to_have_text(
        ui.NON_BLANK, timeout=e2e_settings.llm_timeout_ms
    )
    expect(page.get_by_text(CITATION_TITLE).first).to_be_visible(
        timeout=e2e_settings.ui_timeout_ms
    )

    # ── ④ 内容判据（TC-03-3.1-3 ③ 的兜底口径）：命中的原文里必须能找到那个词 ──
    sources = answer.get("sources") or []
    assert any(ANSWER_KEYWORD in str(item.get("content") or "") for item in sources), (
        f"引用原文里找不到「{ANSWER_KEYWORD}」—— 命中的不是那份夹具。sources={str(sources)[:300]}"
    )

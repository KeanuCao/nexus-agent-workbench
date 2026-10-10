"""S4 对话页发一句 → 出现回答（冒烟集合第 4 条）。

出处：`TC-02-2.4-1`（下拉默认「本地 Qwen（Ollama）」、发送后出现回答）。
定位：`chat-model` / `chat-input` / `chat-send`（设计 §7.2）；
      回答气泡本身是**表外元素**（§7.2.5），用既有语义选择器定位。
副作用：一次真登录（Redis 白名单 1 个键，`cleanup_login` 用完即销）+ 一次本地模型推理（CPU）。
"""
from __future__ import annotations

import re

import pytest
from playwright.sync_api import expect

from lib import ui

pytestmark = pytest.mark.tc("S4", "对话页发一句 → 出现回答", "TC-02-2.4-1")

# 要一句话：把 CPU 推理时间压到最短（阈值见 E2E_LLM_TIMEOUT_MS，别照抄别的项目）
PROMPT = "用一句话打个招呼"


def test_s4_chat_reply_appears(logged_in, e2e_settings):
    page = logged_in.page

    page.goto("/chat")
    expect(page.locator(ui.CHAT_INPUT)).to_be_visible(timeout=e2e_settings.ui_timeout_ms)
    # 默认选中的就是本地模型（验收 2.4-1 ① 的前提：选谁走谁）
    expect(page.locator(ui.CHAT_MODEL)).to_contain_text("本地 Qwen")

    page.locator(ui.CHAT_INPUT).fill(PROMPT)
    page.locator(ui.CHAT_SEND).click()

    # ① 回答**出现且有内容**（逐帧增量那半边由 TC-02-2.4-1 人工判，见 TC-05 备注）
    expect(page.locator(ui.CHAT_ASSISTANT_TEXT).last).to_have_text(
        ui.NON_BLANK, timeout=e2e_settings.llm_timeout_ms
    )
    # ② 气泡上的模型名来自 `meta` 帧（证明真走了模型，而不是前端拼的假回答）
    expect(page.locator(ui.CHAT_ASSISTANT_TAG).last).to_have_text(
        e2e_settings.expect_chat_model, timeout=e2e_settings.ui_timeout_ms
    )
    # ③ 本轮收尾：发送按钮恢复可用（不再停在"生成中…"）
    expect(page.locator(ui.CHAT_SEND)).to_be_enabled(timeout=e2e_settings.llm_timeout_ms)

"""S2 登录失败 → **页面内**出现错误文案（冒烟集合第 2 条，负路径）。

出处：`TC-01-1.4-2`（密码错 → HTTP 200 + `code=10100` + `msg` 可展示）。
判据是**页面内可见文案**（`[data-tid=login-error]` 这条 `el-alert`），不是日志、不是控制台。
副作用：**零写操作** —— 失败的登录不签发 token，Redis 白名单不会多键（`cleanup_login`
因此报 `status=none`，那是预期，不是"清理失败"）。
"""

from __future__ import annotations

import re

import pytest
from playwright.sync_api import expect

from lib import ui

pytestmark = pytest.mark.tc("S2", "登录失败 → 页面内出现错误文案", "TC-01-1.4-2")

# 一个不可能是正确口令的串（不用 admin123 的变体，免得将来撞上弱口令策略）
WRONG_PASSWORD = "e2e-not-the-password"


def test_s2_login_failure_shows_message_in_page(page, e2e_settings, cleanup_login):
    page.goto("/login")
    page.locator(ui.LOGIN_USERNAME).fill(e2e_settings.username)
    page.locator(ui.LOGIN_PASSWORD).fill(WRONG_PASSWORD)
    page.locator(ui.LOGIN_SUBMIT).click()

    # ① 页面内出现那条常驻错误提示（未失败时它**不存在** —— 这正是"出现"这条判据）
    error = page.locator(ui.LOGIN_ERROR)
    expect(error).to_be_visible(timeout=e2e_settings.ui_timeout_ms)
    # ② 文案是后端的 msg 原文（契约 §5.2：`10100` = 用户名或密码错误；防账号枚举，不区分两者）
    expect(error).to_contain_text("用户名或密码错误")
    # ③ 负路径的另一半：没有进到受保护页
    expect(page).to_have_url(re.compile(r"/login"))

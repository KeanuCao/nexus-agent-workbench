"""S3 未登录直达 `/chat` → 被路由守卫送回登录页（冒烟集合第 3 条）。

出处：`TC-02-2.4-8` ③（该路由没加 `public`，由既有守卫处理）+ `TC-01-1.5-2`（带 `redirect` 回跳）。
定位：`login-username`（登录表单确实渲染了）；跳转本身断言的是 **URL**，不依赖任何定位值。
副作用：**零写操作** —— 没有 token 就没有登录请求，守卫在本地就完成了重定向。
"""

from __future__ import annotations

import re

import pytest
from playwright.sync_api import expect

from lib import ui

pytestmark = pytest.mark.tc("S3", "未登录直达 /chat → 被送回登录页", "TC-02-2.4-7 / 2.4-8 ③")


def test_s3_guard_redirects_chat_to_login(page, e2e_settings, cleanup_login):
    page.goto("/chat")

    # `/login?redirect=/chat` —— redirect 参数带上原路径（登录成功后回到它，TC-01-1.5-2 的口径）
    expect(page).to_have_url(
        re.compile(r"/login\?redirect=.*chat"), timeout=e2e_settings.ui_timeout_ms
    )
    # 落到的确实是一个能用的登录页（不是白屏、也不是 404）
    expect(page.locator(ui.LOGIN_USERNAME)).to_be_visible()

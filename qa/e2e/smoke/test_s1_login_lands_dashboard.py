"""S1 登录成功 → 落到控制台（冒烟集合第 1 条）。

出处：`TC-01-1.5-1`（登录成功进控制台）+ `TC-01-1.4-1`（登录接口 200/code=0）的界面侧。
定位：`login-username` / `login-password` / `login-submit` / `dashboard-title` / `nav-logout`
（写法唯一真源 = 设计 §7.2）。
副作用：一次真登录 ⇒ Redis 白名单多 1 个键（TTL 7200s），由 `cleanup_login` 用完即销并自证。
"""

from __future__ import annotations

import re

import pytest
from playwright.sync_api import expect

from lib import ui

pytestmark = pytest.mark.tc("S1", "登录成功 → 落到控制台", "TC-01-1.5-1")


def test_s1_login_success_lands_on_dashboard(page, e2e_settings, cleanup_login):
    result = ui.login(
        page,
        base_url=e2e_settings.base_url,
        username=e2e_settings.username,
        password=e2e_settings.password,
        timeout_ms=e2e_settings.ui_timeout_ms,
    )
    cleanup_login["token"] = result.token

    # 落到控制台（不是停在 /login，也不是被弹回别的页）
    expect(page).to_have_url(re.compile(r"/dashboard"), timeout=e2e_settings.ui_timeout_ms)
    expect(page.locator(ui.DASHBOARD_TITLE)).to_have_text("控制台")
    # 登录态在界面上生效（导航右侧的「退出登录」只对已登录用户出现）
    expect(page.locator(ui.NAV_LOGOUT)).to_be_visible()

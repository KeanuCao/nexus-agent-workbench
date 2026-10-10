"""UI 机械动作库：登录 / 登出 + 收尾自证。只回答「怎么点、怎么等」。

定位写法的**唯一真源** = `docs/design/05-自动化测试.md` §7.2（本节 5.3 交付）。
本文件只落下 S1~S5 实际用到的那几个值，**不复制那张表**；表外元素按 §7.2.5 用
「Role / 文本 / 既有语义选择器」定位，一旦前端改了结构，只需要改本文件。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from playwright.sync_api import Page, expect

from lib.api import api_call

# ── 定位属性（值取自设计 §7.2.1 的 16 个 data-tid；写法照 §7.2.2 的「Playwright 该怎么写」列）──
LOGIN_USERNAME = "[data-tid=login-username]"       # 原生 <input>（tid 就在 input 上，别写 `... input`）
LOGIN_PASSWORD = "[data-tid=login-password]"       # 原生 <input>
LOGIN_SUBMIT = "[data-tid=login-submit]"           # 原生 <button>
LOGIN_ERROR = "[data-tid=login-error]"             # div.el-alert；**未失败时不存在**（S2 的"出现"判据）
NAV_LOGOUT = "[data-tid=nav-logout]"               # 原生 <button>
DASHBOARD_TITLE = "[data-tid=dashboard-title]"     # 原生 <h2>
CHAT_MODEL = "[data-tid=chat-model]"               # div.el-select（触发器，**不是**原生 select）
CHAT_INPUT = "[data-tid=chat-input]"               # 原生 <textarea>
CHAT_SEND = "[data-tid=chat-send]"                 # 原生 <button>
KB_UPLOAD = "[data-tid=kb-upload]"                 # div（无 class）—— 只是作用域锚点
KB_UPLOAD_FILE_INPUT = "[data-tid=kb-upload] input[type=file]"   # 隐藏的文件框，见 §7.2.2
KB_QUESTION = "[data-tid=kb-question]"             # 原生 <textarea>
KB_ASK = "[data-tid=kb-ask]"                       # 原生 <button>
KB_ANSWER = "[data-tid=kb-answer]"                 # div.kb-answer（grounded=true 时才存在）

# ── 表外元素（§7.2.5：用 Role / 文本 / 既有语义选择器，**不要**回头补 data-tid）──
CHAT_ASSISTANT_TEXT = ".chat-bubble--assistant .chat-text"
CHAT_ASSISTANT_TAG = ".chat-bubble--assistant .chat-role .el-tag"
KB_ANSWER_TEXT = "[data-tid=kb-answer] .kb-answer-text"

# ── 常用期望的判据形状 ──
# 「非空白」：to_have_text 的字符串形是精确匹配、正则会退化成"整串要匹配上"，
# 所以用这个正则在两种语义下都成立（长度 ≥ 1 且至少有一个非空白字符）。
NON_BLANK = re.compile(r"[\s\S]*\S[\s\S]*")

# 前端持久化登录态用的 localStorage 键（前端 `stores/user.ts` 的 TOKEN_KEY）。
# 这里**只用于收尾兜底**（拿不到 token 时把它找出来登出），不参与任何判据。
TOKEN_STORAGE_KEY = "nexus.auth.token"


@dataclass(frozen=True)
class LoginResult:
    """一次真登录的结果（token 供收尾使用；不在用例里当判据）。"""

    token: str
    token_type: str


def login(
    page: Page,
    *,
    base_url: str,
    username: str,
    password: str,
    timeout_ms: int,
) -> LoginResult:
    """走界面登录，拿到 token 返回。

    「拿不到 token 就抛」是**机械前置**（后续步骤没有它跑不下去），不是判据：
    登录成不成、落到哪一页由用例自己断言（S1 就是那条断言）。
    """
    page.goto("/login")
    page.locator(LOGIN_USERNAME).fill(username)
    page.locator(LOGIN_PASSWORD).fill(password)

    with page.expect_response(
        lambda r: r.url.endswith("/api/auth/login") and r.request.method == "POST",
        timeout=timeout_ms,
    ) as info:
        page.locator(LOGIN_SUBMIT).click()

    response = info.value
    payload = None
    try:
        payload = response.json()
    except Exception:
        payload = None

    if response.status != 200 or not isinstance(payload, dict) or payload.get("code") != 0:
        raise AssertionError(
            f"机械前置不成立：登录没有成功（拿不到 token）—— "
            f"HTTP {response.status} / body={str(payload)[:300]}"
        )

    data = payload.get("data") or {}
    token = str(data.get("token") or "")
    if not token:
        raise AssertionError(f"机械前置不成立：登录响应里没有 data.token —— body={str(payload)[:300]}")

    # 等界面确实离开了 /login（登录成功后由 LoginView 跳转；**落到哪**由用例断言）
    expect(page).not_to_have_url(re.compile(r"/login"), timeout=timeout_ms)
    return LoginResult(token=token, token_type=str(data.get("tokenType") or "Bearer"))


def logout_and_verify(page: Page, *, base_url: str, token: str = "") -> dict[str, str]:
    """收尾：登出本次登录态 + **自证**（同一个 token 必须已经失效）。

    返回 `{"status": "ok" | "warn" | "none", "detail": "..."}` —— 由 conftest 记进结果文件，
    **不改变用例主结论**（主结论只由用例的断言决定）。

    为什么"自证"是 `GET /api/auth/me → 401 + 40101`：40101 表示过滤器走到了
    「Redis 白名单里没有这个 jti」那一步（TC-01-1.2-5 的推理链），即那条键确实没了。

    最坏情况（status=warn）：那条 jti 还在白名单里 —— 它 TTL 7200s 自净，不影响任何后续用例，
    也**不会**改变业务数据。
    """
    token = token or _read_stored_token(page)
    if not token:
        return {"status": "none", "detail": "本次用例没有登录（没有产生 Redis 白名单键），无需清理"}

    ui_detail = "界面登出：未执行"
    try:
        page.goto("/dashboard", timeout=15_000)
        page.locator(NAV_LOGOUT).click(timeout=10_000)
        expect(page).to_have_url(re.compile(r"/login"), timeout=15_000)
        ui_detail = "界面登出：已回 /login"
    except Exception as exc:  # noqa: BLE001 —— 收尾动作失败不该吞掉用例主结论，如实记进 detail
        ui_detail = f"界面登出未走通（{type(exc).__name__}），改用接口兜底"

    # 兜底（幂等）：界面那条无论走没走成，都用捕获到的 token 再发一次登出。
    # 界面已登出时这里会回 401 —— 那是预期的"再登出一次"。
    logout_status, _ = api_call(page, base_url, "POST", "/api/auth/logout", token=token)
    me_status, me_body = api_call(page, base_url, "GET", "/api/auth/me", token=token)
    me_code = (me_body or {}).get("code")

    detail = (
        f"{ui_detail}；接口登出 HTTP {logout_status}（幂等：界面已登出时应回 401）；"
        f"自证 GET /api/auth/me → HTTP {me_status} + code={me_code}（期望 401 + 40101）"
    )
    if me_status == 401 and me_code == 40101:
        return {"status": "ok", "detail": detail}
    return {
        "status": "warn",
        "detail": detail + "。⚠️ 无害性自证未达成：该 jti 可能仍在 Redis 白名单里"
                           "（TTL 7200s 自净，不阻断后续用例；无需人工收拾）",
    }


def _read_stored_token(page: Page) -> str:
    """兜底：从 localStorage 里把登录态找出来（只在"登录成功但没来得及捕获 token"时有意义）。"""
    try:
        return str(page.evaluate(f"() => localStorage.getItem({TOKEN_STORAGE_KEY!r}) || ''"))
    except Exception:
        return ""

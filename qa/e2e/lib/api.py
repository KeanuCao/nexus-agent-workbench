"""接口层的机械动作：用 Playwright 的 APIRequestContext 发请求，**只取原始观察**。

为什么 E2E 框架里还要发接口请求 —— 三处非它不可，且都不是判据：

1. 收尾要把本次登录态作废（`POST /api/auth/logout`）并自证白名单里确实没了它（见 `lib/ui.py`）；
2. S5 的一次性作用域：把本用例上传的那份文档删掉、并取跑前/跑后的列表指纹；
3. 断言用的响应体，本来就来自浏览器真实发出的那几次请求（`page.expect_response` 拿到的
   `APIResponse` 就用 `payload_of()` 解）。

三条纪律：
- **不吞错**：接口不通 / 响应不是 JSON 都只是"返回 None"，由调用点决定怎么处置；
- **不加断言**：这里没有"该不该通过"，调用点自己看状态码与 body；
- **不写业务数据**，除了"销毁本用例自建对象"这一类收尾动作（见 `lib/kb.py`）。
"""

from __future__ import annotations

from typing import Any, Dict, Optional, Tuple

from playwright.sync_api import APIResponse, Page

JsonPayload = Optional[Dict[str, Any]]


def url_join(base_url: str, path: str) -> str:
    """拼出绝对地址；`base_url` 允许带尾斜杠，`path` 允许不带前导斜杠。"""
    return base_url.rstrip("/") + "/" + path.lstrip("/")


def payload_of(response: APIResponse) -> JsonPayload:
    """把一次响应解成 dict；不是 JSON（如 nginx 的 HTML 错误页）时返回 None。"""
    try:
        body = response.json()
    except Exception:
        return None
    return body if isinstance(body, dict) else None


def api_call(
    page: Page,
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: Dict[str, Any] | None = None,
    timeout_ms: int = 15_000,
) -> Tuple[int, JsonPayload]:
    """发一次请求，返回 `(HTTP 状态码, 响应体 dict 或 None)`。

    - 带 `token` 时加 `Authorization: Bearer <token>`（与前端 `request.ts` 同款）；
    - `body` 传 dict ⇒ Playwright 会 JSON 序列化并置 `Content-Type: application/json`；
    - 失败**不抛**（与 TC 的口径一致：原始观察比"替你判断"有用）：连不上时返回
      `(0, None)` —— "HTTP 0" 就是"请求根本没到服务端"，调用点照常能判。
    """
    headers: Dict[str, str] = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    kwargs: Dict[str, Any] = {"headers": headers, "timeout": timeout_ms}
    if body is not None:
        kwargs["data"] = body

    try:
        response = page.request.fetch(url_join(base_url, path), method=method, **kwargs)
    except Exception:  # 传输层错误（DNS / 连接被拒 / 超时）——收尾阶段尤其不能让它把用例炸成 error
        return 0, None
    return response.status, payload_of(response)

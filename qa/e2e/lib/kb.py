"""知识库接口的机械动作（S5 用）：取列表指纹、删掉本用例自建的那一份文档。

判据不在这里 —— `docs/test-cases/TC-05.md` 的「预期」栏才是。
这里只保证两件事：**幂等**（删不存在的文档返回 10202，不当失败）、**可反复跑**。
"""

from __future__ import annotations

from typing import Any, Dict, List, Tuple

from playwright.sync_api import Page

from lib.api import api_call

LIST_PATH = "/api/kb/documents"

# 列表指纹的形状：只看这三个字段 —— 它们标识"哪一份文档、多大"，且不随会话漂移
# （`createdAt` 之类的时间字段不进指纹：它本来就该变）。
Fingerprint = List[Tuple[Any, Any, Any]]


def list_documents(page: Page, base_url: str, token: str) -> List[Dict[str, Any]]:
    """当前租户的文档列表；取不到就抛（列表取不到，指纹自证与收尾都无从谈起）。"""
    status, payload = api_call(page, base_url, "GET", LIST_PATH, token=token)
    if status != 200 or not payload or payload.get("code") != 0:
        raise AssertionError(f"取文档列表失败：HTTP {status} / body={str(payload)[:300]}")
    items = (payload.get("data") or {}).get("items") or []
    return sorted(items, key=lambda item: item.get("documentId") or 0)


def fingerprint(page: Page, base_url: str, token: str) -> Fingerprint:
    """跑前/跑后各取一次，用来证明"用例跑完之后列表回到了原样"。"""
    return [
        (item.get("documentId"), item.get("fileName"), item.get("chunkCount"))
        for item in list_documents(page, base_url, token)
    ]


def delete_document(page: Page, base_url: str, token: str, document_id: int) -> Tuple[int, Any]:
    """删掉一份文档（幂等：目标不存在时后端回 `10202`，同样算"已经没有了"）。"""
    return api_call(page, base_url, "DELETE", f"{LIST_PATH}/{document_id}", token=token)

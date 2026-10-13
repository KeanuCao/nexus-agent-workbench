#!/usr/bin/env python3
"""report_env.py —— 报告站点口径的共享小工具（finalize-run.py / notify-mail.py / workflow 步骤 6 共用，**只此一份**）

为什么存在（2026-10-11 口径）：
  · 报告端口的**唯一真源** = `<仓库根>/docker-compose/.env.test` 的 `REPORT_PORT`；
  · 脚本里**不许写端口字面量**（换端口只改 .env.test 一处）；
  · 三个消费方（收尾脚本、邮件脚本、workflow 的 Step Summary）都要拼站点地址 ⇒ 读法抽成同目录共享模块，
    避免"N 份实现各自漂移"。

import 约定：
  · `python3 scripts/py/<脚本>` 运行时，Python 会把**脚本所在目录**放进 sys.path[0] ⇒ `import report_env` 天然可用；
  · 若脚本被单独拷出 scripts/py/（非文档用法），notify-mail.py 会用 try/except 降级（读不到端口 = 正文降级）；
    finalize-run.py 不加保护 —— 它本来就依赖仓库布局（.git 上溯 + db-patch 的 schema 路径），拷走跑不成立。
"""
from __future__ import annotations

from pathlib import Path

ENV_FILE_REL = "docker-compose/.env.test"   # REPORT_PORT 的口径真源（相对仓库根）


def repo_root() -> Path | None:
    """按 .git 标记上溯找仓库根（与 finalize-run.py 同一手法）—— .env.test 的定位基准。"""
    here = Path(__file__).resolve()
    for candidate in (here, *here.parents):
        if (candidate / ".git").exists():
            return candidate
    return None


def env_file_port() -> str:
    """从 <仓库根>/docker-compose/.env.test 读 REPORT_PORT（读不到 / 非数字都返回 ""）。"""
    bases: list[Path] = []
    root = repo_root()
    if root is not None:
        bases.append(root)
    bases.append(Path.cwd())   # 兜底：脚本被拷到仓库外时，若 cwd 就是仓库根也认
    for base in bases:
        try:
            text = (base / ENV_FILE_REL).read_text(encoding="utf-8")
        except OSError:
            continue
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            if key.strip() == "REPORT_PORT":
                value = value.split("#", 1)[0].strip()
                if value.isdigit():
                    return value
    return ""

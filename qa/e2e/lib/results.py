"""一次运行的**结构化结果**（run_id + 用例级结论）—— 5.5 落库就读它。

落盘两处（内容相同）：
  · `qa/e2e/artifacts/run-results.json` —— 最近一次运行（覆盖写，供人随手看、供 5.5 取用）
  · `qa/e2e/artifacts/runs/<run_id>.json` —— 每次运行各留一份（历史）

schema 与字段含义见 `qa/e2e/README.md`「结果文件」一节。

本模块只做记账，不参与判定：用例状态完全来自 pytest 的 report（passed / failed / skipped /
error），失败原因取 `longrepr` 原文截断。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List

SCHEMA = "qa-e2e/run-results@1"

# 失败原因原样留一段（够定位即可；完整信息在日志与 Allure 报告里）
_MESSAGE_LIMIT = 4000


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class CaseRecord:
    """一条用例的记账。`tc_id` / `title` / `source_tc` 来自用例上的 `pytest.mark.tc(...)`。"""

    nodeid: str
    tc_id: str = ""
    title: str = ""
    source_tc: str = ""
    phases: Dict[str, str] = field(default_factory=dict)          # setup / call / teardown → outcome
    duration_ms: int = 0
    message: str = ""                                             # 失败原文（截断）
    artifacts: Dict[str, str] = field(default_factory=dict)       # trace / screenshot / video
    cleanup: Dict[str, Dict[str, str]] = field(default_factory=dict)   # scope → {status, detail}

    def status(self) -> str:
        if "failed" in self.phases.values():
            # call 阶段失败 = 用例断言没过；setup / teardown 失败 = 夹具或收尾出问题
            return "failed" if self.phases.get("call") == "failed" else "error"
        if "skipped" in self.phases.values():
            return "skipped"
        if self.phases:
            return "passed"
        return "unknown"

    def as_dict(self) -> Dict[str, Any]:
        data: Dict[str, Any] = {
            "tc_id": self.tc_id,
            "title": self.title,
            "source_tc": self.source_tc,
            "nodeid": self.nodeid,
            "status": self.status(),
            "duration_ms": self.duration_ms,
            "phases": dict(self.phases),
        }
        if self.message:
            data["message"] = self.message
        if self.artifacts:
            data["artifacts"] = dict(self.artifacts)
        if self.cleanup:
            data["cleanup"] = {scope: dict(pair) for scope, pair in self.cleanup.items()}
        return data


class RunRecorder:
    """收集 pytest 的三个 hook 送来的素材，最后写一份 JSON。"""

    def __init__(
        self,
        *,
        run_id: str,
        latest_path: Path,
        runs_dir: Path,
        environment: Dict[str, Any],
        revision: Dict[str, str],
        trigger: Dict[str, str],
    ) -> None:
        self.run_id = run_id
        self.latest_path = latest_path
        self.runs_dir = runs_dir
        self.environment = environment
        self.revision = revision
        self.trigger = trigger
        self.started_at = _now_iso()
        self._order: List[str] = []
        self._cases: Dict[str, CaseRecord] = {}

    # ── 收集 ──────────────────────────────────────────────────────────────────

    def note_item(self, *, nodeid: str, tc_id: str, title: str, source_tc: str) -> None:
        record = self._case(nodeid)
        record.tc_id = tc_id
        record.title = title
        record.source_tc = source_tc

    def note_report(self, report: Any) -> None:
        record = self._case(report.nodeid)
        record.phases[report.when] = report.outcome
        record.duration_ms += int(report.duration * 1000)

        if report.failed:
            record.message = str(report.longrepr)[:_MESSAGE_LIMIT]

        for name, value in report.user_properties:
            if name.startswith("playwright_"):
                # pytest-playwright 在 teardown 报告上挂的产物路径（trace / screenshot / video）
                record.artifacts[name[len("playwright_"):]] = str(value)
            elif name.startswith("cleanup."):
                _, scope, key = name.split(".", 2)
                record.cleanup.setdefault(scope, {})[key] = str(value)

    # ── 落盘 ──────────────────────────────────────────────────────────────────

    def write(self, exitstatus: int) -> Path:
        cases = [self._cases[nodeid].as_dict() for nodeid in self._order]
        totals = {name: 0 for name in ("total", "passed", "failed", "skipped", "error", "unknown")}
        totals["total"] = len(cases)
        for case in cases:
            totals[case["status"]] = totals.get(case["status"], 0) + 1

        document: Dict[str, Any] = {
            "schema": SCHEMA,
            "run_id": self.run_id,
            "started_at": self.started_at,
            "finished_at": _now_iso(),
            "exit_code": exitstatus,
            "environment": dict(self.environment),
            "revision": dict(self.revision),
            "trigger": dict(self.trigger),
            "totals": totals,
            "cases": cases,
        }

        text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        self.latest_path.parent.mkdir(parents=True, exist_ok=True)
        self.latest_path.write_text(text, encoding="utf-8", newline="\n")
        self.runs_dir.mkdir(parents=True, exist_ok=True)
        (self.runs_dir / f"{self.run_id}.json").write_text(text, encoding="utf-8", newline="\n")
        return self.latest_path

    # ── 内部 ──────────────────────────────────────────────────────────────────

    def _case(self, nodeid: str) -> CaseRecord:
        if nodeid not in self._cases:
            self._cases[nodeid] = CaseRecord(nodeid=nodeid)
            self._order.append(nodeid)
        return self._cases[nodeid]

    def cases(self) -> Iterable[CaseRecord]:
        return (self._cases[nodeid] for nodeid in self._order)

"""pytest 工程配置（qa/e2e）：环境读取、夹具、失败留痕、结果落盘。

三件事的归属（别混）：
- **定位写法**的唯一真源 = `docs/design/05-自动化测试.md` §7.2 —— 本文件与用例只引用、不复制；
- **判据**（"该不该通过"）写在用例的 assert 里，与 `docs/test-cases/TC-05.md` 的「预期」栏逐条对应；
- 本文件只放：读环境、登录/收尾夹具、失败留痕与结果文件的装配。

产物路径（交给 5.5 挂卷，见 README「产物路径」）：
    artifacts/allure-results/      allure-pytest 写的原始 JSON（本次运行前会先清空）
    artifacts/playwright/<用例>/   失败留痕：trace.zip + test-failed-1.png（仅失败时保留）
    artifacts/run-results.json     最近一次运行的结构化结果（run_id + 用例级结论）
    artifacts/runs/<run_id>.json   同上的历史副本
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from importlib import metadata as importlib_metadata
from pathlib import Path
from typing import Any, Callable, Iterator

import pytest
from playwright.sync_api import Page, expect

from lib import ui
from lib.results import RunRecorder

# ── 目录 ─────────────────────────────────────────────────────────────────────
E2E_ROOT = Path(__file__).resolve().parent          # qa/e2e
QA_ROOT = E2E_ROOT.parent                           # qa/
REPO_ROOT = E2E_ROOT.parents[1]                     # 仓库根
ARTIFACTS_DIR = E2E_ROOT / "artifacts"
ALLURE_DIR = ARTIFACTS_DIR / "allure-results"
PW_OUTPUT_DIR = ARTIFACTS_DIR / "playwright"
RUNS_DIR = ARTIFACTS_DIR / "runs"
RESULTS_LATEST = ARTIFACTS_DIR / "run-results.json"

# ── 默认值（都可被同名环境变量覆盖，见 README「可配项」）──────────────────────
# 测试环境前端端口（`docker-compose/.env.test` 的 `FRONTEND_PORT=12006`，口径 B 下已发布）
DEFAULT_BASE_URL = "http://127.0.0.1:12006"
DEFAULT_USERNAME = "admin"
DEFAULT_PASSWORD = "admin123"
DEFAULT_UI_TIMEOUT_MS = 15_000
# ⚠️ 上界待首次实跑确定（RAG 那条最慢）——**别照抄别的项目的数字**，跑一次再收紧
DEFAULT_LLM_TIMEOUT_MS = 180_000
DEFAULT_EXPECT_CHAT_MODEL = "qwen2.5:7b"


@dataclass(frozen=True)
class Settings:
    """一次运行的全部可配项（run-results.json 里也记一份，便于对账）。"""

    base_url: str
    username: str
    password: str
    ui_timeout_ms: int
    llm_timeout_ms: int
    expect_chat_model: str
    kb_fixture: Path


@dataclass(frozen=True)
class Session:
    """一条用例里的登录会话（token 只给收尾与接口动作使用，不当判据）。"""

    page: Page
    token: str
    token_type: str


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise RuntimeError(f"环境变量 {name} 不是整数：{raw!r}") from exc


def load_settings() -> Settings:
    return Settings(
        base_url=os.environ.get("E2E_BASE_URL", "").strip() or DEFAULT_BASE_URL,
        username=os.environ.get("E2E_USERNAME", "").strip() or DEFAULT_USERNAME,
        password=os.environ.get("E2E_PASSWORD", "").strip() or DEFAULT_PASSWORD,
        ui_timeout_ms=_env_int("E2E_TIMEOUT_MS", DEFAULT_UI_TIMEOUT_MS),
        llm_timeout_ms=_env_int("E2E_LLM_TIMEOUT_MS", DEFAULT_LLM_TIMEOUT_MS),
        expect_chat_model=os.environ.get("E2E_EXPECT_CHAT_MODEL", "").strip()
        or DEFAULT_EXPECT_CHAT_MODEL,
        kb_fixture=Path(
            os.environ.get("E2E_KB_FIXTURE", "").strip()
            or QA_ROOT / "fixtures" / "rag" / "知识库说明.txt"
        ).expanduser(),
    )


_SETTINGS: Settings | None = None
_RECORDER: RunRecorder | None = None


def _settings() -> Settings:
    global _SETTINGS
    if _SETTINGS is None:
        _SETTINGS = load_settings()
    return _SETTINGS


def _recorder() -> RunRecorder:
    assert _RECORDER is not None, "pytest_configure 尚未执行（不该发生）"
    return _RECORDER


# ── 运行元信息 ───────────────────────────────────────────────────────────────


def _run_id() -> str:
    explicit = os.environ.get("E2E_RUN_ID", "").strip()
    if explicit:
        return explicit
    return time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())


def _dist_version(name: str) -> str:
    try:
        return importlib_metadata.version(name)
    except Exception:
        return "unknown"


def _revision() -> dict[str, str]:
    """被测代码的版本（CI 用 GITHUB_SHA；本地退回 git；都取不到就写 unknown）。"""
    sha = os.environ.get("GITHUB_SHA", "").strip()
    if sha:
        return {"sha": sha[:12], "source": "GITHUB_SHA"}
    try:
        completed = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=10,
        )
        if completed.returncode == 0 and completed.stdout.strip():
            return {"sha": completed.stdout.strip(), "source": "git"}
    except Exception:
        pass
    return {"sha": "", "source": "unknown"}


def _trigger() -> dict[str, str]:
    """这一跑是谁触发的（本地手跑 / CI 的哪一次运行）——只做记录，不参与判定。"""
    trigger: dict[str, str] = {"kind": os.environ.get("E2E_TRIGGER", "local")}
    for key in ("GITHUB_EVENT_NAME", "GITHUB_RUN_ID", "GITHUB_WORKFLOW", "GITHUB_REF", "GITHUB_ACTOR"):
        value = os.environ.get(key, "").strip()
        if value:
            trigger[key.lower()] = value
    repository = os.environ.get("GITHUB_REPOSITORY", "").strip()
    run_id = os.environ.get("GITHUB_RUN_ID", "").strip()
    if repository and run_id:
        server = os.environ.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
        trigger["run_url"] = f"{server}/{repository}/actions/runs/{run_id}"
    return trigger


def _environment(settings: Settings) -> dict[str, Any]:
    return {
        "base_url": settings.base_url,
        "username": settings.username,
        "python": sys.version.split()[0],
        "pytest": pytest.__version__,
        "playwright": _dist_version("playwright"),
        "pytest_playwright": _dist_version("pytest-playwright"),
        "allure_pytest": _dist_version("allure-pytest"),
        "platform": sys.platform,
    }


# ── hooks ────────────────────────────────────────────────────────────────────


def pytest_configure(config: pytest.Config) -> None:
    global _RECORDER
    settings = _settings()

    for directory in (ARTIFACTS_DIR, PW_OUTPUT_DIR, RUNS_DIR):
        directory.mkdir(parents=True, exist_ok=True)
    # Allure 的原始结果是"这一次运行"的：先清空这一层（只清自己，别碰别的目录）
    shutil.rmtree(ALLURE_DIR, ignore_errors=True)
    ALLURE_DIR.mkdir(parents=True, exist_ok=True)

    # `--output` / `--alluredir` 的默认值都相对 **cwd** —— 改成绝对路径，
    # 使"从哪儿发起 pytest"不影响产物落点（5.5 要按固定路径挂卷）
    if getattr(config.option, "output", None) is not None:
        config.option.output = str(PW_OUTPUT_DIR)
    if hasattr(config.option, "allure_report_dir"):
        config.option.allure_report_dir = str(ALLURE_DIR)

    _RECORDER = RunRecorder(
        run_id=_run_id(),
        latest_path=RESULTS_LATEST,
        runs_dir=RUNS_DIR,
        environment=_environment(settings),
        revision=_revision(),
        trigger=_trigger(),
    )


def pytest_collection_modifyitems(session, config, items) -> None:
    recorder = _recorder()
    for item in items:
        marker = item.get_closest_marker("tc")
        args = list(marker.args) if marker else []
        recorder.note_item(
            nodeid=item.nodeid,
            tc_id=str(args[0]) if args else "",
            title=str(args[1]) if len(args) > 1 else "",
            source_tc=str(args[2]) if len(args) > 2 else "",
        )


def pytest_runtest_logreport(report) -> None:
    _recorder().note_report(report)


def pytest_sessionfinish(session, exitstatus) -> None:
    recorder = _recorder()
    path = recorder.write(int(exitstatus))

    cases = list(recorder.cases())
    tally = {name: 0 for name in ("passed", "failed", "skipped", "error", "unknown")}
    for case in cases:
        tally[case.status()] = tally.get(case.status(), 0) + 1

    reporter = session.config.pluginmanager.getplugin("terminalreporter")
    if reporter is None:
        return
    reporter.write_line("")
    reporter.write_line("── E2E 结构化结果 ──────────────────────────────────────────")
    reporter.write_line(f"run_id      : {recorder.run_id}")
    reporter.write_line(f"结果文件    : {path}")
    reporter.write_line(f"历史副本    : {RUNS_DIR / (recorder.run_id + '.json')}")
    reporter.write_line(f"Allure 原始 : {ALLURE_DIR}")
    reporter.write_line(f"失败留痕    : {PW_OUTPUT_DIR}/<用例目录>/（trace.zip + test-failed-1.png，仅失败保留）")
    reporter.write_line(
        f"用例        : {len(cases)} 条 —— 通过 {tally['passed']} / 失败 {tally['failed']} / "
        f"跳过 {tally['skipped']} / 错误 {tally['error']}"
    )
    for case in cases:
        if case.status() in ("failed", "error", "skipped"):
            label = f"{case.tc_id} {case.title}".strip() or case.nodeid
            reporter.write_line(f"  · [{case.status()}] {label}")
    reporter.write_line("────────────────────────────────────────────────────────────")


# ── 夹具 ─────────────────────────────────────────────────────────────────────


@pytest.fixture(scope="session")
def e2e_settings() -> Settings:
    """本次运行的全部可配项（环境变量读取一次；README「可配项」列了清单）。"""
    return _settings()


@pytest.fixture(scope="session")
def browser_context_args(browser_context_args, e2e_settings: Settings) -> dict[str, Any]:
    """在 pytest-playwright 的默认参数上补两样：`base_url`（相对路径 goto 的前提）与视口。"""
    return {
        **browser_context_args,
        "base_url": e2e_settings.base_url,
        "viewport": {"width": 1440, "height": 900},
    }


@pytest.fixture(autouse=True)
def _per_test_setup(request: pytest.FixtureRequest, page: Page, e2e_settings: Settings) -> Iterator[None]:
    """每条用例统一的期望超时与 Allure 标题 —— **不参与判定**。"""
    page.set_default_timeout(e2e_settings.ui_timeout_ms)
    expect.set_options(timeout=e2e_settings.ui_timeout_ms)

    marker = request.node.get_closest_marker("tc")
    if marker and marker.args:
        tc_id = str(marker.args[0])
        title = str(marker.args[1]) if len(marker.args) > 1 else ""
        try:
            import allure
        except ImportError:  # allure-pytest 没装时照常跑，只是报告里少一个标题
            pass
        else:
            allure.dynamic.title(f"{tc_id} {title}".strip())
            allure.dynamic.label("tc", tc_id)
    yield


@pytest.fixture
def cleanup_login(
    page: Page,
    e2e_settings: Settings,
    record_property: Callable[[str, Any], None],
) -> Iterator[dict[str, Any]]:
    """收尾脚手架：用例把本次的 token 放进 `state["token"]`，跑完自动登出 + 自证。

    自证结果（`cleanup.login.status` / `cleanup.login.detail`）会进 run-results.json；
    **它不改变用例主结论** —— 主结论只由用例自己的断言决定。
    """
    state: dict[str, Any] = {"token": ""}
    yield state
    result = ui.logout_and_verify(
        page,
        base_url=e2e_settings.base_url,
        token=str(state.get("token") or ""),
    )
    record_property("cleanup.login.status", result["status"])
    record_property("cleanup.login.detail", result["detail"])


@pytest.fixture
def logged_in(page: Page, e2e_settings: Settings, cleanup_login: dict[str, Any]) -> Session:
    """真登录（**走界面**，不是塞 token）后的会话；跑完由 `cleanup_login` 登出并自证。"""
    result = ui.login(
        page,
        base_url=e2e_settings.base_url,
        username=e2e_settings.username,
        password=e2e_settings.password,
        timeout_ms=e2e_settings.ui_timeout_ms,
    )
    cleanup_login["token"] = result.token
    return Session(page=page, token=result.token, token_type=result.token_type)

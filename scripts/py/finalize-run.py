#!/usr/bin/env python3
"""finalize-run.py —— 冒烟运行的收尾：报告链路 + 结果链路（阶段5 / 5.5 交付，2026-10-10）

一条命令做完四步（顺序即依赖）：
  1) 前置检查   ：docker / artifacts / run-results.json 格式 / run_id 断言（纯只读）
  2) Allure 报告：由 qa/e2e/artifacts/allure-results/ 生成 HTML，落到 artifacts/publish/allure-report/
                  —— 借 **builder 镜像的 JDK 17**（宿主无 Java，实测见设计 05 §7.4.5）；
                     CLI 首次自动下载（阿里云 Maven 镜像）并缓存到 $HOME/.cache/nexus-e2e/allure/，带 sha256 校验
  3) 发布到卷  ：把 artifacts/publish/ 整树发布进报告卷（默认 nexus-test-report）——
                  report-nginx 只读挂它，Windows 侧看 http://localhost:12008/
  4) 结果落库  ：run-results.json → 结果库（nexus_test_results）的 t_e2e_run / t_e2e_case；
                  表与视图 DDL（docker-compose/postgres-results-init/02-results-schema.sql）**每次重放**，
                  以覆盖"initdb 只跑一次、存量卷加脚本不再执行"的缺口（设计 05 §7.4.3）

位置与边界：
  · 只在**测试发行版 nexus-agent-workbench-test 内**跑（结果库 / 报告卷是测试环境独有的），
    与 deploy_test.py 同一侧；**不要**在开发发行版里跑；
  · 不启动、不重启、不重建任何容器：只用一次性容器（--rm）读写卷 + docker exec 打 psql；
  · 只用系统 python3 的标准库 + docker CLI —— 与冒烟 venv（$HOME/nexus-e2e-venv）解耦；
  · 失败即非 0 退出（CI 档），但**尽力把能做的做完**：报告生成失败仍发布（落地页如实标注），
    发布失败仍落库 —— 一次运行把问题都暴露出来，而不是只报第一个。

用法（在测试发行版内的 checkout 里）：
    python3 scripts/py/finalize-run.py                     # 默认：<仓库>/qa/e2e/artifacts + nexus-test-report 卷
    python3 scripts/py/finalize-run.py --expect-run-id 20261010T023815Z
    python3 scripts/py/finalize-run.py --volume nexus-test-report-selftest   # 发到别的卷（预演，不动真卷）
    python3 scripts/py/finalize-run.py --dry-run           # 只打印计划，不做任何写操作/网络

★ 手工重放：本脚本整体幂等（同 run_id 覆盖式落库、发布为整树替换），失败后**原样再跑一遍**即可，
  不必重跑 pytest；只想看计划就用 --dry-run。

判据单一真源：本脚本不含"该不该红"的判断 —— 那是 pytest（用例）与 deploy.py（部署）的事；
它只做"把这次运行的结果放到该放的地方"。输出形态沿用 deploy.py 的 [PASS]/[FAIL]/[WARN] 表。
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

# ── 常量（真源与落点）────────────────────────────────────────────────────────
# Allure CLI：宿主没有 java/unzip（2026-10-10 实测），所以下载 + 解压（zipfile）都自己来；
#   下载源 = 阿里云 Maven 镜像（builder 的 mvn 走同一个源，已实测可达）；sha256 为实测值。
ALLURE_VERSION = "2.46.1"
ALLURE_URL = (
    "https://maven.aliyun.com/repository/public/io/qameta/allure/"
    f"allure-commandline/{ALLURE_VERSION}/allure-commandline-{ALLURE_VERSION}.zip"
)
ALLURE_SHA256 = "d25c519bbde940dc953cff8901130ce18b73013dc19a8f8b90b8b4b1c5eb252f"
ALLURE_HOME = Path.home() / ".cache" / "nexus-e2e" / "allure" / ALLURE_VERSION

BUILDER_IMAGE = "nexus-test-builder:dev"           # docker-compose.test.yml 里 builder 的 image:
RESULTS_DB_CONTAINER = "nexus-test-postgres-results"   # … 里 postgres-results 的 container_name
REPORT_NGINX_CONTAINER = "nexus-test-report-nginx"     # … 里 report-nginx 的 container_name
REPORT_VOLUME = "nexus-test-report"                    # … 里 report 卷的 name:
SCHEMA_SQL_REL = "docker-compose/postgres-results-init/02-results-schema.sql"
STAGING_DIRNAME = "publish"
RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,80}$")   # 进 SQL/路径前先收口，防脏值


# ── 结果收集与输出（形态同 deploy.py）────────────────────────────────────────
class Report:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []

    def add(self, step: str, status: str, summary: str, detail: str = "") -> None:
        self.rows.append((step, status, summary))
        print(f"[{status}] {step}   {summary}", flush=True)
        if detail:
            for line in detail.strip().splitlines()[-10:]:
                print("        " + line, flush=True)

    def failed(self) -> bool:
        return any(status == "FAIL" for _, status, _ in self.rows)


R = Report()


def fatal(message: str) -> int:
    print(f"[FAIL] 前置检查   {message}", flush=True)
    print("[finalize-run] 已中止：前置不满足，未做任何写操作。", flush=True)
    return 1


# ── 命令执行 ─────────────────────────────────────────────────────────────────
def run(args: list[str], timeout: int = 300, stdin_data: str | None = None) -> tuple[int, str]:
    kwargs: dict = dict(capture_output=True, text=True, encoding="utf-8",
                        errors="replace", timeout=timeout)
    if stdin_data is None:
        kwargs["stdin"] = subprocess.DEVNULL
        proc = subprocess.run(args, **kwargs)
    else:
        proc = subprocess.run(args, input=stdin_data, **kwargs)
    out = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode, out


def docker(*args: str, **kwargs) -> tuple[int, str]:
    return run(["docker", *args], **kwargs)


def find_repo_root(start: Path) -> Path:
    """按 .git 标记上溯（不写死层级 —— 搬动 scripts/ 目录时不静默失效）。"""
    for candidate in (start, *start.parents):
        if (candidate / ".git").exists():
            return candidate
    raise SystemExit(f"[finalize-run] 从 {start} 上溯未找到仓库根（.git）")


# ── 静态读取与校验（dry-run 与正常路径共用）─────────────────────────────────
def load_run_results(artifacts: Path, expect_run_id: str | None) -> tuple[dict | None, str]:
    latest = artifacts / "run-results.json"
    if not latest.exists():
        return None, f"找不到 {latest}（pytest 这一跑没落结果？确认 qa/e2e 跑过、且 E2E_RUN_ID 未被改写）"
    try:
        doc = json.loads(latest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"run-results.json 读不出来：{exc}"
    if doc.get("schema") != "qa-e2e/run-results@1":
        return None, f"run-results.json 的 schema 不认识：{doc.get('schema')!r}（格式漂移？先对齐 conftest）"
    run_id = str(doc.get("run_id") or "")
    if not RUN_ID_PATTERN.match(run_id):
        return None, f"run_id 不合规（期望 [A-Za-z0-9._-]{{1,80}}）：{run_id!r}"
    if expect_run_id and run_id != expect_run_id:
        return None, (f"旧产物护栏命中：run-results.json 是 run_id={run_id}，期望 {expect_run_id} "
                      f"⇒ 本次没有新结果，不发布（同 5.2 的旧产物护栏）")
    if not (artifacts / "allure-results").is_dir() or not any((artifacts / "allure-results").iterdir()):
        return None, f"allure-results/ 为空：{artifacts / 'allure-results'}（allure-pytest 没写出来？）"
    return doc, ""


# ── 步骤 2：Allure 报告生成 ─────────────────────────────────────────────────
def ensure_allure_cli() -> tuple[bool, str]:
    """返回 (ok, 说明)。首次：下载（Maven 镜像）→ sha256 校验 → 解压（纯标准库）→ 缓存。"""
    if (ALLURE_HOME / "bin" / "allure").exists():
        return True, f"CLI 缓存命中：{ALLURE_HOME}（{ALLURE_VERSION}）"
    ALLURE_HOME.parent.mkdir(parents=True, exist_ok=True)
    part = ALLURE_HOME.parent / f".{ALLURE_VERSION}.zip.part"
    try:
        req = urllib.request.Request(ALLURE_URL, headers={"User-Agent": "finalize-run/1.0"})
        with urllib.request.urlopen(req, timeout=180) as resp, open(part, "wb") as fh:
            shutil.copyfileobj(resp, fh, length=1024 * 1024)
    except Exception as exc:  # noqa: BLE001 —— 网络类异常一律收口成一条可读信息
        part.unlink(missing_ok=True)
        return False, (f"下载 Allure CLI 失败（网络？）：{exc}\n"
                       f"       URL = {ALLURE_URL}\n"
                       f"       已存在缓存时可离线复用：{ALLURE_HOME}")
    digest = hashlib.sha256()
    with open(part, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    got = digest.hexdigest()
    if got != ALLURE_SHA256:
        part.unlink(missing_ok=True)
        return False, (f"sha256 不符（下载损坏或上游换包）：期望 {ALLURE_SHA256[:16]}… 实得 {got[:16]}…\n"
                       f"       URL = {ALLURE_URL}")
    tmp = ALLURE_HOME.parent / f".extract-{ALLURE_VERSION}"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    try:
        with zipfile.ZipFile(part) as zf:
            zf.extractall(tmp)
    except zipfile.BadZipFile as exc:
        shutil.rmtree(tmp, ignore_errors=True)
        part.unlink(missing_ok=True)
        return False, f"zip 解压失败：{exc}"
    inner = tmp / f"allure-{ALLURE_VERSION}"
    if not (inner / "bin" / "allure").exists():
        shutil.rmtree(tmp, ignore_errors=True)
        part.unlink(missing_ok=True)
        return False, f"解压结果里没有 bin/allure（压缩包结构变了？）：{inner}"
    if ALLURE_HOME.exists():
        shutil.rmtree(ALLURE_HOME)
    inner.rename(ALLURE_HOME)
    shutil.rmtree(tmp, ignore_errors=True)
    part.unlink(missing_ok=True)
    return True, f"已下载并缓存 CLI（{ALLURE_VERSION}，sha256 校验通过）：{ALLURE_HOME}"


def _env_properties(run_doc: dict) -> str:
    """写进发布副本的 allure-results/（不动原始产物）—— 报告首页右侧会出现 Environment 面板。"""
    env = run_doc.get("environment", {}) or {}
    revision = run_doc.get("revision", {}) or {}
    trigger = run_doc.get("trigger", {}) or {}
    lines = [
        f"run.id={run_doc.get('run_id', '')}",
        f"run.trigger={trigger.get('kind', '')}",
        f"code.revision={revision.get('sha', '')}",
        f"target.base.url={env.get('base_url', '')}",
        f"python={env.get('python', '')}",
        f"pytest={env.get('pytest', '')}",
        f"playwright={env.get('playwright', '')}",
    ]
    return "\n".join(lines) + "\n"


def _executor_json(run_doc: dict) -> str:
    """Allure 的 Executor 面板：能从报告跳回 CI 运行页（无 run_url 就只留个名字）。"""
    trigger = run_doc.get("trigger", {}) or {}
    executor: dict[str, str] = {
        "name": "nexus-agent-workbench（测试发行版 / CI）",
        "type": "github",
        "buildName": str(run_doc.get("run_id", "")),
    }
    url = str(trigger.get("run_url", "") or "")
    if url:
        executor["url"] = url
        executor["buildUrl"] = url
    return json.dumps(executor, ensure_ascii=False, indent=2) + "\n"


def build_staging(artifacts: Path, staging: Path, run_doc: dict) -> list[str]:
    """把本次要发布的整树摆到 artifacts/publish/（原始产物只读复制）。返回警告列表。"""
    warnings: list[str] = []
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    shutil.copytree(artifacts / "allure-results", staging / "allure-results")
    (staging / "allure-results" / "environment.properties").write_text(
        _env_properties(run_doc), encoding="utf-8", newline="\n")
    (staging / "allure-results" / "executor.json").write_text(
        _executor_json(run_doc), encoding="utf-8", newline="\n")
    shutil.copy2(artifacts / "run-results.json", staging / "run-results.json")
    (staging / "runs").mkdir()
    run_copy = artifacts / "runs" / f"{run_doc['run_id']}.json"
    if run_copy.exists():
        shutil.copy2(run_copy, staging / "runs" / run_copy.name)
    else:
        warnings.append(f"runs/{run_copy.name} 不在（conftest 应同时写两份）——历史副本缺失")
    deploy_summary = artifacts / "deploy-summary.json"
    if deploy_summary.exists():
        shutil.copy2(deploy_summary, staging / "deploy-summary.json")
    else:
        warnings.append("deploy-summary.json 不在（本次不是从 --summary-json 的部署链路来的？）")
    if (artifacts / "playwright").is_dir():
        shutil.copytree(artifacts / "playwright", staging / "playwright")
    return warnings


def generate_allure(staging: Path) -> tuple[bool, str]:
    """借 builder 镜像的 JDK 17 跑 CLI（宿主无 Java）；生成到 staging/allure-report/。"""
    rc, out = docker("image", "inspect", BUILDER_IMAGE, timeout=60)
    if rc != 0:
        return False, (f"builder 镜像不存在：{BUILDER_IMAGE}（先跑一次部署把镜像建出来）\n"
                       f"       {out.strip().splitlines()[-1] if out.strip() else ''}")
    cmd = ["docker", "run", "--rm", "--entrypoint", "sh"]
    if os.getuid() != 0:
        # 以宿主当前用户身份写 staging（文件归 caotan、下次 rmtree 删得掉；
        # 默认 root 写出来的是 root 文件，会卡住下一次清理）
        cmd += ["--user", f"{os.getuid()}:{os.getgid()}"]
    cmd += [
        "-v", f"{staging / 'allure-results'}:/in:ro",
        "-v", f"{staging}:/out",
        "-v", f"{ALLURE_HOME}:/opt/allure:ro",
        BUILDER_IMAGE,
        "-c", "sh /opt/allure/bin/allure generate /in -o /out/allure-report --clean",
    ]
    rc, out = run(cmd, timeout=300)
    report_index = staging / "allure-report" / "index.html"
    if rc != 0 or not report_index.exists():
        return False, f"allure generate 失败（rc={rc}）\n{out.strip()[-800:]}"
    n_files = sum(1 for p in (staging / "allure-report").rglob("*") if p.is_file())
    size_mb = sum(p.stat().st_size for p in (staging / "allure-report").rglob("*") if p.is_file()) / 1e6
    return True, f"allure-report/：{n_files} 个文件 / {size_mb:.1f}MB（CLI {ALLURE_VERSION} + builder 镜像的 JDK 17）"


# ── 步骤 3：发布到报告卷 ─────────────────────────────────────────────────────
def volume_rel(path: str) -> str:
    """把 run-results.json 里 artifacts/… 的相对路径翻成卷内可点链接（落点是 publish/ 的对应位置）。"""
    p = str(path).replace("\\", "/")
    marker = "artifacts/"
    idx = p.find(marker)
    if idx < 0:
        return ""
    return "./" + p[idx + len(marker):]


def render_hub(run_doc: dict, staging: Path) -> str:
    """本次运行的落地页（站点根 index.html）——为什么不是报告首页，见 report-nginx/default.conf 头注。"""
    run_id = html.escape(str(run_doc.get("run_id", "")))
    totals = run_doc.get("totals", {}) or {}
    env = run_doc.get("environment", {}) or {}
    revision = run_doc.get("revision", {}) or {}
    trigger = run_doc.get("trigger", {}) or {}
    cases = run_doc.get("cases", []) or []
    concluded_pass = (run_doc.get("exit_code") == 0
                      and int(totals.get("failed", 0)) == 0
                      and int(totals.get("error", 0)) == 0
                      and int(totals.get("unknown", 0)) == 0
                      and int(totals.get("total", 0)) > 0)
    verdict = "PASS" if concluded_pass else "FAIL"
    verdict_cls = "pass" if concluded_pass else "fail"
    run_url = str(trigger.get("run_url", "") or "")

    facts = [
        f"用例：通过 {totals.get('passed', 0)} / 失败 {totals.get('failed', 0)} / "
        f"跳过 {totals.get('skipped', 0)} / 错误 {totals.get('error', 0)}（共 {totals.get('total', 0)}）",
        f"耗时：{html.escape(str(run_doc.get('started_at', '')))} → {html.escape(str(run_doc.get('finished_at', '')))}",
        f"代码：{html.escape(str(revision.get('sha', '') or 'unknown'))}（{html.escape(str(revision.get('source', '')))}）"
        f" · 触发：{html.escape(str(trigger.get('kind', '')))}",
        f"环境：{html.escape(str(env.get('base_url', '')))} · python {html.escape(str(env.get('python', '')))}"
        f" · pytest {html.escape(str(env.get('pytest', '')))} · playwright {html.escape(str(env.get('playwright', '')))}",
    ]
    if run_url:
        facts.append(f"CI 运行：<a href=\"{html.escape(run_url)}\">{html.escape(run_url)}</a>")

    links = []
    if (staging / "allure-report" / "index.html").exists():
        links.append('<li><a href="./allure-report/">Allure 报告（本次）</a> —— 用例状态 / 失败原文 / 时间线</li>')
    else:
        links.append('<li class="miss">Allure 报告未生成（本次生成步骤失败，看收尾脚本输出）</li>')
    links.append('<li><a href="./run-results.json">run-results.json</a> —— 结构化结果（落库的同一份）</li>')
    if (staging / "runs" / f"{run_doc.get('run_id')}.json").exists():
        links.append(f'<li><a href="./runs/{run_id}.json">runs/{run_id}.json</a> —— 历史副本</li>')
    if (staging / "deploy-summary.json").exists():
        links.append('<li><a href="./deploy-summary.json">deploy-summary.json</a> —— 部署八步摘要</li>')
    links.append('<li><a href="./allure-results/">allure-results/</a> —— 原始 JSON（报告由它生成）</li>')

    rows = []
    for case in cases:
        status = html.escape(str(case.get("status", "unknown")))
        tc_id = html.escape(str(case.get("tc_id", "")))
        title = html.escape(str(case.get("title", "")))
        duration = case.get("duration_ms")
        duration_text = f"{duration / 1000:.1f}s" if isinstance(duration, (int, float)) and duration else "—"
        marks = []
        artifacts = case.get("artifacts", {}) or {}
        for key, label in (("trace", "trace.zip"), ("screenshot", "截图")):
            rel = volume_rel(str(artifacts.get(key, "") or ""))
            if rel:
                marks.append(f'<a href="{html.escape(rel)}">{label}</a>')
        rows.append(
            f'<tr><td>{tc_id}</td><td>{title}</td><td class="s-{status}">{status}</td>'
            f'<td>{duration_text}</td><td>{" · ".join(marks) or "—"}</td></tr>')

    failure_blocks = []
    for case in cases:
        if str(case.get("status", "")) not in ("failed", "error"):
            continue
        headline = html.escape(f"{case.get('tc_id', '')} {case.get('title', '')}".strip())
        message = html.escape(str(case.get("message", ""))[:2000]) or "（无失败原文）"
        failure_blocks.append(f"<details open><summary>{headline}</summary><pre>{message}</pre></details>")
    failure_html = "".join(failure_blocks) or (
        "<p>本次没有失败/错误用例（trace 与截图 <b>仅失败保留</b>，见 qa/e2e/pytest.ini）。</p>")

    generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    css = """
:root { color-scheme: light dark; }
body { font-family: system-ui, "Segoe UI", "Microsoft YaHei", sans-serif; margin: 2rem auto;
       max-width: 64rem; padding: 0 1rem; line-height: 1.55; }
h1 { font-size: 1.4rem; } h2 { font-size: 1.1rem; margin-top: 1.8rem; }
code { background: rgba(127,127,127,.15); padding: .1em .3em; border-radius: 4px; }
.verdict { font-size: 1.6rem; font-weight: 700; }
.verdict.pass { color: #1a7f37; } .verdict.fail { color: #c11; }
ul.facts { list-style: none; padding-left: 0; } ul.facts li { margin: .2rem 0; }
table { border-collapse: collapse; width: 100%; } th, td { border-bottom: 1px solid rgba(127,127,127,.35);
        padding: .35rem .5rem; text-align: left; vertical-align: top; }
.s-passed { color: #1a7f37; } .s-failed, .s-error { color: #c11; font-weight: 600; } .s-skipped, .s-unknown { color: #888; }
pre { white-space: pre-wrap; background: rgba(127,127,127,.12); padding: .6rem; border-radius: 6px; overflow-x: auto; }
footer { margin-top: 2.5rem; color: #888; font-size: .85rem; }
.miss { color: #c11; }
"""
    parts = [
        "<!doctype html>", '<html lang="zh-CN">', "<head>", '<meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        f"<title>E2E 冒烟 · {run_id}</title>", f"<style>{css}</style>", "</head>", "<body><main>",
        f"<h1>E2E 冒烟运行 <code>{run_id}</code></h1>",
        f'<p class="verdict {verdict_cls}">{verdict}</p>',
        '<ul class="facts">' + "".join(f"<li>{fact}</li>" for fact in facts) + "</ul>",
        "<h2>打开</h2>", "<ul>" + "".join(links) + "</ul>",
        "<h2>用例明细</h2>",
        "<table><thead><tr><th>编号</th><th>标题</th><th>状态</th><th>耗时</th><th>留痕</th></tr></thead><tbody>"
        + "".join(rows) + "</tbody></table>",
        "<h2>失败留痕</h2>", failure_html,
        "<p>trace.zip 用 <code>playwright show-trace &lt;trace.zip&gt;</code> 打开；截图即整页 png。"
        "两者都落在报告卷的 <code>/playwright/</code> 下（仅失败保留）。</p>",
        f"<footer>生成：{generated_at}（UTC） · 由 scripts/py/finalize-run.py 发布；"
        f"布局真源 = docs/design/05-自动化测试.md §7.4.2</footer>",
        "</main></body></html>", "",
    ]
    return "\n".join(parts)


def publish(staging: Path, volume: str) -> tuple[bool, str]:
    rc, out = docker("volume", "inspect", volume, timeout=60)
    if rc != 0:
        return False, f"卷不存在：{volume}（compose 起过吗？卷由 docker-compose.test.yml 声明）"
    rc, out = docker("inspect", "-f", "{{.Config.Image}}", REPORT_NGINX_CONTAINER, timeout=60)
    if rc != 0:
        return False, (f"取不到 {REPORT_NGINX_CONTAINER} 的镜像（容器没起？）—— "
                       f"发布容器要复用 report-nginx 的镜像（一定有、且版本一致）")
    image = out.strip().splitlines()[0].strip()
    rc, out = docker(
        "run", "--rm", "--entrypoint", "sh",
        "-v", f"{staging}:/src:ro",
        "-v", f"{volume}:/dst",
        image, "-c",
        # 整树替换（清掉卷根旧内容，含 Docker 首次挂载时从镜像拷进来的 nginx 欢迎页）+
        # chmod a+rX 收口权限（"一个容器写、另一个容器读"——nginx worker 是 uid 101，见设计 05 §6-3）
        "set -e; find /dst -mindepth 1 -maxdepth 1 -exec rm -rf {} +; "
        "cp -a /src/. /dst/; chmod -R a+rX /dst; "
        "echo __VOLUME_ROOT__; ls -1 /dst",
        timeout=300,
    )
    if rc != 0:
        return False, f"发布失败（rc={rc}）\n{out.strip()[-800:]}"
    listing = out.split("__VOLUME_ROOT__")[-1].strip().splitlines()
    items = [x for x in (line.strip() for line in listing) if x]
    return True, f"{volume}：根目录 {len(items)} 项（{'、'.join(items)}）"


# ── 步骤 4：结果落库 ─────────────────────────────────────────────────────────
def db_read_conn() -> tuple[tuple[str, str] | None, str]:
    rc, out = docker("exec", RESULTS_DB_CONTAINER, "sh", "-c",
                     'printf "%s\\n%s\\n" "$POSTGRES_USER" "$POSTGRES_DB"', timeout=60)
    if rc != 0:
        return None, f"读不到 {RESULTS_DB_CONTAINER} 的连接信息（容器没起？）\n{out.strip()[-300:]}"
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    if len(lines) < 2 or not lines[0] or not lines[1]:
        return None, f"容器里 POSTGRES_USER / POSTGRES_DB 为空：{lines!r}"
    return (lines[0], lines[1]), ""


def build_dml(run_doc: dict) -> str:
    """整份 run-results.json 作为 jsonb 塞进 t_e2e_run.raw，用例行由它派生（单真源）。"""
    raw_json = json.dumps(run_doc, ensure_ascii=False, separators=(",", ":"))
    env_json = json.dumps(run_doc.get("environment", {}) or {}, ensure_ascii=False, separators=(",", ":"))
    for token in ("$run$", "$json$"):
        if token in raw_json or token in env_json:
            raise ValueError(f"run-results.json 里出现了保留的美元引用标记 {token}（不该发生；改标记再跑）")
    run_id = str(run_doc["run_id"])
    totals = run_doc.get("totals", {}) or {}
    revision = run_doc.get("revision", {}) or {}
    trigger = run_doc.get("trigger", {}) or {}
    environment = run_doc.get("environment", {}) or {}
    conclusion = "pass" if (run_doc.get("exit_code") == 0
                            and int(totals.get("failed", 0)) == 0
                            and int(totals.get("error", 0)) == 0
                            and int(totals.get("unknown", 0)) == 0
                            and int(totals.get("total", 0)) > 0) else "fail"

    def q(value: object) -> str:
        return f"$run${value}$run$"

    def _int(value: object, default: int = 0) -> str:
        try:
            return str(int(value))
        except (TypeError, ValueError):
            return str(default)

    return f"""\
-- finalize-run.py 生成：一次运行 = 单事务「删旧 + 插新」（幂等，可整体重放）
BEGIN;
DELETE FROM t_e2e_run WHERE run_id = {q(run_id)};
INSERT INTO t_e2e_run (run_id, started_at, finished_at, exit_code, conclusion,
                       total, passed, failed, skipped, error, unknown,
                       revision_sha, trigger_kind, github_run_id, run_url, base_url,
                       environment, raw)
VALUES (
    {q(run_id)},
    NULLIF({q(run_doc.get('started_at', ''))}, '')::timestamptz,
    NULLIF({q(run_doc.get('finished_at', ''))}, '')::timestamptz,
    NULLIF({q(run_doc.get('exit_code', ''))}, '')::int,
    {q(conclusion)},
    {_int(totals.get('total'))}, {_int(totals.get('passed'))}, {_int(totals.get('failed'))},
    {_int(totals.get('skipped'))}, {_int(totals.get('error'))}, {_int(totals.get('unknown'))},
    NULLIF({q(revision.get('sha', ''))}, ''),
    NULLIF({q(trigger.get('kind', ''))}, ''),
    NULLIF({q(trigger.get('github_run_id', ''))}, ''),
    NULLIF({q(trigger.get('run_url', ''))}, ''),
    NULLIF({q(environment.get('base_url', ''))}, ''),
    {q(env_json)}::jsonb,
    {q(raw_json)}::jsonb
);
INSERT INTO t_e2e_case (run_id, tc_id, title, source_tc, nodeid, status, duration_ms,
                        message, trace_path, screenshot_path, cleanup_login, cleanup_kb)
SELECT r.run_id,
       c->>'tc_id', c->>'title', c->>'source_tc', c->>'nodeid', COALESCE(c->>'status', 'unknown'),
       NULLIF(c->>'duration_ms', '')::int,
       c->>'message',
       c->'artifacts'->>'trace',
       c->'artifacts'->>'screenshot',
       c->'cleanup'->'login'->>'status',
       c->'cleanup'->'kb'->>'status'
FROM t_e2e_run r
CROSS JOIN LATERAL jsonb_array_elements(r.raw->'cases') AS c
WHERE r.run_id = {q(run_id)};
COMMIT;
"""


def db_write(ddl_text: str, dml_text: str, run_doc: dict) -> tuple[bool, str]:
    conn, error = db_read_conn()
    if conn is None:
        return False, error
    user, db = conn
    rc, out = docker("exec", "-i", RESULTS_DB_CONTAINER, "psql", "-U", user, "-d", db,
                     "-v", "ON_ERROR_STOP=1", "-1", "-f", "-", stdin_data=ddl_text, timeout=120)
    if rc != 0:
        return False, f"DDL 应用失败（02-results-schema.sql；rc={rc}）\n{out.strip()[-800:]}"
    rc, out = docker("exec", "-i", RESULTS_DB_CONTAINER, "psql", "-U", user, "-d", db,
                     "-v", "ON_ERROR_STOP=1", "-f", "-", stdin_data=dml_text, timeout=120)
    if rc != 0:
        return False, f"结果写入失败（DML；rc={rc}）\n{out.strip()[-800:]}"
    run_id = str(run_doc["run_id"])
    rc, out = docker("exec", RESULTS_DB_CONTAINER, "psql", "-U", user, "-d", db, "-tAc",
                     f"SELECT count(*) FROM t_e2e_case WHERE run_id = $run${run_id}$run$", timeout=60)
    if rc != 0:
        return False, f"写入后复核失败：{out.strip()[-400:]}"
    got = out.strip().splitlines()[0].strip() if out.strip() else ""
    expected = str((run_doc.get("totals", {}) or {}).get("total", 0))
    if got != expected:
        return False, f"用例行数对不上：期望 {expected} 行，实得 {got} 行（幂等失败？）"
    rc, view_out = docker("exec", RESULTS_DB_CONTAINER, "psql", "-U", user, "-d", db, "-tAc",
                          "SELECT run_id || ' | ' || conclusion || ' | ' || passed || '/' || total"
                          " || ' | pass_rate=' || COALESCE(pass_rate_pct::text, '-')"
                          " FROM v_e2e_run_summary LIMIT 3", timeout=60)
    detail = f"t_e2e_run 1 行 + t_e2e_case {got} 行（{db}）"
    if rc == 0 and view_out.strip():
        detail += "\nv_e2e_run_summary（最近 3 次）：\n" + view_out.strip()
    return True, detail


# ── main ─────────────────────────────────────────────────────────────────────
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="冒烟运行收尾：Allure 报告 → 报告卷 → 结果库（阶段5 / 5.5）",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--artifacts", type=Path, default=None,
                        help="产物目录（默认 <仓库>/qa/e2e/artifacts）")
    parser.add_argument("--volume", default=REPORT_VOLUME,
                        help=f"报告卷名（默认 {REPORT_VOLUME}；预演可发到别的卷）")
    parser.add_argument("--expect-run-id", default=None,
                        help="断言 run-results.json 的 run_id（防'静默旧产物'：与 E2E_RUN_ID 对齐）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印计划（含静态校验），不做任何写操作/网络访问")
    args = parser.parse_args(argv)

    if os.name != "posix":
        print("[finalize-run] 本脚本只在测试发行版（Linux）内运行 —— 见文件头「位置与边界」。", flush=True)
        return 2
    repo = find_repo_root(Path(__file__).resolve().parent)
    artifacts = (args.artifacts or (repo / "qa" / "e2e" / "artifacts")).expanduser().resolve()
    staging = artifacts / STAGING_DIRNAME
    schema_sql = repo / SCHEMA_SQL_REL

    run_doc, problem = load_run_results(artifacts, args.expect_run_id)
    if run_doc is None:
        if args.dry_run:
            print(f"[dry-run] 静态校验失败：{problem}", flush=True)
            return 1
        return fatal(problem)
    run_id = str(run_doc["run_id"])

    if args.dry_run:
        schema_state = "在" if schema_sql.exists() else "**缺失**"
        print(f"[dry-run] run_id={run_id} artifacts={artifacts} 卷={args.volume}", flush=True)
        print("  计划（不检查 docker / 不写任何东西）：", flush=True)
        print(f"    1) Allure 生成：{artifacts / 'allure-results'} → {staging / 'allure-report'}"
              f"（借 {BUILDER_IMAGE} 的 JDK 17；CLI 缓存 {ALLURE_HOME}）", flush=True)
        print(f"    2) 发布：{staging} → 卷 {args.volume}（整树替换 + chmod a+rX）", flush=True)
        print(f"    3) 落库：{RESULTS_DB_CONTAINER} 的 nexus_test_results"
              f"（DDL 每次重放，schema 文件{schema_state}：{schema_sql}）", flush=True)
        print("  dry-run 结束（退出码 0；以下步骤均未执行）。", flush=True)
        return 0

    if not schema_sql.exists():
        return fatal(f"DDL 文件不存在：{schema_sql}（进 SQL 前先看路径 —— 别用缺文件的仓库跑）")
    rc, _ = docker("version", "--format", "{{.Server.Version}}", timeout=30)
    if rc != 0:
        return fatal("docker 不可用（本脚本要在测试发行版里跑 —— 见文件头「位置与边界」）")

    print(f"[finalize-run] run_id={run_id} artifacts={artifacts} 卷={args.volume}"
          f" 起始 {datetime.now(timezone.utc).strftime('%H:%M:%SZ')}", flush=True)
    started = time.monotonic()   # 计时用单调钟：WSL 的墙上时钟会被宿主同步拨动（实测出过负数"用时"）
    R.add("1/4 前置检查", "PASS",
          f"run_id={run_id}（结构校验通过；artifacts={artifacts}）")

    # ── 2: Allure（先摆料 → 准备 CLI → 生成；任何一步失败，落地页仍会如实发布）──
    staging_ready = True
    try:
        warnings = build_staging(artifacts, staging, run_doc)
    except OSError as exc:
        staging_ready = False
        R.add("2/4 Allure 报告生成", "FAIL", "发布目录（staging）搭建失败", str(exc))
    if staging_ready:
        ok_cli, note = ensure_allure_cli()
        if not ok_cli:
            R.add("2/4 Allure 报告生成", "FAIL", "Allure CLI 不可用", note)
        else:
            print(f"        {note}", flush=True)
            ok, note = generate_allure(staging)
            if ok:
                R.add("2/4 Allure 报告生成", "PASS", note,
                      "\n".join(warnings) if warnings else "")
            else:
                R.add("2/4 Allure 报告生成", "FAIL", "生成失败（落地页仍会发布，只是没有报告入口）", note)

    # ── 3: 发布 ──
    if not staging_ready:
        R.add("3/4 发布到报告卷", "SKIP", "staging 未就绪，没有可发布的内容")
    else:
        hub_text = render_hub(run_doc, staging)
        (staging / "index.html").write_text(hub_text, encoding="utf-8", newline="\n")
        ok, note = publish(staging, args.volume)
        if ok:
            R.add("3/4 发布到报告卷", "PASS",
                  f"{note}（Windows: http://localhost:12008/ —— 端口真源 .env.test 的 REPORT_PORT）")
        else:
            R.add("3/4 发布到报告卷", "FAIL", "发布失败", note)

    # ── 4: 落库 ──
    try:
        dml_text = build_dml(run_doc)
    except ValueError as exc:
        R.add("4/4 结果落库", "FAIL", f"生成 SQL 失败：{exc}")
    else:
        ddl_text = schema_sql.read_text(encoding="utf-8")
        ok, note = db_write(ddl_text, dml_text, run_doc)
        if ok:
            R.add("4/4 结果落库", "PASS", f"run_id={run_id} 已落库（幂等：同 run_id 覆盖）", note)
        else:
            R.add("4/4 结果落库", "FAIL", "落库失败", note)

    elapsed = time.monotonic() - started
    if R.failed():
        print(f"[finalize-run] 结论：有失败项（见上）—— 整体幂等，修好后原样重跑即可；用时 {elapsed:.1f}s",
              flush=True)
        return 1
    print(f"[finalize-run] 结论：全部完成 —— 报告 + 结果都已发布；用时 {elapsed:.1f}s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

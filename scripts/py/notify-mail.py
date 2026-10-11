#!/usr/bin/env python3
# =============================================================================
# notify-mail.py —— 把一次运行的结论发成一封信（CI 链路用；task.6 / 5.2 交付物）
#
# 为什么单独成文件（而不是内联在 workflow 里，2026-10-10 用户拍板）：
#   ① `scripts/` 的定位 = **devops 的工具箱**，服务 **开发 / 测试 /（将来）生产** 三个环境 ——
#      邮件通知是这条链路的一环，放这里不是"口径扩展"，是本分；
#   ② ★ 它能被**单独重放**：邮件没到 / 内容不对时，不必重跑整条 workflow（那要几分钟到几十分钟），
#      一条命令就能再发一次 —— 这是选本方案的**全部意义**，重放写法见下方「手工重放」；
#   ③ 判据不在这里：本脚本只读两份 JSON、拼正文、发信；"该不该红"由 deploy.py / pytest 决定
#      （与 deploy_test.py 同一条纪律：**不新增判据**）。
#
# 输入 = **全部走环境变量**（刻意不设 CLI 参数：两处取值源必然漂移；与 workflow 的 env 注入同形）：
#   DEPLOY_SUMMARY   部署摘要 JSON 的路径（deploy.py --summary-json 写的）
#   E2E_ARTIFACTS    冒烟产物目录（读其中的 run-results.json）
#   E2E_RUN_ID       本次运行 ID（CI 里 = ci-<run_id>-<run_attempt>）—— ① 旧产物护栏的钥匙；
#                    ② 报告链接的 run 目录名（本次运行页 / Allure 直链）。缺 = 降级（见下）
#   PR_NUMBER        正文用（来自事件载荷）
#   JOB_STATUS       结论（GitHub 的 job.status：success / failure / cancelled）
#   RUN_URL          Actions 运行页链接
#   REPORT_BASE_URL  报告站点的 host 前缀（可选；默认 http://localhost:<.env.test 的 REPORT_PORT>）
#                    —— 换机器 / 换域名时改这一处即可
#   SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS / MAIL_FROM / MAIL_TO   ← 6 个 repository secrets
#
# 行为（与「内联版」逐条一致；报告链接为 2026-10-11 增补）：
#   · 成功与失败**都**发 —— 由 workflow 的 `if: always()` 保证本脚本一定被调到；
#   · **旧产物护栏**：run-results.json 的 run_id 与 E2E_RUN_ID 不等 ⇒ 当"本次没有结果文件"
#     （自托管 runner 的工作区**跨运行复用**，上一次的结果会留在原地 —— 不能把上次的绿发出去）；
#     没给 E2E_RUN_ID 时护栏无从核对 ⇒ 同样不代发结果（原因写进正文）。
#   · **报告链接（2026-10-11 增补）**：正文给报告地址 —— 根索引 / 本次运行页 / Allure 直链；
#     host 取 REPORT_BASE_URL，**端口不写在本脚本里**（真源 = docker-compose/.env.test 的 REPORT_PORT）；
#     缺 E2E_RUN_ID / REPORT_BASE_URL 时**降级**（给不出的行换成一行说明），绝不崩；
#     正文附一句实话：报告要「收尾」步骤成功发布后才有内容（失败路径下可能只有索引页）。
#   · **不打任何凭据值**：只 print 一行"邮件已发出"；正文里只有 PR 号 / 链接 / 步骤状态；
#   · TLS 口径**由端口推断**：465 = 隐式 TLS（SMTP_SSL）；其余（如 587）= STARTTLS（⚠️ 未实测，见设计 05 §7.1.7-⑤）。
#
# ★ 手工重放（邮件没到 / 想重发一次时照抄这行；值都在你手上，**不要**写进仓库里的任何文件）：
#
#   cd <仓库根> && DEPLOY_SUMMARY=<产物目录>/deploy-summary.json E2E_ARTIFACTS=<产物目录> \
#     E2E_RUN_ID=ci-<run_id>-<attempt> PR_NUMBER=<PR号> JOB_STATUS=failure \
#     RUN_URL=https://github.com/<owner>/<repo>/actions/runs/<run_id> \
#     SMTP_HOST=<host> SMTP_PORT=<465|587> SMTP_USER=<user> SMTP_PASS=<授权码> \
#     MAIL_FROM=<发件人> MAIL_TO=<收件人> python3 scripts/py/notify-mail.py
#
#   REPORT_BASE_URL 可选：不填就按 <仓库根>/docker-compose/.env.test 的 REPORT_PORT 拼 http://localhost:<port>；
#   只看正文、不碰 SMTP（凭据都不用填，CI 里排查正文时最常用）—— 上面那行尾部换成：
#     … python3 scripts/py/notify-mail.py --dry-run
#
# 退出码：0 = 已发出（或 dry-run 已打印）；1 = 缺输入 / 发送失败（**不吞**：workflow 靠它判红）
# =============================================================================
from __future__ import annotations

import json
import os
import smtplib
import sys
from email.message import EmailMessage
from pathlib import Path

# 报告端口口径 = 同目录共享模块 report_env.py（与 finalize-run.py、workflow 步骤 6 共用，**只此一份**）。
# 用 try/except：本脚本被单独拷出 scripts/py/（非文档用法）时也**不崩** —— 读不到端口 → 正文降级。
try:
    from report_env import env_file_port
except ImportError:
    def env_file_port() -> str:
        return ""

# 上下文（dry-run 也要）与 SMTP（只在真发时必填）分开列 —— 缺哪个就点名报哪个，CI 里没人会去猜
CONTEXT_KEYS = ("DEPLOY_SUMMARY", "E2E_ARTIFACTS", "PR_NUMBER", "JOB_STATUS", "RUN_URL")
# 可选键：缺了不拦（可单独重放）——只影响正文里的报告链接/护栏，各自有降级文案
OPTIONAL_KEYS = ("E2E_RUN_ID", "REPORT_BASE_URL")
SMTP_KEYS = ("SMTP_HOST", "SMTP_PORT", "SMTP_USER", "SMTP_PASS", "MAIL_FROM", "MAIL_TO")


def load_json(path: str) -> dict:
    """读一份 JSON；读不到 / 坏掉都返回 {}（正文照写"本次没有"，不因取证文件坏了而崩）。"""
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def report_lines() -> list[str]:
    """正文的报告链接块（2026-10-11 增补）。

    优先级：REPORT_BASE_URL（显式）> http://localhost:<.env.test 的 REPORT_PORT>；
    缺 E2E_RUN_ID / REPORT_BASE_URL 时降级为"只给能给的 + 一行说明"——绝不抛异常。
    """
    base = os.environ.get("REPORT_BASE_URL", "").strip().rstrip("/")
    if not base:
        port = env_file_port()
        if port:
            base = f"http://localhost:{port}"
    run_id = os.environ.get("E2E_RUN_ID", "").strip()
    lines = ["报告："]
    if base:
        lines.append(f"  运行索引：{base}/")
        if run_id:
            lines.append(f"  本次运行：{base}/runs/{run_id}/")
            lines.append(f"  Allure ：{base}/runs/{run_id}/allure-report/")
        else:
            lines.append("  本次运行 / Allure 直链：缺 E2E_RUN_ID —— 只给上面的索引页")
    else:
        lines.append("  报告链接不可用：缺 REPORT_BASE_URL，也没读到 docker-compose/.env.test 的 REPORT_PORT")
    lines.append("  注：报告内容要「收尾」步骤（finalize-run.py）发布成功后才在；失败路径下可能只有索引页。")
    return lines


def render_body() -> str:
    """拼正文：结论 + 运行链接 + 报告地址 + 部署/冒烟摘要 + FAIL/WARN 行（失败路径也要有内容可读）。"""
    dep = load_json(os.environ["DEPLOY_SUMMARY"])
    run_id = os.environ.get("E2E_RUN_ID", "").strip()
    res = load_json(str(Path(os.environ["E2E_ARTIFACTS"]) / "run-results.json"))
    if not run_id:
        res, why = {}, "没给 E2E_RUN_ID，护栏无从核对，不代发结果"
    elif res.get("run_id") != run_id:
        res, why = {}, "部署失败 / 没跑到冒烟"   # 旧产物护栏：不是本次的结果文件，就当没有
    else:
        why = ""
    rows = "\n".join(f'[{s["status"]}] {s["step"]} {s["summary"]}'
                     for s in dep.get("steps", []) if s["status"] in ("FAIL", "WARN"))
    tot = res.get("totals") or {}
    smoke = (f"通过 {tot.get('passed', '-')} / 失败 {tot.get('failed', '-')} / "
             f"跳过 {tot.get('skipped', '-')} / 错误 {tot.get('error', '-')}"
             if res else f"本次没有结果文件（{why}）")
    return (f"PR #{os.environ['PR_NUMBER']}  结论：{os.environ['JOB_STATUS']}\n"
            f"运行：{os.environ['RUN_URL']}\n\n"
            + "\n".join(report_lines()) + "\n\n"
            f"部署：{dep.get('conclusion', '无摘要')}  拉到的 SHA：{(dep.get('git') or {}).get('sha') or '?'}\n"
            f"冒烟：{smoke}\n\n"
            f"{rows or '(无 FAIL/WARN 行)'}")


def send(subject: str, body: str) -> None:
    """发信；TLS 口径由端口推断（465 = 隐式 TLS，其余 = STARTTLS）。"""
    port = int(os.environ["SMTP_PORT"])
    msg = EmailMessage()
    msg["Subject"], msg["From"], msg["To"] = subject, os.environ["MAIL_FROM"], os.environ["MAIL_TO"]
    msg.set_content(body)
    srv = (smtplib.SMTP_SSL(os.environ["SMTP_HOST"], port) if port == 465
           else smtplib.SMTP(os.environ["SMTP_HOST"], port))
    with srv as s:
        if port != 465:
            s.starttls()
        s.login(os.environ["SMTP_USER"], os.environ["SMTP_PASS"])
        s.send_message(msg)


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    dry_run = "--dry-run" in args
    need = CONTEXT_KEYS if dry_run else CONTEXT_KEYS + SMTP_KEYS
    missing = [k for k in need if not os.environ.get(k, "").strip()]
    if missing:
        print(f"[notify-mail] 缺输入（环境变量）：{'、'.join(missing)}", file=sys.stderr)
        print("[notify-mail] 手工重放的写法见本文件头部「★ 手工重放」", file=sys.stderr)
        return 1
    missing_optional = [k for k in OPTIONAL_KEYS if not os.environ.get(k, "").strip()]
    if missing_optional:
        print(f"[notify-mail] 提示：缺可选输入 {'、'.join(missing_optional)}"
              f" —— 正文对应部分降级（不拦）", file=sys.stderr)
    subject = f"[nexus-e2e] PR #{os.environ['PR_NUMBER']} {os.environ['JOB_STATUS']}"
    body = render_body()
    if dry_run:
        print(f"Subject: {subject}\n\n{body}")
        return 0
    try:
        send(subject, body)
    except Exception as exc:  # noqa: BLE001 —— 要红，但**不吐**凭据（smtplib 的异常不含口令）
        print(f"[notify-mail] 发送失败：{exc!r}", file=sys.stderr)
        return 1
    print("邮件已发出")
    return 0


if __name__ == "__main__":
    sys.exit(main())

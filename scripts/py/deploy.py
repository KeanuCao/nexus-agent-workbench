#!/usr/bin/env python3
# =============================================================================
# deploy.py —— nexus 一键部署（阶段交付后的标准动作）
#
# 2026-09-23 首次实跑暴露 3 处**自身缺陷**（崩在第 3 步 rebuild 判定的 NameError + 向量维度 /
#   nginx 上限两条判据造假警报），当日已修；同日**实跑验证通过**（八步全绿、69s）。
#   同一轮还从实跑输出里揪出 2 处"输出层"缺口（人工核对 SHA 的提醒被 PASS 行吞掉、产物大小
#   按"大小紧挨文件名"解析），一并已修。现象、根因、实测对照与验证记录见
#   docs/agent-log/20260923-首次实跑deploy失败.md。
#   教训（留在头部防复发）：**凡断言必须实测** —— 本脚本交付时只 smoke 测过 `--help` 与几个解析
#   函数，主路径一次没执行过，于是三条判据里两条是自造的假警报（被测对象其实都是好的）。
#
# 设计目标（2026-09-23 定）：**一次跑完、一张表、零提示**。
#   一次部署 ≤ 1 分钟（缓存热时）、交互 ≤ 3 次、部署期间不派活、失败即停等人。
#
# 执行位置：**WSL 发行版内**（docker 在其中；脚本直接调 docker，不经双层 shell）
#   dev ：wsl -d nexus-agent-workbench -- python3 /mnt/c/wp/nexus-agent-workbench/scripts/py/deploy.py
#   test：测试发行版内直接跑（CI 走薄包装 scripts/py/deploy_test.py；见 design 05 §4）
#
# 档位（2026-10-09 新增，设计 05 §4.1-②）：compose 文件 + env 文件**成对**由档位常量提供，
#   `-f` 与 `--env-file` 原子地加在每条 compose 命令前；**不开放单独覆盖的 CLI 开关**
#   （只给 --env-file 不给 -f 会拿测试 env 去插值开发 compose —— 防呆断言会拦它）。
# CI 档（2026-10-09 新增，设计 05 §4.2）：--ci 关颜色；--summary-json <path> 落机器可读摘要
#   （每步状态 + 拉到的 SHA）。"失败即停等人"是人工档的流程约定；CI 里由"非 0 退出 + 摘要"承担。
#
# 八步（对应用户给的清单）：
#   1 前置（docker/compose/builder 可用）
#   2 拉取最新代码（容器内 git-sync）—— 只能拿到【已 push】的提交（见 .env 的硬约束）
#   3 配置快照与一致性（含"镜像是否需要 rebuild"的判定）
#   4 db-patch 校验（先只读对账；真有新补丁才跑迁移）
#   5 打包（builder 内 build-all）
#   6 容器：按需 rebuild / 重启后端 / 拉起
#   7 容器 health
#   8 冒烟：/api/health、登录、前端首页 + 前端资产
#
# 刻意不做的事（避免又变成"来回取证"）：
#   - 不校验 rebuild 的结果（用户明确：需要 rebuild 就直接 rebuild）
#   - 不自动修改任何东西（失败只报告 + 给下一步线索，由人工定方案）
#   - 不打印密钥（.env.local 只报存在/缺失）、不打印 token 明文
#   - 不写业务数据（只读 SQL + 一次登录）
# =============================================================================

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field, replace
from pathlib import Path

# ── 路径（按仓库标记上溯定位，不写死层级）─────────────────────────────────────
def _find_repo_root(start: Path) -> Path:
    """
    从脚本位置逐级上溯，找含 `docker-compose/docker-compose.yml` 的那一层作为仓库根。

    为什么不写死 `Path(__file__).resolve().parents[N]`：2026-09-23 本脚本从
    `.claude/skills/deploy/` 迁到 `scripts/py/`，层级一变 parents[N] 就**静默指错**
    —— 脚本照跑，只是 REPO/compose/env 全指到别处，故障形态是"判据莫名其妙"而不是报错。
    改成按标记上溯后，再搬目录也不必改这里；真找不到标记则当场退出去（响亮失败）。
    """
    for d in (start, *start.parents):
        if (d / "docker-compose" / "docker-compose.yml").is_file():
            return d
    raise SystemExit(f"[deploy] 致命：从 {start} 上溯未见 docker-compose/docker-compose.yml，无法定位仓库根")


REPO = _find_repo_root(Path(__file__).resolve().parent)
COMPOSE_DIR = REPO / "docker-compose"      # 两档的 compose/env 都住这里（共用**目录**，不是共用文件）
ENV_LOCAL = COMPOSE_DIR / ".env.local"     # 仅 dev 档消费；test 档不挂（M4-③）
PATCH_DIR = REPO / "db-patch"


# ── 档位（设计 05 §4.1 ②③④⑤⑥）：compose 文件 / env 文件 / 期望健康清单 / 烘镜像清单 / 状态文件 ──
@dataclass(frozen=True)
class Profile:
    """一档 = 一组**成对**的环境参数。路径只从档位出，不开放单独覆盖的 CLI 开关（防呆 ⓐ）。"""
    name: str
    compose_file: Path
    env_file: Path
    project_name: str                  # 防呆 ⓑ：compose 顶层 `name:` 必须等于它
    expected_healthy: list[str]        # 阻断判定：running+healthy 必须全满足
    nonblocking_healthy: list[str]     # 非阻断：不满足只 WARN（报告链路归 5.5，起得慢不该让部署红）
    baked_files: dict[str, list[str]]  # 烘进镜像的文件（**仓库根相对路径**；键 = **服务名**）
    state_file: Path                   # rebuild 基线**按档分开**，否则两档互相污染
    lenient: bool                      # 判据档：True = "前置物不存在 ⇒ WARN；存在但不一致 ⇒ 仍 FAIL"
    warn_missing_env_local: bool       # 仅 dev 档对 .env.local 缺失发 WARN（test 档不需要 DeepSeek）
    cold_start: bool                   # True = 本脚本负责从冷启动拉起环境（CI）；False = 栈由 up.sh 常驻
    port_range: tuple[int, int] | None # 防呆 ⓒ：非 None 时断言已发布端口全部落该段
    backend_container: str             # 后端容器名（step6 判"冷启动 vs 热环境"用）
    login_user: str
    login_password: str

    @property
    def compose_dir(self) -> Path:
        return self.compose_file.parent


PROFILES: dict[str, Profile] = {
    "dev": Profile(
        name="dev",
        compose_file=COMPOSE_DIR / "docker-compose.yml",
        env_file=COMPOSE_DIR / ".env",
        project_name="nexus",
        expected_healthy=["nexus-postgres", "nexus-redis", "nexus-ollama",
                          "nexus-backend", "nexus-frontend"],
        nonblocking_healthy=[],
        # 烘进镜像的文件：内容一变 ⇒ 必须 rebuild 镜像（dist 走卷挂载，不在其中）。
        # ⚠️ 清单必须与该服务 Dockerfile 里的**每一个 COPY 源**对齐（镜像只由这些文件决定）：
        #    2026-09-23 复核发现漏了 entrypoint.sh —— 它被 COPY 进镜像却不在清单里，
        #    改它不触发 rebuild ⇒ 又一次"镜像静默过期"。以后加 COPY 就同步加这里。
        # 刻意不含 builder：它是手工启停的构建容器，其 Dockerfile 变更由 up.sh 第 2 步处理。
        baked_files={
            "nexus-frontend": ["docker-compose/frontend/Dockerfile", "docker-compose/frontend/nginx.conf"],
            "nexus-backend": ["docker-compose/backend/Dockerfile", "docker-compose/backend/entrypoint.sh"],
        },
        state_file=REPO / ".tmp" / "deploy-state.json",
        lenient=False,
        warn_missing_env_local=True,
        cold_start=False,
        port_range=None,
        backend_container="nexus-backend",
        login_user="admin",
        login_password="admin123",
    ),
    "test": Profile(
        name="test",
        compose_file=COMPOSE_DIR / "docker-compose.test.yml",
        env_file=COMPOSE_DIR / ".env.test",
        project_name="nexus-test",
        # 阻断清单 = 5 个应用容器 + **结果库 PG**（pytest 写结果的前置，设计 05 §4.1-⑤）
        expected_healthy=["nexus-test-postgres", "nexus-test-redis", "nexus-test-ollama",
                          "nexus-test-backend", "nexus-test-frontend", "nexus-test-postgres-results"],
        nonblocking_healthy=["nexus-test-metabase", "nexus-test-report-nginx"],
        baked_files={
            "nexus-frontend": ["docker-compose/frontend/Dockerfile", "docker-compose/frontend/nginx.conf"],
            "nexus-backend": ["docker-compose/backend/Dockerfile", "docker-compose/backend/entrypoint.sh"],
            # ⚠️ 测试档**必须**把 builder 纳入 rebuild 判定：CI 里没有 up.sh 第 2 步，
            #    不纳入 ⇒ 改了 git-sync / builder Dockerfile 会静默用旧镜像（"镜像静默过期"类）。
            "builder": ["docker-compose/builder/Dockerfile", "docker-compose/builder/settings.xml",
                        "docker-compose/builder/git-sync", "docker-compose/builder/build-backend",
                        "docker-compose/builder/build-frontend", "docker-compose/builder/build-all",
                        "docker-compose/builder/db-patch-migrate"],
        },
        state_file=REPO / ".tmp" / "deploy-state.test.json",
        lenient=True,
        warn_missing_env_local=False,
        cold_start=True,
        port_range=(12000, 13000),
        backend_container="nexus-test-backend",
        login_user="admin",
        login_password="admin123",
    ),
}

PROFILE: Profile = PROFILES["dev"]   # 由 main() 按 --profile 选定；模块级默认 dev（被 import 时安全）

# ⚠️ 服务名两档**一致**（设计 05 §3.1：nginx.conf / env 全按服务名写）。
#    compose 的 restart / exec / build / up 一律用**服务名**；容器名（nexus-test-*）只出现在
#    `ps --format json` 的 Name 字段里 —— 两者在测试档不同名，写混会得到 "no such service"。
BACKEND_SERVICE = "nexus-backend"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def disable_colors() -> None:
    """--ci / --no-color：把颜色常量置空（Rows.add 在调用时查这些全局名，改完即刻生效）。"""
    global GREEN, RED, YELLOW, DIM, RESET
    GREEN = RED = YELLOW = DIM = RESET = ""


# ── 结果收集 ─────────────────────────────────────────────────────────────────
@dataclass
class Rows:
    """每步一行；status ∈ PASS / FAIL / WARN / INFO / SKIP"""
    rows: list[tuple[str, str, str, str]] = field(default_factory=list)  # (step, status, 摘要, 细节)

    def add(self, step: str, status: str, summary: str, detail: str = "",
            always_show_detail: bool = False) -> None:
        """
        detail 默认**只在 FAIL/WARN 时打印**（这两类要给人留线索）。所以挂在 PASS 行上的人办事项
        必须显式传 `always_show_detail=True` —— 2026-09-23 首跑那条"人工核对 SHA"的提醒就是这么
        静默消失的：写在源码里像模像样，实际一次都没被打印过（PASS 行把 detail 吞了）。
        """
        self.rows.append((step, status, summary, detail))
        mark = {"PASS": f"{GREEN}[PASS]{RESET}", "FAIL": f"{RED}[FAIL]{RESET}",
                "WARN": f"{YELLOW}[WARN]{RESET}", "INFO": f"{DIM}[INFO]{RESET}",
                "SKIP": f"{DIM}[SKIP]{RESET}"}[status]
        line = f"{mark} {step:<22} {summary}"
        print(line, flush=True)
        if detail and (always_show_detail or status in ("FAIL", "WARN")):
            for dl in detail.splitlines():
                print(f"       {DIM}{dl}{RESET}", flush=True)

    @property
    def failed(self) -> int:
        return sum(1 for r in self.rows if r[1] == "FAIL")

    @property
    def warned(self) -> int:
        return sum(1 for r in self.rows if r[1] == "WARN")


R = Rows()


def die(step: str, summary: str, detail: str = "") -> None:
    """致命失败：报告后立即收尾退出（不自动修、不派活）。"""
    R.add(step, "FAIL", summary, detail)
    summary_print()
    write_summary_json()      # CI 档：失败路径**也要**落摘要（人不在，红的原因只能从这里读）
    sys.exit(1)


# ── 命令执行 ─────────────────────────────────────────────────────────────────
def run(args: list[str], timeout: int = 120, cwd: Path | None = None) -> tuple[int, str]:
    """
    跑一条命令，返回 (returncode, 合并后的输出)。永不抛异常。

    两个**载荷性**细节（改这里之前先读 —— 下面多处 `splitlines()[0]` 依赖它们）：
    ① 拼接顺序刻意是 **stdout 在前、stderr 在后**：`docker compose` 的告警（如 ollama-init 里
       `$model` 未被展开的 "variable is not set"）全走 stderr，排在末尾才不会顶掉真正的取值行
       —— 换成 stderr 在前，psql 的维度值 / t_db_patch 计数会被告警行顶掉，判据集体取空。
    ② stdin 显式接 /dev/null：`docker compose exec` 会**吞掉调用方的 stdin**（2026-09-23 实测：
       用 heredoc 喂脚本时，第一条 exec 就把后面剩下的内容全吃光了）⇒ 不接空，本脚本可能把上一层
       的输入吃掉；`exec -T` 只关 TTY，这里再关掉 stdin 才算名副其实的"零交互"。
    """
    try:
        p = subprocess.run(args, cwd=str(cwd or PROFILE.compose_dir), capture_output=True,
                           text=True, timeout=timeout, errors="replace",
                           stdin=subprocess.DEVNULL)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, f"[deploy] 超时（{timeout}s）：{' '.join(args)}"
    except FileNotFoundError as exc:
        return 127, f"[deploy] 命令不存在：{exc}"
    except Exception as exc:  # noqa: BLE001 —— 兜底，任何异常都变成一次 FAIL 而不是崩栈
        return 1, f"[deploy] 执行异常：{exc!r}"


def compose(*args: str, timeout: int = 120) -> tuple[int, str]:
    """
    每条 compose 命令都**原子地**带上档位的 `-f` 与 `--env-file`（设计 05 §4.1-②）：
    路径只从 PROFILE 出 —— 不允许"只给 env 不给 -f"这类半套组合（防呆 ⓐ）。
    用绝对路径，避免相对 cwd 解析的二次解释。
    """
    return run(["docker", "compose",
                "-f", str(PROFILE.compose_file),
                "--env-file", str(PROFILE.env_file),
                *args], timeout=timeout)


def builder(*args: str, timeout: int = 300) -> tuple[int, str]:
    """在 builder 容器里执行入口命令；支持附加参数（如 git-sync --ref <refspec>）。"""
    return compose("exec", "-T", "builder", *args, timeout=timeout)


def http(url: str, method: str = "GET", body: dict | None = None, timeout: int = 10) -> tuple[int, str]:
    """极简 HTTP；只用标准库（不依赖 curl）。返回 (status, body)；网络错返回 (0, 原因)。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:          # 4xx/5xx 也是"有响应"，要读体
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, f"{type(exc).__name__}: {exc}"


def read_env(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def describe(rec: tuple[str, str] | None) -> str:
    """把 compose ps 的一行压成 `running/healthy` 这种短标签（缺失时返回 missing）。"""
    if not rec:
        return "missing"
    state, health = rec[0] or "", rec[1] or ""
    return f"{state}/{health}" if health else (state or "unknown")


def compose_ps_status() -> dict[str, tuple[str, str]]:
    """`compose ps --format json` → {容器名: (State, Health)}；容器不在册则为空 dict。"""
    rc, out = compose("ps", "--format", "json")
    status: dict[str, tuple[str, str]] = {}
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                rec = json.loads(line)
                status[rec.get("Name", "")] = (rec.get("State", ""), rec.get("Health", ""))
            except json.JSONDecodeError:
                pass
    return status


def load_state() -> dict:
    # ⚠️ 状态文件**按档位分开**（设计 05 §4.1-⑥）：共用一个文件会让两档互相污染重建基线
    try:
        return json.loads(PROFILE.state_file.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def save_state(state: dict) -> None:
    PROFILE.state_file.parent.mkdir(parents=True, exist_ok=True)
    PROFILE.state_file.write_text(json.dumps(state, indent=2), encoding="utf-8")


# ── 输出 ─────────────────────────────────────────────────────────────────────
T0 = time.time()

# CI 档运行期状态（由 main() / step2 回填）；dev 档保持 None / 空，行为不变
SUMMARY_JSON: Path | None = None       # --summary-json 的目标文件
GIT_INFO: dict = {}                    # step2 拉到的提交信息（sha / branch / subject / ref）
REF_ARG: str | None = None             # --ref（CI 拉 PR head 用；回填进摘要）


def summary_print() -> None:
    el = time.time() - T0
    print()
    print("=" * 78)
    if R.failed:
        print(f"  结论：部署失败 —— {R.failed} 项 FAIL（见上方 [FAIL] 行）。"
              f"**人工介入**：定方案后再动手，不要自动重试。")
    elif R.warned:
        print(f"  结论：部署完成（{R.warned} 项 WARN，不阻断）")
    else:
        print("  结论：部署成功")
    print(f"  总耗时：{el:.0f}s" + ("   ⚠️ 超过 1 分钟（缓存冷或后端启动慢时属正常）" if el > 60 else ""))
    print("=" * 78)


def write_summary_json() -> None:
    """
    CI 档的机器可读摘要（设计 05 §4.2）：每步 (step, status, summary) + 拉到的 SHA + 结论。
    给 workflow 读（邮件正文 / Actions 摘要用）—— 人不在 CI 里，红的原因只能从这里读。
    刻意**不因写失败而崩**：摘要缺失只报一行，不影响部署本身的退出码语义。
    """
    if SUMMARY_JSON is None:
        return
    doc = {
        "tool": "nexus-deploy",
        "profile": PROFILE.name,
        "compose_file": str(PROFILE.compose_file),
        "env_file": str(PROFILE.env_file),
        "repo": str(REPO),
        "ref": REF_ARG or "",
        "git": GIT_INFO,
        "conclusion": "failed" if R.failed else ("warn" if R.warned else "success"),
        "failed": R.failed,
        "warned": R.warned,
        "elapsed_s": round(time.time() - T0, 1),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "steps": [{"step": s, "status": st, "summary": sm, "detail": dt}
                  for s, st, sm, dt in R.rows],
    }
    try:
        SUMMARY_JSON.parent.mkdir(parents=True, exist_ok=True)
        SUMMARY_JSON.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  摘要 JSON 已写入：{SUMMARY_JSON}")
    except Exception as exc:  # noqa: BLE001
        print(f"  ⚠️ 摘要 JSON 写入失败（不影响部署结论）：{exc!r}")


# ── 0 档位防呆（设计 05 §4.1-②：ⓐ 结构性在 Profile/argparse，ⓑⓒ 在这里） ──────────
def guard_profile() -> None:
    """
    防呆 ⓑ：读被选中 compose 的顶层 `name:`，与档位期望比对；
    防呆 ⓒ：`compose config` 抽查已发布宿主端口落档位段（仅定义了 port_range 的档位，即 test）。

    ⓑ/ⓒ 拦的是同一类事故：**半套组合** —— 拿测试 env 去插值开发 compose（或反之），
    端口/项目名静默串档、直到有人在生产机上看出不对。
    """
    text = PROFILE.compose_file.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^name:\s*(\S+)\s*$", text, re.M)
    actual = m.group(1) if m else "(未找到 top-level name:)"
    if actual != PROFILE.project_name:
        die("0 档位", f"compose 顶层 name 不符：{PROFILE.compose_file.name}={actual} / 档位期望 {PROFILE.project_name}",
            "半套组合（-f 与 --env-file 不配对）会让端口/项目名静默串档；先把档位常量改对再跑")
    R.add("0 档位", "PASS",
          f"profile={PROFILE.name} / {PROFILE.compose_file.name} + {PROFILE.env_file.name}（成对）")
    if PROFILE.port_range is not None:
        lo, hi = PROFILE.port_range
        rc, out = compose("config", timeout=60)
        if rc != 0:
            die("0 档位", "docker compose config 失败（compose / env 组合有问题）", out.strip()[-400:])
        ports = sorted({int(p) for p in re.findall(r"published:\s*\"?(\d+)\"?", out)})
        bad = [p for p in ports if not (lo <= p <= hi)]
        if not ports or bad:
            die("0 档位", f"已发布端口未全落 {lo}~{hi}：{ports or '(解析为空)'}",
                "测试档端口必须落 12000~13000；出现段外端口 ⇒ env 文件串档或端口表被改坏。\n"
                "若 published: 的解析格式变了，把 `compose config` 原文贴出来核对")
        R.add("0 档位", "PASS", f"已发布端口全部落 {lo}~{hi}：{ports}")


# ── 1 前置 ───────────────────────────────────────────────────────────────────
def step1_preflight() -> None:
    rc, out = run(["docker", "version", "--format", "{{.Server.Version}}"], cwd=REPO)
    if rc != 0:
        die("1 前置", "docker 不可用（WSL 里 docker 起了吗）", out.strip()[:400])
    if not PROFILE.compose_file.exists():
        die("1 前置", f"compose 文件不存在：{PROFILE.compose_file}")
    # builder 是手工启停的常驻容器；不在就拉起来（它是打包/迁移的唯一执行者）
    rc, out = compose("ps", "-a", "--format", "json")
    services = {}
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                rec = json.loads(line)
                services[rec.get("Service", "")] = rec
            except json.JSONDecodeError:
                pass
    b = services.get("builder")
    if b is None or b.get("State") != "running":
        rc, out = compose("up", "-d", "builder", timeout=180)
        if rc != 0:
            die("1 前置", "builder 未运行且拉起失败（首次会连带构建镜像，失败请看构建输出）", out.strip()[-500:])
        R.add("1 前置", "PASS", "builder 已拉起（它是打包/迁移的执行者）")
    else:
        R.add("1 前置", "PASS", f"docker OK / builder running / {len(services)} 个容器在册")
    if PROFILE.cold_start:
        # 测试档（CI）：栈可能是从零冷的 —— step3 / step4 立刻要用 PG / redis / ollama，
        # 先把基础设施拉起来；ollama-init 一并放跑（模型 ≈6GB 与 step5 的构建**并行**，缩短首跑时长）。
        # 开发档不走这里（栈由 up.sh 常驻）。
        rc, out = compose("up", "-d", "postgres", "postgres-results",
                          "redis", "ollama", "ollama-init", timeout=600)
        if rc != 0:
            die("1 前置", "测试档基础设施 up -d 失败（postgres / postgres-results / redis / ollama）",
                out.strip()[-500:])
        R.add("1 前置", "PASS", "测试档：基础设施已拉起（含 ollama-init，模型拉取与后续步骤并行）")


# ── 2 拉取最新代码 ───────────────────────────────────────────────────────────
def step2_git_sync(expect_sha: str | None, ref: str | None) -> dict:
    # ref 走 **exec 层**参数（--ref），不改容器 env（env 只在容器创建时注入，改它必须重建容器；
    # 见设计 05 §4.1-⑦）。省略 --ref ⇒ git-sync 维持分支模式，dev 行为不变。
    cmd = ["git-sync"] + (["--ref", ref] if ref else [])
    rc, out = builder(*cmd, timeout=180)
    if rc != 0:
        die("2 拉取最新代码",
            f"git-sync 失败（容器内拉不到远端？分支/ref 对不对？实际执行：git-sync {' '.join(cmd[1:])}）",
            out.strip()[-600:])
    sha = branch = subject = ""
    for line in out.splitlines():
        m = re.match(r"\s+([0-9a-f]{7,})\s+\d{4}-\d{2}-\d{2}\s+(.*)$", line)
        if m and not sha:
            sha, subject = m.group(1), m.group(2)
        m2 = re.search(r"\[git-sync\] 分支\s*:\s*(\S+)", line)
        if m2:
            branch = m2.group(1)
    info = {"sha": sha, "branch": branch, "ref": ref or "", "subject": subject}
    if not sha:
        die("2 拉取最新代码", "git-sync 成功但没解析到提交（输出格式变了？）", out.strip()[-500:])
    if expect_sha and not (sha.startswith(expect_sha) or expect_sha.startswith(sha)):
        die("2 拉取最新代码", f"拉到 {sha}，与期望 {expect_sha} 不一致",
            "说明【要部署的提交还没 push/merge 到远端分支（或 --ref 拉错了）】——容器只能拿到远端已有的提交")
    label = f"ref={ref} " if ref else ""
    R.add("2 拉取最新代码", "PASS", f"{label}{sha}  {subject[:38]}",
          "⚠️ 请人工核对：这个 SHA 就是你刚 merge 的（或该 PR 的 head）吗？", always_show_detail=True)
    GIT_INFO.update(info)
    return info


# ── 3 配置快照与一致性（含"是否需要 rebuild 镜像"）────────────────────────────
def step3_config(force_rebuild: bool, ref: str | None) -> dict:
    cfg = read_env(PROFILE.env_file)
    backend_port = cfg.get("BACKEND_PORT", "8089")
    frontend_port = cfg.get("FRONTEND_PORT", "8088")

    # 3.1 分支真源链：容器 env vs env 文件（**容器 env 才是构建真源**：改 env 后要 up -d builder 才生效）
    # ⚠️ --ref 非空时（CI 拉 PR head），分支 env 不是真源 —— 本次实际拉的是 ref ⇒ 改为 INFO（设计 05 §4.1-⑦）。
    if ref:
        R.add("3 配置一致性", "INFO", f"NEXUS_REPO_BRANCH 检查跳过：本次由 --ref 指定（{ref}）")
    else:
        rc, out = compose("exec", "-T", "builder", "printenv", "NEXUS_REPO_BRANCH", timeout=60)
        container_branch = out.strip().splitlines()[0] if rc == 0 and out.strip() else "(未知)"
        if container_branch != cfg.get("NEXUS_REPO_BRANCH", "main"):
            R.add("3 配置一致性", "FAIL",
                  f"分支不一致：容器={container_branch} / env 文件={cfg.get('NEXUS_REPO_BRANCH')}",
                  "容器 env 才是构建真源；改 env 后要 up -d builder 重建容器才生效")
        else:
            R.add("3 配置一致性", "PASS", f"NEXUS_REPO_BRANCH={container_branch}（容器 env 与 env 文件一致）")

    # 3.2 模型清单：真源 = compose 的 ollama-init 清单（probe.sh 的 nexus_expected_models 读的就是它）
    expected = parse_expected_models()
    rc, out = compose("exec", "-T", "ollama", "ollama", "list", timeout=60)
    found = []
    if rc == 0:
        for line in out.splitlines()[1:]:
            parts = line.split()
            if parts:
                found.append(re.sub(r":latest$", "", parts[0]))
    missing = [m for m in expected if m not in found]
    if not expected or rc != 0:
        R.add("3 配置一致性", "WARN", "模型清单未能对账（ollama 未响应或清单解析失败）")
    elif missing and PROFILE.lenient:
        # 测试档：模型暂缺 = "前置物不存在"（设计 05 §4.1-⑨ 判据档口径）。
        # 首次 CI 运行时 ollama-init 往往仍在拉取（≈6GB），这里红会挡住整条链路；
        # 真问题（清单写错 / 拉取失败）会在 5.4 的用例与 M7 的人工核对里暴露。
        R.add("3 配置一致性", "WARN", f"模型暂缺：{' '.join(missing)}",
              "测试档（lenient）：首次运行 ollama-init 可能仍在拉取（≈6GB）；E2E 用例前需就绪")
    elif missing:
        R.add("3 配置一致性", "FAIL", f"模型缺失：{' '.join(missing)}",
              "补拉：for m in <名单>; do docker compose exec -T ollama ollama pull $m; done")
    else:
        R.add("3 配置一致性", "PASS", f"模型齐备：{' '.join(expected)}")

    # 3.3 nginx 请求体上限（>1MB 上传的必需项；缺失 ⇒ 413 而响应体不是 Result，极难猜）
    #     判据取【运行中容器内那份】而非宿主源码 —— 它才是真正生效的那份
    # ⚠️ 两侧判据都**锚定指令行首**（`^[[:space:]]*client_max_body_size`），不许退回子串或行数：
    #    源文件第 47 行的**注释**里也写着这个指令名 ⇒ `grep -c` 数出 2，而首跑的判据是
    #    `endswith("1")` ⇒ 假警报（根因 3）；反向的坑更坏 —— 子串匹配会把「被 # 注释掉的指令」
    #    当成"有"，那是**漏报**：镜像里根本没生效，却给一个 PASS。
    # ⚠️ frontend/nginx.conf 是**共用资产**（测试档零改动复用同一份）⇒ 路径跟档位目录走即可
    host_conf = PROFILE.compose_dir.joinpath("frontend/nginx.conf").read_text(encoding="utf-8", errors="replace")
    if re.search(r"^[ \t]*client_max_body_size", host_conf, re.M):
        rc, out = compose("exec", "-T", "nexus-frontend", "grep", "-E",
                          r"^[[:space:]]*client_max_body_size",
                          "/etc/nginx/conf.d/default.conf", timeout=60)
        hits = [ln.strip() for ln in out.splitlines() if ln.strip().startswith("client_max_body_size")]
        if hits:
            R.add("3 配置一致性", "PASS",
                  f"容器内 nginx 已生效：{hits[0]}" + (f"（{len(hits)} 处）" if len(hits) > 1 else ""))
        else:
            R.add("3 配置一致性", "WARN", "容器内 nginx.conf 未见 client_max_body_size 指令行",
                  "宿主源码里有 ⇒ 镜像里的是旧的 ⇒ 本次将触发前端镜像 rebuild"
                  "（前端容器本就没起时，这一行也是这个形态）\n"
                  f"exec 原始输出：{out.strip()[-200:] or '（空）'}")
    elif PROFILE.lenient:
        # 测试档：宿主 nginx.conf 缺该指令 = "前置物不存在" ⇒ WARN（设计 05 §4.1-⑨）；
        # "存在但不一致"（容器内旧版）仍是上面那条 WARN 的既有口径。
        R.add("3 配置一致性", "WARN", "宿主 nginx.conf 缺 client_max_body_size（>1MB 上传会 413）",
              "测试档（lenient）：降级为 WARN；开发档同项是 FAIL")
    else:
        R.add("3 配置一致性", "FAIL", "宿主 nginx.conf 缺 client_max_body_size（>1MB 上传会 413）")

    # 3.4 向量维度对账：代码常量 vs DB 列的**声明维度**（模型清单已在上面对过）
    # ⚠️ DB 侧判据 = `format_type()` 渲染出的类型串（形如 `vector(1024)`），**不是** atttypmod 算术：
    #    `atttypmod-4` 是 **varchar 的 VARHDRSZ 惯例**，pgvector 的 atttypmod **直接存维度**
    #    ⇒ 首跑读出 1020（真值 1024）= 假警报（根因 2）。2026-09-23 实机三种写法对照：
    #      atttypmod=1024 ／ atttypmod-4=1020 ／ format_type=vector(1024)，代码 EXPECTED_DIMENSION=1024
    #    取类型串另有个好处：类型本身被换掉（如 halfvec）时，打印出来的原文就能看出来。
    dim_code = ""
    for p in (REPO / "backend").rglob("OllamaEmbeddingService.java"):
        m = re.search(r"EXPECTED_DIMENSION\s*=\s*(\d+)", p.read_text(encoding="utf-8", errors="replace"))
        if m:
            dim_code = m.group(1)
            break
    rc, out = compose("exec", "-T", "postgres", "psql", "-U", cfg.get("PG_USER", "nexus"),
                      "-d", cfg.get("PG_DB", "nexus"), "-tAc",
                      "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                      "WHERE attrelid='t_kb_chunk'::regclass AND attname='embedding'",
                      timeout=60)
    db_raw = out.strip().splitlines()[0] if rc == 0 and out.strip() else ""
    m = re.search(r"\((\d+)\)", db_raw)
    dim_db = m.group(1) if m else ""
    if dim_code and dim_db and dim_code != dim_db:
        R.add("3 配置一致性", "FAIL", f"向量维度不一致：代码={dim_code} / DB={dim_db}",
              "这是「换模型只改一处」的经典故障：两者必须同时改（另见 db-patch 里的维度补丁）")
    elif dim_code and dim_db:
        R.add("3 配置一致性", "PASS", f"向量维度一致：{db_raw} = 代码常量 {dim_code}")
    else:
        R.add("3 配置一致性", "WARN", f"向量维度未对账（代码={dim_code or '?'} / DB={db_raw or '取不到'}）",
              "DB 侧取不到 ⇒ 表/列不存在（或 postgres 没起）；psql 原文："
              + (out.strip()[-200:] or "（空）"))

    # 3.5 镜像 rebuild 判定（烘进镜像的文件内容变了才需要；dist 走卷挂载不需要）
    # 状态形如 {"baked": {"nexus-frontend": {"<相对路径>": "<sha256>", ...}, ...}} —— **按服务分组**存，
    # 比较也只发生在"同一服务的同一文件"之间（旧版把状态摊平成 文件→sha，跨服务会串味）。
    # ⚠️ 首跑正是崩在这段：判定条件里引用了 `f`，而 `f` 只是字典推导式的迭代变量，
    #    **推导式的名字不外泄**（外层只有 `for svc, files in ...`）⇒ NameError（根因 1）。
    #    教训：跨行要用的值先落成变量（下面的 cur），别指望推导式把名字漏出来。
    state = load_state()
    baked = state.get("baked") or {}
    need: list[str] = []
    for svc, files in PROFILE.baked_files.items():
        cur = {f: sha256(REPO / f) for f in files if (REPO / f).is_file()}
        was = baked.get(svc) or {}
        # 键集不等 = 首次运行（无基线）/ 文件增删 / 清单改过 —— 与内容变化同等对待：一律保守重建
        if force_rebuild or set(was) != set(cur) or any(was.get(f) != h for f, h in cur.items()):
            need.append(svc)
        state.setdefault("baked", {})[svc] = cur
    R.add("3 镜像判定", "INFO",
          f"需要 rebuild：{' '.join(sorted(set(need))) or '无'}" + ("（--rebuild 强制）" if force_rebuild else ""))
    save_state(state)
    return {"backend_port": backend_port, "frontend_port": frontend_port, "rebuild": sorted(set(need))}


def parse_expected_models() -> list[str]:
    """
    真源：**档位对应的** compose 文件里 ollama-init 服务的模型清单（probe.sh 的 nexus_expected_models 读的是开发那份）。

    ⚠️ 当前形态是 shell 内联循环 `for model in qwen2.5:7b bge-m3; do`，**不是 YAML 列表项** ——
    按 YAML 列表去解析会一个都找不到、然后误报"模型清单未能对账"。两种形态都兜住。
    """
    # ⚠️ 真源 = **档位对应的** compose 文件（设计 05 §4.1-②-ⓑ 的 4 处直接读者之一）：
    #    不随档位切换 ⇒ 拿开发 compose 的模型清单给测试环境对账（两份今天恰好相同 ⇒ 静默不报）。
    text = PROFILE.compose_file.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"\n  ollama-init:\n(.*?)(?=\n  [a-z-]+:\n)", text, re.S)
    if not m:
        return []
    block = m.group(1)
    # 形态 A：shell 内联 `for model in <名字...>; do`
    mm = re.search(r"for\s+model\s+in\s+([^;]+);", block)
    if mm:
        return [t for t in mm.group(1).split() if t]
    # 形态 B：YAML 列表 `- <名字>`
    models = []
    for line in block.splitlines():
        if line.strip().startswith("#"):
            continue
        m2 = re.match(r"\s+-\s+([A-Za-z0-9._:-]+)\s*$", line)
        if m2 and not m2.group(1).startswith(("ollama", "serve", "set", "if", "for", "echo")):
            models.append(m2.group(1))
    return models


# ── 4 db-patch 校验（先只读对账；真有新补丁才跑迁移）──────────────────────────
def step4_db_patch(env: dict) -> None:
    host = sorted(p.name for p in PATCH_DIR.glob("*.sql"))
    rc, out = compose("exec", "-T", "postgres", "psql", "-U", env.get("PG_USER", "nexus"),
                      "-d", env.get("PG_DB", "nexus"), "-tAc", "SELECT count(*) FROM t_db_patch", timeout=60)
    applied_n = out.strip().splitlines()[0] if rc == 0 and out.strip() else ""
    fresh_db = False
    if not applied_n.isdigit():
        if PROFILE.lenient:
            # 测试档：全新库还没有 t_db_patch（迁移表由第一次迁移自举）⇒ 不是失败，直接去迁移。
            # 不降级的话，CI 的**首次运行**会被这条判据自己挡住（开发档不会遇到：库早就迁移过）。
            fresh_db = True
            R.add("4 db-patch 校验", "INFO", "t_db_patch 尚不存在（全新库）⇒ 直接执行迁移")
        else:
            R.add("4 db-patch 校验", "FAIL", "查不到 t_db_patch（迁移表未建？）", out.strip()[-300:])
            return
    if not fresh_db and int(applied_n) == len(host):
        R.add("4 db-patch 校验", "PASS", f"宿主 {len(host)} 个 = 已应用 {applied_n} 个（无新补丁，跳过迁移）")
        return
    # 有差额 ⇒ 真的需要迁移（迁移本身会做 checksum/乱序/篡改校验，失败即中止）
    rc, out = builder("db-patch-migrate", timeout=600)
    if rc != 0:
        die("4 db-patch 校验", "db-patch-migrate 失败（SQL 错误 / 历史补丁被篡改 / 乱序）", out.strip()[-800:])
    scanned = sum(int(x) for x in re.findall(r"扫描到\s+(\d+)\s+个", out))
    m = re.search(r"本次应用\s+(\d+)\s+个[，,]\s*跳过\s+(\d+)\s+个", out)
    if not m:
        die("4 db-patch 校验", "迁移跑完了但没解析到统计行（输出格式变了？）", out.strip()[-500:])
    a, s = int(m.group(1)), int(m.group(2))
    if scanned != len(host) or a + s != len(host):
        R.add("4 db-patch 校验", "FAIL",
              f"补丁数对不上：宿主 {len(host)} / 容器扫到 {scanned} / 应用+跳过 {a + s}",
              "宿主有而容器没扫到的补丁 ⇒ 它**没 push/没 merge**（容器只能看到远端）")
    else:
        R.add("4 db-patch 校验", "PASS", f"扫描 {scanned} / 应用 {a} / 跳过 {s}（与宿主 {len(host)} 一致）")


# ── 5 打包 ───────────────────────────────────────────────────────────────────
def step5_build(skip: bool) -> None:
    if skip:
        R.add("5 打包", "SKIP", "按 --no-build 跳过（沿用上次产物）")
        return
    rc, out = builder("build-all", timeout=900)
    if rc != 0:
        tail = "\n".join(out.strip().splitlines()[-12:])
        die("5 打包", "build-all 失败（后端 mvn 或前端 npm 出错）", tail)
    # 产物大小取自 build-all 末尾那行 `ls -lh .../app.jar`，形状是
    #   `<权限> <链接数> <属主> <属组> <大小> <月> <日> <时:分> <路径>`
    # —— 大小与路径**中间隔着一个时间戳**，所以"大小紧挨着文件名"的正则永不匹配：
    #    首跑就是这么显示成 `app.jar ?` 的（根因同上：判据没对着真源实测）。
    # 改判"在**以 app.jar 结尾的那一行**里找带 K/M/G 后缀的尺寸列"，对 ls 的列序不敏感。
    jar_size = ""
    for ln in out.splitlines():
        if ln.rstrip().endswith("app.jar"):
            m = re.search(r"([\d.]+[KMG])\b", ln)
            jar_size = m.group(1) if m else ""
            break
    files = re.search(r"frontend:\s*(\d+)\s*个文件", out)
    ok_be = "BUILD SUCCESS" in out or "产物已就绪" in out
    if not ok_be:
        die("5 打包", "后端未见成功标记（BUILD SUCCESS）", out.strip()[-500:])
    R.add("5 打包", "PASS",
          f"后端 app.jar {jar_size or '?'} / 前端 {files.group(1) if files else '?'} 个文件")


# ── 6 容器：按需 rebuild / 重启后端 / 拉起 ───────────────────────────────────
def step6_containers(cfg: dict, skip_build: bool) -> None:
    need = cfg.get("rebuild") or []
    if need and not skip_build:
        rc, out = compose("build", *need, timeout=900)
        if rc != 0:
            die("6 容器", f"镜像 rebuild 失败：{' '.join(need)}", out.strip()[-600:])
    if need:
        rc, out = compose("up", "-d", *need, timeout=300)
        if rc != 0:
            die("6 容器", f"重建容器失败：{' '.join(need)}", out.strip()[-500:])
        R.add("6 容器", "PASS", f"已 rebuild 并重建：{' '.join(need)}（按规则不做校验）")
    else:
        R.add("6 容器", "PASS", "无需 rebuild（烘进镜像的文件未变；dist 走卷挂载，重启即可生效）")

    if PROFILE.cold_start:
        # 测试档（CI）：栈每次可能是冷的 / 半冷的 —— 本步把**全栈**拉起来（基础设施 + Metabase +
        # 报告 Nginx + 应用）。builder 带 profiles: ["build"]，裸 up 不会把它算进来（它已在 step1 起好）。
        # —— 5.1 的缺口补齐：开发档里栈由 up.sh 常驻，测试档没人管，必须由本脚本负责。
        was_running = compose_ps_status().get(PROFILE.backend_container, ("", ""))[0] == "running"
        rc, out = compose("up", "-d", timeout=600)
        if rc != 0:
            die("6 容器", "测试档 docker compose up -d（全栈）失败", out.strip()[-500:])
        R.add("6 容器", "PASS", "测试档全栈已 up -d（基础设施 / Metabase / 报告 Nginx / 应用一并纳入）")
        if was_running:
            # ⚠️ 热环境：容器已在跑，up -d 对它是 no-op ⇒ 必须 restart 才吃到本次新 jar。
            #    restart 用**服务名**（BACKEND_SERVICE），不是容器名（nexus-test-backend）——
            #    两者在测试档不同名，写混会得到 "no such service"。
            rc, out = compose("restart", BACKEND_SERVICE, timeout=180)
            if rc != 0:
                die("6 容器", "重启后端失败（热环境：加载本次新 jar）", out.strip()[-400:])
            R.add("6 容器", "PASS", "后端已重启（热环境：加载本次新 jar）")
        else:
            R.add("6 容器", "PASS", "冷启动：后端由 up -d 直接以本次新 jar 启动，无需 restart")
    else:
        # 应用容器：up -d 应用配置变更（不变则是 no-op），restart 让后端加载新 jar
        rc, out = compose("up", "-d", "nexus-backend", "nexus-frontend", timeout=300)
        if rc != 0:
            die("6 容器", "docker compose up -d 应用容器失败", out.strip()[-500:])
        rc, out = compose("restart", "nexus-backend", timeout=180)
        if rc != 0:
            die("6 容器", "重启 nexus-backend 失败", out.strip()[-400:])
        R.add("6 容器", "PASS", "nexus-backend 已重启（加载新 jar）；nexus-frontend 不需要重启")


# ── 7 容器 health ────────────────────────────────────────────────────────────
def step7_health(timeout_s: int = 180) -> None:
    exp = PROFILE.expected_healthy
    start = time.time()
    last = ""
    while time.time() - start < timeout_s:
        status = compose_ps_status()
        bad = [c for c in exp if c not in status or status[c][0] != "running"
               or status[c][1] not in ("", "healthy")]
        last = " / ".join(f"{c}={describe(status.get(c))}" for c in exp)
        if not bad:
            R.add("7 容器 health", "PASS", f"{len(exp)}/{len(exp)} running+healthy")
            break
        time.sleep(3)
    else:
        R.add("7 容器 health", "FAIL", f"未在 {timeout_s}s 内全部 healthy", last +
              "\n证据：docker compose -f <档位 compose> --env-file <档位 env> ps -a；"
              "日志：同上加 logs --tail 50 <容器>")
        return
    if PROFILE.nonblocking_healthy:
        # 非阻断项（测试档：Metabase / 报告 Nginx）—— 报告链路归 5.5，起得慢不该让部署红（设计 05 §4.1-⑤）
        status = compose_ps_status()
        bad2 = [c for c in PROFILE.nonblocking_healthy
                if c not in status or status[c][0] != "running" or status[c][1] not in ("", "healthy")]
        detail = " / ".join(f"{c}={describe(status.get(c))}" for c in PROFILE.nonblocking_healthy)
        if bad2:
            R.add("7 容器 health", "WARN", f"非阻断容器未就绪：{' '.join(bad2)}", detail)
        else:
            R.add("7 容器 health", "PASS", f"非阻断容器也已就绪：{' '.join(PROFILE.nonblocking_healthy)}")


# ── 8 冒烟 ───────────────────────────────────────────────────────────────────
def step8_smoke(env: dict) -> None:
    fp, bp = env["frontend_port"], env["backend_port"]
    # 地址一律 127.0.0.1，不用 localhost —— 与 probe.sh 的 NEXUS_PROBE_HOST 同一条判据：
    # WSL 内 localhost 可能先解析到 ::1，而 docker 发布的端口未必监听 IPv6（本机 2026-09-23 是
    # 双栈发布、两种写法都通，但判据不该押在 daemon 的 ipv6 配置上）⇒ 固定 127.0.0.1 结果确定。
    base = "http://127.0.0.1"
    # 8.1 业务健康（三项依赖）+ 反代链路
    for label, url in (("经 nginx", f"{base}:{fp}/api/health"),
                       ("直连后端", f"{base}:{bp}/api/health")):
        code, body = http(url, timeout=20)
        if code == 200 and '"code":0' in body.replace(" ", ""):
            checks = re.search(r'"checks":\s*\{([^}]*)\}', body)
            R.add("8 冒烟 /api/health", "PASS", f"{label} 200 UP" +
                  (f"（{checks.group(1)[:70].strip()}）" if checks else ""))
            break
    else:
        R.add("8 冒烟 /api/health", "FAIL", "两个端口都没拿到 200 + code=0",
              "直连后端也失败 ⇒ 后端没起或依赖 DOWN："
              "docker compose -f <档位 compose> --env-file <档位 env> logs --tail 100 nexus-backend")
    # 8.2 登录接口（账号来自档位 / --login-user / --login-password；默认 admin/admin123 不变）
    code, body = http(f"{base}:{fp}/api/auth/login", "POST",
                      {"username": PROFILE.login_user, "password": PROFILE.login_password}, timeout=20)
    if code == 200 and '"code":0' in body.replace(" ", ""):
        tok = re.search(r'"token"\s*:\s*"([^"]{8,})"', body)
        R.add("8 冒烟 登录", "PASS",
              f"POST /api/auth/login 200 code=0（用户 {PROFILE.login_user}；token 已获取，长度 {len(tok.group(1)) if tok else '?'}）")
    else:
        R.add("8 冒烟 登录", "FAIL", f"登录返回 HTTP {code}",
              body.strip()[:300] + f"\n（{PROFILE.login_user} 的口令是否被改？种子数据是否还在？）")
    # 8.3 前端首页 + 首页引用的资源（证明 nginx root 指向的是**新** dist）
    code, html = http(f"{base}:{fp}/", timeout=20)
    if code != 200:
        R.add("8 冒烟 前端", "FAIL", f"GET / 返回 HTTP {code}", "前端容器/nginx 未就绪")
        return
    m = re.search(r'(?:src|href)="(/assets/[^"]+\.js)"', html)
    if not m:
        R.add("8 冒烟 前端", "WARN", "首页 200，但没解析到 assets/*.js（构建形态变了？）")
        return
    asset = m.group(1)
    code2, _ = http(f"{base}:{fp}{asset}", timeout=20)
    if code2 == 200:
        R.add("8 冒烟 前端", "PASS", f"首页 200，资源 {asset} 200（served 的是最新 dist）")
    else:
        R.add("8 冒烟 前端", "FAIL", f"首页 200 但 {asset} 返回 {code2}",
              "典型症状：index.html 是新的、assets 没跟上（dist 替换不完整）")


# ── main ─────────────────────────────────────────────────────────────────────
def main(argv: list[str] | None = None) -> int:
    global PROFILE, SUMMARY_JSON, REF_ARG
    ap = argparse.ArgumentParser(description="nexus 一键部署（一次跑完、一张表、零提示）")
    ap.add_argument("--profile", choices=sorted(PROFILES), default="dev",
                    help="档位：dev=开发环境（默认，行为与此前一致）/ test=测试环境（设计 05 §4）")
    ap.add_argument("--no-build", action="store_true", help="跳过打包（沿用上次产物）")
    ap.add_argument("--rebuild", action="store_true", help="强制 rebuild 运行镜像")
    ap.add_argument("--expect-sha", default=None, help="断言 git-sync 拉到的提交前缀（防'没 push 就部署'）")
    ap.add_argument("--ref", default=None,
                    help="git-sync 的 refspec（CI 拉 PR head 用：refs/pull/<N>/head；省略=分支模式）")
    ap.add_argument("--login-user", default=None, help="冒烟登录用户（默认取档位值 admin）")
    ap.add_argument("--login-password", default=None, help="冒烟登录口令（默认取档位值；日志不打明文）")
    ap.add_argument("--summary-json", default=None, help="把机器可读摘要写到该路径（CI 档读它）")
    ap.add_argument("--ci", action="store_true", help="CI 档：关颜色（失败即非 0 退出本就成立，见 SKILL.md）")
    ap.add_argument("--no-color", action="store_true", help="关闭 ANSI 颜色（日志重定向时更干净）")
    args = ap.parse_args(argv)

    PROFILE = PROFILES[args.profile]
    if args.login_user or args.login_password:
        PROFILE = replace(PROFILE,
                          login_user=args.login_user or PROFILE.login_user,
                          login_password=args.login_password or PROFILE.login_password)
    REF_ARG = args.ref
    if args.summary_json:
        SUMMARY_JSON = Path(args.summary_json).expanduser()
    if args.ci or args.no_color:
        disable_colors()

    print("=" * 78)
    print(f" nexus 部署（{PROFILE.name} 档）  {time.strftime('%Y-%m-%d %H:%M:%S')}   仓库 {REPO}")
    print(f" compose={PROFILE.compose_file.name} + env={PROFILE.env_file.name}"
          + (f"   ref={REF_ARG}" if REF_ARG else ""))
    print("=" * 78)
    if PROFILE.warn_missing_env_local and not ENV_LOCAL.exists():
        R.add("0 环境", "WARN", ".env.local 缺失 ⇒ DEEPSEEK_API_KEY 未注入（云端模型那一半不可用）")

    guard_profile()
    step1_preflight()
    step2_git_sync(args.expect_sha, args.ref)
    cfg = step3_config(args.rebuild, args.ref)
    step4_db_patch(read_env(PROFILE.env_file))
    step5_build(args.no_build)
    step6_containers(cfg, args.no_build)
    step7_health()
    step8_smoke(cfg)

    summary_print()
    write_summary_json()
    return 1 if R.failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[deploy] 被中断")
        sys.exit(130)

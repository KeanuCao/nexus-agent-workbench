#!/usr/bin/env python3
# =============================================================================
# deploy.py —— nexus 一键部署（阶段交付后的标准动作）
#
# 设计目标（2026-09-23 定）：**一次跑完、一张表、零提示**。
#   一次部署 ≤ 1 分钟（缓存热时）、交互 ≤ 3 次、部署期间不派活、失败即停等人。
#
# 执行位置：**WSL 发行版内**（docker 在其中；脚本直接调 docker，不经双层 shell）
#   wsl -d nexus-agent-workbench -- python3 /mnt/c/wp/nexus-agent-workbench/.claude/skills/deploy/deploy.py
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
from dataclasses import dataclass, field
from pathlib import Path

# ── 路径（脚本位置即真源：<repo>/.claude/skills/deploy/deploy.py）─────────────
REPO = Path(__file__).resolve().parents[3]
COMPOSE_DIR = REPO / "docker-compose"
ENV_FILE = COMPOSE_DIR / ".env"
ENV_LOCAL = COMPOSE_DIR / ".env.local"
COMPOSE_FILE = COMPOSE_DIR / "docker-compose.yml"
PATCH_DIR = REPO / "db-patch"
STATE_FILE = REPO / ".tmp" / "deploy-state.json"

EXPECTED_HEALTHY = ["nexus-postgres", "nexus-redis", "nexus-ollama", "nexus-backend", "nexus-frontend"]
# 烘进镜像的文件：内容一变 ⇒ 必须 rebuild 镜像（dist 走卷挂载，不在其中）
# 注意不含 builder：它是手工启停的构建容器，其 Dockerfile 变更由 up.sh 第 2 步处理
BAKED_FILES = {
    "nexus-frontend": ["docker-compose/frontend/Dockerfile", "docker-compose/frontend/nginx.conf"],
    "nexus-backend": ["docker-compose/backend/Dockerfile"],
}

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


# ── 结果收集 ─────────────────────────────────────────────────────────────────
@dataclass
class Rows:
    """每步一行；status ∈ PASS / FAIL / WARN / INFO / SKIP"""
    rows: list[tuple[str, str, str, str]] = field(default_factory=list)  # (step, status, 摘要, 细节)

    def add(self, step: str, status: str, summary: str, detail: str = "") -> None:
        self.rows.append((step, status, summary, detail))
        mark = {"PASS": f"{GREEN}[PASS]{RESET}", "FAIL": f"{RED}[FAIL]{RESET}",
                "WARN": f"{YELLOW}[WARN]{RESET}", "INFO": f"{DIM}[INFO]{RESET}",
                "SKIP": f"{DIM}[SKIP]{RESET}"}[status]
        line = f"{mark} {step:<22} {summary}"
        print(line, flush=True)
        if detail and status in ("FAIL", "WARN"):
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
    sys.exit(1)


# ── 命令执行 ─────────────────────────────────────────────────────────────────
def run(args: list[str], timeout: int = 120, cwd: Path | None = None) -> tuple[int, str]:
    """跑一条命令，返回 (returncode, 合并后的输出)。永不抛异常。"""
    try:
        p = subprocess.run(args, cwd=str(cwd or COMPOSE_DIR), capture_output=True,
                           text=True, timeout=timeout, errors="replace")
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, f"[deploy] 超时（{timeout}s）：{' '.join(args)}"
    except FileNotFoundError as exc:
        return 127, f"[deploy] 命令不存在：{exc}"
    except Exception as exc:  # noqa: BLE001 —— 兜底，任何异常都变成一次 FAIL 而不是崩栈
        return 1, f"[deploy] 执行异常：{exc!r}"


def compose(*args: str, timeout: int = 120) -> tuple[int, str]:
    return run(["docker", "compose", *args], timeout=timeout)


def builder(script: str, timeout: int = 300) -> tuple[int, str]:
    return compose("exec", "-T", "builder", script, timeout=timeout)


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


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def save_state(state: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")


# ── 输出 ─────────────────────────────────────────────────────────────────────
T0 = time.time()


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


# ── 1 前置 ───────────────────────────────────────────────────────────────────
def step1_preflight() -> None:
    rc, out = run(["docker", "version", "--format", "{{.Server.Version}}"], cwd=REPO)
    if rc != 0:
        die("1 前置", "docker 不可用（WSL 里 docker 起了吗）", out.strip()[:400])
    if not COMPOSE_FILE.exists():
        die("1 前置", f"compose 文件不存在：{COMPOSE_FILE}")
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
            die("1 前置", "nexus-builder 未运行且拉起失败", out.strip()[-500:])
        R.add("1 前置", "PASS", "nexus-builder 已拉起（它是打包/迁移的执行者）")
    else:
        R.add("1 前置", "PASS", f"docker OK / builder running / {len(services)} 个容器在册")


# ── 2 拉取最新代码 ───────────────────────────────────────────────────────────
def step2_git_sync(expect_sha: str | None) -> dict:
    rc, out = builder("git-sync", timeout=180)
    if rc != 0:
        die("2 拉取最新代码", "git-sync 失败（容器内拉不到远端？分支名对不对？）", out.strip()[-600:])
    sha = branch = ""
    for line in out.splitlines():
        m = re.match(r"\s+([0-9a-f]{7,})\s+\d{4}-\d{2}-\d{2}\s+(.*)$", line)
        if m and not sha:
            sha, subject = m.group(1), m.group(2)
        m2 = re.search(r"\[git-sync\] 分支\s*:\s*(\S+)", line)
        if m2:
            branch = m2.group(1)
    info = {"sha": sha, "branch": branch, "subject": subject if sha else ""}
    if not sha:
        die("2 拉取最新代码", "git-sync 成功但没解析到提交（输出格式变了？）", out.strip()[-500:])
    if expect_sha and not (sha.startswith(expect_sha) or expect_sha.startswith(sha)):
        die("2 拉取最新代码", f"拉到 {sha}，与期望 {expect_sha} 不一致",
            "说明【要部署的提交还没 push/merge 到远端分支】——容器只能拿到已 push 的提交")
    R.add("2 拉取最新代码", "PASS", f"{sha}  {info['subject'][:38]}",
          "⚠️ 请人工核对：这个 SHA 就是你刚 merge 的那个吗？")
    return info


# ── 3 配置快照与一致性（含"是否需要 rebuild 镜像"）────────────────────────────
def step3_config(force_rebuild: bool) -> dict:
    cfg = read_env(ENV_FILE)
    backend_port = cfg.get("BACKEND_PORT", "8089")
    frontend_port = cfg.get("FRONTEND_PORT", "8088")

    # 3.1 分支真源链：容器 env vs .env（**容器 env 才是构建真源**：改 .env 后要 up -d builder 才生效）
    rc, out = compose("exec", "-T", "builder", "printenv", "NEXUS_REPO_BRANCH", timeout=60)
    container_branch = out.strip().splitlines()[0] if rc == 0 and out.strip() else "(未知)"
    if container_branch != cfg.get("NEXUS_REPO_BRANCH", "main"):
        R.add("3 配置一致性", "FAIL",
              f"分支不一致：容器={container_branch} / .env={cfg.get('NEXUS_REPO_BRANCH')}",
              "容器 env 才是构建真源；改 .env 后要 up -d builder 重建容器才生效")
    else:
        R.add("3 配置一致性", "PASS", f"NEXUS_REPO_BRANCH={container_branch}（容器 env 与 .env 一致）")

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
    elif missing:
        R.add("3 配置一致性", "FAIL", f"模型缺失：{' '.join(missing)}",
              "补拉：for m in <名单>; do docker compose exec -T ollama ollama pull $m; done")
    else:
        R.add("3 配置一致性", "PASS", f"模型齐备：{' '.join(expected)}")

    # 3.3 nginx 请求体上限（>1MB 上传的必需项；缺失 ⇒ 413 而响应体不是 Result，极难猜）
    #     判据取【运行中容器内那份】而非宿主源码 —— 它才是真正生效的那份
    if "client_max_body_size" in COMPOSE_DIR.joinpath("frontend/nginx.conf").read_text(encoding="utf-8"):
        rc, out = compose("exec", "-T", "nexus-frontend", "grep", "-c", "client_max_body_size",
                          "/etc/nginx/conf.d/default.conf", timeout=60)
        if rc == 0 and out.strip().endswith("1"):
            R.add("3 配置一致性", "PASS", "nginx client_max_body_size 已生效（容器内那份）")
        else:
            R.add("3 配置一致性", "WARN", "容器内 nginx.conf 未见 client_max_body_size",
                  "宿主源码里有 ⇒ 镜像里的是旧的 ⇒ 本次将触发前端镜像 rebuild")
    else:
        R.add("3 配置一致性", "FAIL", "宿主 nginx.conf 缺 client_max_body_size（>1MB 上传会 413）")

    # 3.4 向量维度三方对账：代码常量 vs DB 列（模型清单已在上面对过）
    dim_code = None
    for p in (REPO / "backend").rglob("OllamaEmbeddingService.java"):
        m = re.search(r"EXPECTED_DIMENSION\s*=\s*(\d+)", p.read_text(encoding="utf-8", errors="replace"))
        if m:
            dim_code = m.group(1)
            break
    rc, out = compose("exec", "-T", "postgres", "psql", "-U", cfg.get("PG_USER", "nexus"),
                      "-d", cfg.get("PG_DB", "nexus"), "-tAc",
                      "SELECT atttypmod-4 FROM pg_attribute WHERE attrelid='t_kb_chunk'::regclass AND attname='embedding'",
                      timeout=60)
    dim_db = out.strip().splitlines()[0] if rc == 0 and out.strip() else ""
    if dim_code and dim_db and dim_code != dim_db:
        R.add("3 配置一致性", "FAIL", f"向量维度不一致：代码={dim_code} / DB={dim_db}",
              "这是「换模型只改一处」的经典故障：两者必须同时改（另见 db-patch 里的维度补丁）")
    elif dim_code and dim_db:
        R.add("3 配置一致性", "PASS", f"向量维度一致：代码={dim_code} / DB 列={dim_db}")
    else:
        R.add("3 配置一致性", "WARN", f"向量维度未对账（代码={dim_code or '?'} / DB={dim_db or '表未建'}）")

    # 3.5 镜像 rebuild 判定（烘进镜像的文件内容变了才需要；dist 走卷挂载不需要）
    state = load_state()
    baked = state.get("baked", {})
    need: list[str] = []
    for svc, files in BAKED_FILES.items():
        cur = {f: sha256(REPO / f) for f in files if (REPO / f).exists()}
        if force_rebuild or (f in baked and baked.get(f) != cur.get(f)) or (baked and f not in baked):
            need.append(svc)
        elif not baked:
            need.append(svc)  # 首次运行：无基线，保守 rebuild 一次
        state.setdefault("baked", {}).update(cur)
    R.add("3 镜像判定", "INFO",
          f"需要 rebuild：{' '.join(sorted(set(need))) or '无'}" + ("（--rebuild 强制）" if force_rebuild else ""))
    save_state(state)
    return {"backend_port": backend_port, "frontend_port": frontend_port, "rebuild": sorted(set(need))}


def parse_expected_models() -> list[str]:
    """
    真源：docker-compose.yml 的 ollama-init 服务里的模型清单（probe.sh 的 nexus_expected_models 读的就是它）。

    ⚠️ 当前形态是 shell 内联循环 `for model in qwen2.5:7b bge-m3; do`，**不是 YAML 列表项** ——
    按 YAML 列表去解析会一个都找不到、然后误报"模型清单未能对账"。两种形态都兜住。
    """
    text = COMPOSE_FILE.read_text(encoding="utf-8", errors="replace")
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
    if not applied_n.isdigit():
        R.add("4 db-patch 校验", "FAIL", "查不到 t_db_patch（迁移表未建？）", out.strip()[-300:])
        return
    if int(applied_n) == len(host):
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
    jar = re.search(r"([\d.]+[KMG])\s+\S*app\.jar", out)
    files = re.search(r"frontend:\s*(\d+)\s*个文件", out)
    ok_be = "BUILD SUCCESS" in out or "产物已就绪" in out
    if not ok_be:
        die("5 打包", "后端未见成功标记（BUILD SUCCESS）", out.strip()[-500:])
    R.add("5 打包", "PASS",
          f"后端 app.jar {jar.group(1) if jar else '?'} / 前端 {files.group(1) if files else '?'} 个文件")


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
    start = time.time()
    last = ""
    while time.time() - start < timeout_s:
        rc, out = compose("ps", "--format", "json")
        status = {}
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("{"):
                try:
                    rec = json.loads(line)
                    status[rec.get("Name", "")] = (rec.get("State", ""), rec.get("Health", ""))
                except json.JSONDecodeError:
                    pass
        bad = [c for c in EXPECTED_HEALTHY if c not in status or status[c][0] != "running"
               or status[c][1] not in ("", "healthy")]
        last = " / ".join(f"{c}={describe(status.get(c))}" for c in EXPECTED_HEALTHY)
        if not bad:
            R.add("7 容器 health", "PASS", f"{len(EXPECTED_HEALTHY)}/{len(EXPECTED_HEALTHY)} running+healthy")
            return
        time.sleep(3)
    R.add("7 容器 health", "FAIL", f"未在 {timeout_s}s 内全部 healthy", last +
          "\n证据：docker compose ps -a；日志：docker compose logs --tail 50 <容器>")


# ── 8 冒烟 ───────────────────────────────────────────────────────────────────
def step8_smoke(env: dict) -> None:
    fp, bp = env["frontend_port"], env["backend_port"]
    # 8.1 业务健康（三项依赖）+ 反代链路
    for label, url in (("经 nginx", f"http://localhost:{fp}/api/health"),
                       ("直连后端", f"http://localhost:{bp}/api/health")):
        code, body = http(url, timeout=20)
        if code == 200 and '"code":0' in body.replace(" ", ""):
            checks = re.search(r'"checks":\s*\{([^}]*)\}', body)
            R.add("8 冒烟 /api/health", "PASS", f"{label} 200 UP" +
                  (f"（{checks.group(1)[:70].strip()}）" if checks else ""))
            break
    else:
        R.add("8 冒烟 /api/health", "FAIL", "两个端口都没拿到 200 + code=0",
              "直连后端也失败 ⇒ 后端没起或依赖 DOWN：docker compose logs --tail 100 nexus-backend")
    # 8.2 登录接口
    code, body = http(f"http://localhost:{fp}/api/auth/login", "POST",
                      {"username": "admin", "password": "admin123"}, timeout=20)
    if code == 200 and '"code":0' in body.replace(" ", ""):
        tok = re.search(r'"token"\s*:\s*"([^"]{8,})"', body)
        R.add("8 冒烟 登录", "PASS", f"POST /api/auth/login 200 code=0（token 已获取，长度 {len(tok.group(1)) if tok else '?'}）")
    else:
        R.add("8 冒烟 登录", "FAIL", f"登录返回 HTTP {code}",
              body.strip()[:300] + "\n（admin/admin123 是否被改？种子数据是否还在？）")
    # 8.3 前端首页 + 首页引用的资源（证明 nginx root 指向的是**新** dist）
    code, html = http(f"http://localhost:{fp}/", timeout=20)
    if code != 200:
        R.add("8 冒烟 前端", "FAIL", f"GET / 返回 HTTP {code}", "前端容器/nginx 未就绪")
        return
    m = re.search(r'(?:src|href)="(/assets/[^"]+\.js)"', html)
    if not m:
        R.add("8 冒烟 前端", "WARN", "首页 200，但没解析到 assets/*.js（构建形态变了？）")
        return
    asset = m.group(1)
    code2, _ = http(f"http://localhost:{fp}{asset}", timeout=20)
    if code2 == 200:
        R.add("8 冒烟 前端", "PASS", f"首页 200，资源 {asset} 200（served 的是最新 dist）")
    else:
        R.add("8 冒烟 前端", "FAIL", f"首页 200 但 {asset} 返回 {code2}",
              "典型症状：index.html 是新的、assets 没跟上（dist 替换不完整）")


# ── main ─────────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description="nexus 一键部署（一次跑完、一张表、零提示）")
    ap.add_argument("--no-build", action="store_true", help="跳过打包（沿用上次产物）")
    ap.add_argument("--rebuild", action="store_true", help="强制 rebuild 运行镜像")
    ap.add_argument("--expect-sha", default=None, help="断言 git-sync 拉到的提交前缀（防'没 push 就部署'）")
    args = ap.parse_args()

    print("=" * 78)
    print(f" nexus 部署  {time.strftime('%Y-%m-%d %H:%M:%S')}   仓库 {REPO}")
    print("=" * 78)
    if not ENV_LOCAL.exists():
        R.add("0 环境", "WARN", ".env.local 缺失 ⇒ DEEPSEEK_API_KEY 未注入（云端模型那一半不可用）")

    step1_preflight()
    step2_git_sync(args.expect_sha)
    cfg = step3_config(args.rebuild)
    step4_db_patch(read_env(ENV_FILE))
    step5_build(args.no_build)
    step6_containers(cfg, args.no_build)
    step7_health()
    step8_smoke(cfg)

    summary_print()
    return 1 if R.failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[deploy] 被中断")
        sys.exit(130)

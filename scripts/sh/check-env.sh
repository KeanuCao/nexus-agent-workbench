#!/usr/bin/env bash
# =============================================================================
# scripts/sh/check-env.sh —— 启动前置自检（在环境"起来之前"执行；任一 [FAIL] → up.sh 中止）
#
# 执行位置：WSL 发行版 nexus-agent-workbench 内（Windows 侧经 wsl -d ... 触发）
#           cd /mnt/c/wp/nexus-agent-workbench && ./scripts/sh/check-env.sh
#
# 语义边界（**本脚本只做前置检查，不做运行期巡检**）：
#   前置检查问的是"这台机器**能不能**把环境拉起来"，因此每一条 FAIL 都必须在
#   "一个容器都没起"的干净机器上可复现；反过来，"容器还没起"是**完全正常的场景**，
#   绝不能在这里判 FAIL —— 这正是本项检查里"模型就绪只判 WARN"的原因（见第 8 项）。
#
# 检查项（8 项 + 1 个凭据子项，判据来源见下）：
#   0  必备工具（bash/curl/docker/compose）      1  Docker daemon
#   2  compose 插件                              3  WSL 环境
#   4  端口占用（WSL 侧 + Windows 侧双侧）       4b .env 凭据耦合（本项目特有的静默陷阱）
#   5  镜像加速前缀（docker.m.daocloud.io）      6  compose 配置合法性
#   7  资源（可用内存）                          8  模型就绪（**只判 WARN**）
#
# 判据来源（唯一事实源，别从 docs/drafts/环境检查脚本.md 抄 —— 该草稿有 8 处判据已核实有误）：
#   docs/api/README.md §4            —— 两个探活口分工、模型就绪独立判据、避坑清单
#   docs/task/task.1+环境准备.md     —— 0.3.1 验收标准（8 项逐条订正版）
#   docker-compose/.env + docker-compose.yml —— 端口/凭据的唯一真源
#
# 输出契约：逐项 [PASS]/[WARN]/[FAIL]；存在 FAIL → 退出码 1（up.sh 据此中止），否则 0。
#
# 明确不做（防止后续按草稿重新生成这些错误检查项）：
#   · 不查 pg_extension（本库从未 CREATE EXTENSION，现阶段必然 FAIL；可用 ≠ 已启用）
#   · 不查 t_db_patch / 业务表 tenant_id（补丁迁移的成败由 up.sh 第 5 步的退出码判定；
#     多租户属阶段1。本脚本是**启动前置门禁**，只查"环境能不能开始工作"）
#   · 不做 GPU / nvidia-smi 检查（已定 CPU 推理）
#   · 不调 /api/ai/ping（阶段2 才有该接口）
#   · 不提示修改 /etc/docker/daemon.json（镜像加速走 Dockerfile/compose 前缀，用户规则）
#   · 不给 nexus-frontend 补 healthcheck（compose 里已有）
# =============================================================================

# 刻意不启用 set -e：本脚本要"跑完全部检查项再汇总"，任何单点失败都不得中断流程；
# 这里的每个非零返回都是**预期的信息**，不是致命错误。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/probe.sh
. "${SCRIPT_DIR}/lib/probe.sh"

nexus_load_env

# ── 计数与输出 ───────────────────────────────────────────────────────────────
PASS_N=0; WARN_N=0; FAIL_N=0
FAIL_ITEMS=()

_pass() { PASS_N=$((PASS_N + 1)); printf '[PASS] %s\n' "$*"; }
_warn() { WARN_N=$((WARN_N + 1)); printf '[WARN] %s\n' "$*"; }
_info() { printf '       %s\n' "$*"; }
_fail() {
    FAIL_N=$((FAIL_N + 1)); FAIL_ITEMS+=("$*")
    printf '[FAIL] %s\n' "$*"
}

printf '%s\n' "=============================================================================="
printf '%s\n' " nexus 环境前置自检（check-env.sh）"
printf '%s\n' " 判据来源：docs/api/README.md §4 + docs/task/task.1+环境准备.md 0.3.1"
printf '%s\n' " 输出契约：[PASS]/[WARN] 不阻断；存在 [FAIL] 则退出码非 0（up.sh 会中止）"
printf '%s\n' "=============================================================================="
printf ' 仓库根   : %s\n' "$NEXUS_REPO_ROOT"
printf ' compose  : %s\n' "$NEXUS_COMPOSE_FILE"
printf ' .env     : %s\n' "$NEXUS_ENV_FILE"
printf ' 宿主端口 : backend=%s frontend=%s pg=%s redis=%s ollama=%s（取自 .env）\n' \
    "$NEXUS_BACKEND_PORT" "$NEXUS_FRONTEND_PORT" "$NEXUS_PG_PORT" "$NEXUS_REDIS_PORT" "$NEXUS_OLLAMA_PORT"
printf '%s\n' "------------------------------------------------------------------------------"

# ── 前置：仓库关键文件是否存在（缺了后面每一项都没意义）─────────────────────
if [ ! -f "$NEXUS_COMPOSE_FILE" ]; then
    _fail "前置 项目文件：找不到 $NEXUS_COMPOSE_FILE"
    _info "→ 本脚本必须放在 <仓库>/scripts/ 下执行（它按相对路径定位 compose 目录）"
    printf '\n合计：PASS %d / WARN %d / FAIL %d\n' "$PASS_N" "$WARN_N" "$FAIL_N"
    exit 1
fi
if [ ! -f "$NEXUS_ENV_FILE" ]; then
    # .env 缺失不致命（compose 各引用处都有 :- 默认值），但端口/凭据会退回默认值，
    # 与"你以为改过的值"不一致 —— 列为 WARN 并明确告知。
    _warn "前置 .env：找不到 $NEXUS_ENV_FILE，端口/凭据将退回 compose 内置默认值"
    _info "→ 仓库应自带 docker-compose/.env（dev 默认值，随仓库提交）"
fi

# ── 0 必备工具 ───────────────────────────────────────────────────────────────
TOOLS_NOTE="bash ${BASH_VERSION%%(*}"
if nexus_has_curl; then TOOLS_NOTE="${TOOLS_NOTE} / curl OK"; else TOOLS_NOTE="${TOOLS_NOTE} / curl 缺失"; fi
if nexus_has_jq; then   TOOLS_NOTE="${TOOLS_NOTE} / jq OK";    else TOOLS_NOTE="${TOOLS_NOTE} / jq 缺失(走 sed 兜底解析)"; fi
if nexus_has_ss; then   TOOLS_NOTE="${TOOLS_NOTE} / ss OK";    else TOOLS_NOTE="${TOOLS_NOTE} / ss 缺失(端口检查降级)"; fi
if command -v docker >/dev/null 2>&1; then TOOLS_NOTE="${TOOLS_NOTE} / docker OK"; else TOOLS_NOTE="${TOOLS_NOTE} / docker 缺失"; fi
if nexus_has_curl; then
    _pass "0 必备工具：${TOOLS_NOTE}"
else
    _fail "0 必备工具：curl 缺失（健康/模型探活全部依赖它）"
    _info "→ WSL 内安装：sudo apt-get update && sudo apt-get install -y curl"
fi

# ── 1 Docker daemon ──────────────────────────────────────────────────────────
DAEMON_OK="no"
if ! command -v docker >/dev/null 2>&1; then
    _fail "1 Docker daemon：找不到 docker 命令"
    _info "→ 确认是在 WSL 发行版 nexus-agent-workbench 内执行："
    _info "  wsl -d nexus-agent-workbench -- bash -c \"cd /mnt/c/wp/nexus-agent-workbench && ./scripts/sh/check-env.sh\""
else
    DOCKER_SERVER_VERSION="$(nexus_run_timeout 15 docker version --format '{{.Server.Version}}' 2>/dev/null | tr -d '\r')"
    if [ -n "$DOCKER_SERVER_VERSION" ]; then
        DAEMON_OK="yes"
        _pass "1 Docker daemon：Server $DOCKER_SERVER_VERSION（docker version 退出码 0）"
    else
        _fail "1 Docker daemon：无法连接 docker 引擎（docker version 拿不到 Server 版本）"
        _info "→ 排查顺序（照着走，别跳步）："
        _info "   ① sudo service docker start            # WSL 内手工拉起引擎"
        _info "   ② sudo service docker status           # 看是否真的起来了"
        _info "   ③ sudo journalctl -u docker --no-pager | tail -30    # 引擎日志原文"
        _info "   ④ sudo dockerd --debug                 # 前台起，直接看报错（Ctrl-C 退出）"
        _info "   注：Windows 侧不跑 docker，docker 只在 WSL 内；本机不配置 daemon.json 镜像源"
    fi
fi

# ── 2 compose 插件 ───────────────────────────────────────────────────────────
if command -v docker >/dev/null 2>&1 && nexus_run_timeout 15 docker compose version >/dev/null 2>&1; then
    COMPOSE_VER="$(nexus_run_timeout 15 docker compose version --short 2>/dev/null | tr -d '\r')"
    _pass "2 compose 插件：docker compose ${COMPOSE_VER:-可用}（注意写法是 'docker compose'，非 'docker-compose'）"
else
    _fail "2 compose 插件：docker compose 不可用"
    _info "→ 需要 compose v2 插件；安装说明：https://docs.docker.com/compose/install/linux/"
    _info "→ 自检命令：docker compose version"
fi

# ── 3 WSL 环境 ───────────────────────────────────────────────────────────────
KERNEL_RELEASE="$(uname -r 2>/dev/null)"
if nexus_is_wsl; then
    _pass "3 WSL 环境：内核 ${KERNEL_RELEASE}（含 microsoft 关键字，确认在 WSL 内）"
else
    # 只判 WARN：脚本与发行版无耦合，在原生 Linux（CI 容器等）里同样能跑通；
    # 唯一前提是 docker 在该环境内 —— 那已由第 1 项独立覆盖。
    _warn "3 WSL 环境：内核 ${KERNEL_RELEASE} 未含 microsoft 关键字"
    _info "→ 本项目约定：docker/compose/.sh 脚本都在 WSL 发行版 nexus-agent-workbench 内执行"
    _info "→ 若你确实在 Linux 宿主上跑，本项可忽略（不影响后续检查结论）"
fi

# ── 4 端口占用（双侧：WSL 侧 ss + Windows 侧 Get-NetTCPConnection）───────────
# 为什么必须双侧：WSL2 NAT 模式下 Windows 本机占用同一端口会让端口转发失败；
# 为什么必须排除"自己的容器"：up.sh 要能**重复执行**，第二次跑时 5 个端口当然被本项目
# 容器占着 —— 把那当成冲突会让脚本在幂等场景下自杀（这是最容易写错的点）。
PORT_SPECS="PG_PORT:${NEXUS_PG_PORT}:PostgreSQL REDIS_PORT:${NEXUS_REDIS_PORT}:Redis OLLAMA_PORT:${NEXUS_OLLAMA_PORT}:Ollama BACKEND_PORT:${NEXUS_BACKEND_PORT}:后端 FRONTEND_PORT:${NEXUS_FRONTEND_PORT}:前端"
PORT_CONFLICTS=0
PORT_OWNED_BY_US=""
for spec in $PORT_SPECS; do
    PORT_VAR="${spec%%:*}"; rest="${spec#*:}"
    PORT_NUM="${rest%%:*}"; PORT_DESC="${rest#*:}"
    publisher="$(nexus_port_publisher "$PORT_NUM")"
    if [ -n "$publisher" ]; then
        case "$publisher" in
            *nexus-*)
                # 本项目容器占用 = 正常（重复执行 up.sh / 已有实例在跑）
                PORT_OWNED_BY_US="${PORT_OWNED_BY_US} ${PORT_NUM}(${publisher})"
                continue ;;
            *)
                PORT_CONFLICTS=$((PORT_CONFLICTS + 1))
                _fail "4 端口占用：宿主 ${PORT_NUM}（${PORT_DESC}）被**其他容器**占用：${publisher}"
                _info "→ 处置：改 ${NEXUS_ENV_FILE} 里的 ${PORT_VAR}=<新端口>（只改这一处即可，容器内端口固定不受影响）"
                continue ;;
        esac
    fi
    SS_LINE="$(nexus_wsl_port_listener_line "$PORT_NUM")"
    WIN_OWNER="$(nexus_windows_port_owner "$PORT_NUM")"
    if [ -n "$SS_LINE" ]; then
        if [ "$DAEMON_OK" != "yes" ]; then
            _warn "4 端口占用：宿主 ${PORT_NUM}（${PORT_DESC}）在 WSL 侧已有监听，但 docker daemon 不可用（第 1 项），无法判定是否本项目容器"
            _info "→ 证据(ss -ltnp)：${SS_LINE}"
        else
            PORT_CONFLICTS=$((PORT_CONFLICTS + 1))
            _fail "4 端口占用：宿主 ${PORT_NUM}（${PORT_DESC}）在 WSL 侧被非本项目进程占用"
            _info "→ 证据(ss -ltnp)：${SS_LINE}"
            _info "→ 处置：改 ${NEXUS_ENV_FILE} 里的 ${PORT_VAR}=<新端口>（只改这一处即可）"
        fi
    elif [ -n "$WIN_OWNER" ]; then
        case "${WIN_OWNER#*:}" in
            # Windows 侧的 wslrelay/vmmem/wslhost 是 WSL2 自己的端口转发宿主进程，
            # 它出现在 Windows 侧**不代表**有外部程序抢占 —— 降级为 WARN，交人工确认。
            wslrelay*|vmmem*|wslhost*|wslservice*|docker*|com.docker*)
                _warn "4 端口占用：宿主 ${PORT_NUM}（${PORT_DESC}）在 Windows 侧由 WSL/Docker 宿主进程监听（pid:name=${WIN_OWNER}）"
                _info "→ 多为 WSL2 端口转发（mirrored 网络模式下属正常）；若非本项目实例，请人工确认后再决定是否换端口" ;;
            *)
                PORT_CONFLICTS=$((PORT_CONFLICTS + 1))
                _fail "4 端口占用：宿主 ${PORT_NUM}（${PORT_DESC}）被 Windows 侧进程占用（pid:name=${WIN_OWNER}）"
                _info "→ 处置：改 ${NEXUS_ENV_FILE} 里的 ${PORT_VAR}=<新端口>（只改这一处即可）"
                _info "→ 查占用者：powershell -Command \"Get-Process -Id ${WIN_OWNER%%:*}\"" ;;
        esac
    fi
done
if [ "$PORT_CONFLICTS" -eq 0 ]; then
    # 区分"查过且没冲突"与"根本没能力查" —— 后者报 PASS 会给人虚假的安全感
    if nexus_has_ss && nexus_windows_port_check_available; then
        _pass "4 端口占用：5 个宿主端口双侧（WSL + Windows）均无外部冲突（pg=${NEXUS_PG_PORT} redis=${NEXUS_REDIS_PORT} ollama=${NEXUS_OLLAMA_PORT} backend=${NEXUS_BACKEND_PORT} frontend=${NEXUS_FRONTEND_PORT}）"
    elif nexus_has_ss; then
        _pass "4 端口占用：WSL 侧无冲突（Windows 侧 powershell.exe 不可用，未做双侧校验）"
    elif nexus_windows_port_check_available; then
        _warn "4 端口占用：Windows 侧无冲突，但 WSL 侧 ss 不可用 → **WSL 侧未校验**"
        _info "→ 手工确认：ss -ltnp | grep -E ':(${NEXUS_PG_PORT}|${NEXUS_REDIS_PORT}|${NEXUS_OLLAMA_PORT}|${NEXUS_BACKEND_PORT}|${NEXUS_FRONTEND_PORT})'"
    else
        _warn "4 端口占用：ss 与 powershell.exe 都不可用 → **本项未能校验**（不代表没有冲突）"
        _info "→ 手工确认：ss -ltnp | grep -E ':(${NEXUS_PG_PORT}|${NEXUS_REDIS_PORT}|${NEXUS_OLLAMA_PORT}|${NEXUS_BACKEND_PORT}|${NEXUS_FRONTEND_PORT})'"
    fi
    [ -n "$PORT_OWNED_BY_US" ] && _info "→ 已由本项目容器占用（重复执行属正常）：${PORT_OWNED_BY_US# }"
    _info "→ 端口变量可安全修改：容器内端口固定（backend 8089 / frontend 80），改 .env 不影响健康检查与 nginx 反代"
fi

# ── 4b .env 凭据耦合（本项目特有的静默陷阱，2026-09-11 核实）─────────────────
# 事实：compose 里 postgres 用 ${PG_USER}/${PG_PASSWORD}/${PG_DB}，
#       但 nexus-backend 的 environment **只有 SERVER_PORT** —— 后端走的是
#       application.yml 的默认值（恰好与 .env 相同，所以此刻能连上）。
# 后果：用户照"改 .env"的提示顺手改了 PG_PASSWORD → postgres 容器变了、后端没变
#       → /api/health 的 checks.postgres 变 DOWN，而用户不知道是自己按提示改出来的。
# 所以这里必须把"哪些变量改了安全、哪些会断"讲清楚，并把"已经改坏"的情形直接检出来。
APP_YML="${NEXUS_REPO_ROOT}/backend/nexus-start/src/main/resources/application.yml"
yml_pg_default() {   # 从 application.yml 取 ${POSTGRES_XXX:default} 的默认值
    [ -f "$APP_YML" ] || return 0
    sed -n "s/.*\${$1:\([^}]*\)}.*/\1/p" "$APP_YML" 2>/dev/null | head -n1
}
backend_has_pg_env() {   # nexus-backend 服务块里是否已显式注入 POSTGRES_*
    awk '/^  nexus-backend:/{f=1;next} /^  [A-Za-z0-9_-]+:/{f=0} f' "$NEXUS_COMPOSE_FILE" 2>/dev/null | grep -q 'POSTGRES_'
}
DEFAULT_PG_USER="$(yml_pg_default POSTGRES_USER)"
DEFAULT_PG_PASSWORD="$(yml_pg_default POSTGRES_PASSWORD)"
DEFAULT_PG_DB="$(yml_pg_default POSTGRES_DB)"
if backend_has_pg_env; then
    _pass "4b 凭据来源：nexus-backend 已显式注入 POSTGRES_*（.env 与后端已解耦，改凭据是安全的）"
elif [ -z "$DEFAULT_PG_USER$DEFAULT_PG_PASSWORD$DEFAULT_PG_DB" ]; then
    _warn "4b 凭据耦合：无法从 application.yml 解析默认值（文件缺失或结构已变），跳过本项"
else
    if [ "$DEFAULT_PG_USER" = "$NEXUS_PG_USER" ] \
       && [ "$DEFAULT_PG_PASSWORD" = "$NEXUS_PG_PASSWORD" ] \
       && [ "$DEFAULT_PG_DB" = "$NEXUS_PG_DB" ]; then
        _pass "4b 凭据耦合：.env 与后端 application.yml 默认值一致（当前可用）"
        _info "注意：这是**静默耦合** —— 靠"两边值恰好相同"成立，不是靠配置链路："
        _info "   · 安全改：*_PORT 端口变量（容器内端口固定，改宿主端口不波及任何逻辑）"
        _info "   · 会断：PG_USER / PG_PASSWORD / PG_DB —— nexus-backend 的 environment 里没有"
        _info "     POSTGRES_*，改了 .env 只改 postgres 容器，后端仍按 application.yml 的"
        _info "     默认值（${DEFAULT_PG_USER}/${DEFAULT_PG_PASSWORD}/${DEFAULT_PG_DB}）连库 → checks.postgres 变 DOWN"
        _info "   · 确需改凭据：同时给 compose 的 nexus-backend.environment 补"
        _info "     POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB（0.2 起 db-patch 迁移链路也要同步）"
    else
        _warn "4b 凭据耦合：.env 与后端 application.yml 默认值**不一致**"
        _info "   .env            : user=${NEXUS_PG_USER} password=${NEXUS_PG_PASSWORD} db=${NEXUS_PG_DB}"
        _info "   后端默认值      : user=${DEFAULT_PG_USER} password=${DEFAULT_PG_PASSWORD} db=${DEFAULT_PG_DB}"
        _info "→ 后果分两种情形，都不好看："
        _info "   ① 全新环境（pg-data 卷还不存在）：postgres 按 .env 初始化，后端按默认值连 → checks.postgres = DOWN"
        _info "   ② 已有 pg-data 卷：POSTGRES_* 只在 initdb 时生效，库密码其实没变 → 这台机器看着正常，"
        _info "      换台干净机器必挂 —— 同一套代码出现"环境相关"的故障，最难查"
        _info "→ 处置二选一：① 把 .env 改回与默认值一致（dev 默认值本就够用，推荐）"
        _info "             ② 或给 compose 的 nexus-backend.environment 补上 POSTGRES_*（根治）"
    fi
fi

# ── 5 镜像加速前缀（Dockerfile FROM / compose image）─────────────────────────
# 规则：镜像加速走前缀 docker.m.daocloud.io（官方镜像加 /library/），
#       **禁止**改 /etc/docker/daemon.json。本项只校验仓库内写法，不碰 daemon 配置。
# 本地构建的镜像天然没有 registry 前缀，属正常 —— 用白名单显式登记，
# 新增本地镜像忘了登记时会**响亮地**报 FAIL（而不是静默放过）。
LOCAL_IMAGES="nexus-builder:dev nexus-backend:dev nexus-frontend:dev"
MIRROR_BAD=""
while IFS= read -r img; do
    [ -n "$img" ] || continue
    case " $LOCAL_IMAGES " in *" $img "*) continue ;; esac
    case "$img" in
        docker.m.daocloud.io/*) ;;
        *) MIRROR_BAD="${MIRROR_BAD}${MIRROR_BAD:+, }compose image: ${img}" ;;
    esac
done < <(sed -n 's/^[[:space:]]*image:[[:space:]]*\(.*\)$/\1/p' "$NEXUS_COMPOSE_FILE" 2>/dev/null | tr -d '\r')
for df in "$NEXUS_COMPOSE_DIR"/*/Dockerfile; do
    [ -f "$df" ] || continue
    while IFS= read -r img; do
        [ -n "$img" ] || continue
        case " $LOCAL_IMAGES " in *" $img "*) continue ;; esac
        case "$img" in
            docker.m.daocloud.io/*) ;;
            *) MIRROR_BAD="${MIRROR_BAD}${MIRROR_BAD:+, }${df#"$NEXUS_REPO_ROOT"/} FROM ${img}" ;;
        esac
    done < <(sed -n 's/^FROM[[:space:]]\+\([^[:space:]]*\).*/\1/p' "$df" 2>/dev/null | tr -d '\r')
done
if [ -z "$MIRROR_BAD" ]; then
    _pass "5 镜像加速：compose image / Dockerfile FROM 均带 docker.m.daocloud.io 前缀（本地构建镜像已在白名单）"
    _info "→ 手动验证加速是否真的生效（可选）：docker pull docker.m.daocloud.io/library/hello-world"
    _info "→ 本检查**不提示**修改 /etc/docker/daemon.json：加速配置随仓库走，干净机器才能复现"
else
    _fail "5 镜像加速：以下镜像引用缺少 docker.m.daocloud.io 前缀"
    _info "   ${MIRROR_BAD}"
    _info "→ 规则：官方镜像走 /library/<镜像名>，非官方不加 /library/"
    _info "→ 仍**不要**改 /etc/docker/daemon.json（daemon 级 mirror 会波及 WSL 内其他项目）"
fi

# ── 6 compose 配置合法性 ─────────────────────────────────────────────────────
if [ "$DAEMON_OK" = "yes" ] && command -v docker >/dev/null 2>&1; then
    if ( cd "$NEXUS_COMPOSE_DIR" && nexus_run_timeout 60 docker compose config --quiet >/tmp/nexus-compose-config.err 2>&1 ); then
        _pass "6 compose 配置：docker compose config --quiet 通过（.env 插值 + 语法均合法）"
    else
        _fail "6 compose 配置：docker compose config --quiet 失败（compose 语法或 .env 插值有问题）"
        while IFS= read -r line; do _info "   ${line}"; done < <(head -n 15 /tmp/nexus-compose-config.err)
        _info "→ 原始命令（在 docker-compose 目录内执行，compose 才会自动读取同目录 .env）"
    fi
else
    _warn "6 compose 配置：docker daemon 不可用，跳过（第 1 项先修）"
fi

# ── 7 资源（可用内存）────────────────────────────────────────────────────────
MEM_AVAIL_MB=""
if command -v free >/dev/null 2>&1; then
    # Mem: 行第 7 列是 available（现代 procps）；老版本没有该列时退回第 4 列 free
    MEM_AVAIL_MB="$(free -m 2>/dev/null | awk '/^Mem:/{print ($7 ~ /^[0-9]+$/ ? $7 : $4)}')"
fi
if [ -n "$MEM_AVAIL_MB" ]; then
    if [ "$MEM_AVAIL_MB" -lt 8192 ]; then
        _warn "7 资源：可用内存 ${MEM_AVAIL_MB}MB < 8GB（7B 模型推理需 5GB+）"
        _info "→ 不必然失败，但 7B 推理可能极慢或被 OOM Kill；可选处置："
        _info "   · 在 Windows 用户目录建 %USERPROFILE%\\.wslconfig，调大 memory= 并 wsl --shutdown 重启 WSL（改动由用户自行操作）"
        _info "   · 或先用更小的模型演示（模型清单在 compose 的 ollama-init 命令里，脚本会自动跟随）"
    else
        _pass "7 资源：WSL 可用内存 ${MEM_AVAIL_MB}MB（>= 8GB）"
    fi
else
    _warn "7 资源：无法读取内存信息（free 不可用），跳过"
fi

# ── 8 模型就绪 —— **只判 WARN，绝不判 FAIL** ─────────────────────────────────
# 理由（必须写清楚，否则后人很容易"顺手改成 FAIL"）：clean 机器首次运行时 ollama 容器
# 通常尚未启动；判 FAIL 会让**第一次 up.sh 永远无法通过前置门禁**，与设计文档 §4.3
# "由 ollama-init 在 up 过程中拉模型"直接矛盾。模型就绪属于**运行期**判据（up.sh 第 7 步）。
EXPECTED_MODELS="$(nexus_expected_models)"
nexus_probe_models
case "$NEXUS_MODELS_STATE" in
    ALL)
        _pass "8 模型就绪：${EXPECTED_MODELS} 均已存在（判定依据 GET /api/tags 的 models[].name）" ;;
    MISSING)
        _warn "8 模型就绪：缺失 ${NEXUS_MODELS_MISSING}（已有：${NEXUS_MODELS_FOUND:-无}）"
        _info "→ 属正常：模型由 compose 的一次性容器 ollama-init 在 up 过程中拉取（约 5GB）"
        _info "→ 想提前预热可手动拉：docker exec nexus-ollama ollama pull ${NEXUS_MODELS_MISSING%% *}" ;;
    UNREACHABLE)
        _warn "8 模型就绪：ollama 尚未响应（http://${NEXUS_PROBE_HOST}:${NEXUS_OLLAMA_PORT}/api/tags 不可达）"
        _info "→ 干净机器首次运行就是如此（容器还没起），up.sh 会拉起带 模型拉取 的完整流程"
        _info "→ 注意：容器 healthy ≠ 模型就绪 —— ollama 的 healthcheck 是 'ollama list'，零模型时退出码也是 0" ;;
    NO_CURL)
        _warn "8 模型就绪：宿主没有 curl，无法探活模型（见第 0 项）" ;;
esac
# 一次性容器的退出码是"模型是否拉取成功"的唯一权威判据（§4.5，ps 默认不列已退出容器）
for st in $NEXUS_ONESHOT_CONTAINERS; do
    st_state="$(nexus_oneshot_state "$st")"
    st_status="${st_state%%|*}"; st_code="${st_state#*|}"
    case "$st_status" in
        exited)
            if [ "$st_code" = "0" ]; then
                _info "→ ${st} 已退出(0)：模型拉取成功（这是权威判据，优先于 /api/tags 的瞬时快照）"
            else
                _warn "8 模型就绪：${st} 已退出(${st_code}) → 拉取失败"
                _info "→ 看日志：docker logs --tail 50 ${st}（网络中断时重跑 up.sh 即可续拉，模型 blob 会复用）"
            fi ;;
        running) _info "→ ${st} 正在拉取模型（进行中）" ;;
        missing) ;;
    esac
done

# ── 汇总 ─────────────────────────────────────────────────────────────────────
printf '%s\n' "------------------------------------------------------------------------------"
printf ' 合计：PASS %d / WARN %d / FAIL %d\n' "$PASS_N" "$WARN_N" "$FAIL_N"
if [ "$FAIL_N" -gt 0 ]; then
    printf '%s\n' " 失败清单（up.sh 会因此中止）："
    for item in "${FAIL_ITEMS[@]}"; do printf '   · %s\n' "$item"; done
    printf '%s\n' " 修完重跑本脚本，全绿后再执行 ./scripts/sh/up.sh"
else
    printf '%s\n' " 结论：前置检查通过，可执行 ./scripts/sh/up.sh 一键拉起"
fi
printf '%s\n' "=============================================================================="

[ "$FAIL_N" -eq 0 ] || exit 1
exit 0

#!/usr/bin/env bash
# =============================================================================
# scripts/check-health.sh —— 运行期巡检（环境**起来之后**执行；失败**仅报告、绝不中止任何流程**）
#
# 执行位置：WSL 发行版 nexus-agent-workbench 内
#   cd /mnt/c/wp/nexus-agent-workbench && ./scripts/check-health.sh
# Windows 侧一键触发：
#   wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/check-health.sh"
#
# ┌───────────────────────────────────────────────────────────────────────────┐
# │ 语义分野：本脚本与 check-env.sh **相反**，所以必须是两个独立脚本             │
# │                                                                           │
# │  check-env.sh    启动**前置门禁**：环境"起来之前"跑；每条 FAIL 都必须能在   │
# │                  "一个容器都没起"的干净机器上复现；FAIL ⇒ 中止 up.sh        │
# │  check-health.sh 运行期**报告**：环境"起来之后"跑，给此刻快照；             │
# │                  FAIL **只打印结论**，不中止任何流程                        │
# │                                                                           │
# │  ⇒ **禁止在 up.sh（或任何流程）里把本脚本当门禁调用**：本脚本判 FAIL 就把   │
# │    流程掐掉，等于把"环境还没起"这种**完全正常的状态**当成错误。             │
# │  ⇒ 本脚本在"一个容器都没起"时必须**正常跑完**并给全 FAIL 报告 +            │
# │    "若尚未启动，请先执行 ./scripts/up.sh" 的提示，而不是报环境错误退出。    │
# └───────────────────────────────────────────────────────────────────────────┘
#
# 退出码契约（**与 check-env.sh 相反**，别互相套用）：
#   全部通过（含 [WARN]）→ 0 ；存在 [FAIL] → 1 ；用法错误 → 2
#   该退出码**只表达报告结论**，任何调用方都不得据此中止流程。
#
# 检查对象（判据来源：docs/api/README.md §4 —— §4.1 两个探活口分工 / §4.2 判定规则表 /
#           §4.3 抖动免疫 / §4.5 模型就绪 / §4.6 避坑清单；端口一律取自 docker-compose/.env）：
#   常驻 5 个 ：nexus-postgres / nexus-redis / nexus-ollama / nexus-backend / nexus-frontend
#   一次性 1 个：nexus-ollama-init（restart: "no"，**退出码是"模型是否拉取成功"的唯一权威判据**；
#               `docker compose ps` 默认不列已退出容器 ⇒ 走 docker inspect，陷阱 1）
#   不纳入     ：nexus-builder（profiles: ["build"] 隔离、运行期不常驻）
#
# 三层判据（**不可混用**，混用是最大坑源）：
#   L1 容器级：State + compose healthcheck（后端打 /actuator/health，覆盖 Boot 内建
#              db/redis/diskSpace/ping，**不含 ollama**）→ 回答"进程起没起来、要不要重启"
#   L2 业务级：GET /api/health（覆盖 postgres/redis/ollama；任一 DOWN 返 503）→
#              回答"三个依赖**此刻**是否真通"。**唯一**同时覆盖三项的入口
#   L3 模型级：GET /api/tags（**ollama 健康 ≠ 模型就绪**：healthcheck 是 `ollama list`，
#              零模型时退出码也是 0）→ 回答"AI 能力是否真可用"
#   为什么必须三层都查（活教材）：停 ollama → 后端容器仍 healthy，但 /api/health 立刻 503；
#   只查容器状态的脚本会**完全漏掉 AI 依赖故障**。
#
# 判据纪律：**不得从 docs/drafts/环境检查脚本.md 抄** —— 该草稿有 8 处判据已核实有误
# （pg_extension 必然 FAIL、前端端口 80/8088 混用、/api/actuator/health 必然 404、
#  模型就绪判 FAIL 会让干净机器永远过不了门禁 …… 逐条见 docs/task/task.1+环境准备.md 订正表）。
#
# 明确不做（沿用 §4.6，防止后续 agent 照草稿重新生成）：
#   · 不查 pg_extension「已安装」（本库从未 CREATE EXTENSION，现阶段必然 FAIL；可用 ≠ 已启用）
#   · 不查 t_db_patch / 业务表 tenant_id（0.2 未开工、多租户属阶段1，表都还不存在）
#   · 不做 GPU / nvidia-smi 检查（已定 CPU 推理）
#   · 不调 /api/ai/ping（阶段2 才有该接口）
#   · 不给 nexus-frontend 补 healthcheck（compose 里已有）
#   · 不提示修改 /etc/docker/daemon.json（镜像加速走 Dockerfile/compose 前缀）
#   · 本脚本**只读**：不 start/stop/restart 任何容器、不写文件、不改 .env
#
# 探针函数全部来自 scripts/lib/probe.sh（本文件不写内联的 curl/docker 探活命令，
# 也不写端口/模型名字面量 —— 判据治一处，评审时按此检查）。
# =============================================================================

# 不启用 set -e：本脚本要"跑完全部检查项再汇总"，任何单点失败都是**要报告的信息**，
# 不是要中断流程的错误 —— 这正是它与 check-env.sh 的语义分野在代码上的落点。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/probe.sh
. "${SCRIPT_DIR}/lib/probe.sh"

nexus_load_env

usage() {
    cat <<'USAGE'
用法: ./scripts/check-health.sh [--help]

运行期巡检：环境**起来之后**执行，输出"此刻快照"式的健康报告。
本脚本是**报告**，不是门禁 —— 失败只打印，不中止任何流程。

退出码:
  0   全部通过（含 [WARN]）
  1   存在 [FAIL]（**仅表达报告结论**，调用方不得据此中止流程）
  2   用法错误

与 check-env.sh 的区别（别混用、别合并）:
  check-env.sh    启动前置门禁：环境起来之前跑，每条 FAIL 必须在"零容器"的机器上可复现，
                  FAIL ⇒ 中止 up.sh
  check-health.sh 运行期报告：环境起来之后跑，"一个容器都没起"属**正常场景**，
                  本脚本照常跑完并提示先执行 ./scripts/up.sh
  ⇒ **不要在 up.sh 里调用本脚本当门禁**（会把"环境还没起"误判成错误而掐断流程）

检查对象:
  常驻 5 个：nexus-postgres / nexus-redis / nexus-ollama / nexus-backend / nexus-frontend
  一次性 1 个：nexus-ollama-init（退出码 = 模型是否拉取成功的唯一权威判据）
  不纳入：nexus-builder（profiles 隔离、运行期不常驻）

判据来源: docs/api/README.md §4（§4.1 两个探活口分工 / §4.2 判定规则表 / §4.3 抖动免疫 /
          §4.5 模型就绪 / §4.6 避坑清单）；端口取自 docker-compose/.env（唯一真源）。
警告: 不要照 docs/drafts/环境检查脚本.md 抄 —— 该草稿有 8 处判据已核实有误。

示例:
  ./scripts/check-health.sh            # 跑一次巡检
  ./scripts/check-health.sh --help     # 看本说明
USAGE
}

for arg in "$@"; do
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        *) printf '未知参数：%s\n\n' "$arg" >&2; usage >&2; exit 2 ;;
    esac
done

# ── 计数与输出 ───────────────────────────────────────────────────────────────
PASS_N=0; WARN_N=0; FAIL_N=0
FAIL_ITEMS=()
# 前缀固定 7 字符宽（"[PASS] "），正文与 _info/_hint 的缩进对齐
_pass() { PASS_N=$((PASS_N + 1)); printf '[PASS] %s\n' "$*"; }
_warn() { WARN_N=$((WARN_N + 1)); printf '[WARN] %s\n' "$*"; }
_fail() { FAIL_N=$((FAIL_N + 1)); FAIL_ITEMS+=("$*"); printf '[FAIL] %s\n' "$*"; }
_info() { printf '       %s\n' "$*"; }
_hint() { printf '       → %s\n' "$*"; }
_sect() { printf '\n---- %s ----\n' "$*"; }

START_TS="$(date +%s)"
DOCKER_OK="no"
BACKEND_HEALTH="unknown"     # L1 里记录，供 L2+ 段做"两级是否一致"的判断
FRONTEND_HEALTH="unknown"
FRONTEND_HC_UNHEALTHY="no"
RUNNING_N=0
L2_OK="no"
L2_RECHECK="no"
FE_PAGE_OK="no"
FE_PROXY_OK="no"

printf '%s\n' "=============================================================================="
printf '%s\n' " nexus 运行期巡检（check-health.sh）—— 环境**起来之后**执行"
printf '%s\n' " 语义：报告，不是门禁 —— [FAIL] 只打印，不中止任何流程"
printf '%s\n' " 判据来源：docs/api/README.md §4 + docs/task/task.1+环境准备.md 0.3.3"
printf '%s\n' " 退出码：0 = 无 FAIL；1 = 存在 FAIL（仅表达报告结论，调用方不得据此中止流程）"
printf '%s\n' "=============================================================================="
printf ' 仓库根   : %s\n' "$NEXUS_REPO_ROOT"
printf ' compose  : %s\n' "$NEXUS_COMPOSE_FILE"
printf ' .env     : %s\n' "$NEXUS_ENV_FILE"
printf ' 宿主端口 : backend=%s frontend=%s pg=%s redis=%s ollama=%s（取自 .env）\n' \
    "$NEXUS_BACKEND_PORT" "$NEXUS_FRONTEND_PORT" "$NEXUS_PG_PORT" "$NEXUS_REDIS_PORT" "$NEXUS_OLLAMA_PORT"
printf ' 检查对象 : 5 常驻 + 1 一次性（%s）；nexus-builder 不纳入\n' "$NEXUS_ONESHOT_CONTAINERS"
printf ' 探活地址 : http://127.0.0.1:<port>（不用 localhost：容器内 localhost 会先解析到 ::1，见 6/6 段）\n'
printf '%s\n' "------------------------------------------------------------------------------"

NEXUS_VERSION_STR="${BASH_VERSION%%(*}"

# ── 1/6 前置：执行环境与本轮工具 ─────────────────────────────────────────────
_sect "1/6 前置：执行环境与工具"

if nexus_has_curl; then
    _pass "curl 可用（L2 / L3 / 前端三条 HTTP 层判据都依赖它）"
else
    _fail "宿主没有 curl → L2 / L3 / 前端三段 HTTP 判据全部无法执行（本报告不完整）"
    _hint "WSL 内安装：sudo apt-get update && sudo apt-get install -y curl"
fi

if [ -f "$NEXUS_COMPOSE_FILE" ]; then
    _pass "compose 文件存在（容器名 / 服务名 / 期望模型名都从它解析）"
else
    _warn "找不到 compose 文件：$NEXUS_COMPOSE_FILE"
    _hint "期望模型名与部分提示会退回内置默认值，本报告可能与你实际的 compose 配置不符"
fi
if [ -f "$NEXUS_ENV_FILE" ]; then
    _pass ".env 存在（宿主端口唯一真源）"
else
    _warn "找不到 .env：$NEXUS_ENV_FILE —— 端口退回 compose 内置默认值"
fi

if ! command -v docker >/dev/null 2>&1; then
    _fail "找不到 docker 命令 → 容器级（L1）与一次性容器判据无法执行"
    _hint "确认是在 WSL 发行版 nexus-agent-workbench 内执行本脚本"
else
    DOCKER_SERVER_VERSION="$(nexus_run_timeout 15 docker version --format '{{.Server.Version}}' 2>/dev/null | tr -d '\r')"
    if [ -n "$DOCKER_SERVER_VERSION" ]; then
        DOCKER_OK="yes"
        _pass "docker 引擎可用（Server ${DOCKER_SERVER_VERSION}；bash ${NEXUS_VERSION_STR}）"
    else
        _fail "docker 引擎不可达（docker version 拿不到 Server 版本）→ 容器级判据无法执行"
        _hint "排查：sudo service docker start / sudo service docker status / sudo journalctl -u docker --no-pager | tail -30"
        _hint "注意：docker 只在 WSL 内运行，Windows 侧不跑 docker"
    fi
fi
if nexus_has_jq; then
    _info "JSON 解析：jq 可用（精确解析）"
else
    _info "JSON 解析：jq 缺失 → 走 §4.2.3 的子串兜底路径（依赖 Jackson 的紧凑输出与固定字段顺序）"
fi

# ── 2/6 L1 容器级 ────────────────────────────────────────────────────────────
_sect "2/6 L1 容器级：State + compose healthcheck（回答“进程起没起来、要不要重启”）"

if [ "$DOCKER_OK" != "yes" ]; then
    _warn "跳过 L1：docker 引擎不可用（见 1/6 段）—— 本轮无法判断任何容器状态"
else
    for c in $NEXUS_RESIDENT_CONTAINERS; do
        st="$(nexus_container_status "$c")"
        cs="${st%%|*}"; ch="${st#*|}"
        svc="$(nexus_service_of "$c")"
        case "$cs" in
            running)
                RUNNING_N=$((RUNNING_N + 1))
                [ "$c" = "nexus-backend" ] && BACKEND_HEALTH="$ch"
                [ "$c" = "nexus-frontend" ] && FRONTEND_HEALTH="$ch"
                case "$ch" in
                    healthy)
                        _pass "$c  running / healthy" ;;
                    starting)
                        _warn "$c running / starting（仍在 start_period 窗口内 —— 稍等 1 分钟重跑本脚本；此刻判 FAIL 会误伤启动中的环境）" ;;
                    unhealthy)
                        case "$c" in
                            nexus-frontend)
                                # 已知假故障（陷阱 8）：先记下，等 6/6 段的功能探活证据再下结论
                                FRONTEND_HC_UNHEALTHY="yes"
                                _warn "nexus-frontend running / unhealthy —— **疑似已知假故障**（healthcheck 命令自身地址写错，服务本身正常）"
                                _info "  判定延后：需 6/6 段的功能探活证据，才能区分假故障与真故障（该段给结论与证据，此处不重复计数）" ;;
                            *)
                                _fail "$c running / unhealthy（unhealthy 不触发重启，容器仍是 running；且它**不告诉你**是哪个 indicator 挂了）"
                                _info "  healthcheck 报错原文：$(nexus_container_health_log "$c")"
                                _hint "看容器级视图：curl -s http://127.0.0.1:${NEXUS_BACKEND_PORT}/actuator/health（本脚本 4/6 段会在异常时自动补测）"
                                _hint "重启单个服务：cd ${NEXUS_COMPOSE_DIR} && docker compose restart ${svc}" ;;
                        esac ;;
                    "")
                        _pass "$c  running（该容器未配置 healthcheck）" ;;
                esac ;;
            missing)
                _fail "$c 容器不存在（本轮没这个容器）"
                _hint "拉起该服务：cd ${NEXUS_COMPOSE_DIR} && docker compose up -d ${svc}"
                _hint "注意给的是**服务名** ${svc} 而不是容器名（给容器名会报 no such service，陷阱 7）" ;;
            *)
                _fail "$c 状态异常：${cs}（期望 running）"
                _hint "看事件与退出码：docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' $c"
                _hint "日志：docker logs --tail 50 $c" ;;
        esac
    done

    # 一次性容器：退出码是"模型是否拉取成功"的唯一权威判据（ps 默认不列已退出容器）
    for c in $NEXUS_ONESHOT_CONTAINERS; do
        st="$(nexus_oneshot_state "$c")"
        os="${st%%|*}"; oc="${st#*|}"
        case "$os" in
            exited)
                if [ "$oc" = "0" ]; then
                    _pass "$c 已退出(0)：模型拉取成功（一次性容器，退出码即权威判据）"
                else
                    _warn "$c 已退出(${oc})：模型拉取失败（网络中断类问题居多）—— 「拉取失败」不等于「环境坏了」"
                    _hint "日志：docker logs --tail 50 $c"
                    _hint "续拉（blob 按 digest 复用，不会重下已完成的）：cd ${NEXUS_COMPOSE_DIR} && docker compose up -d ollama-init"
                fi ;;
            running)  _warn "$c 正在拉取模型（进行中：约 5GB，属正常等待，不是故障）" ;;
            created)  _warn "$c 已创建未启动（等 ollama 健康；稍后重跑 up.sh 会带上它）" ;;
            missing)  _warn "$c 从未运行过（干净机器首次 up.sh 之前属正常；模型就绪以 5/6 段的 /api/tags 快照为准）" ;;
            *)        _warn "$c 状态：${os:-未知}" ;;
        esac
    done
    _info "注：nexus-builder 不纳入运行期检查（profiles 隔离、运行期不常驻，用完即走）"
fi

# ── 3/6 L2 业务级 ────────────────────────────────────────────────────────────
_sect "3/6 L2 业务级：GET /api/health（唯一同时覆盖 postgres / redis / ollama 的探活口）"

if ! nexus_has_curl; then
    _warn "跳过 L2：宿主没有 curl（见 1/6 段）"
else
    nexus_probe_api_health
    L2_FIRST_STATE="$NEXUS_HEALTH_STATE"
    # 抖动免疫（§4.3）：单次采样命中 503 时 5s 后复采一次；复采仍 503 才判 FAIL。
    # 为什么必须复采：redis 刚恢复时后端连接池还在重建，"一个 200 紧跟一个 503"是常见现象，
    # 单次判定会把"还在抖"读成"已就绪"（up.sh 里是"连续 2 次 200 才算就绪"，同源判据、不同用途，别互相套用）。
    if [ "$L2_FIRST_STATE" = "DEGRADED" ]; then
        _info "首次采样：503（${NEXUS_HEALTH_DETAIL}）→ 5s 后复采一次确认（§4.3 抖动免疫）"
        sleep 5
        nexus_probe_api_health
        L2_RECHECK="yes"
    fi

    case "$NEXUS_HEALTH_STATE" in
        UP)
            if [ "$L2_RECHECK" = "yes" ]; then
                _pass "GET http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health → 200 / code=0 / checks 全 UP（首次 503、复采已恢复 ⇒ 判定为抖动，非故障）"
            else
                _pass "GET http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health → 200 / code=0 / checks 全 UP"
            fi
            L2_OK="yes"
            _info "checks: ${NEXUS_HEALTH_CHECKS}（三项全 UP 是 200 的充要条件，任一 DOWN 后端会返 503）" ;;
        DEGRADED)
            L2_DOWN_NAMES="$(printf '%s' "$NEXUS_HEALTH_CHECKS" | tr ' ' '\n' | grep '=DOWN$' | cut -d= -f1)"
            L2_DOWN_DISP=""
            for d in $L2_DOWN_NAMES; do L2_DOWN_DISP="${L2_DOWN_DISP}${L2_DOWN_DISP:+、}${d}"; done
            _fail "GET http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health → 503 / code=${NEXUS_HEALTH_CODE}：依赖不可用（已复采确认，不是抖动）"
            _info "后端 msg：${NEXUS_HEALTH_MSG}"
            _info "最后一次 data.checks：${NEXUS_HEALTH_CHECKS} → **点名判 DOWN 的依赖：${L2_DOWN_DISP}**"
            _info "注意：503 是**契约内的正常响应**（不是接口挂了）—— 后端进程活着，是它依赖的东西断了"
            for d in $L2_DOWN_NAMES; do
                case "$d" in
                    postgres) _hint "postgres：docker logs --tail 50 nexus-postgres；docker exec nexus-postgres pg_isready -U ${NEXUS_PG_USER} -d ${NEXUS_PG_DB}" ;;
                    redis)    _hint "redis：docker logs --tail 50 nexus-redis；docker exec nexus-redis redis-cli ping" ;;
                    ollama)   _hint "ollama：docker logs --tail 50 nexus-ollama；curl -s http://127.0.0.1:${NEXUS_OLLAMA_PORT}/api/version" ;;
                    *)        _hint "未知依赖 ${d}：docker logs --tail 50 nexus-${d}" ;;
                esac
            done
            _hint "依赖恢复后本脚本可直接重跑（只读、幂等）；要整体重建再跑 ./scripts/up.sh" ;;
        NO_RESPONSE)
            _fail "GET http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health → 无响应（curl 连不上）"
            _hint "含义：后端进程没起，或端口不对 —— 不适用“503 = 依赖挂了”的处置，两者完全不同"
            _hint "端口核对：.env 的 BACKEND_PORT=${NEXUS_BACKEND_PORT}；容器内固定 8089"
            _hint "日志：docker logs --tail 100 nexus-backend（容器不在？cd ${NEXUS_COMPOSE_DIR} && docker compose up -d nexus-backend）" ;;
        NOT_FOUND)
            _fail "GET /api/health → 404：路径写错或版本不匹配"
            _hint "路径：宿主 ${NEXUS_BACKEND_PORT}/api/health（/api 前缀别丢；/api/actuator/health 必然 404）" ;;
        ERROR)
            _fail "GET /api/health → 500：后端内部异常"
            _hint "日志：docker logs --tail 100 nexus-backend" ;;
        ABNORMAL)
            _fail "GET /api/health → 非预期 HTTP ${NEXUS_HEALTH_HTTP}（期望 200 或 503）"
            _hint "可能是别的东西占了这个端口：ss -ltnp | grep :${NEXUS_BACKEND_PORT}" ;;
        CONTRACT_BROKEN)
            _fail "GET /api/health → 响应不符合契约：${NEXUS_HEALTH_DETAIL}"
            _hint "若 body 是 HTML → 打到了 nginx 的 SPA 回退（**假通过**），核对地址与端口"
            _hint "若涉及 data.version → 属**构建层**缺陷（资源过滤未生效），查 nexus-start 的 maven-resources-plugin" ;;
        NO_CURL)
            _warn "跳过 L2：宿主没有 curl" ;;
        *)
            _fail "GET /api/health → 未知状态 ${NEXUS_HEALTH_STATE}" ;;
    esac
fi

# ── 4/6 字段级审计 + 容器级视图对照 ──────────────────────────────────────────
_sect "4/6 字段级审计（§4.2.2 全表）＋ 容器级视图对照（GET /actuator/health）"

AUDIT_RAN="no"
if ! nexus_has_curl; then
    _warn "跳过字段审计：宿主没有 curl"
elif [ -z "$NEXUS_HEALTH_BODY" ] \
     || { [ "$NEXUS_HEALTH_HTTP" != "200" ] && [ "$NEXUS_HEALTH_HTTP" != "503" ]; }; then
    _info "跳过字段审计：本轮没拿到 200/503 的 Result JSON（HTTP=${NEXUS_HEALTH_HTTP:-?}）—— 字段无从校验"
else
    AUDIT_RAN="yes"

    # data.service：固定 nexus-start，不匹配说明打到了别的服务
    if [ "$NEXUS_HEALTH_SERVICE" = "nexus-start" ]; then
        _pass "data.service = nexus-start"
    else
        _fail "data.service = '${NEXUS_HEALTH_SERVICE}'（期望 nexus-start）→ 大概率打到了别的服务"
        _hint "核对 .env 的 BACKEND_PORT=${NEXUS_BACKEND_PORT} 与 application.yml 的 nexus.health.service-name"
    fi

    # data.status 与 HTTP 同真同假（§4.2.2）
    case "${NEXUS_HEALTH_HTTP}/${NEXUS_HEALTH_STATUS}" in
        200/UP|503/DOWN)
            _pass "data.status = ${NEXUS_HEALTH_STATUS}（与 HTTP ${NEXUS_HEALTH_HTTP} 一致）" ;;
        *)
            _fail "data.status = '${NEXUS_HEALTH_STATUS}' 与 HTTP ${NEXUS_HEALTH_HTTP} 不一致 → 契约被破坏（报后端 bug，见 §4.2.2）" ;;
    esac

    # data.version：**构建层**缺陷的探测器（0.4 遗留验证项的收口手段）
    # 三态：yes=通过 / no=不通过 / ""=**本轮未校验**（探针在更早的字段上已判契约破坏并提前返回）。
    # "" 绝不能当 FAIL：否则探针一旦在别的字段上失败，这里会跟着报一串"字段为空"的假失败
    # （实测踩过：version 坏掉时 timestamp 还没被校验，就打印了 timestamp='' 的假 FAIL）。
    if [ "$NEXUS_HEALTH_VERSION_OK" = "yes" ]; then
        _pass "data.version = ${NEXUS_HEALTH_VERSION}（semver，且已显式拒绝 @project.version@ 字面量 ⇒ 资源过滤生效）"
    elif [ "$NEXUS_HEALTH_VERSION_OK" = "no" ]; then
        _fail "data.version = '${NEXUS_HEALTH_VERSION}'：**构建层缺陷**，不是环境问题（别以为容器没起好）"
        _hint "查 backend/nexus-start/pom.xml 的 maven-resources-plugin 过滤是否生效（占位符 @project.version@ 未被替换）"
        _hint "交叉验证：curl -s http://127.0.0.1:${NEXUS_BACKEND_PORT}/actuator/info → info.app.version 应同为该版本"
    else
        _info "data.version 未校验（探针已在更前的字段判契约破坏并返回，见 3/6 段；修完那一处再看本项）"
    fi

    # data.timestamp：只断言"ISO-8601 秒级"，**不断言时区**（容器内 Z=UTC、本地直跑 +08:00 都合法）
    if [ "$NEXUS_HEALTH_TS_OK" = "yes" ]; then
        _pass "data.timestamp = ${NEXUS_HEALTH_TIMESTAMP}（ISO-8601 秒级；**不断言时区**：Z 与 +08:00 都算合法）"
    elif [ "$NEXUS_HEALTH_TS_OK" = "no" ]; then
        _fail "data.timestamp = '${NEXUS_HEALTH_TIMESTAMP}' 不是 ISO-8601 秒级时间（契约 §2.2）"
    else
        _info "data.timestamp 未校验（同上：探针在更前的字段已返回）"
    fi

    # data.checks 键数恰好 3，且无未知键（多出未知键 = 契约已变更，报告而非静默通过）
    CHECKS_KEYS="$(nexus_json_field "$NEXUS_HEALTH_BODY" checks_keys)"
    CHECKS_KEY_N=0
    UNKNOWN_KEYS=""
    for k in $CHECKS_KEYS; do
        CHECKS_KEY_N=$((CHECKS_KEY_N + 1))
        case "$k" in
            postgres|redis|ollama) ;;
            *) UNKNOWN_KEYS="${UNKNOWN_KEYS}${UNKNOWN_KEYS:+ }${k}" ;;
        esac
    done
    if [ "$CHECKS_KEY_N" -eq 3 ] && [ -z "$UNKNOWN_KEYS" ]; then
        _pass "data.checks 键数恰好 3（postgres / redis / ollama），无未知键"
    else
        _fail "data.checks 键数 = ${CHECKS_KEY_N}（期望恰好 3）${UNKNOWN_KEYS:+，且出现未知键：${UNKNOWN_KEYS}}"
        _info "  实际键：${CHECKS_KEYS:-（无）}"
        _hint "契约已变更：同步更新 docs/api/README.md §2.2 与本脚本判据，**不要**按“看着像通过”放过"
    fi
fi

# 容器级视图对照：只在"两级对不上"时补测 /actuator/health（正常路径不必多打一次 HTTP）
if [ "$DOCKER_OK" != "yes" ]; then
    _info "跳过容器级视图对照：docker 引擎不可用"
elif [ "$BACKEND_HEALTH" = "healthy" ] && [ "$L2_OK" = "yes" ]; then
    _pass "两级结论一致：nexus-backend healthy ＋ /api/health 三项全 UP（无需再打 /actuator/health）"
else
    if [ "$L2_OK" = "yes" ]; then L2_DISP="UP"; else L2_DISP="非UP（详见 3/6 段）"; fi
    _info "两级不一致/有异常（L1 backend=${BACKEND_HEALTH}，L2=${L2_DISP}）→ 补测 /actuator/health 解释"
    _info "  它覆盖 Boot 内建 db / redis / diskSpace / ping，**不含 ollama** —— 是“容器该不该重启”的视图"
    nexus_probe_actuator_health
    case "$NEXUS_ACTUATOR_STATE" in
        UP)
            _warn "GET /actuator/health → 200 / status=UP（组件：${NEXUS_ACTUATOR_COMPONENTS}）"
            _info "  ⇒ 容器自身依赖没问题。若 L1 仍报 unhealthy，问题在 healthcheck 命令本身；若 L2 报 503，问题在 ollama（Boot 不覆盖它）" ;;
        DOWN)
            _warn "GET /actuator/health → status=${NEXUS_ACTUATOR_STATUS} / HTTP ${NEXUS_ACTUATOR_HTTP}：判 DOWN 的组件 —— ${NEXUS_ACTUATOR_DOWN:-（未列出）}"
            _info "  组件明细：${NEXUS_ACTUATOR_COMPONENTS:-（无）}"
            _hint "数据库/缓存类：docker logs --tail 50 nexus-postgres（或 nexus-redis）"
            _hint "磁盘类：docker system df；WSL 磁盘满会同时影响 PG 写入与模型拉取" ;;
        NO_RESPONSE)
            _warn "GET /actuator/health 无响应（后端进程没起 / BACKEND_PORT 不对）—— 与 L2 的结论互相印证" ;;
        NOT_FOUND)
            _warn "GET /actuator/health → 404：路径写错（注意 /actuator/health **不带** /api 前缀）" ;;
        ERROR)
            _warn "GET /actuator/health → 500：看 docker logs --tail 100 nexus-backend" ;;
        CONTRACT_BROKEN)
            _warn "GET /actuator/health 响应不符合 actuator 格式：${NEXUS_ACTUATOR_DETAIL}" ;;
        *)
            _warn "GET /actuator/health → ${NEXUS_ACTUATOR_STATE}（HTTP ${NEXUS_ACTUATOR_HTTP}）：${NEXUS_ACTUATOR_DETAIL}" ;;
    esac
fi

# ── 5/6 L3 模型级 ────────────────────────────────────────────────────────────
_sect "5/6 L3 模型级：GET /api/tags（ollama 健康 ≠ 模型就绪，§4.5）"

EXPECTED_MODELS="$(nexus_expected_models)"
MODELS_SNAPSHOT="unknown"
if ! nexus_has_curl; then
    _warn "跳过 L3：宿主没有 curl"
else
    nexus_probe_models
    MODELS_SNAPSHOT="$NEXUS_MODELS_STATE"
    case "$NEXUS_MODELS_STATE" in
        ALL)
            _pass "模型齐备：${EXPECTED_MODELS}（判据 models[].name，:latest 已归一化）"
            _info "  实际清单：${NEXUS_MODELS_FOUND}" ;;
        MISSING)
            _warn "模型未齐：缺失 ${NEXUS_MODELS_MISSING}（已有：${NEXUS_MODELS_FOUND:-无}）"
            _info "  「正在拉」不是「环境坏了」⇒ 本项**只报 WARN，不判 FAIL**（§4.5）"
            _info "  为什么单独查它：nexus-ollama 的 healthcheck 是 'ollama list'，零模型时退出码也是 0 —— 容器 healthy 与模型是否拉好完全无关"
            for m in $NEXUS_MODELS_MISSING; do
                _hint "补拉 ${m}：docker exec nexus-ollama ollama pull ${m}"
            done
            _hint "或重跑一次性容器（blob 按 digest 复用，不重下已完成的）：cd ${NEXUS_COMPOSE_DIR} && docker compose up -d ollama-init" ;;
        UNREACHABLE)
            _warn "ollama 未响应（http://${NEXUS_PROBE_HOST}:${NEXUS_OLLAMA_PORT}/api/tags 不可达）"
            _hint "日志：docker logs --tail 50 nexus-ollama；容器状态见 2/6 段" ;;
        NO_CURL)
            _warn "跳过 L3：宿主没有 curl" ;;
        *)
            _warn "模型探活返回未知状态：${NEXUS_MODELS_STATE}" ;;
    esac
    if [ "$MODELS_SNAPSHOT" = "MISSING" ]; then
        for c in $NEXUS_ONESHOT_CONTAINERS; do
            st="$(nexus_oneshot_state "$c")"
            if [ "${st%%|*}" = "exited" ] && [ "${st#*|}" = "0" ]; then
                _info "  注：${c} 退出码为 0 但当前清单缺模型（可能被并发拉取/卷变更）—— 以本段 /api/tags 的**当前**清单为准"
            fi
        done
    fi
fi

# ── 6/6 前端页面与 nginx 反代 ────────────────────────────────────────────────
_sect "6/6 前端页面与 nginx 反代（宿主 ${NEXUS_FRONTEND_PORT}；80 是容器内端口）"

if ! nexus_has_curl; then
    _warn "跳过前端段：宿主没有 curl"
else
    nexus_http_get "http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/" 5
    case "$NEXUS_HTTP_CODE" in
        200) FE_PAGE_OK="yes"
             _pass "GET http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/ → 200（前端页面可达）" ;;
        000) _fail "GET http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/ → 无响应（前端容器没起 / FRONTEND_PORT 不对）"
             _hint "端口核对：.env 的 FRONTEND_PORT=${NEXUS_FRONTEND_PORT}（80 是容器内端口，不要直接探测 80）"
             _hint "日志：docker logs --tail 50 nexus-frontend" ;;
        *)   _fail "GET http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/ → HTTP ${NEXUS_HTTP_CODE}（期望 200）"
             _hint "日志：docker logs --tail 50 nexus-frontend" ;;
    esac

    # 反代判据只能用 /api/health：
    #   · /api/actuator/health 必然 404（nginx 的 location /api/ 无尾路径 = 保留 /api 前缀，Actuator 路径不带 /api）
    #   · 走前端端口查 /actuator/* 会命中 SPA 回退 → **200 + HTML 的"假通过"**（§4.6）⇒ 必须判 body，不能只看状态码
    nexus_http_get "http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/api/health" 10
    if printf '%s' "$NEXUS_HTTP_BODY" | grep -q '"code"'; then
        FE_PROXY_OK="yes"
        case "$NEXUS_HTTP_CODE" in
            200) _pass "反代链路 GET http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/api/health → 200 + 完整 Result JSON（nginx location /api/ → nexus-backend:8089 生效）" ;;
            503) _warn "反代链路**通**（拿到完整 Result JSON），但后端依赖不可用（503）—— 故障定位已由 3/6 段给出" ;;
            *)   FE_PROXY_OK="no"
                 _fail "反代返回 HTTP ${NEXUS_HTTP_CODE}（拿到了 Result JSON，但既不是 200 也不是 503）" ;;
        esac
    else
        _fail "反代未生效：HTTP ${NEXUS_HTTP_CODE}，且响应体不是 Result JSON"
        _hint "若 body 是 HTML → 命中了 SPA 回退（try_files → /index.html）：这是**假通过**，别据此判定反代正常"
        _hint "核对 docker-compose/frontend/nginx.conf 的 location /api/ 与 upstream 端口（容器内 8089）"
        _hint "反证（预期 404 + code=40400，说明 /api 确实被转到了后端）：curl -s http://127.0.0.1:${NEXUS_FRONTEND_PORT}/api/actuator/health"
    fi
fi

# 承接 L1 的 nexus-frontend unhealthy：给出"假故障 vs 真故障"的结论与证据
if [ "$FRONTEND_HC_UNHEALTHY" = "yes" ]; then
    printf '\n'
    _info "── 承接 2/6 段的 nexus-frontend unhealthy：以下是判定依据 ──"
    if ! nexus_has_curl; then
        _warn "无法判定这条 unhealthy 是假故障还是真故障：宿主没有 curl（见 1/6 段）"
    else
        nexus_probe_frontend_loopback
        FE_HC_LOG="$(nexus_container_health_log nexus-frontend)"
        if [ "$FE_PAGE_OK" = "yes" ] && [ "$FE_PROXY_OK" = "yes" ]; then
            _info "结论：**已知假故障 —— 不是故障，服务本身完全正常**，不影响演示（2/6 段那条 WARN 就是它，别当成环境坏了）"
            _info "  证据① 容器内对照实验：${NEXUS_LOOPBACK_DETAIL:-（未取到）}"
            _info "  证据② healthcheck 报错原文：${FE_HC_LOG:-（未取到）}"
            _info "  证据③ 宿主功能探活：页面 200 ＋ 反代 /api/health 拿到完整 Result JSON"
            _info "  根因：healthcheck 命令是 wget -qO- localhost；容器内 localhost 优先解析到 ::1(IPv6)，而 nginx 只听 IPv4"
            _info "  处置：属**另一项待办**（docker-compose.yml 里 nexus-frontend 的 healthcheck 改用 127.0.0.1）"
            _info "       本脚本既不改 compose，也不因这条 healthcheck 判 FAIL —— 功能探活才是判据"
        else
            _fail "nexus-frontend unhealthy，且功能探活**未全部通过** ⇒ 这次不是假故障，按真故障查"
            _info "  容器内对照实验：${NEXUS_LOOPBACK_DETAIL:-（未取到）}"
            if [ "$FE_PAGE_OK" = "yes" ]; then _info "  页面探活：200（通）"; else _info "  页面探活：未通过"; fi
            if [ "$FE_PROXY_OK" = "yes" ]; then _info "  反代探活：拿到 Result JSON（通）"; else _info "  反代探活：未通过"; fi
            _hint "日志：docker logs --tail 50 nexus-frontend"
            _hint "容器内自测：docker exec nexus-frontend wget -qO- 127.0.0.1"
        fi
    fi
fi

# ── 汇总 ─────────────────────────────────────────────────────────────────────
END_TS="$(date +%s)"
ELAPSED=$((END_TS - START_TS))
# 实测踩过一次：WSL2 的时钟在脚本运行期间被校正，delta 算出来是负数（-2s）。
# 报告里出现负数会被当成脚本 bug，故显式标注"未测准"而不是硬打一个负值出来。
if [ "$ELAPSED" -lt 0 ]; then ELAPSED_DISP="未测准（WSL 时钟发生回跳）"; else ELAPSED_DISP="${ELAPSED}s"; fi
printf '\n%s\n' "------------------------------------------------------------------------------"
printf ' 合计：PASS %d / WARN %d / FAIL %d   （耗时约 %s）\n' \
    "$PASS_N" "$WARN_N" "$FAIL_N" "$ELAPSED_DISP"

if [ "$FAIL_N" -gt 0 ]; then
    printf '%s\n' " 失败清单（仅报告，不中止任何流程）："
    for item in "${FAIL_ITEMS[@]}"; do printf '   · %s\n' "$item"; done
fi

if [ "$RUNNING_N" -eq 0 ]; then
    printf '\n'
    if [ "$DOCKER_OK" != "yes" ]; then
        printf '%s\n' " 本轮看到 **0 个本项目容器在运行**，且 docker 引擎不可用 —— 先修 1/6 段。"
    else
        printf '%s\n' " 本轮看到 **0 个本项目容器在运行**。"
        printf '%s\n' " 若这是一台尚未启动的机器：这是**正常状态**（不是环境坏了），请先执行"
        printf '%s\n' "       ./scripts/up.sh"
        printf '%s\n' " 若你刚跑过 up.sh：说明拉起失败，按上面的 [FAIL] 项逐个查；容器清单用"
        printf '%s\n' "       cd ${NEXUS_COMPOSE_DIR} && docker compose ps -a     # 必须带 -a：一次性容器已退出，默认不显示"
    fi
elif [ "$FAIL_N" -gt 0 ]; then
    printf '\n 常用排查命令（WSL 内执行）：\n'
    printf '   cd %s && docker compose ps -a\n' "$NEXUS_COMPOSE_DIR"
    printf '   docker logs --tail 100 nexus-backend\n'
    printf '   curl -s http://127.0.0.1:%s/api/health\n' "$NEXUS_BACKEND_PORT"
    printf '   curl -s http://127.0.0.1:%s/actuator/health\n' "$NEXUS_BACKEND_PORT"
    printf '   docker inspect -f "{{json .State.Health}}" nexus-backend\n'
fi

if [ "$FAIL_N" -eq 0 ]; then
    printf '\n 结论：巡检通过（%d 项 WARN 已在上文逐条说明；WARN 不阻断）\n' "$WARN_N"
else
    printf '\n 结论：有 %d 项未通过，见上面的 [FAIL] 行\n' "$FAIL_N"
fi
printf '%s\n' " 退出码契约：0 = 无 FAIL；1 = 存在 FAIL（**只表达报告结论**，任何流程都不得据此中止）"
printf '%s\n' " 本脚本是运行期报告，不是启动门禁；环境未起时请先跑 ./scripts/up.sh"
printf '%s\n' "=============================================================================="

[ "$FAIL_N" -eq 0 ] || exit 1
exit 0

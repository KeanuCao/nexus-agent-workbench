#!/usr/bin/env bash
# =============================================================================
# scripts/up.sh —— 一键拉起全套环境（设计文档 §4.3 的九步流程）
#
# 执行位置：WSL 发行版 nexus-agent-workbench 内
#   cd /mnt/c/wp/nexus-agent-workbench && ./scripts/up.sh
# Windows 侧一键触发（写入 README）：
#   wsl -d nexus-agent-workbench -- bash -c "cd /mnt/c/wp/nexus-agent-workbench && ./scripts/up.sh"
#
# 九步（顺序即依赖关系，勿随意调整）：
#   1 前置自检（check-env.sh）—— 任一 FAIL 即中止
#   2 构建打包容器 builder       —— 运行期不常驻，只在构建/迁移时使用
#   3 拉起基础设施 postgres/redis/ollama（显式点名）
#   4 db-patch 迁移（**条件执行**：目录或命令不存在则 WARN 跳过）
#   5 构建前后端镜像（多阶段，共用 builder 作为 stage 1）
#   6 拉起应用容器 backend/frontend（同时启动一次性容器 ollama-init 拉模型）
#   7 等模型就绪（/api/tags，超时默认 30min，**不中止**只告警）
#   8 健康检查（容器级 / 业务级两层 + 前端与反代）
#   9 打印服务清单与常用命令
#
# 本脚本刻意避开的三条坑（每条都对应一次已核实的返工风险）：
#   ① 【模型就绪不能当前置门禁】由 check-env.sh 判 WARN、本脚本第 7 步轮询。
#      若前置判 FAIL，干净机器第一次 up.sh 永远过不了门禁，与"由 ollama-init 在
#      up 过程中拉模型"的设计自相矛盾。
#   ② 【第 4 步必须条件执行】/db-patch 目录当前不存在、builder 内也没有 db-patch-migrate
#      命令（0.2 未开工）—— 无条件执行会直接失败/卡死。
#   ③ 【第 2 步必须显式点名服务】docker compose build builder。裸 `build` 会把
#      nexus-backend / nexus-frontend 一起拉进构建（那时应用镜像还不可构建）。
#
# 关于 set -e 的取舍：本脚本**不启用** set -e。九步里有"必须中止"（构建失败、
# 依赖不健康）也有"只告警继续"（模型拉取超时）两类语义，全局 -e 会把后者也变成中止；
# 且 $(...) 里的非零返回（如 grep 无匹配）会被误判为致命错误。故显式判断每一步。
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/probe.sh
. "${SCRIPT_DIR}/lib/probe.sh"

nexus_load_env

# ── 可调参数（环境变量覆盖）──────────────────────────────────────────────────
MODEL_WAIT_TIMEOUT="${NEXUS_MODEL_WAIT_TIMEOUT:-1800}"   # 第 7 步：模型拉取总超时（30min）
HEALTH_WAIT_TIMEOUT="${NEXUS_HEALTH_WAIT_TIMEOUT:-180}"  # 第 8 步：依赖探活总超时
INFRA_WAIT_TIMEOUT="${NEXUS_INFRA_WAIT_TIMEOUT:-240}"    # 第 3 步：基础设施 healthy 超时
DB_PATCH_CMD="${NEXUS_DB_PATCH_CMD:-db-patch-migrate}"   # 第 4 步：迁移命令（0.2 交付物）
# 注：db-patch 由 builder 容器执行（含 psql/工具链），**不在后端启动流程中执行** ——
# 这样补丁失败时后端根本不会被拉起，比"启动后 503 门控"简单直接（设计文档 §3.2）。

# ── 输出与失败处理 ───────────────────────────────────────────────────────────
UP_FAIL_N=0
step() { printf '\n========== [%s] %s ==========\n' "$1" "$2"; }
ok()   { printf '  [PASS] %s\n' "$*"; }
warn() { printf '  [WARN] %s\n' "$*"; }
info() { printf '         %s\n' "$*"; }
upfail() { UP_FAIL_N=$((UP_FAIL_N + 1)); printf '  [FAIL] %s\n' "$*"; }
die()  { printf '\n  [FAIL] %s\n' "$*" >&2; exit 1; }

compose() { ( cd "$NEXUS_COMPOSE_DIR" && docker compose "$@" ); }

# 等某个容器转为 healthy（compose healthcheck 即容器级判据）
wait_healthy() {
    local name="$1" timeout="${2:-180}" start now st
    start="$(date +%s)"
    while :; do
        st="$(nexus_container_status "$name")"
        if [ "$st" = "running|healthy" ]; then return 0; fi
        now="$(date +%s)"
        if [ $((now - start)) -ge "$timeout" ]; then return 1; fi
        sleep 3
    done
}

printf '%s\n' "=============================================================================="
printf '%s\n' " nexus 一键拉起（up.sh）—— WSL 内执行，九步流程"
printf ' 仓库根 : %s\n' "$NEXUS_REPO_ROOT"
printf '%s\n' "=============================================================================="

# ── 1/9 前置自检 ─────────────────────────────────────────────────────────────
step "1/9" "前置自检（scripts/check-env.sh）"
if bash "${SCRIPT_DIR}/check-env.sh"; then
    ok "前置检查通过（无 FAIL；WARN 不阻断）"
else
    die "前置检查存在 FAIL → 已中止。按上面的失败清单逐条修完再重跑 up.sh"
fi

# ── 2/9 打包容器 ─────────────────────────────────────────────────────────────
step "2/9" "构建打包容器 nexus-builder（git + mvn 3.9/JDK17 + node20 + psql）"
info "显式点名服务 builder —— 裸 build 会把 nexus-backend/nexus-frontend 一起拉进构建"
info "builder 在 profiles: [\"build\"] 下，显式点名即激活其 profile（2026-09-11 实测）"
if compose build builder; then
    ok "nexus-builder:dev 就绪（后续前后端镜像与 db-patch 迁移都由它承担）"
else
    die "builder 构建失败：看上面的构建日志（Dockerfile 在 docker-compose/builder/）"
fi

# ── 3/9 基础设施 ─────────────────────────────────────────────────────────────
step "3/9" "拉起基础设施 postgres / redis / ollama（显式点名，不裸 up -d）"
info "裸 up -d 会连带构建并启动应用镜像 —— 基础设施先起、健康后再谈应用"
if ! compose up -d postgres redis ollama; then
    die "基础设施启动失败：docker compose up -d postgres redis ollama 退出码非 0"
fi
for svc in nexus-postgres nexus-redis; do
    if wait_healthy "$svc" "$INFRA_WAIT_TIMEOUT"; then
        ok "$svc healthy"
    else
        die "$svc 在 ${INFRA_WAIT_TIMEOUT}s 内未转为 healthy（证据：$(nexus_container_status "$svc")；日志：docker logs --tail 50 $svc）"
    fi
done
# ollama 不健康会让第 6 步卡住：ollama-init 的 depends_on 是 ollama(service_healthy)，
# 而 compose 等依赖健康是没有超时的 —— 与其在后面静默挂住，不如在这里明确失败。
if wait_healthy nexus-ollama "$INFRA_WAIT_TIMEOUT"; then
    ok "nexus-ollama healthy"
else
    die "nexus-ollama 在 ${INFRA_WAIT_TIMEOUT}s 内未转为 healthy：ollama-init 依赖它，会卡在第 6 步 waiting（日志：docker logs --tail 50 nexus-ollama）"
fi

# ── 4/9 db-patch 迁移（条件执行）─────────────────────────────────────────────
step "4/9" "执行 db-patch 数据库补丁迁移（由 builder 容器执行，条件触发）"
DB_PATCH_DIR="${NEXUS_REPO_ROOT}/db-patch"
if [ ! -d "$DB_PATCH_DIR" ]; then
    warn "跳过：补丁目录 ${DB_PATCH_DIR} 不存在"
    info "→ 0.2（db-patch 工作流）尚未开工，属**预期状态**，不视为失败"
    info "→ 设计：迁移由 builder 容器执行（PatchCli），不在后端启动流程中；0.2 交付后本步自动生效"
elif ! compose run --rm -T --entrypoint sh builder -c "command -v ${DB_PATCH_CMD} >/dev/null 2>&1"; then
    warn "跳过：builder 容器内找不到 '${DB_PATCH_CMD}' 命令（0.2 未交付）"
    info "→ 补丁目录已存在但迁移入口未实现；仍按"存在才执行"处理，避免直接把 up.sh 卡死"
else
    info "执行：docker compose run --rm builder ${DB_PATCH_CMD}"
    if compose run --rm -T builder "$DB_PATCH_CMD"; then
        ok "db-patch 迁移完成（未执行补丁按文件名排序应用；已执行补丁走 checksum 幂等）"
    else
        die "db-patch 迁移失败 → 迁移终止，后端不会启动（看上面的 PatchCli 输出：SQL 报错或历史补丁 checksum 不一致）"
    fi
fi

# ── 5/9 前后端镜像 ───────────────────────────────────────────────────────────
step "5/9" "构建前后端镜像（多阶段；共用 nexus-builder 作为 stage 1，运行镜像各自瘦身）"
info "backend → eclipse-temurin:17-jre-alpine；frontend → nginx:1.27-alpine"
if compose build; then
    ok "nexus-backend:dev / nexus-frontend:dev 构建完成"
else
    die "镜像构建失败：看上面的构建日志（backend 依赖 target/*.jar 唯一命中；frontend 依赖 package-lock.json 与 dist 产物）"
fi

# ── 6/9 应用容器 ─────────────────────────────────────────────────────────────
step "6/9" "拉起应用容器 nexus-backend / nexus-frontend（并启动一次性容器 ollama-init 拉模型）"
info "前端容器 depends_on 后端 service_healthy —— 后端不健康，前端不会被拉起"
if ! compose up -d; then
    die "应用容器启动失败：docker compose up -d 退出码非 0"
fi
ok "容器已下发；进入等待阶段（模型拉取与依赖探活都是轮询，慢属正常）"

# ── 7/9 模型就绪（超时只告警，不中止）────────────────────────────────────────
EXPECTED_MODELS="$(nexus_expected_models)"
step "7/9" "等待 Ollama 模型就绪（GET /api/tags，超时 ${MODEL_WAIT_TIMEOUT}s）"
info "判据：models[].name 同时存在 [${EXPECTED_MODELS}]（:latest 已归一化后比较）"
info "为什么单独查 /api/tags：容器 healthy ≠ 模型就绪，后端 checks.ollama 只打 /api/version"
nexus_wait_models_ready "$MODEL_WAIT_TIMEOUT" 10
MODELS_RC=$?
case "$MODELS_RC" in
    0) ok "模型就绪：${EXPECTED_MODELS}" ;;
    2) warn "ollama-init 已退出且退出码非 0 → 模型拉取失败（网络中断类问题居多）"
       info "日志：docker logs --tail 50 nexus-ollama-init"
       info "断点续拉：docker compose -f ${NEXUS_COMPOSE_FILE} up -d ollama-init   （重跑一次性容器，blob 按 digest 复用不重下）"
       info "或逐个补拉：for m in ${EXPECTED_MODELS}; do docker exec nexus-ollama ollama pull \$m; done" ;;
    *) warn "等待模型超时（${MODEL_WAIT_TIMEOUT}s）—— 不中止，其余服务继续检查"
       if [ -n "$NEXUS_MODELS_MISSING" ]; then
           info "仍缺失：${NEXUS_MODELS_MISSING}"
           info "断点续拉：for m in ${NEXUS_MODELS_MISSING}; do docker exec nexus-ollama ollama pull \$m; done"
       else
           info "ollama 进程尚未响应：docker logs --tail 50 nexus-ollama"
       fi
       info "模型落在命名卷 ollama-models，一次下载永久复用；重跑 up.sh 不会重下已完成的 blob" ;;
esac

# ── 8/9 健康检查（分两层，两个探活口不可混用）────────────────────────────────
step "8/9" "健康检查（容器级 / 业务级两层；两个探活口不可混用）"
info "L1 容器级 = compose healthcheck → 后端打 /actuator/health（Boot 内建 db/redis/diskSpace，**不含 ollama**）"
info "L2 业务级 = GET /api/health（覆盖 postgres/redis/ollama；任一 DOWN 返 503，用 data.checks 定位具体依赖）"

printf '\n  L1 容器级：\n'
for c in $NEXUS_RESIDENT_CONTAINERS; do
    st="$(nexus_container_status "$c")"
    s="${st%%|*}"; h="${st#*|}"
    case "$s" in
        running)
            case "$h" in
                healthy)  ok "$c running / healthy" ;;
                starting) warn "$c running / starting（探活启动窗口内，最终结论以下面的 L2 为准）" ;;
                unhealthy) warn "$c running / unhealthy（unhealthy 不触发重启，且**可能是 healthcheck 探活命令本身写错**而非服务问题）"
                           info "   两份证据一起看：以 L2/L3 的**实际探活结果**为准；docker inspect $c 可看 healthcheck 的报错原文" ;;
                *)        ok "$c running（该容器未配置 healthcheck）" ;;
            esac ;;
        missing) upfail "$c 容器不存在（第 6 步的 docker compose up -d 没创建它？）" ;;
        *)       upfail "$c 状态异常：${s}（期望 running）" ;;
    esac
done
for c in $NEXUS_ONESHOT_CONTAINERS; do
    st="$(nexus_oneshot_state "$c")"
    s="${st%%|*}"; ec="${st#*|}"
    case "$s" in
        exited)
            if [ "$ec" = "0" ]; then ok "$c 已退出(0)：模型拉取成功（一次性容器，退出码即权威判据）"
            else warn "$c 已退出(${ec})：模型拉取失败（详见第 7 步指引；不阻断本次拉起）"; fi ;;
        running) warn "$c 仍在拉取模型（进行中）" ;;
        created) warn "$c 已创建未启动（依赖 ollama 健康；稍后重跑 up.sh 会带上它）" ;;
        *)       warn "$c 状态：${s:-未知}" ;;
    esac
done
info "注：nexus-builder 不纳入运行期检查（profiles 隔离、运行期不常驻，用完即走）"

printf '\n  L2 业务级（判据：连续 2 次 HTTP 200 且 code=0，间隔 5s —— 单次快照会误判抖动）：\n'
if nexus_wait_api_health_up "$HEALTH_WAIT_TIMEOUT"; then
    ok "GET http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health → UP"
    info "checks: ${NEXUS_HEALTH_CHECKS}"
    [ -n "$NEXUS_HEALTH_NOTE" ] && info "注：${NEXUS_HEALTH_NOTE}"
else
    upfail "GET http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health 未在 ${HEALTH_WAIT_TIMEOUT}s 内就绪"
    info "最后一次探测：state=${NEXUS_HEALTH_STATE} http=${NEXUS_HEALTH_HTTP:-?} code=${NEXUS_HEALTH_CODE:-?}"
    info "checks: ${NEXUS_HEALTH_CHECKS:-（无）}"
    case "$NEXUS_HEALTH_STATE" in
        NO_RESPONSE) info "→ 后端进程没起 / 端口不对（注意 BACKEND_PORT=${NEXUS_BACKEND_PORT}）"
                     info "   docker logs --tail 100 nexus-backend" ;;
        DEGRADED)    info "→ 依赖不可用：${NEXUS_HEALTH_DETAIL}（503 是契约内的正常响应，不是接口挂了）"
                     info "   逐项排查：docker logs --tail 50 nexus-<依赖名>；恢复后重跑本脚本即可" ;;
        CONTRACT_BROKEN) info "→ 契约被破坏：${NEXUS_HEALTH_DETAIL}"
                     info "   data.version 类缺陷属**构建层**问题：查 nexus-start 的 maven-resources-plugin 过滤"
                     info "   交叉验证：curl -s http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/actuator/info" ;;
        NOT_FOUND)   info "→ 404：路径写错或打到了别的服务（/api 前缀别丢）" ;;
        ERROR)       info "→ 500：后端内部异常，docker logs --tail 100 nexus-backend" ;;
        NO_CURL)     info "→ 宿主没有 curl（见 check-env.sh 第 0 项）" ;;
        *)           info "→ ${NEXUS_HEALTH_DETAIL}" ;;
    esac
fi

printf '\n  L3 前端与 nginx 反代：\n'
if [ "$UP_FAIL_N" -gt 0 ]; then
    info "后端未就绪，跳过前端检查（前端容器 depends_on 后端 healthy，此刻本就不会起来）"
else
    FE_OK="no"
    FE_WAIT=0
    while [ "$FE_WAIT" -lt 120 ]; do
        nexus_http_get "http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/" 5
        [ "$NEXUS_HTTP_CODE" = "200" ] && { FE_OK="yes"; break; }
        sleep 3; FE_WAIT=$((FE_WAIT + 3))
    done
    if [ "$FE_OK" = "yes" ]; then
        ok "前端页面 http://localhost:${NEXUS_FRONTEND_PORT} 返回 200（8088 是宿主端口，80 是容器内端口）"
    else
        upfail "前端页面 http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT} 未返回 200（实得 ${NEXUS_HTTP_CODE}）"
        info "→ docker logs --tail 50 nexus-frontend；确认 FRONTEND_PORT=${NEXUS_FRONTEND_PORT}"
    fi
    # 反代判据只用 /api/health：/api/actuator/health 必然 404（nginx 的 location /api/ 无尾路径
    # = 保留 /api 前缀，而 Actuator 路径不带 /api），走前端端口查 actuator 恒失败，别踩。
    nexus_http_get "http://${NEXUS_PROBE_HOST}:${NEXUS_FRONTEND_PORT}/api/health" 10
    if printf '%s' "$NEXUS_HTTP_BODY" | grep -q '"code"'; then
        case "$NEXUS_HTTP_CODE" in
            200) ok "nginx 反代 /api → nexus-backend:8089 链路通（拿到完整 Result JSON）" ;;
            503) warn "nginx 反代链路通，但后端依赖不可用（503 + Result JSON，已由 L2 覆盖）" ;;
            *)   upfail "nginx 反代返回 HTTP ${NEXUS_HTTP_CODE}（拿到了 Result JSON，但状态码非预期）" ;;
        esac
    else
        upfail "nginx 反代未生效：HTTP ${NEXUS_HTTP_CODE} 且响应体不是 Result JSON"
        info "→ 若返回的是 HTML，说明打到了 SPA 回退（try_files → /index.html）—— 这是"假通过"，"
        info "  务必核对 docker-compose/frontend/nginx.conf 的 location /api/ 与容器内端口 8089"
    fi
fi

# ── 9/9 服务清单 ─────────────────────────────────────────────────────────────
step "9/9" "服务清单与常用命令"
compose ps -a
printf '\n  入口地址（宿主端口取自 .env，改端口只改一处）：\n'
printf '    前端页面   http://localhost:%s\n' "$NEXUS_FRONTEND_PORT"
printf '    业务健康   http://localhost:%s/api/health          （覆盖 postgres/redis/ollama）\n' "$NEXUS_BACKEND_PORT"
printf '    容器健康   http://localhost:%s/actuator/health     （容器自身依赖，不含 ollama）\n' "$NEXUS_BACKEND_PORT"
printf '    Ollama    http://localhost:%s/api/tags            （模型清单）\n' "$NEXUS_OLLAMA_PORT"
printf '    PostgreSQL localhost:%s（%s/%s）  Redis localhost:%s\n' \
    "$NEXUS_PG_PORT" "$NEXUS_PG_USER" "$NEXUS_PG_DB" "$NEXUS_REDIS_PORT"
printf '\n  常用命令（都在 WSL 内、docker-compose 目录下执行）：\n'
printf '    docker compose ps -a                      # 容器状态（-a 才看得到已退出的一次性容器）\n'
printf '    docker compose logs -f nexus-backend      # 后端日志\n'
printf '    docker compose stop / docker compose down # 停止（数据在命名卷，down 不会丢库）\n'
printf '    ./scripts/check-env.sh                    # 重新体检（含端口/镜像源/模型）\n'
printf '\n  前端本地开发（Windows 侧）：cd frontend && npm install && npm run dev  → http://localhost:5173\n'

# ── 汇总 ─────────────────────────────────────────────────────────────────────
printf '\n%s\n' "=============================================================================="
if [ "$UP_FAIL_N" -eq 0 ]; then
    printf '%s\n' " 结论：环境已就绪（后端 /api/health = UP，前端页面可达）"
    exit 0
else
    printf ' 结论：有 %d 项未通过，见上面的 [FAIL] 行\n' "$UP_FAIL_N"
    printf '%s\n' " 提示：容器已起来时，单独重跑第 8 步的判据可看 ./scripts/check-env.sh；"
    printf '%s\n' "       依赖故障恢复后直接重跑 ./scripts/up.sh 即可（全流程幂等）"
    exit 1
fi

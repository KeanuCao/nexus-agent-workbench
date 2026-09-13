#!/usr/bin/env bash
# =============================================================================
# scripts/lib/probe.sh —— 共享探针库（check-env.sh / up.sh / 后续 check-health.sh 共用）
#
# 为什么单独抽一个库（面试可讲的取舍）：
#   "依赖是否就绪"的判据如果在多个脚本里各写一份，迟早漂移 —— 典型事故是 up.sh 的轮询
#   放宽了某条判据、check-env.sh 还在用旧版，于是出现"前置检查全绿、一键启动却卡住"
#   这种最难查的不一致。本文件是这些判据的**唯一实现**，调用脚本只做编排与打印。
#
# 判据来源（唯一事实源，禁止从 openapi.yaml 或 docs/drafts/ 反推）：
#   docs/api/README.md §4 —— §4.1 两个探活口分工 / §4.2 判定规则表 /
#                            §4.3 轮询与超时 / §4.5 模型就绪独立判据
#
# 四条硬约束（每条都对应一个已核实的坑，改动前先读）：
#   ① 两个探活口不可混用：容器级打 /actuator/health（Boot 内建 db/redis/diskSpace，
#      **不含 ollama**）；业务级打 /api/health（覆盖 postgres/redis/ollama，任一 DOWN 返 503）。
#      只查容器状态的脚本会**完全漏掉 AI 依赖故障**：stop ollama 后后端容器仍然 healthy。
#   ② 取 body 一律 `curl -s`，**不加 -f**：503 的 body 才是定位"哪个依赖挂了"的唯一信息，
#      `-f` 会在 503 时丢弃 body 并返回非 0，等于把最有用的信息扔掉。
#   ③ 端口/凭据/期望模型名一律从 docker-compose/.env 与 compose 文件解析，脚本内不硬编码
#      —— 尤其不要写 8080：后端宿主端口是 BACKEND_PORT=8089。
#   ④ 复用 wsl.abc.md §3.6 的实测结论：`docker inspect -f '{{...}}'` 在**双层 shell 引号**
#      场景下会取到空值；本文件是脚本文件执行（引号不被外层剥掉），可以用 -f，
#      但每个取状态的地方仍保留去 -f 的兜底分支，避免环境差异导致静默取空。
#
# 调用约定：
#   · 纯探针函数（nexus_probe_*）只"读环境、答问题"，不打印装饰性输出、不 exit；
#     需要返回多个值时写全局变量（NEXUS_*），因此**必须在当前 shell 调用，不要放进 $( )**。
#   · 等待类函数（nexus_wait_*）会打印进度（它们天然是给人看的），返回 0/1。
#   · 只用 bash 3.2 就有的语法（无关联数组、无间接展开）；实测环境为 WSL 内 bash 5.2。
#
# 与 0.3.5 规格的函数命名对照（**本文件保留 nexus_ 前缀，不做一次性改名**）：
#   0.3.5 规格名             → 本文件实现                    说明
#   probe_container_state    → nexus_container_status       输出 "<state>|<health>"：容器级判据要 health，单给 state 不够
#   probe_ollama_init_exit   → nexus_oneshot_state          输出 "<state>|<exitcode>"：必须区分"没跑过/正在跑/已退出"，
#                                                           单给退出码无法区分"还没跑"（= 正常的干净机器）
#   probe_api_health         → nexus_probe_api_health       同义（仅加前缀）
#   probe_actuator_health    → nexus_probe_actuator_health  同义（本次 0.3.3 新增）
#   probe_models_ready       → nexus_probe_models           不返回 0/1：就绪判据要能说清"缺哪几个"、"是不是 ollama 不可达"
#                                                           —— 布尔装不下，故写 NEXUS_MODELS_* 全局
#   probe_port_of <变量名>    → nexus_env_value <键> <兜底>    泛化为读 .env 任意键（凭据同样来自 .env）；
#                                                           端口读取方式（.env 唯一真源 + 与 compose 一致的兜底值）未变
#   为什么加 nexus_ 前缀：本文件被 `source` 进调用方 shell，裸名（probe_*）太通用、易与其他库撞名；
#   前缀还能让 `grep -n nexus_probe scripts/` 一次找全所有调用点。
#   为什么不做改名对齐：改名要同步改 up.sh / check-env.sh 两处**已交付**脚本，行为收益为零，
#   却会把"已评审的文件"重新变成未评审状态。语义差异已在上表逐条说明，评审可直接对照。
# =============================================================================

# ── 路径：本文件位于 <repo>/scripts/lib/probe.sh ──────────────────────────────
NEXUS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEXUS_REPO_ROOT="$(cd "${NEXUS_LIB_DIR}/../.." && pwd)"
NEXUS_COMPOSE_DIR="${NEXUS_REPO_ROOT}/docker-compose"
NEXUS_ENV_FILE="${NEXUS_COMPOSE_DIR}/.env"
NEXUS_COMPOSE_FILE="${NEXUS_COMPOSE_DIR}/docker-compose.yml"

# 探活目标地址一律用 127.0.0.1 而非 localhost：
#   WSL 内 localhost 可能同时解析出 ::1 与 127.0.0.1，而 docker 的端口发布默认只监听 IPv4
#   （除非 daemon 开了 ipv6）。走 127.0.0.1 结果确定，不会出现"有时通有时不通"。
NEXUS_PROBE_HOST="127.0.0.1"

# ── 常驻容器清单（与 docker-compose.yml 一一对应；builder 为手工启停的构建容器，不在此列）──
NEXUS_RESIDENT_CONTAINERS="nexus-postgres nexus-redis nexus-ollama nexus-backend nexus-frontend"
# 一次性容器：退出码是"模型是否拉取成功"的唯一权威判据（§4.5）
NEXUS_ONESHOT_CONTAINERS="nexus-ollama-init"

# =============================================================================
# 配置读取
# =============================================================================

# 从 docker-compose/.env 读单个键（.env 是宿主端口/凭据的唯一真源）；缺失或为空时用兜底值。
# 兜底值必须与 compose 里的 ${VAR:-默认值} 逐一一致，否则会出现
# "脚本按 A 端口判断、compose 实际用 B 端口"的诡异现象。
nexus_env_value() {
    local key="$1" fallback="${2-}" value=""
    if [ -f "$NEXUS_ENV_FILE" ]; then
        value="$(grep -E "^[[:space:]]*${key}=" "$NEXUS_ENV_FILE" 2>/dev/null \
            | head -n1 | cut -d= -f2- | tr -d '\r' \
            | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"//' -e 's/"$//')"
    fi
    if [ -n "$value" ]; then printf '%s' "$value"; else printf '%s' "$fallback"; fi
}

# 载入全部环境变量到 NEXUS_* 全局（compose 侧默认值见 docker-compose/docker-compose.yml）
nexus_load_env() {
    NEXUS_PG_PORT="$(nexus_env_value PG_PORT 5432)"
    NEXUS_REDIS_PORT="$(nexus_env_value REDIS_PORT 6379)"
    NEXUS_OLLAMA_PORT="$(nexus_env_value OLLAMA_PORT 11434)"
    NEXUS_BACKEND_PORT="$(nexus_env_value BACKEND_PORT 8089)"
    NEXUS_FRONTEND_PORT="$(nexus_env_value FRONTEND_PORT 8088)"
    NEXUS_PG_USER="$(nexus_env_value PG_USER nexus)"
    NEXUS_PG_PASSWORD="$(nexus_env_value PG_PASSWORD nexus123)"
    NEXUS_PG_DB="$(nexus_env_value PG_DB nexus)"
}

# 期望的模型清单：唯一真源是 compose 的 ollama-init 命令（`for model in A B`）。
# 不在脚本里硬编码，是为了改模型清单时不会漏改脚本（§4.5 明确要求同步）；解析失败才用兜底。
nexus_expected_models() {
    local list=""
    if [ -f "$NEXUS_COMPOSE_FILE" ]; then
        list="$(grep -oE 'for model in [^;]*' "$NEXUS_COMPOSE_FILE" 2>/dev/null \
            | head -n1 | sed 's/for model in //' | tr -d '\r')"
    fi
    if [ -n "$list" ]; then printf '%s' "$list"; else printf '%s' "qwen2.5:7b nomic-embed-text"; fi
}

# 由容器名反查 compose 服务名 —— 给用户的命令必须是**服务名**：
#   `docker compose up -d nexus-postgres` 会报 `no such service`（陷阱 7 的同源坑：
#   服务名 postgres / redis / ollama / ollama-init / nexus-backend / nexus-frontend，
#   容器名才是 nexus-* 前缀）。从 compose 文件解析而非硬编码映射表 —— 改了 compose 不会漏改脚本。
# 解析不到时原样返回容器名（调用方给出的是"提示"，不参与判定）。
nexus_service_of() {
    local cname="$1" svc=""
    if [ -f "$NEXUS_COMPOSE_FILE" ]; then
        svc="$(awk -v target="container_name: ${cname}" '
            /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { s = $1; sub(/:$/, "", s) }
            index($0, target) > 0 { print s; exit }
        ' "$NEXUS_COMPOSE_FILE" 2>/dev/null | tr -d '\r')"
    fi
    printf '%s' "${svc:-$cname}"
}

# =============================================================================
# 基础工具探测
# =============================================================================

nexus_is_wsl()        { uname -r 2>/dev/null | grep -qi microsoft; }
nexus_has_jq()        { command -v jq >/dev/null 2>&1; }
nexus_has_curl()      { command -v curl   >/dev/null 2>&1; }
nexus_has_ss()        { command -v ss     >/dev/null 2>&1; }

# 带超时执行（timeout 缺失时退化为直接执行）—— 避免 dockerd 无响应时脚本永远挂住
nexus_run_timeout() {
    local secs="$1"; shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$secs" "$@"
    else
        "$@"
    fi
}

# =============================================================================
# HTTP 与 JSON 取值
# =============================================================================

# 用法：nexus_http_get <url> [max_time]
# 结果写全局 NEXUS_HTTP_CODE / NEXUS_HTTP_BODY（不要放进 $( )，子 shell 里全局不会回传）。
# 连接失败时 curl 的 -w 仍会输出 000，调用方据此区分"进程没起"与"依赖挂了"（§4.1 ③）。
nexus_http_get() {
    local url="$1" max_time="${2:-10}" resp=""
    NEXUS_HTTP_CODE="000"
    NEXUS_HTTP_BODY=""
    if ! nexus_has_curl; then
        NEXUS_HTTP_CODE="NO_CURL"
        return
    fi
    # -s 静默进度；--max-time 见 §4.3（/api/health 依赖全挂时最坏约 7s，故给 10s）；
    # 不加 -f（约束 ②）；-w 把状态码追加到 body 之后，一次请求同时拿到两者
    resp="$(curl -s --max-time "$max_time" -w $'\n%{http_code}' "$url" 2>/dev/null)" || true
    NEXUS_HTTP_CODE="$(printf '%s' "$resp" | tail -n1)"
    NEXUS_HTTP_BODY="$(printf '%s' "$resp" | sed '$d')"
    [ -n "$NEXUS_HTTP_CODE" ] || NEXUS_HTTP_CODE="000"
}

# 用法：nexus_json_field <json> <field>
# field ∈ code | msg | status | service | version | timestamp | checks_raw | checks_keys | check.<依赖名>
#   checks_keys 输出实际键名（空格分隔，如 "postgres redis ollama"）——
#   §4.2.2 要求 checks 键数**恰好 3 个**，键数/未知键只能从真实键名算，不能拿
#   "postgres=UP redis=UP ollama=UP" 这个三元组去数（键被改名时它仍是 3 个，会把契约破坏漏过去）。
# 有 jq 用 jq；无 jq 走 sed/grep 兜底 —— 兜底可用性有前提：后端 Jackson 默认非美化输出、
# 字段顺序固定（Result{code,msg,data} / HealthReport{status,service,version,timestamp,checks}
# / HealthChecks{postgres,redis,ollama}），顺序即契约，见 §4.2.3。
nexus_json_field() {
    local json="$1" field="$2"
    [ -n "$json" ] || return 0
    if nexus_has_jq; then
        case "$field" in
            code)        printf '%s' "$json" | jq -r '.code // empty' 2>/dev/null ;;
            msg)         printf '%s' "$json" | jq -r '.msg // empty' 2>/dev/null ;;
            status)      printf '%s' "$json" | jq -r '.data.status // empty' 2>/dev/null ;;
            service)     printf '%s' "$json" | jq -r '.data.service // empty' 2>/dev/null ;;
            version)     printf '%s' "$json" | jq -r '.data.version // empty' 2>/dev/null ;;
            timestamp)   printf '%s' "$json" | jq -r '.data.timestamp // empty' 2>/dev/null ;;
            checks_raw)  printf '%s' "$json" | jq -r 'if .data.checks then (.data.checks | to_entries | map("\(.key)=\(.value)") | join(" ")) else "" end' 2>/dev/null ;;
            checks_keys) printf '%s' "$json" | jq -r 'if .data.checks then (.data.checks | keys | join(" ")) else "" end' 2>/dev/null ;;
            check.*)     printf '%s' "$json" | jq -r ".data.checks.${field#check.} // empty" 2>/dev/null ;;
        esac
        return 0
    fi
    # 每个模式都容忍 `:` 后的空白：后端 Jackson 输出是紧凑的（无空格），但换个序列化配置
    # 或拿别的工具造的样例就可能带空格 —— 实测中正是"带空格的 JSON"让本兜底静默取到空值，
    # 进而把健康状态误报成 CONTRACT_BROKEN。多写一个 [[:space:]]* 的代价远小于误判。
    case "$field" in
        # 顶层 code 锚定在开头，避免匹配到嵌套字段；msg 允许包含中文与全角标点（不含双引号）
        code)        printf '%s' "$json" | sed -n 's/^{"code":[[:space:]]*\([^,}]*\).*/\1/p' | head -n1 ;;
        msg)         printf '%s' "$json" | sed -n 's/.*"msg":[[:space:]]*"\([^"]*\)".*/\1/p'  | head -n1 ;;
        status)      printf '%s' "$json" | sed -n 's/.*"status":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1 ;;
        service)     printf '%s' "$json" | sed -n 's/.*"service":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1 ;;
        version)     printf '%s' "$json" | sed -n 's/.*"version":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1 ;;
        timestamp)   printf '%s' "$json" | sed -n 's/.*"timestamp":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1 ;;
        # checks 是**最后**一个字段（HealthReport record 声明顺序），取到行尾前的最后一个 } 亦可。
        # 归一化成 "k=v k=v"（与 jq 分支输出格式一致）：先删掉所有空白，再把 ":" 换成 =
        # —— 否则 `"postgres": "UP"` 会变成 `postgres= UP`，后续按键取值就取到空串（实测踩过）。
        checks_raw)  printf '%s' "$json" | sed -n 's/.*"checks":[[:space:]]*{\([^}]*\)}.*/\1/p' | head -n1 \
                         | tr -d '[:space:]' | sed 's/":/=/g; s/"//g; s/,/ /g' ;;
        # 键名 = checks_raw 里每个 "k=v" 的 k（顺序与后端 record 声明一致：postgres → redis → ollama）
        checks_keys) nexus_json_field "$json" checks_raw | tr ' ' '\n' | sed -n 's/^\([^=]*\)=.*/\1/p' | paste -sd' ' - ;;
        check.*)     nexus_json_field "$json" checks_raw | tr ' ' '\n' | sed -n "s/^${field#check.}=//p" | head -n1 ;;
    esac
}

# =============================================================================
# 业务级探活：GET /api/health（覆盖 postgres/redis/ollama 三项）
# =============================================================================

# 结果写全局：
#   NEXUS_HEALTH_STATE ∈ UP | DEGRADED | NO_RESPONSE | NOT_FOUND | ERROR | ABNORMAL | CONTRACT_BROKEN | NO_CURL
#   NEXUS_HEALTH_HTTP / NEXUS_HEALTH_CODE / NEXUS_HEALTH_MSG / NEXUS_HEALTH_CHECKS / NEXUS_HEALTH_DETAIL
#   NEXUS_HEALTH_NOTE  —— 不影响判定的观察（如 timestamp 用 Z 表示 UTC），由调用方选择性打印
#   NEXUS_HEALTH_BODY  —— 原始响应体：调用方据此做 §4.2.2 的**额外字段审计**
#                         （data.service 固定值、data.status 与 HTTP 一致性、checks 键数恰好 3）
#   NEXUS_HEALTH_SERVICE / NEXUS_HEALTH_STATUS / NEXUS_HEALTH_VERSION / NEXUS_HEALTH_TIMESTAMP —— 取值，供报告打印
#   NEXUS_HEALTH_VERSION_OK / NEXUS_HEALTH_TS_OK —— 本函数**已算出**的形态判定（yes / no / "" = 本轮未走到该步）
#     为什么把判定结果带出来而不是让调用方重写正则：形态正则属"判据"，只该在本文件出现一次
#     （否则 check-health.sh 里会再长出一份 version/timestamp 正则，两处迟早漂移）。
#     "严重性由调用方决定"：本函数只回答"形态对不对"，报 PASS 还是 FAIL 由报告脚本定。
# 语义（§4.2.1 穷举表）：
#   UP              200 + code=0 + 三项 checks 全 UP
#   DEGRADED        503 + 完整 Result（依赖不可用，正常契约响应，**不是接口挂了**）
#   NO_RESPONSE     curl 拿不到响应（exit 7 语义）→ 后端进程没起 / 端口不对（BACKEND_PORT）
#   NOT_FOUND       404 / code 40400 → 路径写错或版本不匹配
#   ERROR           500 / code 50000 → 后端内部异常，看 docker logs nexus-backend
#   ABNORMAL        其他 HTTP 码
#   CONTRACT_BROKEN 拿到响应但不符合契约：HTML 假通过 / checks 出现第三值 / status 与 HTTP 不一致等
nexus_probe_api_health() {
    local url="http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/api/health"
    NEXUS_HEALTH_STATE="UNKNOWN"; NEXUS_HEALTH_HTTP=""; NEXUS_HEALTH_CODE=""
    NEXUS_HEALTH_MSG=""; NEXUS_HEALTH_CHECKS=""; NEXUS_HEALTH_DETAIL=""; NEXUS_HEALTH_NOTE=""
    NEXUS_HEALTH_BODY=""; NEXUS_HEALTH_SERVICE=""; NEXUS_HEALTH_STATUS=""
    NEXUS_HEALTH_VERSION=""; NEXUS_HEALTH_TIMESTAMP=""
    NEXUS_HEALTH_VERSION_OK=""; NEXUS_HEALTH_TS_OK=""

    nexus_http_get "$url" 10
    NEXUS_HEALTH_HTTP="$NEXUS_HTTP_CODE"
    NEXUS_HEALTH_BODY="$NEXUS_HTTP_BODY"

    case "$NEXUS_HTTP_CODE" in
        NO_CURL) NEXUS_HEALTH_STATE="NO_CURL";        NEXUS_HEALTH_DETAIL="宿主没有 curl，无法探活"; return ;;
        000)     NEXUS_HEALTH_STATE="NO_RESPONSE";    NEXUS_HEALTH_DETAIL="curl 未取得响应（后端进程没起 / 端口不对）"; return ;;
    esac

    # 假通过陷阱（§4.2.1 表末行）：打错地址时可能拿到 200 + HTML（nginx SPA 回退、
    # 或别的东西占了这个端口）。先验 JSON 形状，再谈状态码。
    if ! printf '%s' "$NEXUS_HTTP_BODY" | grep -q '"code"'; then
        NEXUS_HEALTH_STATE="CONTRACT_BROKEN"
        NEXUS_HEALTH_DETAIL="响应体不是 Result JSON（疑似 HTML / 打到了 nginx 的 SPA 回退）"
        return
    fi

    local code msg
    code="$(nexus_json_field "$NEXUS_HTTP_BODY" code)"
    msg="$(nexus_json_field "$NEXUS_HTTP_BODY" msg)"
    NEXUS_HEALTH_CODE="$code"; NEXUS_HEALTH_MSG="$msg"
    # 取值（只记录，不在本函数判）：data.service 固定值、data.status 与 HTTP 的一致性由调用方审计（§4.2.2）
    NEXUS_HEALTH_SERVICE="$(nexus_json_field "$NEXUS_HTTP_BODY" service)"
    NEXUS_HEALTH_STATUS="$(nexus_json_field "$NEXUS_HTTP_BODY" status)"

    # 先分流"本就不带完整报告的失败出口"：404 / 500 时 data 可以是 null（契约 §1.1
    # "失败且无数据时为 null"），**不能**把"checks 缺失"当成契约破坏 —— 否则真实的
    # 404/500 会被误报成 CONTRACT_BROKEN，把排查方向带偏（mock 实测踩到过）。
    case "$NEXUS_HTTP_CODE" in
        200|503) ;;  # 继续做完整字段校验
        404) NEXUS_HEALTH_STATE="NOT_FOUND"; NEXUS_HEALTH_DETAIL="404 → 路径写错或版本不匹配（/api 前缀别丢）"; return ;;
        500) NEXUS_HEALTH_STATE="ERROR";     NEXUS_HEALTH_DETAIL="500 → 后端内部异常，看 docker logs --tail 100 nexus-backend"; return ;;
        *)   NEXUS_HEALTH_STATE="ABNORMAL";  NEXUS_HEALTH_DETAIL="非预期 HTTP ${NEXUS_HTTP_CODE}"; return ;;
    esac

    local pg rd ol
    pg="$(nexus_json_field "$NEXUS_HTTP_BODY" check.postgres)"
    rd="$(nexus_json_field "$NEXUS_HTTP_BODY" check.redis)"
    ol="$(nexus_json_field "$NEXUS_HTTP_BODY" check.ollama)"
    NEXUS_HEALTH_CHECKS="postgres=${pg:-?} redis=${rd:-?} ollama=${ol:-?}"

    # 取值域只有 UP / DOWN（后端刻意不复用 Spring HealthStatus，就没有 OUT_OF_SERVICE/UNKNOWN）；
    # 出现第三值 = 契约已被擅自变更 → 必须报错而不是"看着像通过就放过"（§4.2.2）
    local v
    for v in "$pg" "$rd" "$ol"; do
        if [ "$v" != "UP" ] && [ "$v" != "DOWN" ]; then
            NEXUS_HEALTH_STATE="CONTRACT_BROKEN"
            NEXUS_HEALTH_DETAIL="checks 取值越界（期望 UP|DOWN，实得 '${v}'）→ 契约已被变更"
            return
        fi
    done

    # data.version 专条（§4.2.2，脚本最容易漏的一条）：
    # 期望 semver；字面量 @project.version@ 或空串 = Maven 资源过滤失效，**属构建层缺陷**，
    # 要给出查 maven-resources-plugin 的提示，别让用户以为是容器没起好。
    local ver
    ver="$(nexus_json_field "$NEXUS_HTTP_BODY" version)"
    NEXUS_HEALTH_VERSION="$ver"
    case "$ver" in
        ''|@*@)
            NEXUS_HEALTH_VERSION_OK="no"
            NEXUS_HEALTH_STATE="CONTRACT_BROKEN"
            NEXUS_HEALTH_DETAIL="data.version='${ver}' → 构建期资源过滤未生效（@project.version@ 未被替换）"
            return ;;
    esac
    if ! printf '%s' "$ver" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+'; then
        NEXUS_HEALTH_VERSION_OK="no"
        NEXUS_HEALTH_STATE="CONTRACT_BROKEN"
        NEXUS_HEALTH_DETAIL="data.version='${ver}' 不是 semver → 契约可能已变更"
        return
    fi
    NEXUS_HEALTH_VERSION_OK="yes"

    # data.timestamp（§4.2.2）：只硬判"是不是 ISO-8601 秒级时间"，**不断言时区**（文档原话）。
    # 实测偏差（2026-09-11，本机运行中的后端）：容器内返回的是 `2026-09-11T15:28:57Z`，
    # 而文档 §4.2.2 的正则写的是 `[+-]hh:mm` 形式。`Z` 是 ISO-8601 对 UTC 的标准写法、
    # 同样合法，若照文档正则硬判会把一个正常服务判成契约破坏 —— 故降级为"提示"，
    # 只把真正畸形的值判 CONTRACT_BROKEN。这条偏差已回报 backend-engineer 决定改哪一侧。
    local ts
    ts="$(nexus_json_field "$NEXUS_HTTP_BODY" timestamp)"
    NEXUS_HEALTH_TIMESTAMP="$ts"
    if ! printf '%s' "$ts" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}'; then
        NEXUS_HEALTH_TS_OK="no"
        NEXUS_HEALTH_STATE="CONTRACT_BROKEN"
        NEXUS_HEALTH_DETAIL="data.timestamp='${ts}' 不是 ISO-8601 秒级时间（契约 §2.2）"
        return
    fi
    NEXUS_HEALTH_TS_OK="yes"
    case "$ts" in
        *Z) NEXUS_HEALTH_NOTE="data.timestamp 用 Z 表示 UTC（ISO-8601 合法）：容器内为 UTC 时区，与文档 §4.2.2 写的 +00:00 写法不同，两者都接受" ;;
    esac

    case "$NEXUS_HTTP_CODE" in
        200)
            if [ "$code" = "0" ] && [ "$pg$rd$ol" = "UPUPUP" ]; then
                NEXUS_HEALTH_STATE="UP"
            else
                NEXUS_HEALTH_STATE="CONTRACT_BROKEN"
                NEXUS_HEALTH_DETAIL="HTTP 200 但 code=${code} / checks=[${NEXUS_HEALTH_CHECKS}] → 200 与报告内容自相矛盾（契约 §4.2.1：三项全 UP 才该返 200），报后端 bug"
            fi ;;
        503)
            # 503 是**契约内的正常响应**：依赖挂了，body 里 data.checks 能精确定位到具体依赖
            NEXUS_HEALTH_STATE="DEGRADED"
            NEXUS_HEALTH_DETAIL="依赖不可用：$(printf '%s' "$NEXUS_HEALTH_CHECKS" | tr ' ' '\n' | grep '=DOWN' | cut -d= -f1 | paste -sd, -)"
            ;;
    esac
}

# =============================================================================
# 容器级探活：GET /actuator/health（**Boot 内建 db / redis / diskSpace，不含 ollama**，§4.1）
# =============================================================================
# 什么时候用它：**只在 L1/L2 异常时**当"解释器" ——
#   ① 容器 healthy 但 /api/health 503 → 后端业务探活挂了哪些依赖（L2 的 checks 已能给，这里做交叉验证）
#   ② 容器 unhealthy 但 /api/health 全 UP → 矛盾！只有 Boot 侧视图能说清是哪个 indicator 挂的
#      （diskSpace 只在这里覆盖：/api/health 的 checks 不含磁盘）
#   ③ 从宿主看 healthcheck 的**报错原文**：compose healthcheck 跑的就是这个口，形状一致才好对照
# 正常路径（容器 healthy + /api/health UP）不必调用：多打一次 HTTP 换不来新信息。
# 结果写全局：
#   NEXUS_ACTUATOR_STATE ∈ UP（status=UP）| DOWN（status≠UP，含 503）| NO_RESPONSE | NOT_FOUND
#                           | ERROR | ABNORMAL | CONTRACT_BROKEN（200/503 但 body 不是 actuator 格式）| NO_CURL
#   NEXUS_ACTUATOR_HTTP / NEXUS_ACTUATOR_BODY / NEXUS_ACTUATOR_STATUS / NEXUS_ACTUATOR_COMPONENTS
#   NEXUS_ACTUATOR_DOWN —— 值为非 UP 的组件名（空格分隔），用于"点名"
#   NEXUS_ACTUATOR_DETAIL
nexus_probe_actuator_health() {
    local url="http://${NEXUS_PROBE_HOST}:${NEXUS_BACKEND_PORT}/actuator/health"
    NEXUS_ACTUATOR_STATE="UNKNOWN"; NEXUS_ACTUATOR_HTTP=""; NEXUS_ACTUATOR_BODY=""
    NEXUS_ACTUATOR_STATUS=""; NEXUS_ACTUATOR_COMPONENTS=""; NEXUS_ACTUATOR_DOWN=""; NEXUS_ACTUATOR_DETAIL=""

    nexus_http_get "$url" 5
    NEXUS_ACTUATOR_HTTP="$NEXUS_HTTP_CODE"
    NEXUS_ACTUATOR_BODY="$NEXUS_HTTP_BODY"
    case "$NEXUS_HTTP_CODE" in
        NO_CURL) NEXUS_ACTUATOR_STATE="NO_CURL";     NEXUS_ACTUATOR_DETAIL="宿主没有 curl"; return ;;
        000)     NEXUS_ACTUATOR_STATE="NO_RESPONSE"; NEXUS_ACTUATOR_DETAIL="curl 未取得响应（后端进程没起 / BACKEND_PORT 不对）"; return ;;
        404)     NEXUS_ACTUATOR_STATE="NOT_FOUND";   NEXUS_ACTUATOR_DETAIL="404 → 路径写错（注意 /actuator/health 不带 /api 前缀）"; return ;;
        500)     NEXUS_ACTUATOR_STATE="ERROR";       NEXUS_ACTUATOR_DETAIL="500 → 后端内部异常"; return ;;
    esac
    case "$NEXUS_HTTP_CODE" in
        200|503) ;;
        *) NEXUS_ACTUATOR_STATE="ABNORMAL"; NEXUS_ACTUATOR_DETAIL="非预期 HTTP ${NEXUS_HTTP_CODE}"; return ;;
    esac

    # 形状先验：actuator 的 body 是 {"status":...,"components":{...}}，**不是** Result。
    # 拿不到顶层 status 就当契约不符（例如打到了 nginx 的 SPA 回退 → HTML 200）。
    if nexus_has_jq; then
        NEXUS_ACTUATOR_STATUS="$(printf '%s' "$NEXUS_ACTUATOR_BODY" | jq -r '.status // empty' 2>/dev/null)"
        NEXUS_ACTUATOR_COMPONENTS="$(printf '%s' "$NEXUS_ACTUATOR_BODY" \
            | jq -r 'if .components then (.components | to_entries | map("\(.key)=\(.value.status)") | join(" ")) else "" end' 2>/dev/null)"
    else
        NEXUS_ACTUATOR_STATUS="$(printf '%s' "$NEXUS_ACTUATOR_BODY" | sed -n 's/^{"status":[[:space:]]*"\([A-Z_]*\)".*/\1/p' | head -n1)"
        # 组件形如 "db":{"status":"UP",...}；只取键与状态两个字段，嵌套 details 不参与匹配
        NEXUS_ACTUATOR_COMPONENTS="$(printf '%s' "$NEXUS_ACTUATOR_BODY" \
            | grep -oE '"[A-Za-z]+":[[:space:]]*\{[[:space:]]*"status":[[:space:]]*"[A-Z_]+"' \
            | sed 's/"\([A-Za-z]*\)":[[:space:]]*{[[:space:]]*"status":[[:space:]]*"\([A-Z_]*\)"/\1=\2/' | paste -sd' ' -)"
    fi
    if [ -z "$NEXUS_ACTUATOR_STATUS" ]; then
        NEXUS_ACTUATOR_STATE="CONTRACT_BROKEN"
        NEXUS_ACTUATOR_DETAIL="HTTP ${NEXUS_HTTP_CODE} 但 body 不是 actuator 格式（疑似 HTML / 打到了别的服务）"
        return
    fi
    NEXUS_ACTUATOR_DOWN="$(printf '%s' "$NEXUS_ACTUATOR_COMPONENTS" | tr ' ' '\n' | grep -v '=UP$' | cut -d= -f1 | paste -sd' ' -)"

    if [ "$NEXUS_ACTUATOR_STATUS" = "UP" ] && [ "$NEXUS_HTTP_CODE" = "200" ]; then
        NEXUS_ACTUATOR_STATE="UP"
    else
        # status=DOWN 配 503 是 Boot 默认映射；两者不一致同样算契约破坏（§4.2.2 的同款判据）
        NEXUS_ACTUATOR_STATE="DOWN"
        NEXUS_ACTUATOR_DETAIL="status=${NEXUS_ACTUATOR_STATUS} / HTTP ${NEXUS_HTTP_CODE}；判 DOWN 的组件：${NEXUS_ACTUATOR_DOWN:-（未列出）}"
    fi
}

# =============================================================================
# 模型就绪探活：GET /api/tags（**ollama 健康 ≠ 模型就绪**，§4.5）
# =============================================================================
# 结果写全局：
#   NEXUS_MODELS_STATE ∈ ALL | MISSING | UNREACHABLE | NO_CURL
#   NEXUS_MODELS_MISSING  缺失的模型名（空格分隔）
#   NEXUS_MODELS_FOUND    实际在卷里的模型名（归一化后，空格分隔）
nexus_probe_models() {
    local url="http://${NEXUS_PROBE_HOST}:${NEXUS_OLLAMA_PORT}/api/tags"
    NEXUS_MODELS_STATE="UNREACHABLE"; NEXUS_MODELS_MISSING=""; NEXUS_MODELS_FOUND=""

    nexus_http_get "$url" 5
    if [ "$NEXUS_HTTP_CODE" = "NO_CURL" ]; then NEXUS_MODELS_STATE="NO_CURL"; return; fi
    if [ "$NEXUS_HTTP_CODE" != "200" ]; then return; fi

    # :latest 归一化陷阱（已出过真实事故，§4.5）：无 tag 拉取的模型在列表里显示为
    # nomic-embed-text:latest，用整字段精确匹配会**永不命中** → 在列表侧剥掉 :latest 再比。
    # 匹配同样容忍 `:` 后空白（ollama 是 Go 的紧凑输出，但别把判据押在序列化风格上）。
    NEXUS_MODELS_FOUND="$(printf '%s' "$NEXUS_HTTP_BODY" \
        | grep -oE '"name"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | sed 's/.*"\([^"]*\)"$/\1/' | sed 's/:latest$//' | paste -sd' ' -)"

    local expected m missing=""
    expected="$(nexus_expected_models)"
    for m in $expected; do
        if ! printf '%s\n' $NEXUS_MODELS_FOUND | grep -qx "$m"; then
            missing="${missing}${missing:+ }${m}"
        fi
    done
    NEXUS_MODELS_MISSING="$missing"
    if [ -z "$missing" ]; then NEXUS_MODELS_STATE="ALL"; else NEXUS_MODELS_STATE="MISSING"; fi
}

# 一次性容器状态：打印 "<state>|<exitcode>"（如 "exited|0"、"running|"、"missing|"）
# `docker compose ps` 默认不列已退出容器，一次性容器的结论只能靠 inspect / ps -a。
nexus_oneshot_state() {
    local name="$1" state="" code="" raw=""
    state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null | tr -d '\r')"
    code="$(docker inspect -f '{{.State.ExitCode}}' "$name" 2>/dev/null | tr -d '\r')"
    if [ -z "$state" ]; then
        # 兜底：去掉 -f 的普通 inspect（wsl.abc.md §3.6 记录过 -f 模板取空值的情形）
        raw="$(docker inspect "$name" 2>/dev/null)"
        if [ -n "$raw" ]; then
            state="$(printf '%s' "$raw" | grep -m1 -o '"Status": *"[^"]*"' | sed 's/.*"\([a-zA-Z]*\)"/\1/')"
            code="$(printf '%s' "$raw"  | grep -m1 -o '"ExitCode": *[0-9-]*' | grep -o '[0-9-]*$')"
        fi
        [ -n "$state" ] || state="missing"
    fi
    printf '%s|%s' "$state" "$code"
}

# =============================================================================
# 容器状态与端口占用
# =============================================================================

# 容器状态：打印 "<state>|<health>"，如 "running|healthy"、"running|"（无 healthcheck）、"missing|"
nexus_container_status() {
    local name="$1" state="" health="" raw=""
    state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null | tr -d '\r')"
    if [ -z "$state" ]; then
        raw="$(docker inspect "$name" 2>/dev/null)"
        if [ -n "$raw" ]; then
            state="$(printf '%s' "$raw" | grep -m1 -o '"Status": *"[^"]*"' | sed 's/.*"\([a-zA-Z]*\)"/\1/')"
            health="$(printf '%s' "$raw" | grep -m1 -o '"Health": *{[^}]*}' | grep -o '"Status": *"[^"]*"' | sed 's/.*"\([a-zA-Z]*\)"/\1/')"
        fi
        [ -n "$state" ] || state="missing"
    else
        # 无 healthcheck 的容器这里就是空串（不是取错值）
        health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$name" 2>/dev/null | tr -d '\r')"
    fi
    printf '%s|%s' "$state" "$health"
}

# 容器最后一次 healthcheck 的输出原文（打印用；取不到则空串）。
# 为什么需要它：`unhealthy` 这个状态**只说明探活命令失败了，不说明哪一项坏了** ——
# 探活命令自己打印的报错才是证据（已实测：前端那条报的是 "wget: can't connect to remote host"）。
# 取法：.State.Health.Log 是最近若干次结果（Docker 只留最近几次），逐条 Output 拼接后取**最后一行**
# （最后一条 = 最近一次）。
nexus_container_health_log() {
    local name="$1" out=""
    # 截断到 200 字符：healthy 容器的输出是整段 JSON（实测 backend 每行 500+ 字符），
    # 报错类输出则是一行短文本 —— 截断对前者是防刷屏，对后者无影响。
    out="$(docker inspect -f '{{if .State.Health}}{{range .State.Health.Log}}{{.Output}}{{end}}{{end}}' \
        "$name" 2>/dev/null | tr -d '\r' | awk 'NF' | tail -n1 | cut -c1-200)"
    printf '%s' "$out"
}

# 前端 healthcheck「假故障」的**容器内对照实验**（陷阱 8 / wsl.abc.md 实测记录）：
#   容器内 `wget -qO- localhost` → 解析到 ::1(IPv6)，而 nginx 只监听 IPv4 → 连接被拒（exit 1）
#   容器内 `wget -qO- 127.0.0.1` → 通（exit 0）
#   容器内 `wget -qO- "[::1]"`   → 拒（exit 1）—— 第三条用来**坐实"IPv6 没有监听"**，
#     否则"localhost 失败"还有别的解释（比如服务真挂了），证据链不闭合。
# 实证（2026-09-12，nexus-frontend 容器内）：/etc/hosts 同时有 `127.0.0.1 localhost` 与
#   `::1 localhost`；nginx.conf 是 `listen 80;`（只 IPv4）→ 三条探测的退出码依次 1 / 0 / 1。
# 只做 GET（纯查询），不改容器状态。
# 结果写全局：
#   NEXUS_LOOPBACK_STATE ∈ IPV6_FALSE_ALARM（符合假故障特征）| OK（localhost 也能通）
#                          | FAIL（IPv4 回环都不通 ⇒ 服务可能真挂了）
#                          | SKIP（容器不在运行 / docker 不可用 / 镜像内没有 wget）
#   NEXUS_LOOPBACK_DETAIL —— 可直接打印的证据串（含三个退出码）
nexus_probe_frontend_loopback() {
    local name="${1:-nexus-frontend}" st lc rc v6
    NEXUS_LOOPBACK_STATE="SKIP"; NEXUS_LOOPBACK_DETAIL=""
    st="$(nexus_container_status "$name")"
    [ "${st%%|*}" = "running" ] || { NEXUS_LOOPBACK_DETAIL="容器未运行（${st%%|*}）"; return 0; }

    docker exec "$name" wget -qO- localhost >/dev/null 2>&1; lc=$?
    docker exec "$name" wget -qO- 127.0.0.1 >/dev/null 2>&1; rc=$?
    docker exec "$name" wget -qO- "[::1]"   >/dev/null 2>&1; v6=$?
    NEXUS_LOOPBACK_DETAIL="容器内 wget -qO- localhost → exit=${lc}；127.0.0.1 → exit=${rc}；[::1] → exit=${v6}"
    if [ "$rc" -eq 0 ] && [ "$lc" -ne 0 ]; then
        NEXUS_LOOPBACK_STATE="IPV6_FALSE_ALARM"
    elif [ "$rc" -eq 0 ]; then
        NEXUS_LOOPBACK_STATE="OK"
    else
        NEXUS_LOOPBACK_STATE="FAIL"
    fi
}

# 宿主端口是否由本项目/其他容器发布：打印容器名（逗号分隔），无则空。
# 两步都做：`--filter publish` 是精确查询；`.Ports` 列文本匹配是兜底
# —— 只要一路命中就足以判定"占用者是我们自己的容器"，避免重复执行 up.sh 时误报冲突。
nexus_port_publisher() {
    local port="$1" names=""
    names="$(docker ps --filter "publish=${port}" --format '{{.Names}}' 2>/dev/null | tr -d '\r' | paste -sd, -)"
    if [ -z "$names" ]; then
        names="$(docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null \
            | grep -E "(^|,| )0\.0\.0\.0:${port}->|(^|,| )\[::\]:${port}->" \
            | awk '{print $1}' | paste -sd, -)"
    fi
    printf '%s' "$names"
}

# WSL 侧监听该端口的 ss 明细行（供人工核对占用进程），无则空
nexus_wsl_port_listener_line() {
    local port="$1"
    nexus_has_ss || return 0
    ss -ltnp 2>/dev/null | awk -v pat=":${port}\$" 'NR>1 && $4 ~ pat { print; exit }'
}

# Windows 侧端口检查是否可用（用于区分"检查过且没冲突"与"根本没法检查"）
nexus_windows_port_check_available() {
    [ -x /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe ] && return 0
    command -v powershell.exe >/dev/null 2>&1
}

# Windows 侧监听该端口的占用者：打印 "<pid>:<进程名>"，无占用或不可用则空。
# WSL2 NAT 模式下 Windows 侧监听同样会抢占转发，必须双侧都查（设计文档 §4.2 第 4 项）。
nexus_windows_port_owner() {
    local port="$1" ps_exe="" cmd=""
    for ps_exe in /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe powershell.exe; do
        if [ -x "$ps_exe" ] || command -v "$ps_exe" >/dev/null 2>&1; then break; fi
        ps_exe=""
    done
    [ -n "$ps_exe" ] || return 0
    # 单引号模板 + 占位符替换：避免 $c / $p 被 bash 抢先展开（双层引号是 wsl.abc.md 记过的坑）
    cmd='$c = Get-NetTCPConnection -State Listen -LocalPort PORT -ErrorAction SilentlyContinue | Select-Object -First 1; if ($c) { $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue; Write-Output ("{0}:{1}" -f $c.OwningProcess, $p.ProcessName) }'
    cmd="${cmd/PORT/$port}"
    "$ps_exe" -NoProfile -NonInteractive -Command "$cmd" 2>/dev/null | tr -d '\r' | head -n1
}

# =============================================================================
# 等待类工具（会打印进度；返回 0 = 达成，1 = 超时）
# =============================================================================

# 通用轮询：nexus_wait_until <总超时秒> <间隔秒> <命令...>，命令返回 0 即成功
nexus_wait_until() {
    local timeout="$1" interval="$2"; shift 2
    local start now
    start="$(date +%s)"
    while :; do
        if "$@" >/dev/null 2>&1; then return 0; fi
        now="$(date +%s)"
        if [ $((now - start)) -ge "$timeout" ]; then return 1; fi
        sleep "$interval"
    done
}

# 等 /api/health 就绪：**连续 2 次 HTTP 200 且 code=0，两次之间间隔 5s**（§4.3）。
# 为什么一次 200 不够：探活是瞬时快照 —— redis 刚恢复时"一个 200 紧跟一个 503"是常态，
# 且 backend 有 60s start_period，单次采样落在窗口内就会把"还在抖"读成"已就绪"。
# 间隔策略：前 30s 用 2s（快速捕获启动窗口），之后 5s（每次探活都会真打三个依赖，
# 高频轮询既无意义又会在 PG 侧堆连接）。
# 返回 0=就绪 1=超时；结束后 NEXUS_HEALTH_* 保留**最后一次**探测结果，供调用方打印定位信息。
nexus_wait_api_health_up() {
    local timeout="${1:-180}" start now elapsed streak=0
    start="$(date +%s)"
    while :; do
        nexus_probe_api_health
        if [ "$NEXUS_HEALTH_STATE" = "UP" ]; then
            streak=$((streak + 1))
            if [ "$streak" -ge 2 ]; then return 0; fi
            sleep 5
        else
            streak=0
        fi
        now="$(date +%s)"; elapsed=$((now - start))
        if [ "$elapsed" -ge "$timeout" ]; then return 1; fi
        printf '      [等待] %3ss  %s  http=%s  %s\n' \
            "$elapsed" "$NEXUS_HEALTH_STATE" "${NEXUS_HEALTH_HTTP:-?}" "${NEXUS_HEALTH_CHECKS:-${NEXUS_HEALTH_DETAIL}}"
        if [ "$elapsed" -lt 30 ]; then sleep 2; else sleep 5; fi
    done
}

# 等两个模型就绪（§4.5）：间隔 10s（5GB 级下载，频繁轮询无收益）。
# 提前失败：一次性容器 ollama-init 退出码非 0 就没必要干等 30min —— 直接返回 2（拉取失败）。
# 返回 0=就绪 1=超时 2=ollama-init 失败
nexus_wait_models_ready() {
    local timeout="${1:-1800}" interval="${2:-10}" start now elapsed state st code
    start="$(date +%s)"
    while :; do
        nexus_probe_models
        if [ "$NEXUS_MODELS_STATE" = "ALL" ]; then return 0; fi

        for st in $NEXUS_ONESHOT_CONTAINERS; do
            state="$(nexus_oneshot_state "$st")"
            code="${state#*|}"
            if [ "${state%%|*}" = "exited" ] && [ -n "$code" ] && [ "$code" != "0" ]; then
                return 2
            fi
        done

        now="$(date +%s)"; elapsed=$((now - start))
        if [ "$elapsed" -ge "$timeout" ]; then return 1; fi
        printf '      [等待] %4ss  模型未就绪（%s）缺失: %s\n' \
            "$elapsed" "$NEXUS_MODELS_STATE" "${NEXUS_MODELS_MISSING:-未知}"
        sleep "$interval"
    done
}

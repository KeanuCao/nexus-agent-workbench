#!/usr/bin/env bash
# =============================================================================
# qa/scripts/lib/tc-common.sh —— 认证类用例脚本的公共**机械动作**库（TC-01）
#
# 谁在用它（qa/scripts/tc01-*.sh）：
#   tc01-two-tenant-me.sh       两租户各用**自己的** token 调 GET /api/auth/me
#   tc01-cross-tenant-token.sh  跨租户 token（本地重签）调 GET /api/auth/me
#   tc01-login-then-me.sh       合法 token 放行（登录响应 data.user ↔ /me 的 data 逐字段对照）
#   tc01-logout-then-me.sh      登出即失效（同一个 token，登出前后各调一次 /me + 日志分流佐证）
#
# 为什么抽这个库（2026-09-17，用户拍板把 1.2-4 / 1.2-5 也脚本化时一并评估）：
#   四个脚本里，常量与端口解析、JSON 取值、HTTP 动作、Redis 指纹、退出路径的**清理脚手架**
#   逐字重复；其中最后一项**最不能各写一份** —— 「信号不触发 EXIT trap」那条实测结论（见下）
#   若只在某一处修好，其余脚本会静默地带着"Ctrl-C 之后环境里留着键"的缺口。
#   改库的时机：两个既有脚本**用户还没跑过**（TC-01.md 的「实测」栏为空、无已记录结论会被
#   重构作废），此刻换是零成本。先例：scripts/lib/probe.sh（同为"判据只写一份"的落点）。
#
# 本库的边界（qa/README.md 铁律 #2「判据不藏脚本里」）：
#   ✅ 装：机械动作 —— 路径/端口解析、JSON 取值、HTTP 动作、Redis 只读指纹、后端日志只读抓取、
#         字段对照**打印**、退出路径的清理脚手架。
#   ❌ 不装：判据、PASS/FAIL、期望码、账号口令、用例的步骤顺序 —— 那些在用例脚本与 TC 文档里。
#   ⚠️ 唯一例外：tc_selftest_observers —— 它给的是**本库自身取值器**的自检结论，与任何 TC
#      判据无关（沿用既有脚本 --self-test 的口径：「这些『通过 / 不通过』说的是工具自己」）。
#
# 调用约定（用例脚本的骨架，照此写即可）：
#   set -euo pipefail
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"
#   admin_token=''
#   tc_cleanup() { tc_post_logout 'admin' "$admin_token"; }   # 只做清理动作，别在里面调 tc_finish
#   tc_begin tc_cleanup '⑤ 清理：…' '⑥ 无害性自证：…'
#   tc_preflight_tools
#   tc_preflight_services          # ← 中间可插用例自己的观察（如跨租户脚本的夹具密钥指纹）
#   tc_fingerprint_before '① 指纹（跑前）：…'
#   …用例自己的步骤（tc_section / tc_post_login / tc_get_me / tc_token_claim / tc_note_jti）…
#   tc_finish 0
#
# 退出码约定（**不是判据**，只表示"动作做没做成"；沿用两个既有脚本的约定）：
#   0 = 动作全部完成
#   2 = 用法错误（各脚本自己判）
#   3 = 前提不成立（缺 python3 / curl / docker、Redis 取不到指纹、/api/health 非 200；
#       用例也可用它表示"夹具前提不成立"，如跨租户脚本的「夹具密钥自检不通过」）
#   4 = 某个 HTTP 动作没成功（如登录没拿到 data.token）
#
# 清理脚手架为什么长这样（Test Harmlessness §2；实测结论，别简化）：
#   · 正常路径：用例显式调 tc_finish → 跑 hook（登出）→ 打印指纹 → exit
#   · 异常路径（set -e 触发 / 显式 exit）：EXIT trap 兜底跑同一段
#   · **Ctrl-C / kill：EXIT trap 不会执行**（实测 2026-09-17，WSL bash 5.2：未被 trap 的信号
#     直接终止 bash），所以 tc_begin 里显式写了 `trap 'exit 130' INT` / `trap 'exit 143' TERM`，
#     把信号转成一次正常退出，再由 EXIT trap 兜底清理。
#     前提：**前台运行** —— `… &` 起在后台的 bash 会继承 SIGINT 的忽略态，而"入口即被忽略的
#     信号无法再被 trap"；那种跑法不在本库的设计范围内。
#   · hook 里只做**幂等**清理（登出 = POST /api/auth/logout，后端 TokenStore.remove 幂等：
#     重复登出同一个键不报错），所以兜底路径重复调用是安全的。
# =============================================================================

# ── 路径与常量：本文件位于 <repo>/qa/scripts/lib/tc-common.sh ────────────────────
# 仓库根由脚本自身位置推导（不写死 /mnt/c/...）：用例脚本按推荐方式取 lib 路径时，
# 无论仓库挂在哪、从哪个目录调用，解析结果都自洽。解析出来的值会在前置检查里打印出来。
TC_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TC_SCRIPTS_DIR="$(cd "${TC_LIB_DIR}/.." && pwd)"
TC_REPO_DIR="$(cd "${TC_SCRIPTS_DIR}/../.." && pwd)"
TC_ENV_FILE="${TC_REPO_DIR}/docker-compose/.env"
readonly TC_LIB_DIR TC_SCRIPTS_DIR TC_REPO_DIR TC_ENV_FILE

readonly TC_REDIS_CONTAINER='nexus-redis'
readonly TC_REDIS_KEY_PATTERN='nexus:auth:token:*'
readonly TC_BACKEND_CONTAINER='nexus-backend'

# dev 密钥默认值（HS256 签名密钥），必须与 backend/nexus-start/src/main/resources/application.yml
# 的 `nexus.jwt.secret: ${NEXUS_JWT_SECRET:<此值>}` 一字不差。它变了这里也要改 ——
# ⚠️ 不一致的后果由夹具自己的守卫兜住：tc01-cross-tenant-token.sh 在做任何请求**之前**
#    先用本地密钥验签一份**后端亲自签发**的真 token，验不过就以退出码 3 停下、**不给结论**
#    （否则"签名错"与"租户隔离生效"都会得到 40101，会伪装成 PASS）。
readonly TC_DEV_JWT_SECRET='nexus-dev-only-jwt-secret-please-override-in-any-real-environment'

# ── 后端地址：宿主端口从 docker-compose/.env 解析（与 compose 同源，脚本内不写死 8089）──
tc_resolve_base_url() {
  local port='8089' from_env=''
  if [ -r "$TC_ENV_FILE" ]; then
    from_env="$(grep -E '^[[:space:]]*BACKEND_PORT[[:space:]]*=' "$TC_ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d ' \r"' || true)"
    if [ -n "$from_env" ]; then
      port="$from_env"
    fi
  fi
  printf '%s' "${TC01_BASE_URL:-http://localhost:${port}}"
}
TC_BASE_URL="$(tc_resolve_base_url)"
readonly TC_BASE_URL

# ── 运行期状态（由 tc_begin 初始化；用例脚本只读它们，不要自己改）────────────────
tc_work_dir=''
tc_cleanup_hook=''
tc_cleanup_title=''
tc_fingerprint_title=''
tc_cleaned=0
tc_jti_labels=()
tc_jti_values=()

# ── 打印 ──────────────────────────────────────────────────────────────────────
tc_section() { printf '\n===== %s =====\n' "$1"; }

# 响应体打印：curl 连不上时 `-o` 指定的文件**根本不会建**（实测），直接 cat 会被 set -e 吞成退出码 1
tc_show_body() {
  if [ -f "$1" ]; then
    cat "$1"
  else
    printf '（curl 没拿到响应体 —— 后端不可达 / 连接被拒）'
  fi
}

# ── 从 JSON 文件里取「点分路径」的值（替代 jq；WSL 内无 jq、无 node）──────────────
# 取不到（键不存在 / 父节点为 null / 不是 JSON）→ 退出码 1、不打印任何东西。
# 调用方一律写成 `$(tc_json_get … || echo '（取不到）')` 或 `|| true`：
# **静默取空是最危险的结果**（两个空值摆在一起看着像"一致"），所以这里宁可失败。
tc_json_get() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
    for part in sys.argv[2].split("."):
        node = node[int(part)] if isinstance(node, list) else node[part]
except (OSError, KeyError, IndexError, TypeError, ValueError):
    sys.exit(1)
print("" if node is None else node)
PY
}

# ── 本地解码 token 载荷的某个 claim（**只解码、不验签**；仅用于观察）──────────────
tc_token_claim() {
  python3 - "$1" "$2" <<'PY'
import base64
import json
import sys

parts = sys.argv[1].split(".")
if len(parts) != 3:
    sys.exit(1)
segment = parts[1] + "=" * (-len(parts[1]) % 4)
print(json.loads(base64.urlsafe_b64decode(segment)).get(sys.argv[2], ""))
PY
}

# ── Redis 白名单指纹（**只读**）──────────────────────────────────────────────────
# 用 --scan 而不是 KEYS：不阻塞 Redis（本机能查也不该养成坏习惯）
tc_redis_keys() {
  docker exec "$TC_REDIS_CONTAINER" redis-cli --scan --pattern "$TC_REDIS_KEY_PATTERN" 2>/dev/null | tr -d '\r' | sort
}

tc_redis_exists() {  # <完整键名> → 打印 0/1
  docker exec "$TC_REDIS_CONTAINER" redis-cli exists "$1" 2>/dev/null | tr -d '\r'
}

# ── 后端日志（**只读**；佐证用，取不到不致命）────────────────────────────────────
tc_backend_logs() {  # <行数>（默认 200）
  docker logs --tail "${1:-200}" "$TC_BACKEND_CONTAINER" 2>&1 | tr -d '\r'
}

# stdin → 只把匹配行**原样**打印（不做任何判断）。
# 模式为空时**拒绝执行**：`grep -F ''` 会匹配所有行，打印一大片无关日志只会被误读成"佐证"。
_tc_grep() {  # _tc_grep <grep 开关> <模式>
  local flag="$1" pattern="$2"
  if [ -z "$pattern" ]; then
    printf '      （模式为空 —— 这一段跳过。空模式会匹配所有行，打出来只会被误读成"佐证"）\n'
    return 0
  fi
  grep "$flag" -- "$pattern" || true
}

tc_grep_lines() { _tc_grep -F "${1:-}"; }     # 固定字符串
tc_grep_lines_re() { _tc_grep -E "${1:-}"; }  # 扩展正则

# ── HTTP 动作（只发请求、原样打印，不做任何判断）────────────────────────────────
# 三个函数都带一个**可选的**基地址参数（缺省 = TC_BASE_URL 即直连后端）：TC-03 的用例要能
# 整条链路走 nginx（FRONTEND_PORT，见契约 §7.9 与设计 §5.1），而"登录"这一步也必须走同一个
# 入口才叫整条链路。既有调用方一律只传原来的参数，行为不变。
tc_post_login() {  # <请求体> <响应写到这个文件> [基地址]
  local body="$1" outfile="$2" base="${3:-$TC_BASE_URL}" http
  http="$(curl -s -o "$outfile" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "$body" "${base}/api/auth/login" || true)"
  printf 'POST %s/api/auth/login\n  body: %s\n  → HTTP %s\n  body(原样): ' "$base" "$body" "$http"
  tc_show_body "$outfile"; printf '\n'
}

tc_get_me() {  # <标签> <token> <响应写到这个文件> [基地址]
  local label="$1" token="$2" outfile="$3" base="${4:-$TC_BASE_URL}" http
  http="$(curl -s -o "$outfile" -w '%{http_code}' -H "Authorization: Bearer ${token}" \
        "${base}/api/auth/me" || true)"
  printf 'GET %s/api/auth/me   [%s]\n  → HTTP %s\n  body(原样): ' "$base" "$label" "$http"
  tc_show_body "$outfile"; printf '\n'
}

tc_post_logout() {  # <标签> <token> [基地址]（token 为空则跳过 —— 幂等）
  if [ -z "$2" ]; then
    printf '  [%s] 没有 token，跳过登出\n' "$1"
    return 0
  fi
  local out base="${3:-$TC_BASE_URL}"
  out="$(curl -s -X POST -H "Authorization: Bearer $2" -w '\n  → HTTP %{http_code}' \
        "${base}/api/auth/logout" || true)"
  printf '  [%s] POST %s/api/auth/logout\n  body(原样): %s\n' "$1" "$base" "$out"
}

# ── 字段对照表（**只打印、不比对**；值取不到时打「（取不到）」，绝不静默留空）──────
# 字段清单 = docs/api/README.md §5.2 的 data.user（UserInfoVO），与 §5.4 的 /me 的 data 同构。
# 左右两列的路径不同（登录侧 data.user.*、/me 侧 data.*），所以这里写死两侧前缀。
tc_print_field_compare() {  # <账号名> <登录响应文件> <me 响应文件>
  local who="$1" login_json="$2" me_json="$3" field
  printf '\n  字段对照（左 = 登录响应的 data.user；右 = /api/auth/me 的 data）：\n'
  printf '    %s\t%s\t%s\n' '字段' "登录/${who}" "me/${who}"
  for field in userId username nickname tenantId tenantCode tenantName; do
    printf '    %s\t%s\t%s\n' "$field" \
      "$(tc_json_get "$login_json" "data.user.${field}" || echo '（取不到）')" \
      "$(tc_json_get "$me_json" "data.${field}" || echo '（取不到）')"
  done
}

# ── 前置检查（**只读**；任何写操作都在它们之后）──────────────────────────────────
# 拆成两段，是为了让用例能在中间插自己的观察（如跨租户脚本的"夹具密钥来源/指纹"两行）。
tc_preflight_tools() {
  tc_section '前置检查（只读）'
  local tool
  for tool in python3 curl docker; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      printf '错误：找不到 %s。本脚本要在 WSL（发行版 nexus-agent-workbench）内跑。\n' "$tool" >&2
      exit 3
    fi
  done
  printf '  工具：python3 / curl / docker 均可用\n'
  printf '  后端地址：%s（端口取自 %s 的 BACKEND_PORT；可用环境变量 TC01_BASE_URL 覆盖）\n' \
    "$TC_BASE_URL" "$TC_ENV_FILE"
}

tc_preflight_services() {
  local redis_ping health_code
  redis_ping="$(docker exec "$TC_REDIS_CONTAINER" redis-cli ping 2>&1 | tr -d '\r' || true)"
  if [ "$redis_ping" != 'PONG' ]; then
    printf '错误：取不到 Redis 指纹（docker exec %s redis-cli ping → %s）。\n' "$TC_REDIS_CONTAINER" "$redis_ping" >&2
    printf '      无害性自证（§2 第 3 条）依赖它，所以**先停下**，一个键都不写。\n' >&2
    exit 3
  fi
  printf '  Redis：docker exec %s redis-cli ping → PONG\n' "$TC_REDIS_CONTAINER"

  health_code="$(curl -s -o "$tc_work_dir/health.json" -w '%{http_code}' "${TC_BASE_URL}/api/health" || true)"
  if [ "$health_code" != '200' ]; then
    printf '错误：GET %s/api/health → HTTP %s（预期 200）。原始响应：\n' "$TC_BASE_URL" "$health_code" >&2
    tc_show_body "$tc_work_dir/health.json" >&2
    printf '\n      环境未就绪 —— 先跑 ./scripts/check-health.sh 看哪一项 DOWN，再回来。\n' >&2
    exit 3
  fi
  printf '  后端：GET %s/api/health → HTTP 200\n' "$TC_BASE_URL"
}

# ── 无害性自证：跑前 / 跑后的 Redis 白名单指纹（**键清单**，不是只比个数）──────────
# 跑前已存在的键若在窗口内自然过期，「跑前有、跑后没有」会非空 —— 那是既有键的 TTL，
# 不是本脚本没清干净；清单里能看到具体是哪几个，由人判断。
tc_note_jti() {  # <标签> <jti>：登记"本次自建的 jti"，指纹段会逐个打 EXISTS
  if [ -n "${2:-}" ]; then
    tc_jti_labels+=("$1")
    tc_jti_values+=("$2")
  fi
}

tc_fingerprint_before() {  # <节标题>
  tc_section "$1"
  tc_redis_keys > "$tc_work_dir/keys-before.txt"
  printf '  键数 = %s\n' "$(wc -l < "$tc_work_dir/keys-before.txt" | tr -d ' ')"
  if [ -s "$tc_work_dir/keys-before.txt" ]; then
    sed 's/^/  /' "$tc_work_dir/keys-before.txt"
  else
    printf '  （空）\n'
  fi
}

tc_fingerprint_after() {  # <节标题>
  tc_section "$1"
  tc_redis_keys > "$tc_work_dir/keys-after.txt" || true
  printf '  键数 = %s\n' "$(wc -l < "$tc_work_dir/keys-after.txt" | tr -d ' ')"
  if [ -s "$tc_work_dir/keys-after.txt" ]; then
    sed 's/^/  /' "$tc_work_dir/keys-after.txt"
  else
    printf '  （空）\n'
  fi

  tc_section '指纹差异（跑后 vs 跑前）'
  if [ -f "$tc_work_dir/keys-before.txt" ]; then
    printf '  跑后有、跑前没有的键：\n'
    comm -13 "$tc_work_dir/keys-before.txt" "$tc_work_dir/keys-after.txt" | sed 's/^/    /'
    printf '  跑前有、跑后没有的键：\n'
    comm -23 "$tc_work_dir/keys-before.txt" "$tc_work_dir/keys-after.txt" | sed 's/^/    /'
  else
    printf '  （跑前指纹没取到，无法比较 —— 说明脚本在取指纹之前就退出了）\n'
  fi

  printf '  本次自建 jti 的存活检查（EXISTS，1 = 键还在）：\n'
  if [ "${#tc_jti_labels[@]}" -gt 0 ]; then
    local i=0
    while [ "$i" -lt "${#tc_jti_labels[@]}" ]; do
      printf '    %-5s jti=%s → %s\n' "${tc_jti_labels[$i]}" "${tc_jti_values[$i]}" \
        "$(tc_redis_exists "nexus:auth:token:${tc_jti_values[$i]}" || echo '?')"
      i=$((i + 1))
    done
  else
    printf '    （本次没有登记自建 jti —— 通常是脚本在拿到 token 之前就退出了）\n'
  fi
}

# ── 退出路径：清理脚手架（正常收尾与异常兜底共用同一段）────────────────────────────
tc_begin() {  # <清理hook函数名> <清理节标题> <指纹节标题>
  tc_work_dir="$(mktemp -d)"
  tc_cleanup_hook="$1"
  tc_cleanup_title="$2"
  tc_fingerprint_title="$3"
  tc_cleaned=0
  trap tc_on_exit EXIT
  # 信号本身不会触发 EXIT trap（实测，见文件头）——先转成一次正常退出，再交给 EXIT trap 兜底
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

tc_run_cleanup() {  # 清理动作（hook）+ 无害性自证
  tc_section "$tc_cleanup_title"
  "$tc_cleanup_hook"
  # 清理动作已完成 —— 置位后即便下面的取指纹失败，EXIT trap 也不会重复登出
  tc_cleaned=1
  tc_fingerprint_after "$tc_fingerprint_title"
}

tc_finish() {  # <退出码>：走正常收尾（清理 + 指纹），然后退出
  tc_run_cleanup
  tc_cleaned=1
  exit "$1"
}

tc_on_exit() {  # EXIT trap：只在"非正常路径"（set -e 触发 / 信号）时兜底
  local rc=$?
  if [ "$tc_cleaned" -eq 0 ]; then
    printf '\n[清理] 非正常路径退出（退出码 %s）—— 兜底执行清理与指纹，见下\n' "$rc"
    tc_run_cleanup || true
  fi
  if [ -n "$tc_work_dir" ]; then
    rm -rf "$tc_work_dir"
  fi
  exit "$rc"
}

# ── 本库自检（--self-test 的一部分）：取值器 ─────────────────────────────────────
# ⚠️ 这里的「通过 / 不通过」说的是**本库的取值器**，与任何 TC 判据无关。
# 验证三件事：① 按契约形状能取到值；② 取不到时**必须失败**（否则"两边都空"会被看成"一致"）；
# ③ 字段对照表把取不到的值标成「（取不到）」而不是留空。
_tc_selftest_check() {  # <期望> <实际> <说明>
  if [ "$1" = "$2" ]; then
    printf '      %s：通过\n' "$3"
  else
    printf '      %s：**不通过**（期望 "%s"，实际 "%s"）\n' "$3" "$1" "$2"
  fi
}

tc_selftest_observers() {
  local tmp_dir sample_token val rc out
  tmp_dir="$(mktemp -d)"

  printf '\n[本库自检 1] 取值器：json_get / token_claim / 字段对照表（纯本地）\n'

  # 按 docs/api/README.md §5.2 的形状造一份"登录响应"（token 是本地拼的三段式，签名段是假的 ——
  # token_claim 只解码不验签，够用），再复制一份当 /me 的 data、改编一份当"缺字段"的坏样本。
  sample_token="$(python3 - <<'PY'
import base64
import json


def b64e(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


header = {"alg": "HS256"}
payload = {"sub": "11", "jti": "11111111-2222-3333-4444-555555555555", "tenantId": 1,
           "username": "admin", "iat": 1789659137, "exp": 1789666337}
print(".".join([b64e(json.dumps(header, separators=(",", ":")).encode()),
                b64e(json.dumps(payload, separators=(",", ":")).encode()),
                "selftest-signature-not-real"]))
PY
)"
  cat > "$tmp_dir/login.json" <<JSON
{"code": 0, "msg": "success", "data": {"token": "${sample_token}", "tokenType": "Bearer", "expiresIn": 7200, "user": {"userId": 11, "username": "admin", "nickname": "默认租户管理员", "tenantId": 1, "tenantCode": "default", "tenantName": "默认租户"}}}
JSON
  cat > "$tmp_dir/me.json" <<'JSON'
{"code": 0, "msg": "success", "data": {"userId": 11, "username": "admin", "nickname": "默认租户管理员", "tenantId": 1, "tenantCode": "default", "tenantName": "默认租户"}}
JSON
  cat > "$tmp_dir/me-missing-nickname.json" <<'JSON'
{"code": 0, "msg": "success", "data": {"userId": 11, "username": "admin", "tenantId": 1, "tenantCode": "default", "tenantName": "默认租户"}}
JSON

  _tc_selftest_check '11' "$(tc_json_get "$tmp_dir/login.json" data.user.userId || true)" 'json_get 取 data.user.userId'
  _tc_selftest_check '7200' "$(tc_json_get "$tmp_dir/login.json" data.expiresIn || true)" 'json_get 取 data.expiresIn（整数）'
  val="$(tc_json_get "$tmp_dir/login.json" data.token || true)"
  if [ -n "$val" ]; then
    printf '      json_get 取 data.token：通过（长度 %s）\n' "${#val}"
  else
    printf '      json_get 取 data.token：**不通过**（取到空）\n'
  fi

  rc=0
  out="$(tc_json_get "$tmp_dir/login.json" data.user.nosuchfield 2>/dev/null)" || rc=$?
  if [ "$rc" -ne 0 ] && [ -z "$out" ]; then
    printf '      取不存在的路径 data.user.nosuchfield：通过（退出码 %s、无输出 —— 不会静默留空）\n' "$rc"
  else
    printf '      取不存在的路径 data.user.nosuchfield：**不通过**（退出码 %s，输出 "%s"）—— 静默留空会被看成"两边一致"\n' "$rc" "$out"
  fi

  _tc_selftest_check '11111111-2222-3333-4444-555555555555' "$(tc_token_claim "$sample_token" jti || true)" 'token_claim 解 jti'
  _tc_selftest_check '1' "$(tc_token_claim "$sample_token" tenantId || true)" 'token_claim 解 tenantId'
  rc=0
  out="$(tc_token_claim 'not-a-jws' jti 2>/dev/null)" || rc=$?
  if [ "$rc" -ne 0 ]; then
    printf '      token_claim 对非三段式 token：通过（退出码 %s、不打印 token 长度与 jti）\n' "$rc"
  else
    printf '      token_claim 对非三段式 token：**不通过**（退出码 0）\n'
  fi

  printf '\n[本库自检 2] 字段对照表的两种形态（夹具自检，不是用例结论）\n'
  printf '  (a) 两边一致时（样例数据）：\n'
  tc_print_field_compare 'selftest' "$tmp_dir/login.json" "$tmp_dir/me.json"
  printf '  (b) 右边缺 nickname 时 —— 该格必须显示「（取不到）」，不能留空：\n'
  tc_print_field_compare 'selftest' "$tmp_dir/login.json" "$tmp_dir/me-missing-nickname.json"

  rm -rf "$tmp_dir"
}

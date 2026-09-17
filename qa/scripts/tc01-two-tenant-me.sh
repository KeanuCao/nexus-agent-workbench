#!/usr/bin/env bash
# =============================================================================
# tc01-two-tenant-me.sh —— 两个租户各用**自己的** token 调 GET /api/auth/me，响应原样并排打印
#
# 服务于：docs/test-cases/TC-01.md §2 的 TC-01-1.3-2（两租户互不可见）
#
# 为什么有它：这是一串纯机械动作（登录 → 从响应里抠 token → 带 token 调 /me → 登出），
#   而 WSL 内**没有 jq**（实测 MISS），手工只能写成
#   `grep -o '"token":"[^"]*"' | cut -d'"' -f4` 这种容易抄错的形态。机械动作归脚本，
#   **判断留给人** —— 人擅长的是看两条响应长得对不对，不是拼引号。
#
# ⚠️ 判据不藏脚本里（qa/README.md 铁律 #2）：本脚本**只做动作、只打印观察**，
#   不打 PASS/FAIL、不写「一致 / 不一致」。判据在 docs/test-cases/TC-01.md 的「预期」栏。
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 会写 Redis 的**两个**键：
#        nexus:auth:token:{jti}（admin 一个、demo 一个；TTL = nexus.jwt.expire-seconds = 7200s）
#      为什么非写不可：`/api/auth/me` 要求 token 同时满足「签名有效」与「Redis 白名单里该 jti 还在」，
#        而白名单只能由**真登录**写入（本地签的 token 过不了过滤器第 ③ 步 ——
#        TokenStore.exists 只判键是否存在，见 TokenStore.java:71-73）。不真登录就拿不到判据。
#      谁在消费它：白名单是**全局共享**的（所有带 token 的请求都查它）→ 清理必须精确到 jti，
#        绝不允许 FLUSHALL / 按前缀批量删。
#   ② 幂等清理 —— 清理段对两个 token 各调一次 POST /api/auth/logout（TokenStore.remove 幂等，
#      重复删同一个键不报错）。异常退出：`set -e` 与显式 exit 路径由 EXIT trap 兜底；
#      **Ctrl-C / kill 则要另外 trap** —— 实测（2026-09-17，WSL bash 5.2）：未被 trap 的信号
#      直接终止 bash，**EXIT trap 根本不会执行**，所以下面显式写了 `trap 'exit 130' INT` /
#      `trap 'exit 143' TERM`，把信号转换成一次正常退出，再让 EXIT trap 去做清理。
#      （前提：**前台运行**。`… &` 起在后台的 bash 会继承 SIGINT 的忽略态，而"入口即被忽略的
#        信号无法再被 trap" —— 那种跑法下 Ctrl-C 不触发清理，不在本脚本的设计范围内。）
#      残留的**最坏情况**：登录请求已发出、token 还没落到脚本手里就被中断 —— 那个 jti 登不掉，
#        只能等 TTL 自然过期（本脚本唯一无法自清的残留；它不阻断任何后续用例）。
#      **清理失败会怎样**：残留键最长存活 7200s（TTL 自然过期，无需人工修），期间仅脚本打印过的那两个
#        token 可用；要立刻清：重跑本脚本（幂等），或按下面指纹段打印的 jti 手工
#        `docker exec nexus-redis redis-cli DEL nexus:auth:token:<jti>`（该键是本用例自建的一次性对象）。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 白名单**键清单**指纹（不是只比个数），并打印
#      差异清单 + 两个自建 jti 的 EXISTS。跑前已存在的键若在窗口内自然过期，「跑前有、跑后无」
#      清单会非空 —— 那是既有键的 TTL，不是本脚本没清干净；清单里能看到具体是哪几个。
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   3 = 前提不成立（缺 python3 / curl / docker、Redis 取不到指纹、后端 /api/health 非 200）
#   4 = 某个 HTTP 动作没成功（登录没拿到 data.token）—— 原始响应已打印，照它判断
#
# 依赖：python3（JSON 取值；WSL 内实测 3.12.3）、curl、docker（取 Redis 指纹）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# =============================================================================

set -euo pipefail

readonly REPO_DIR='/mnt/c/wp/nexus-agent-workbench'
readonly ENV_FILE="${REPO_DIR}/docker-compose/.env"
readonly REDIS_CONTAINER='nexus-redis'
readonly REDIS_KEY_PATTERN='nexus:auth:token:*'

# ── 后端地址：宿主端口从 docker-compose/.env 解析（与 compose 同源，脚本内不写死 8089）──
backend_port='8089'
if [ -r "$ENV_FILE" ]; then
  port_from_env="$(grep -E '^[[:space:]]*BACKEND_PORT[[:space:]]*=' "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d ' \r"' || true)"
  if [ -n "$port_from_env" ]; then
    backend_port="$port_from_env"
  fi
fi
readonly BASE_URL="${TC01_BASE_URL:-http://localhost:${backend_port}}"

work_dir="$(mktemp -d)"
admin_token=''
demo_token=''
admin_jti=''
demo_jti=''
cleaned=0

section() { printf '\n===== %s =====\n' "$1"; }

# ── 从 JSON 文件里取「点分路径」的值（替代 jq）─────────────────────────────────
# 取不到（键不存在 / 父节点为 null / 不是 JSON）→ 退出码 1、不打印任何东西
json_get() {
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

# ── 本地解码 token 载荷的某个 claim（**只解码、不验签**；仅用于观察「返回值与 claim 的关系」）──
token_claim() {
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

# ── Redis 白名单指纹（只读）────────────────────────────────────────────────────
# 用 --scan 而不是 KEYS：不阻塞 Redis（本机能查也不该养成坏习惯）
redis_keys() {
  docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "$REDIS_KEY_PATTERN" 2>/dev/null | tr -d '\r' | sort
}

redis_exists() {  # redis_exists <完整键名> → 打印 0/1
  docker exec "$REDIS_CONTAINER" redis-cli exists "$1" 2>/dev/null | tr -d '\r'
}

# ── 响应体打印：curl 连不上时 -o 的文件**根本不会建**（实测），直接 cat 会被 set -e 吞成退出码 1 ──
show_body() {
  if [ -f "$1" ]; then
    cat "$1"
  else
    printf '（curl 没拿到响应体 —— 后端不可达 / 连接被拒）'
  fi
}

# ── HTTP 动作（只发请求、原样打印，不做任何判断）────────────────────────────────
post_login() {  # post_login <请求体> <响应写到这个文件>
  local body="$1" outfile="$2" http
  http="$(curl -s -o "$outfile" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "$body" "${BASE_URL}/api/auth/login" || true)"
  printf 'POST %s/api/auth/login\n  body: %s\n  → HTTP %s\n  body(原样): ' "$BASE_URL" "$body" "$http"
  show_body "$outfile"; printf '\n'
}

get_me() {  # get_me <标签> <token> <响应写到这个文件>
  local label="$1" token="$2" outfile="$3" http
  http="$(curl -s -o "$outfile" -w '%{http_code}' -H "Authorization: Bearer ${token}" \
        "${BASE_URL}/api/auth/me" || true)"
  printf 'GET %s/api/auth/me   [%s 的 token]\n  → HTTP %s\n  body(原样): ' "$BASE_URL" "$label" "$http"
  show_body "$outfile"; printf '\n'
}

post_logout() {  # post_logout <标签> <token>（token 为空则跳过 —— 幂等）
  if [ -z "$2" ]; then
    printf '  [%s] 没有 token，跳过登出\n' "$1"
    return 0
  fi
  local out
  out="$(curl -s -X POST -H "Authorization: Bearer $2" -w '\n  → HTTP %{http_code}' \
        "${BASE_URL}/api/auth/logout" || true)"
  printf '  [%s] POST %s/api/auth/logout\n  body(原样): %s\n' "$1" "$BASE_URL" "$out"
}

# ── 清理 + 无害性自证（正常路径与异常路径共用同一段）──────────────────────────────
cleanup_and_fingerprint() {
  section '⑤ 清理：登出本次自建的 token（幂等，重复登出不报错）'
  post_logout 'admin' "$admin_token"
  post_logout 'demo' "$demo_token"
  # 清理动作已完成 —— 置位后即便下面的取指纹失败，EXIT trap 也不会重复登出
  cleaned=1

  section '⑥ 无害性自证：Redis 白名单指纹（跑后）'
  redis_keys > "$work_dir/keys-after.txt" || true
  printf '  键数 = %s\n' "$(wc -l < "$work_dir/keys-after.txt" | tr -d ' ')"
  if [ -s "$work_dir/keys-after.txt" ]; then
    sed 's/^/  /' "$work_dir/keys-after.txt"
  else
    printf '  （空）\n'
  fi

  section '指纹差异（跑后 vs 跑前）'
  if [ -f "$work_dir/keys-before.txt" ]; then
    printf '  跑后有、跑前没有的键：\n'
    comm -13 "$work_dir/keys-before.txt" "$work_dir/keys-after.txt" | sed 's/^/    /'
    printf '  跑前有、跑后没有的键：\n'
    comm -23 "$work_dir/keys-before.txt" "$work_dir/keys-after.txt" | sed 's/^/    /'
  else
    printf '  （跑前指纹没取到，无法比较 —— 说明脚本在取指纹之前就退出了）\n'
  fi
  printf '  本次自建 jti 的存活检查（EXISTS，1 = 键还在）：\n'
  if [ -n "$admin_jti" ]; then
    printf '    admin jti=%s → %s\n' "$admin_jti" "$(redis_exists "nexus:auth:token:${admin_jti}" || echo '?')"
  fi
  if [ -n "$demo_jti" ]; then
    printf '    demo  jti=%s → %s\n' "$demo_jti" "$(redis_exists "nexus:auth:token:${demo_jti}" || echo '?')"
  fi
}

finish() {  # finish <退出码>：走正常收尾（清理 + 指纹），然后退出
  cleanup_and_fingerprint
  cleaned=1
  exit "$1"
}

cleanup() {  # EXIT trap：只在"非正常路径"（set -e 触发 / 信号）时兜底
  local rc=$?
  if [ "$cleaned" -eq 0 ]; then
    printf '\n[清理] 非正常路径退出（退出码 %s）—— 兜底执行清理与指纹，见下\n' "$rc"
    cleanup_and_fingerprint || true
  fi
  if [ -n "$work_dir" ]; then
    rm -rf "$work_dir"
  fi
  exit "$rc"
}
trap cleanup EXIT
# 信号本身不会触发 EXIT trap（实测），先转成一次正常退出，再交给 EXIT trap 兜底
trap 'exit 130' INT
trap 'exit 143' TERM

# ── 前置检查（全部只读；任何写操作都在这一段之后）──────────────────────────────
section '前置检查（只读）'
for tool in python3 curl docker; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf '错误：找不到 %s。本脚本要在 WSL（发行版 nexus-agent-workbench）内跑。\n' "$tool" >&2
    exit 3
  fi
done
printf '  工具：python3 / curl / docker 均可用\n'
printf '  后端地址：%s（端口取自 %s 的 BACKEND_PORT；可用环境变量 TC01_BASE_URL 覆盖）\n' "$BASE_URL" "$ENV_FILE"

redis_ping="$(docker exec "$REDIS_CONTAINER" redis-cli ping 2>&1 | tr -d '\r' || true)"
if [ "$redis_ping" != 'PONG' ]; then
  printf '错误：取不到 Redis 指纹（docker exec %s redis-cli ping → %s）。\n' "$REDIS_CONTAINER" "$redis_ping" >&2
  printf '      无害性自证（§2 第 3 条）依赖它，所以**先停下**，一个键都不写。\n' >&2
  exit 3
fi
printf '  Redis：docker exec %s redis-cli ping → PONG\n' "$REDIS_CONTAINER"

health_code="$(curl -s -o "$work_dir/health.json" -w '%{http_code}' "${BASE_URL}/api/health" || true)"
if [ "$health_code" != '200' ]; then
  printf '错误：GET %s/api/health → HTTP %s（预期 200）。原始响应：\n' "$BASE_URL" "$health_code" >&2
  show_body "$work_dir/health.json" >&2
  printf '\n      环境未就绪 —— 先跑 ./scripts/check-health.sh 看哪一项 DOWN，再回来。\n' >&2
  exit 3
fi
printf '  后端：GET %s/api/health → HTTP 200\n' "$BASE_URL"

# ── 跑前指纹 ─────────────────────────────────────────────────────────────────
section '① 指纹（跑前）：Redis 白名单 nexus:auth:token:* 键清单'
redis_keys > "$work_dir/keys-before.txt"
printf '  键数 = %s\n' "$(wc -l < "$work_dir/keys-before.txt" | tr -d ' ')"
if [ -s "$work_dir/keys-before.txt" ]; then
  sed 's/^/  /' "$work_dir/keys-before.txt"
else
  printf '  （空）\n'
fi

# ── ② 真登录 admin（写第 1 个键）────────────────────────────────────────────────
section '② 真登录 admin（租户 default；会写 1 个 Redis 白名单键）'
post_login '{"username":"admin","password":"admin123"}' "$work_dir/admin-login.json"
admin_token="$(json_get "$work_dir/admin-login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '\n错误：admin 的登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去，先查登录失败原因。\n' >&2
  finish 4
fi
admin_jti="$(token_claim "$admin_token" jti || true)"
printf '  admin token 长度 = %s\n' "${#admin_token}"
printf '  admin token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(token_claim "$admin_token" sub)" "$(token_claim "$admin_token" tenantId)" \
  "$(token_claim "$admin_token" username)" "$admin_jti"

# ── ③ 真登录 demo（写第 2 个键）─────────────────────────────────────────────────
section '③ 真登录 demo（租户 demo；会写 1 个 Redis 白名单键）'
post_login '{"username":"demo","password":"demo123"}' "$work_dir/demo-login.json"
demo_token="$(json_get "$work_dir/demo-login.json" data.token || true)"
if [ -z "$demo_token" ]; then
  printf '\n错误：demo 的登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去。\n' >&2
  finish 4
fi
demo_jti="$(token_claim "$demo_token" jti || true)"
printf '  demo token 长度 = %s\n' "${#demo_token}"
printf '  demo token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(token_claim "$demo_token" sub)" "$(token_claim "$demo_token" tenantId)" \
  "$(token_claim "$demo_token" username)" "$demo_jti"

# ── ④ 两个 token 各调一次 /me，并打印字段对照 ────────────────────────────────────
print_compare() {  # print_compare <账号名> <登录响应文件> <me 响应文件>
  local who="$1" login_json="$2" me_json="$3" field
  printf '\n  字段对照（左 = 登录响应的 data.user；右 = /api/auth/me 的 data）：\n'
  printf '    %s\t%s\t%s\n' '字段' "登录/${who}" "me/${who}"
  for field in userId username nickname tenantId tenantCode tenantName; do
    printf '    %s\t%s\t%s\n' "$field" \
      "$(json_get "$login_json" "data.user.${field}" || echo '（取不到）')" \
      "$(json_get "$me_json" "data.${field}" || echo '（取不到）')"
  done
}

section '④-a 用 admin 的 token 调 GET /api/auth/me'
get_me 'admin' "$admin_token" "$work_dir/admin-me.json"
print_compare 'admin' "$work_dir/admin-login.json" "$work_dir/admin-me.json"

section '④-b 用 demo 的 token 调 GET /api/auth/me'
get_me 'demo' "$demo_token" "$work_dir/demo-me.json"
print_compare 'demo' "$work_dir/demo-login.json" "$work_dir/demo-me.json"

finish 0

#!/usr/bin/env bash
# =============================================================================
# tc01-cross-tenant-token.sh —— 造一个「token 里的 tenantId 与身份不属于同一租户」的请求，看服务端怎么处理
#
# 服务于：docs/test-cases/TC-01.md §2 的 **TC-01-1.3-5**（新增用例）
# 契约依据：docs/api/README.md §5.4 末句 ——
#   「它也是除 401 之外的第二道闸：若 token 里的租户与用户实际所属租户不一致，
#     查询会自动带 tenant_id 条件 → 查不到 → 401。」
#
# 为什么需要它（正是 TC-01-1.3-2 覆盖不到的那半句）：
#   GET /api/auth/me **不收任何入参**（AuthController.java:84-87），永远只看调用者自己
#   —— 即便租户条件**完全没被注入**，"admin 用自己的 token 调 /me" 照样返回 admin 自己，
#   返回值一字不差（假阴性）。要真证「够不到别人的数据」，必须让**身份**与**租户**来自不同租户，
#   再看服务端是"拒绝"还是"照单全收"。
#
# 怎么造（**全是本地计算**：不写库、不写配置、不碰容器、不加任何新 Redis 键）：
#   ① 真登录 admin、demo → 各拿到一份**已在 Redis 白名单里**的真 token；
#   ② 本地用同一密钥重签：**逐字节沿用真 token 的 header、沿用它的 jti / sub / iat / exp**，
#      只把 tenantId 换成对方租户的：
#        · 沿用 jti ⇒ 过得了过滤器第 ③ 步（Redis 白名单只判键是否存在、不解析 value ——
#          TokenStore.java:71-73 的 exists() 就是 hasKey）；
#        · 签名用同一密钥算 ⇒ 过得了第 ② 步（这一点由下面的"夹具密钥自检"当场证明，不靠假设）。
#      → 这个请求因此能带着"自相矛盾的身份"走进业务代码，撞上的才是**租户隔离**这一层。
#   ③ 用它调 GET /api/auth/me。两个方向各一次：
#        A：admin 的身份（sub/jti）+ demo 的租户
#        B：demo  的身份（sub/jti）+ admin 的租户
#
# 结果怎么读（判据在 TC 里；这里只说会看到什么）：
#   隔离生效 → t_user 查询带上 tenant_id=<对方租户> → 查不到该 userId → null → HTTP 401 + code=40101
#   隔离失效 → HTTP 200，且 data 是**混合身份**：A 租户的 username/userId + B 租户的 tenantId/tenantCode/tenantName
#
# ⚠️ **假通过陷阱（本脚本用「夹具密钥自检」堵它，这是它存在的头号理由）**：
#   签名不对时后端也回 40101 —— 与"隔离生效"**同一个码**。若不先验签，
#   一个密钥配错（后端被 NEXUS_JWT_SECRET 覆盖过）就会伪装成 PASS。
#   所以脚本在做任何请求**之前**，先用本地密钥验签那份真实的 admin token：
#     · 验不过 → 退出码 3、**不给结论**（诊断信息里带本地密钥指纹）；
#     · 验过   → 本地 HMAC-SHA256 与后端 jjwt 的签发逐字节一致 ⇒ 下面重签的签名必被后端接受，
#                40101 这个码才真正指向"租户隔离把它拒了"。
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 会写 Redis 的**两个**键：nexus:auth:token:{jti}（admin / demo 各一，TTL 7200s）。
#      为什么非写不可：过滤器要求真 token 的 jti 在白名单里，而白名单只能由真登录写入。
#      两份**伪造**的 token 不产生任何新键（复用真 token 的 jti）。
#      谁在消费它：白名单全局共享 → 清理精确到 jti，绝不批量删。
#   ② 幂等清理 —— 清理段对两个真 token 各调一次 POST /api/auth/logout（remove 幂等）；
#      jti 一删，两份伪造 token 同时失效（它们共用同一个 jti）。
#      异常退出：`set -e` 与显式 exit 由 EXIT trap 兜底；**Ctrl-C / kill 另需 trap** ——
#      实测（2026-09-17，WSL bash 5.2）：未被 trap 的信号直接终止 bash，**EXIT trap 不会执行**，
#      故下面显式写了 `trap 'exit 130' INT` / `trap 'exit 143' TERM`，把信号转成一次正常退出。
#      （前提：**前台运行**。`… &` 起在后台的 bash 会继承 SIGINT 的忽略态，而"入口即被忽略的
#        信号无法再被 trap" —— 那种跑法下 Ctrl-C 不触发清理，不在本脚本的设计范围内。）
#      残留的**最坏情况**：登录请求已发出、token 还没落到脚本手里就被中断 —— 那个 jti 登不掉，
#        只能等 TTL 自然过期（本脚本唯一无法自清的残留；它不阻断任何后续用例）。
#      **清理失败会怎样**：残留键最长存活 7200s（TTL 自然过期，无需人工修）；要立刻清：
#        重跑本脚本，或按指纹段打印的 jti 手工 `docker exec nexus-redis redis-cli DEL nexus:auth:token:<jti>`。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 白名单**键清单**指纹 + 差异清单 + 两个自建 jti 的 EXISTS。
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   3 = 前提不成立（缺 python3 / curl / docker、Redis 取不到指纹、health 非 200、
#       **夹具密钥自检不通过**、两个账号租户 ID 相同）
#   4 = 某个 HTTP 动作没成功（登录没拿到 data.token）
#
# 用法：
#   bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc01-cross-tenant-token.sh              # 正常跑（需要环境就绪）
#   bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc01-cross-tenant-token.sh --self-test  # 只跑夹具自检（纯本地，不需要环境）
#
# 依赖：python3（HMAC + JSON + base64url；WSL 内实测 3.12.3）、curl、docker（取指纹）、openssl（仅 --self-test 用）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**。
#
# ⚠️ 与 qa/scripts/tc01-two-tenant-me.sh 有逐字相同的公共段（常量、json_get、Redis 指纹、HTTP 动作）
#    —— 改任何一边都要同步另一边（两个脚本各自独立可跑，刻意不做共享库）。
# =============================================================================

set -euo pipefail

readonly REPO_DIR='/mnt/c/wp/nexus-agent-workbench'
readonly ENV_FILE="${REPO_DIR}/docker-compose/.env"
readonly REDIS_CONTAINER='nexus-redis'
readonly REDIS_KEY_PATTERN='nexus:auth:token:*'

# application.yml:111 的 dev 默认值（与后端同源；后端若被环境变量覆盖，见"夹具密钥自检"）
readonly DEV_SECRET='nexus-dev-only-jwt-secret-please-override-in-any-real-environment'

# ── 后端地址：宿主端口从 docker-compose/.env 解析（与 compose 同源，脚本内不写死 8089）──
backend_port='8089'
if [ -r "$ENV_FILE" ]; then
  port_from_env="$(grep -E '^[[:space:]]*BACKEND_PORT[[:space:]]*=' "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d ' \r"' || true)"
  if [ -n "$port_from_env" ]; then
    backend_port="$port_from_env"
  fi
fi
readonly BASE_URL="${TC01_BASE_URL:-http://localhost:${backend_port}}"

work_dir=''
admin_token=''
demo_token=''
admin_jti=''
demo_jti=''
cleaned=0

section() { printf '\n===== %s =====\n' "$1"; }

# ── 从 JSON 文件里取「点分路径」的值（替代 jq；WSL 内无 jq）─────────────────────
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

# ── 本地解码 token 载荷的某个 claim（只解码、不验签）────────────────────────────
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

# ── 重签：<secret> <真 token> <新 tenantId> <方向标签> → stdout = 新 token，stderr = 说明 ──
# 夹具密钥自检也在这个函数里：验不过真 token 就直接退出码 3（见文件头"假通过陷阱"）
forge_token() {
  TC01_JWT_SECRET="$1" python3 - "$2" "$3" "$4" <<'PY'
import base64
import hashlib
import hmac
import json
import os
import sys

PREFIX = "[tc01-cross-tenant-token]"

real_token = sys.argv[1]
new_tenant_id = int(sys.argv[2])
label = sys.argv[3]
secret = os.environ["TC01_JWT_SECRET"].encode("utf-8")


def b64d(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def b64e(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def die(message):
    sys.stderr.write(PREFIX + " 错误：" + message + "\n")
    sys.exit(3)


parts = real_token.split(".")
if len(parts) != 3:
    die("拿到的真 token 不是三段式 JWS（长度 %d），无法处理" % len(real_token))
header_b64, payload_b64, signature_b64 = parts

# ① 夹具密钥自检：本地密钥能不能验过这份**后端亲自签发**的 token
expected = hmac.new(secret, (header_b64 + "." + payload_b64).encode("ascii"), hashlib.sha256).digest()
if not hmac.compare_digest(expected, b64d(signature_b64)):
    die("夹具密钥自检**不通过**：本地密钥验不过后端签发的真 token。\n"
        "        多半是后端用了别的密钥（application.yml 的 nexus.jwt.secret 被 NEXUS_JWT_SECRET 覆盖过；\n"
        "        compose 的 nexus-backend.environment 里默认没有这个变量，若你手工加过，就得把同名变量喂给本脚本）。\n"
        "        本用例的结论**无效**：继续做只会拿到 40101，与「隔离生效」同码，属**假通过**。\n"
        "        本地密钥指纹（sha256 前 8 位）：" + hashlib.sha256(secret).hexdigest()[:8])

payload_raw = b64d(payload_b64)
header_raw = b64d(header_b64)
claims = json.loads(payload_raw)

if "tenantId" not in claims or "jti" not in claims:
    die("真 token 的载荷缺少 tenantId / jti，本用例的前提不成立："
        + payload_raw.decode("utf-8", "replace"))
if int(claims["tenantId"]) == new_tenant_id:
    die("目标租户与 token 里的租户相同（都是 %s）—— 两个账号的租户 ID 撞了？\n"
        "        该看 t_tenant 种子数据或登录返回值，本用例无法成立。" % new_tenant_id)

forged_claims = dict(claims)
forged_claims["tenantId"] = new_tenant_id
forged_payload_raw = json.dumps(forged_claims, separators=(",", ":"), ensure_ascii=False).encode("utf-8")

changed = []
unchanged = []
for key in claims:
    if claims[key] != forged_claims.get(key):
        changed.append("%s: %s → %s" % (key, claims[key], forged_claims[key]))
    else:
        unchanged.append(key)
for key in forged_claims:
    if key not in claims:
        changed.append("%s: （新增）%s" % (key, forged_claims[key]))

sys.stderr.write(PREFIX + " [%s] 夹具密钥自检通过：本地 HMAC 与后端签发的真 token 逐字节一致\n" % label)
sys.stderr.write(PREFIX + " [%s] header 段逐字节沿用真 token：%s\n"
                 % (label, header_raw.decode("utf-8", "replace")))
sys.stderr.write(PREFIX + " [%s] 沿用真 token 的 jti=%s（它在 Redis 白名单里 ⇒ 过滤器第 ③ 步放行）\n"
                 % (label, claims["jti"]))
sys.stderr.write(PREFIX + " [%s] 载荷差异：%s\n" % (label, "；".join(changed) or "（无）"))
sys.stderr.write(PREFIX + " [%s] 其余未变字段：%s\n" % (label, ", ".join(unchanged) or "（无）"))
if payload_raw != json.dumps(claims, separators=(",", ":"), ensure_ascii=False).encode("utf-8"):
    sys.stderr.write(PREFIX + " [%s] ⚠️ 载荷「重序列化」与后端原始字节不完全相同"
                             "（字段级语义一致；本行列出供你核对，不影响结论）\n" % label)

signing_input = header_b64 + "." + b64e(forged_payload_raw)
signature = hmac.new(secret, signing_input.encode("ascii"), hashlib.sha256).digest()
sys.stdout.write(signing_input + "." + b64e(signature) + "\n")
PY
}

# ── Redis 白名单指纹（只读）────────────────────────────────────────────────────
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
  printf 'GET %s/api/auth/me   [%s]\n  → HTTP %s\n  body(原样): ' "$BASE_URL" "$label" "$http"
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

# ── 夹具自检（--self-test）：只跑本地计算，不碰 docker / 网络 / Redis / 后端 ──────────
run_self_test() {
  printf '===== 夹具自检（--self-test）：只跑本地计算 =====\n'
  printf '不碰 docker / 网络 / Redis / 后端。下面这些「通过 / 不通过」说的是**夹具自己**，与 TC 判据无关。\n'

  local tmp_dir fake_token forged forging_err verify_out signing_input openssl_sig forged_sig
  tmp_dir="$(mktemp -d)"

  printf '\n[自检 1] 本地造一份「模拟后端签发」的真 token（secret = application.yml 的 dev 默认值）\n'
  fake_token="$(TC01_JWT_SECRET="$DEV_SECRET" python3 - <<'PY'
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

secret = os.environ["TC01_JWT_SECRET"].encode("utf-8")
header = {"alg": "HS256"}
payload = {
    "sub": "11",
    "jti": str(uuid.uuid4()),
    "tenantId": 1,
    "username": "selftest-admin",
    "iat": int(time.time()),
    "exp": int(time.time()) + 7200,
}


def encode(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


signing_input = ".".join([
    encode(json.dumps(header, separators=(",", ":")).encode("utf-8")),
    encode(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
])
signature = hmac.new(secret, signing_input.encode("ascii"), hashlib.sha256).digest()
sys.stdout.write(signing_input + "." + encode(signature) + "\n")
PY
)"
  printf '  模拟真 token 长度 = %s\n' "${#fake_token}"

  printf '\n[自检 2] 用 forge_token 把它的 tenantId 由 1 改成 2，验三件事\n'
  forging_err="$tmp_dir/forge.err"
  forged="$(forge_token "$DEV_SECRET" "$fake_token" 2 '自检' 2>"$forging_err")"
  sed 's/^/  /' "$forging_err"
  printf '  重签后 token 长度 = %s\n' "${#forged}"

  if [ "${fake_token%%.*}" = "${forged%%.*}" ]; then
    printf '  (a) header 段与模拟真 token 逐字节相同：通过\n'
  else
    printf '  (a) header 段与模拟真 token 逐字节相同：**不通过** ← 夹具改了 header，后端可能拒收\n'
  fi

  verify_out="$(TC01_JWT_SECRET="$DEV_SECRET" python3 - "$fake_token" "$forged" <<'PY'
import base64
import hashlib
import hmac
import json
import os
import sys

secret = os.environ["TC01_JWT_SECRET"].encode("utf-8")


def decode(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def claims_of(token):
    return json.loads(decode(token.split(".")[1]))


real_claims = claims_of(sys.argv[1])
forged_claims = claims_of(sys.argv[2])
keys = sorted(set(real_claims) | set(forged_claims))
diff = {k: (real_claims.get(k), forged_claims.get(k)) for k in keys
        if real_claims.get(k) != forged_claims.get(k)}
signing_input = ".".join(sys.argv[2].split(".")[:2])
signature_ok = hmac.compare_digest(
    hmac.new(secret, signing_input.encode("ascii"), hashlib.sha256).digest(),
    decode(sys.argv[2].split(".")[2]))
print("载荷差异字段 = %s" % (diff if diff else "（无）"))
print("未变字段 = %s" % ", ".join(k for k in keys if k not in diff))
print("重签后签名自洽（同一密钥复算）= %s" % signature_ok)
PY
)"
  printf '%s\n' "$verify_out" | sed 's/^/  (b) /'

  signing_input="${forged%.*}"
  forged_sig="${forged##*.}"
  if command -v openssl >/dev/null 2>&1; then
    openssl_sig="$(printf '%s' "$signing_input" | openssl dgst -sha256 -hmac "$DEV_SECRET" -binary | base64 | tr '+/' '-_' | tr -d '=')"
    if [ "$openssl_sig" = "$forged_sig" ]; then
      printf '  (c) openssl 独立复算签名（与脚本不是同一段代码）：与脚本算出的相同\n'
    else
      printf '  (c) openssl 独立复算签名（与脚本不是同一段代码）：与脚本算出的**不同** ← 夹具算法有问题\n'
      printf '      openssl 段 = %s\n      脚本段    = %s\n' "$openssl_sig" "$forged_sig"
    fi
  else
    printf '  (c) 跳过：本机没有 openssl（独立复算做不了，不因此判定夹具没问题）\n'
  fi

  printf '\n[自检 3] 假通过陷阱的守卫：拿一个**错的密钥**去重签同一份真 token，应被拒绝（退出码 3）\n'
  local wrong_rc=0 wrong_err
  wrong_err="$(forge_token 'a-deliberately-wrong-secret-0123456789-abcdefghijklmnop' "$fake_token" 2 '自检-错误密钥' 2>&1 >/dev/null)" || wrong_rc=$?
  printf '  退出码 = %s（预期 3）\n' "$wrong_rc"
  printf '%s\n' "$wrong_err" | sed 's/^/  /'

  rm -rf "$tmp_dir"
  printf '\n===== 夹具自检结束 =====\n'
}

# ── 入口：--self-test 只跑本地自检，不进主流程 ────────────────────────────────────
if [ "${1:-}" = '--self-test' ]; then
  run_self_test
  exit 0
fi
if [ "$#" -gt 0 ]; then
  printf '错误：未知参数 %s（只支持 --self-test）\n' "$1" >&2
  exit 2
fi

# ── 主流程 ────────────────────────────────────────────────────────────────────
work_dir="$(mktemp -d)"

cleanup_and_fingerprint() {
  section '⑧ 清理：登出两个真 token（幂等；jti 一删，两份伪造 token 同时失效）'
  post_logout 'admin' "$admin_token"
  post_logout 'demo' "$demo_token"
  # 清理动作已完成 —— 置位后即便下面的取指纹失败，EXIT trap 也不会重复登出
  cleaned=1

  section '⑨ 无害性自证：Redis 白名单指纹（跑后）'
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

finish() {  # finish <退出码>
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

section '前置检查（只读）'
for tool in python3 curl docker; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf '错误：找不到 %s。本脚本要在 WSL（发行版 nexus-agent-workbench）内跑。\n' "$tool" >&2
    exit 3
  fi
done
printf '  工具：python3 / curl / docker 均可用\n'
printf '  后端地址：%s（端口取自 %s 的 BACKEND_PORT；可用环境变量 TC01_BASE_URL 覆盖）\n' "$BASE_URL" "$ENV_FILE"

if [ -n "${NEXUS_JWT_SECRET:-}" ]; then
  secret="$NEXUS_JWT_SECRET"
  secret_source='环境变量 NEXUS_JWT_SECRET'
else
  secret="$DEV_SECRET"
  secret_source='application.yml 的 dev 默认值（NEXUS_JWT_SECRET 未设置）'
fi
printf '  本地夹具密钥来源：%s\n' "$secret_source"
printf '  本地夹具密钥指纹（sha256 前 8 位）：%s\n' "$(printf '%s' "$secret" | sha256sum | cut -c1-8)"

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

section '① 指纹（跑前）：Redis 白名单 nexus:auth:token:* 键清单'
redis_keys > "$work_dir/keys-before.txt"
printf '  键数 = %s\n' "$(wc -l < "$work_dir/keys-before.txt" | tr -d ' ')"
if [ -s "$work_dir/keys-before.txt" ]; then
  sed 's/^/  /' "$work_dir/keys-before.txt"
else
  printf '  （空）\n'
fi

section '② 真登录 admin（租户 default；会写 1 个 Redis 白名单键）'
post_login '{"username":"admin","password":"admin123"}' "$work_dir/admin-login.json"
admin_token="$(json_get "$work_dir/admin-login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '\n错误：admin 的登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去。\n' >&2
  finish 4
fi
admin_jti="$(token_claim "$admin_token" jti || true)"
admin_claim_tenant="$(token_claim "$admin_token" tenantId || true)"
printf '  admin token 长度 = %s\n' "${#admin_token}"
printf '  admin token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(token_claim "$admin_token" sub)" "$admin_claim_tenant" \
  "$(token_claim "$admin_token" username)" "$admin_jti"

section '③ 真登录 demo（租户 demo；会写 1 个 Redis 白名单键）'
post_login '{"username":"demo","password":"demo123"}' "$work_dir/demo-login.json"
demo_token="$(json_get "$work_dir/demo-login.json" data.token || true)"
if [ -z "$demo_token" ]; then
  printf '\n错误：demo 的登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去。\n' >&2
  finish 4
fi
demo_jti="$(token_claim "$demo_token" jti || true)"
demo_claim_tenant="$(token_claim "$demo_token" tenantId || true)"
printf '  demo token 长度 = %s\n' "${#demo_token}"
printf '  demo token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(token_claim "$demo_token" sub)" "$demo_claim_tenant" \
  "$(token_claim "$demo_token" username)" "$demo_jti"

section '④ 重签方向 A：admin 的身份（sub/jti）+ demo 的租户（本地计算，不写任何状态）'
if ! forged_a="$(forge_token "$secret" "$admin_token" "$demo_claim_tenant" '方向A')"; then
  printf '\n错误：夹具前提不成立（原因见上）—— 本用例**不给结论**。\n' >&2
  finish 3
fi

section '⑤ 用方向 A 的 token 调 GET /api/auth/me'
get_me '方向A：admin 的身份 + demo 的租户' "$forged_a" "$work_dir/forged-a-me.json"

section '⑥ 重签方向 B：demo 的身份（sub/jti）+ admin 的租户（本地计算，不写任何状态）'
if ! forged_b="$(forge_token "$secret" "$demo_token" "$admin_claim_tenant" '方向B')"; then
  printf '\n错误：夹具前提不成立（原因见上）—— 本用例**不给结论**。\n' >&2
  finish 3
fi

section '⑦ 用方向 B 的 token 调 GET /api/auth/me'
get_me '方向B：demo 的身份 + admin 的租户' "$forged_b" "$work_dir/forged-b-me.json"

finish 0

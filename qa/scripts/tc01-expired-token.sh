#!/usr/bin/env bash
# =============================================================================
# tc01-expired-token.sh —— 造一个「签名正确、exp 已过、jti 从未进过 Redis」的 JWT
#
# 服务于：docs/test-cases/TC-01.md §1.2 的 TC-01-1.2-3（过期 token → HTTP 401 + code=40102）
#
# 为什么用「本地签一个」而不是「把 nexus.jwt.expire-seconds 改成 0 再登录」：
#   1) 改成 0 会让登录直接失败 —— TokenStore.save 有 TTL 正数守卫
#      （ttlSeconds <= 0 抛 SystemException → GlobalExceptionHandler 出 HTTP 500 + code=50000），
#      根本拿不到 token，步骤 ① 就完不成；
#   2) 改配置要重建 nexus-backend 容器，生效期间**其他用例**拿到的 token 也会秒过期（污染）；
#   3) 过滤器的判定顺序是「② 解析（含有效期）→ ③ Redis 白名单」，② 在 ③ 之前，
#      所以「本地签一个已过期的 token」与「真签发后等到过期」在过滤器里走的是**同一条路径**。
#      详见 JwtAuthenticationFilter#doFilterInternal 与 TC-01.md 的「TC-01-1.2-3 备注」行。
#
# 无害性（Test Harmlessness §1 的「只读观察」，优先级最高的一档）：
#   本脚本**只做本地计算**（HMAC-SHA256 + base64url），不写数据库、不写 Redis、不改任何配置、
#   不碰任何容器。跑完环境与跑之前逐字节一致 —— 没有需要清理的状态，也就没有清理失败一说。
#   中途 Ctrl-C 同理：什么都不会留下。
#
# 输出约定：
#   stdout —— 只有 token 本身（供 TOKEN=$(...) 捕获）
#   stderr —— 给人看的说明（密钥来源 / iat / exp / 过期了多少秒 / jti）
#
# 依赖：python3（≥3.6；只用 hmac / hashlib / base64 / json / uuid / time 标准库）。
#       刻意不用 jq（WSL 内没有，见 TC-01.md §1.2 的既有备注）、不用 node（WSL 内也没有）。
#       已实测：发行版 nexus-agent-workbench 的 /usr/bin/python3 = 3.12.3。
#
# 用法（在 WSL 里跑）：
#   TOKEN=$(bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc01-expired-token.sh)
#   bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc01-expired-token.sh --expired-by 300
#
# 密钥来源：默认取 backend/nexus-start/src/main/resources/application.yml 里
#   nexus.jwt.secret 的 dev 默认值。该值随仓库公开，且 docker-compose.yml 的
#   nexus-backend.environment **未**注入 NEXUS_JWT_SECRET —— 所以默认值就是当前生效值。
#   ⚠️ 若你给后端设过 NEXUS_JWT_SECRET，必须用同名环境变量喂给本脚本，否则签出来的
#      token 签名不匹配 → 后端回 **40101** 而不是 40102（那是夹具密钥不对，不是产品缺陷；
#      脚本会在 stderr 打印「密钥来源」与「密钥指纹」帮你认这一条）。
# =============================================================================

set -euo pipefail

# application.yml:111 的 dev 默认值（与后端同源；后端若被环境变量覆盖，见文件头说明）
readonly DEV_SECRET='nexus-dev-only-jwt-secret-please-override-in-any-real-environment'

readonly USAGE='用法：tc01-expired-token.sh [--expired-by <秒>]
  --expired-by <秒>   token 的 exp 取「当前时刻 - 秒」（默认 60，必须 > 0）
  输出：stdout = token；stderr = 说明。示例：
    TOKEN=$(bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc01-expired-token.sh)'

expired_by=60

while [ $# -gt 0 ]; do
  case "$1" in
    --expired-by)
      if [ $# -lt 2 ]; then
        echo "错误：--expired-by 需要一个秒数" >&2
        exit 2
      fi
      expired_by="$2"
      shift 2
      ;;
    -h|--help)
      echo "$USAGE"
      exit 0
      ;;
    *)
      echo "错误：未知参数 '$1'" >&2
      echo "$USAGE" >&2
      exit 2
      ;;
  esac
done

case "$expired_by" in
  ''|*[!0-9]*)
    echo "错误：--expired-by 必须是正整数秒，收到 '$expired_by'" >&2
    exit 2
    ;;
esac
if [ "$expired_by" -le 0 ]; then
  echo "错误：--expired-by 必须 > 0（等于 0 会签出一个「刚好现在过期」的 token，边界上判据不稳）" >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "错误：找不到 python3。本脚本需要在 WSL 里跑（如 wsl -d nexus-agent-workbench），" >&2
  echo "      该发行版已确认有 /usr/bin/python3；容器内与宿主 Windows 均不保证。" >&2
  exit 3
fi

# 密钥解析：环境变量优先（与 Spring 的 ${NEXUS_JWT_SECRET:默认值} 同序）
if [ -n "${NEXUS_JWT_SECRET:-}" ]; then
  secret="$NEXUS_JWT_SECRET"
  secret_source="环境变量 NEXUS_JWT_SECRET"
else
  secret="$DEV_SECRET"
  secret_source="application.yml 的 dev 默认值（NEXUS_JWT_SECRET 未设置）"
fi

# 密钥只经环境变量传入 python（避免出现在 ps 的命令行里）
TC01_JWT_SECRET="$secret" python3 - "$expired_by" "$secret_source" <<'PY'
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

PREFIX = "[tc01-expired-token]"


def b64url(raw: bytes) -> str:
    """JWS 紧凑序列化用的 base64url：去 padding（与 jjwt 一致）。"""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def die(message: str) -> None:
    sys.stderr.write(f"{PREFIX} {message}\n")
    sys.exit(1)


expired_by = int(sys.argv[1])
secret_source = sys.argv[2]
secret = os.environ["TC01_JWT_SECRET"]

# 与 JwtUtil#buildSecretKey 同一条守卫：HS256 密钥短于 32 字节时，后端会启动即失败
secret_bytes = secret.encode("utf-8")
if len(secret_bytes) < 32:
    die(f"密钥长度不足 32 字节（当前 {len(secret_bytes)}），后端根本起不来，先查 NEXUS_JWT_SECRET")

now = int(time.time())
exp = now - expired_by
iat = exp - 7200  # 与 nexus.jwt.expire-seconds 的默认值一致，让 token「像真签发的」一样短

# claims 与 JwtUtil#issue 逐项对齐（sub/jti/tenantId/username/iat/exp）：
#   · 少了 sub / tenantId 时，万一 exp 判定没生效，后端会因缺 claim 回 40101 而不是误放行；
#   · jti 是随机 UUID，从未登记进 Redis → 即便 exp 判定没生效，也只会走到 ③ 回 40101。
#     两重都是「失败即失败、不会变成假通过」的方向。
payload = {
    "sub": "1",
    "jti": str(uuid.uuid4()),
    "tenantId": 1,
    "username": "admin",
    "iat": iat,
    "exp": exp,
}
header = {"alg": "HS256"}  # 与 jjwt 在未设 typ 时的输出一致

segments = [
    b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
    b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
]
signing_input = ".".join(segments)
signature = hmac.new(secret_bytes, signing_input.encode("ascii"), hashlib.sha256).digest()
token = signing_input + "." + b64url(signature)

fingerprint = hashlib.sha256(secret_bytes).hexdigest()[:8]
sys.stderr.write(f"{PREFIX} 密钥来源：{secret_source}\n")
sys.stderr.write(f"{PREFIX} 密钥指纹（sha256 前 8 位）：{fingerprint}（与后端不一致时会回 40101）\n")
sys.stderr.write(f"{PREFIX} iat={iat} exp={exp} now={now} → exp 已过期 {expired_by} 秒\n")
sys.stderr.write(f"{PREFIX} jti={payload['jti']}（从未登记进 Redis）\n")

sys.stdout.write(token + "\n")
PY

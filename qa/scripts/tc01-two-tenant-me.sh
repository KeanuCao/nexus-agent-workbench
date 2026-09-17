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
# 机制要点（脚本只是把这条链走一遍，判断仍在你）：
#   `GET /api/auth/me` 要 token 同时满足「签名有效」（过滤器第 ② 步）与「Redis 白名单里该 jti
#   还在」（第 ③ 步），而白名单只能由**真登录**写入 —— 所以本脚本必须真登录，也因此会写键。
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
#      重复删同一个键不报错）；异常退出（含 Ctrl-C / kill）由 tc_begin 装的 trap 兜底，见
#      qa/scripts/lib/tc-common.sh 的文件头（「信号不触发 EXIT trap」是实测结论）。
#      残留的**最坏情况**：登录请求已发出、token 还没落到脚本手里就被中断 —— 那个 jti 登不掉，
#        只能等 TTL 自然过期（本脚本唯一无法自清的残留；它不阻断任何后续用例）。
#      **清理失败会怎样**：残留键最长存活 7200s（TTL 自然过期，无需人工修），期间仅脚本打印过的那两个
#        token 可用；要立刻清：重跑本脚本（幂等），或按下面指纹段打印的 jti 手工
#        `docker exec nexus-redis redis-cli DEL nexus:auth:token:<jti>`（该键是本用例自建的一次性对象）。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 白名单**键清单**指纹（不是只比个数），并打印
#      差异清单 + 两个自建 jti 的 EXISTS。
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   3 = 前提不成立（缺 python3 / curl / docker、Redis 取不到指纹、后端 /api/health 非 200）
#   4 = 某个 HTTP 动作没成功（登录没拿到 data.token）—— 原始响应已打印，照它判断
#
# 用法：
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc01-two-tenant-me.sh
#
# 依赖：python3（JSON 取值；WSL 内实测 3.12.3）、curl、docker（取 Redis 指纹）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# 公共机械动作段（常量解析 / JSON 取值 / HTTP 动作 / Redis 指纹 / 清理脚手架）在
#   qa/scripts/lib/tc-common.sh —— 改那边影响全部四个 tc01-* 脚本，本文件只留本用例的编排。
# =============================================================================

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

admin_token=''
demo_token=''

# ── 清理 hook：只做清理动作（幂等），由库在正常收尾与异常兜底两条路径上调用 ──────────
tc_cleanup() {
  tc_post_logout 'admin' "$admin_token"
  tc_post_logout 'demo' "$demo_token"
}

tc_begin tc_cleanup \
  '⑤ 清理：登出本次自建的 token（幂等，重复登出不报错）' \
  '⑥ 无害性自证：Redis 白名单指纹（跑后）'

# ── 前置检查（全部只读；任何写操作都在这一段之后）──────────────────────────────
tc_preflight_tools
tc_preflight_services

# ── ① 跑前指纹 ───────────────────────────────────────────────────────────────
tc_fingerprint_before '① 指纹（跑前）：Redis 白名单 nexus:auth:token:* 键清单'

# ── ② 真登录 admin（写第 1 个键）────────────────────────────────────────────────
tc_section '② 真登录 admin（租户 default；会写 1 个 Redis 白名单键）'
tc_post_login '{"username":"admin","password":"admin123"}' "$tc_work_dir/admin-login.json"
admin_token="$(tc_json_get "$tc_work_dir/admin-login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '\n错误：admin 的登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去，先查登录失败原因。\n' >&2
  tc_finish 4
fi
tc_note_jti 'admin' "$(tc_token_claim "$admin_token" jti || true)"
printf '  admin token 长度 = %s\n' "${#admin_token}"
printf '  admin token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(tc_token_claim "$admin_token" sub)" "$(tc_token_claim "$admin_token" tenantId)" \
  "$(tc_token_claim "$admin_token" username)" "$(tc_token_claim "$admin_token" jti)"

# ── ③ 真登录 demo（写第 2 个键）─────────────────────────────────────────────────
tc_section '③ 真登录 demo（租户 demo；会写 1 个 Redis 白名单键）'
tc_post_login '{"username":"demo","password":"demo123"}' "$tc_work_dir/demo-login.json"
demo_token="$(tc_json_get "$tc_work_dir/demo-login.json" data.token || true)"
if [ -z "$demo_token" ]; then
  printf '\n错误：demo 的登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去。\n' >&2
  tc_finish 4
fi
tc_note_jti 'demo' "$(tc_token_claim "$demo_token" jti || true)"
printf '  demo token 长度 = %s\n' "${#demo_token}"
printf '  demo token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(tc_token_claim "$demo_token" sub)" "$(tc_token_claim "$demo_token" tenantId)" \
  "$(tc_token_claim "$demo_token" username)" "$(tc_token_claim "$demo_token" jti)"

# ── ④ 两个 token 各调一次 /me，并打印字段对照 ────────────────────────────────────
tc_section '④-a 用 admin 的 token 调 GET /api/auth/me'
tc_get_me 'admin 的 token' "$admin_token" "$tc_work_dir/admin-me.json"
tc_print_field_compare 'admin' "$tc_work_dir/admin-login.json" "$tc_work_dir/admin-me.json"

tc_section '④-b 用 demo 的 token 调 GET /api/auth/me'
tc_get_me 'demo 的 token' "$demo_token" "$tc_work_dir/demo-me.json"
tc_print_field_compare 'demo' "$tc_work_dir/demo-login.json" "$tc_work_dir/demo-me.json"

tc_finish 0

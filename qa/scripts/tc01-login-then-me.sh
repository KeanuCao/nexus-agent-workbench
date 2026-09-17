#!/usr/bin/env bash
# =============================================================================
# tc01-login-then-me.sh —— 真登录拿 token，带着**同一个** token 调 GET /api/auth/me
#
# 服务于：docs/test-cases/TC-01.md §1 的 **TC-01-1.2-4**（合法 token 放行）
# 契约依据：docs/api/README.md §5.2（登录响应 data.user）、§5.4（/me 的 data 与它**同构**）
#
# 为什么有它：原步骤要执行者手工拼两条命令 ——
#   `TOKEN=$(curl -s -X POST … | grep -o '"token":"[^"]*"' | cut -d'"' -f4)` 再把 $TOKEN 喂给第二条。
#   这正是「人最不擅长、也最容易出错」的一环（WSL 内没有 jq，连"取 JSON 里的字段"都得拼引号），
#   而拼接错误会被误读成产品缺陷。机械动作归脚本，**比对留给人**：本脚本把登录响应与 /me 响应
#   **原样**打印，并把两边的 6 个字段并排摆成一张表 —— 人只需看一眼两列是否逐字段相同。
#
# ⚠️ 判据不藏脚本里（qa/README.md 铁律 #2）：本脚本**只做动作、只打印观察**，
#   不打 PASS/FAIL、不写「一致 / 不一致」。判据在 docs/test-cases/TC-01.md 的「预期」栏。
#
# ⚠️ **静默取空是这条用例的头号假通过形态**：若取值器坏了，两边都取不到值、表格两列全空，
#   看上去反而"完全一致"。所以：任何时候取不到值都会打印「（取不到）」而**不留空**，
#   表格里一旦出现「（取不到）」就是**没取到值**，不得当成"两边一致"。
#   （`--self-test` 里专门有一段验这条：取不到的路径必须失败，而不是静默留空。）
#
# 本用例**单独证明不了**的事（别把它的 PASS 读大了）：它证的是「合法 token 能过」，
#   **不能**证「该接口需要鉴权」—— 那半边由 TC-01-1.2-1（无 token → 401）与
#   TC-01-1.2-2（伪造 token → 401）覆盖；也不能证签名错会怎样（那是 1.2-2/1.2-3）。
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 会写 Redis 的**一个**键：nexus:auth:token:{jti}
#      （TTL = nexus.jwt.expire-seconds = 7200s）。
#      为什么非写不可：`/api/auth/me` 要求 token 同时满足「签名有效」（过滤器第 ② 步）与
#        「Redis 白名单里该 jti 还在」（第 ③ 步），而白名单只能由**真登录**写入 ——
#        本地签一个 token 过不了第 ③ 步（TokenStore.exists 只判键是否存在），
#        不真登录就拿不到"合法 token 放行"的判据。
#      谁在消费它：白名单是**全局共享**的（所有带 token 的请求都查它）→ 清理精确到 jti，
#        绝不允许 FLUSHALL / 按前缀批量删。
#   ② 幂等清理 —— 清理段调一次 POST /api/auth/logout（TokenStore.remove 幂等，重复删不报错）；
#      异常退出（含 Ctrl-C / kill）由 tc_begin 装的 trap 兜底，见 qa/scripts/lib/tc-common.sh 文件头。
#      残留的**最坏情况**：登录请求已发出、token 还没落到脚本手里就被中断 —— 那个 jti 登不掉，
#        只能等 TTL 自然过期（本脚本唯一无法自清的残留；它不阻断任何后续用例）。
#      **清理失败会怎样**：残留键最长存活 7200s（TTL 自然过期，无需人工修），期间仅脚本打印过的那个
#        token 可用；要立刻清：重跑本脚本（幂等），或按指纹段打印的 jti 手工
#        `docker exec nexus-redis redis-cli DEL nexus:auth:token:<jti>`（该键是本用例自建的一次性对象）。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 白名单**键清单**指纹（不是只比个数），并打印
#      差异清单 + 自建 jti 的 EXISTS（登出后应为 0）。
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   2 = 用法错误（只支持 --self-test）
#   3 = 前提不成立（缺 python3 / curl / docker、Redis 取不到指纹、后端 /api/health 非 200）
#   4 = 某个 HTTP 动作没成功（登录没拿到 data.token）—— 原始响应已打印，照它判断
#
# 用法：
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc01-login-then-me.sh              # 正常跑（需要环境就绪）
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc01-login-then-me.sh --self-test  # 只跑工具自检（纯本地，不需要环境）
#
# 依赖：python3（JSON 取值；WSL 内实测 3.12.3）、curl、docker（取 Redis 指纹）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# 公共机械动作段在 qa/scripts/lib/tc-common.sh；本文件只留本用例的编排。
# =============================================================================

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

admin_token=''

# ── 清理 hook：只做清理动作（幂等），由库在正常收尾与异常兜底两条路径上调用 ──────────
tc_cleanup() {
  tc_post_logout 'admin' "$admin_token"
}

# ── 工具自检（--self-test）：只跑本地计算，不碰 docker / 网络 / Redis / 后端 ──────────
run_self_test() {
  printf '===== 工具自检（--self-test）：只跑本地计算 =====\n'
  printf '不碰 docker / 网络 / Redis / 后端。下面这些「通过 / 不通过」说的是**本脚本依赖的取值器**，与 TC 判据无关。\n'
  tc_selftest_observers
  printf '\n===== 工具自检结束 =====\n'
}

# ── 入口：--self-test 只跑本地自检，不进主流程（也不建工作目录、不装 trap）────────────
if [ "${1:-}" = '--self-test' ]; then
  run_self_test
  exit 0
fi
if [ "$#" -gt 0 ]; then
  printf '错误：未知参数 %s（只支持 --self-test）\n' "$1" >&2
  exit 2
fi

# ── 主流程 ────────────────────────────────────────────────────────────────────
tc_begin tc_cleanup \
  '④ 清理：登出本次自建的 token（幂等，重复登出不报错）' \
  '⑤ 无害性自证：Redis 白名单指纹（跑后）'

# ── 前置检查（全部只读；任何写操作都在这一段之后）──────────────────────────────
tc_preflight_tools
tc_preflight_services

# ── ① 跑前指纹 ───────────────────────────────────────────────────────────────
tc_fingerprint_before '① 指纹（跑前）：Redis 白名单 nexus:auth:token:* 键清单'

# ── ② 真登录 admin（写 1 个键）──────────────────────────────────────────────────
tc_section '② 真登录 admin（租户 default；会写 1 个 Redis 白名单键）'
tc_post_login '{"username":"admin","password":"admin123"}' "$tc_work_dir/login.json"
admin_token="$(tc_json_get "$tc_work_dir/login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '\n错误：登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去，先查登录失败原因。\n' >&2
  tc_finish 4
fi
admin_jti="$(tc_token_claim "$admin_token" jti || true)"
tc_note_jti 'admin' "$admin_jti"
printf '  admin token 长度 = %s\n' "${#admin_token}"
printf '  admin token 载荷（本地解码，仅观察）：sub=%s tenantId=%s username=%s jti=%s\n' \
  "$(tc_token_claim "$admin_token" sub)" "$(tc_token_claim "$admin_token" tenantId)" \
  "$(tc_token_claim "$admin_token" username)" "$admin_jti"
printf '  该 jti 在白名单里的状态（EXISTS，1 = 在）：%s   ← ③ 能被放行的前提之一\n' \
  "$(tc_redis_exists "nexus:auth:token:${admin_jti}" || echo '?')"

# ── ③ 带着**同一个** token 调 /me，并打印两边字段对照 ─────────────────────────────
tc_section '③ 用同一个 token 调 GET /api/auth/me'
tc_get_me 'admin 的 token' "$admin_token" "$tc_work_dir/me.json"
printf '  （下面这张表是「登录响应的 data.user」与「/me 的 data」并排 —— 逐字段对不对，由你看，脚本不判）'
tc_print_field_compare 'admin' "$tc_work_dir/login.json" "$tc_work_dir/me.json"

tc_finish 0

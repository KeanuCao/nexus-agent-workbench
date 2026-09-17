#!/usr/bin/env bash
# =============================================================================
# tc01-logout-then-me.sh —— 真登录拿 token → 登出 → 用**同一个** token 再调 GET /api/auth/me
#
# 服务于：docs/test-cases/TC-01.md §1 的 **TC-01-1.2-5**（登出即失效）
# 契约依据：docs/api/README.md §5.1 第 5 条（登出 = 服务端删掉 Redis 里那条记录 → 同一个 token
#   立刻失效）、§5.3（logout 成功 = 200 + code=0；失败 = 401 + 40100/40101）、§5.4（/me 的失败码）
#
# 为什么有它：原步骤是三步手工动作 —— 登录拿 token → 登出 → 带着同一个 token 再调 /me，
#   每一步都要把上一步的输出（那个 200 多字符的 token）喂给下一步。机械动作归脚本，
#   **结论留给人**：本脚本把每一步的原始响应原样打印，并额外打印"键还在不在"与后端日志的相关行。
#
# ⚠️⚠️ **这条用例最容易读错的地方（务必先读）**：`40101` 是**两个不同原因共用的码** ——
#   过滤器第 ③ 步「Redis 白名单里没有该 jti（已登出/被清除）」用它，
#   第 ② 步「token 非法（签名不匹配 / 格式非法）」**也用它**（JwtAuthenticationFilter:149 与 :181），
#   两者都是 HTTP 401 + code=40101。
#   ⇒ **这条用例要证的是「登出之后落在第 ③ 步」，不是「拿到 40101 就算过」。**
#   本脚本用两条**只读观察**把这两条路径分开（都由你判，脚本不下结论）：
#     (1) 第 ④ 步后打印该 jti 的 `EXISTS` = 0 —— 键确实被删了，而 token 一字未变；
#     (2) 第 ⑥ 步抓后端日志：第 ③ 步的 warn **带 jti**、文案是「Redis 白名单无此登录态（已登出或被清除）」，
#         第 ② 步的两条 warn（「token 非法（type=…）」「token 已过期」）**不带 jti**。
#         所以日志里能与**本次 jti** 对上的「Redis 白名单无此登录态 + path=/api/auth/me」，
#         就是落闸在第 ③ 步的直接证据。
#   （推理链兜底：第 ④ 步登出回 200 + code=0，说明那一刻这个 token 的 ② 与 ③ 都过了；
#     ② 只看 token 字节与当前时刻，之后不会再变 ⇒ ⑤ 的 40101 只可能来自 ③。）
#
# 为什么第 ③ 步要先调一次 /me（基线）：没有它，「登出后 401」与「这个 token 本来就不行」分不开。
#   有了它，③ 与 ⑤ 是**同一个 token** 的一前一后，两个响应之间唯一的变量就是"键在不在了"。
#   顺带堵住另一种假通过：万一 logout 根本没要求鉴权（回 200 却什么也没做），③ 的基线也会先暴露它。
#
# ⚠️ 判据不藏脚本里（qa/README.md 铁律 #2）：本脚本**只做动作、只打印观察**，
#   不打 PASS/FAIL、不写「失效 / 没失效」。判据在 docs/test-cases/TC-01.md 的「预期」栏。
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 会写 Redis 的**一个**键：nexus:auth:token:{jti}
#      （TTL = nexus.jwt.expire-seconds = 7200s），并在第 ④ 步**删掉它**（这正是用例本身）。
#      为什么非写不可：'登出即失效'判的就是"白名单里那条记录没了"，而白名单只能由真登录写入；
#        没有真登录，就没有"登出前有效"这个可对照的基线。
#      谁在消费它：白名单**全局共享**（所有带 token 的请求都查它）→ 只操作本脚本自建的那一个 jti，
#        绝不允许 FLUSHALL / 按前缀批量删。
#   ② 幂等清理 —— 第 ④ 步的登出**就是用例**；清理段（⑦）再用同一个 token 登一次作兜底
#      （TokenStore.remove 幂等：键已不在时后端回 401，且不写任何状态 —— 那一行 401 不是失败）。
#      异常退出（含 Ctrl-C / kill）由 tc_begin 装的 trap 兜底，见 qa/scripts/lib/tc-common.sh 文件头；
#      兜底时会真登出（键还在 ⇒ 回 200），两种结果都不写新状态。
#      残留的**最坏情况**：登录请求已发出、token 还没落到脚本手里就被中断 —— 那个 jti 登不掉，
#        只能等 TTL 自然过期（本脚本唯一无法自清的残留；它不阻断任何后续用例）。
#      **清理失败会怎样**：残留键最长存活 7200s（TTL 自然过期，无需人工修）；要立刻清：
#        重跑本脚本（幂等），或按指纹段打印的 jti 手工
#        `docker exec nexus-redis redis-cli DEL nexus:auth:token:<jti>`（该键是本用例自建的一次性对象）。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 白名单**键清单**指纹（不是只比个数），并打印
#      差异清单 + 自建 jti 的 EXISTS（跑后应为 0）。
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   2 = 用法错误（只支持 --self-test）
#   3 = 前提不成立（缺 python3 / curl / docker、Redis 取不到指纹、后端 /api/health 非 200）
#   4 = 某个 HTTP 动作没成功（登录没拿到 data.token）—— 原始响应已打印，照它判断
#
# 用法：
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc01-logout-then-me.sh              # 正常跑（需要环境就绪）
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc01-logout-then-me.sh --self-test  # 只跑工具自检（纯本地，不需要环境）
#
# 依赖：python3（JSON 取值；WSL 内实测 3.12.3）、curl、docker（取 Redis 指纹 **与后端日志**）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# 公共机械动作段在 qa/scripts/lib/tc-common.sh；本文件只留本用例的编排。
# =============================================================================

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

admin_token=''

# ── 清理 hook：只做清理动作（幂等），由库在正常收尾与异常兜底两条路径上调用 ──────────
tc_cleanup() {
  tc_post_logout 'admin（⑦ 幂等兜底）' "$admin_token"
}

# ── 工具自检（--self-test）：只跑本地计算，不碰 docker / 网络 / Redis / 后端 ──────────
# 除了本库取值器，这里还验一段**本用例独有的**筛选逻辑：日志分流。
# ⚠️ 它证明的是"给定下面这两种文案时，本脚本的筛选确实能把它们分开"；
#    **真实后端**的文案来自源码核对（JwtAuthenticationFilter:134/144/148/180），本自检不重验它 ——
#    若哪天过滤器改了日志文案，要同步复核这里的样例（与 TC-01.md 的备注行）。
run_self_test() {
  printf '===== 工具自检（--self-test）：只跑本地计算 =====\n'
  printf '不碰 docker / 网络 / Redis / 后端。下面这些「通过 / 不通过」说的是**本脚本依赖的取值器与筛选器**，与 TC 判据无关。\n'
  tc_selftest_observers

  printf '\n[本用例自检] 日志分流：把"第 ③ 步"的行与"第 ② 步"的行分开\n'
  local tmp_dir sample_log jti hits
  tmp_dir="$(mktemp -d)"
  jti='11111111-2222-3333-4444-555555555555'
  cat > "$tmp_dir/sample.log" <<LOG
2026-09-17T23:59:00.100  INFO 1 --- [nio-8089-exec-1] c.n.m.s.service.impl.AuthServiceImpl      : 登录成功：userId=11 username=admin tenantId=1 tenantCode=default jti=$jti expiresIn=7200s
2026-09-17T23:59:01.100  WARN 1 --- [nio-8089-exec-2] c.n.i.security.JwtAuthenticationFilter   : 鉴权失败：token 非法（type=SignatureException），path=/api/auth/me
2026-09-17T23:59:02.100  INFO 1 --- [nio-8089-exec-3] c.n.m.s.service.impl.AuthServiceImpl      : 登出成功：userId=11 jti=$jti
2026-09-17T23:59:02.110  INFO 1 --- [nio-8089-exec-3] c.n.i.security.TokenStore                 : 登出：删除登录态 jti=$jti 结果=true
2026-09-17T23:59:03.100  WARN 1 --- [nio-8089-exec-4] c.n.i.security.JwtAuthenticationFilter   : 鉴权失败：Redis 白名单无此登录态（已登出或被清除），path=/api/auth/me jti=$jti
LOG

  hits="$(tc_grep_lines "$jti" < "$tmp_dir/sample.log" | grep -c 'Redis 白名单无此登录态' || true)"
  printf '  (a) 含本次 jti 且文案是「Redis 白名单无此登录态」的行数 = %s（样例里应为 1）\n' "$hits"
  printf '  (b) 含本次 jti 的全部行（样例里应为 4：签发 / 登出成功 / 删除登录态 / 白名单无此登录态）：\n'
  tc_grep_lines "$jti" < "$tmp_dir/sample.log" | sed 's/^/      /'
  printf '  (c) 第 ② 步的两条文案（样例里应为 1 —— 它**不带 jti**，所以不会混进 (b)）：\n'
  tc_grep_lines_re '鉴权失败：token (非法|已过期)' < "$tmp_dir/sample.log" | sed 's/^/      /'

  printf '  (d) 空模式必须被拒绝（否则 `grep -F ""` 会匹配所有行、把整段日志当成"佐证"）：\n'
  tc_grep_lines '' < "$tmp_dir/sample.log" | sed 's/^/      /'

  rm -rf "$tmp_dir"
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
  '⑦ 清理：再登出一次（幂等兜底）—— 第 ④ 步已登出过，键若已不在，后端会回 401，且不写任何状态' \
  '⑧ 无害性自证：Redis 白名单指纹（跑后）'

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
printf '  该 jti 在白名单里的状态（EXISTS，1 = 在）：%s   ← 登出前它是有效的\n' \
  "$(tc_redis_exists "nexus:auth:token:${admin_jti}" || echo '?')"

# ── ③ 基线：登出前，用这个 token 调一次 /me ───────────────────────────────────────
tc_section '③ 基线（登出前）：用同一个 token 调 GET /api/auth/me'
tc_get_me 'admin 的 token（登出前）' "$admin_token" "$tc_work_dir/me-before.json"

# ── ④ 登出（用例本体）────────────────────────────────────────────────────────────
tc_section '④ 登出：POST /api/auth/logout（同一个 token）'
tc_post_logout 'admin（第 ④ 步：登出）' "$admin_token"
printf '  登出后该 jti 的白名单状态（EXISTS，0 = 已被删除）：%s   ← 与 ⑤ 并排看：变的只有"键在不在了"\n' \
  "$(tc_redis_exists "nexus:auth:token:${admin_jti}" || echo '?')"

# ── ⑤ 登出后，用**同一个** token 再调一次 /me ─────────────────────────────────────
tc_section '⑤ 登出后：用同一个 token 再调 GET /api/auth/me'
tc_get_me 'admin 的 token（登出后，与 ③ 是同一个 token）' "$admin_token" "$tc_work_dir/me-after.json"

# ── ⑥ 佐证：后端日志（只读；用来分清 40101 是第 ③ 步还是第 ② 步落闸）────────────────
tc_section '⑥ 佐证：后端日志（只读、原样打印 —— 分清 40101 落在第 ③ 步还是第 ② 步）'
log_file="$tc_work_dir/backend-log.txt"
log_rc=0
tc_backend_logs 200 > "$log_file" 2>&1 || log_rc=$?
printf '  抓取：docker logs --tail 200 %s → 退出码 %s；%s 行\n' \
  "$TC_BACKEND_CONTAINER" "$log_rc" "$(wc -l < "$log_file" | tr -d ' ')"
if [ "$log_rc" -ne 0 ]; then
  printf '  ⚠️ 取后端日志没成功（原文照录）：%s\n' "$(tr '\n' ' ' < "$log_file")"
fi
printf '\n  (a) 含本次 jti=%s 的行（这份 token 的一生：签发 → 登出/删除 → 之后的鉴权失败）：\n' "$admin_jti"
tc_grep_lines "$admin_jti" < "$log_file" | sed 's/^/      /'
printf '\n  (b) 窗口内第 ② 步的两条文案（「token 非法（type=…）」/「token 已过期」）——**不带 jti**，\n'
printf '      无法归因到本次请求，只作对照：\n'
tc_grep_lines_re '鉴权失败：token (非法|已过期)' < "$log_file" | sed 's/^/      /'
printf '  说明：第 ③ 步的 warn 带 jti、文案是「Redis 白名单无此登录态（已登出或被清除）」；\n'
printf '        第 ② 步的两条 warn 不带 jti。所以 (a) 里能与本次 jti 对上的那一行\n'
printf '        「…白名单无此登录态…path=/api/auth/me jti=%s」，就是落闸在第 ③ 步的直接证据。\n' "$admin_jti"
printf '        本段是**佐证**：取不到（日志被轮转 / 窗口太小 / 容器名不是 %s）不影响 ③④⑤ 的原始响应。\n' \
  "$TC_BACKEND_CONTAINER"

tc_finish 0

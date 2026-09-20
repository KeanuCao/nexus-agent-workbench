#!/usr/bin/env bash
# =============================================================================
# tc02-pool-full.sh —— TC-02 的「N 条流式对话同时打出去，逐条打印 HTTP 状态与响应体」
#
# 服务于：docs/test-cases/TC-02.md 的 **TC-02-2.3-5**（模型线程池满 → 503 + 20100）
# 契约依据：docs/api/README.md §6.3「池满：开流前 → Result → HTTP 503 + 20100」；
#           设计 docs/design/02-统一AI网关.md 决策 D8（AbortPolicy + 队列容量 0，满了不排队）
#
# 为什么有它：这一步要做的事是「**同时**发出 N 条请求，再把 N 条响应并排摆出来」——
#   手工做就要 N 条后台命令 + 收集各自的输出文件，属于"人最容易拼错、而拼错会被误读成
#   产品缺陷"的那一类。机械动作归脚本；**判据在 TC-02 文档**：本脚本不判"池满没满"、
#   不判哪一条该被拒，它只如实打印每条请求拿到的 HTTP 状态码、耗时与响应体（正文截断显示）。
#
# ⚠️ 这一条为什么"复现成本高"（TC-02 里也写了）：
#   默认 max-size=16，真要打过 16 条并发，就要同时跑 16 段 Ollama 生成（CPU 推理），代价不小。
#   更省的造法是**先把池改小**（把 max-size/core-size 都压到 1，见 TC-02-2.3-5 的步骤），那时
#   只要 2 条并发就够。改小要用本脚本把环境的两个变量摆出来（第 ② 段），别靠猜。
#
# 它做的事（每一步都只做动作、只打印观察）：
#   ① 前置（工具 / Redis / 后端 /api/health）—— 只读
#   ② 环境事实（只读）：后端容器里 NEXUS_AI_EXECUTOR_MAX_SIZE / CORE_SIZE 的字节数
#      （0 = 没设 → 用 application.yml 的默认值 core 4 / max 16）
#   ③ 跑前指纹：Redis 白名单键清单（只读）
#   ④ 真登录 admin（写 1 个白名单键；N 条请求共用这一个 token）
#   ⑤ 同时发出 N 条 POST /api/chat/stream，然后逐条打印：HTTP 状态码 / 耗时 / 响应体（截断）
#   ⑥ 后端日志里含 [chat] 的行与含「模型线程池已满」的行（只读，原样）
#   ⑦ 清理：登出本次 token（幂等）→ 跑后指纹 + 差异
#
# 环境变量（全部可选）：
#   TC02_CONCURRENCY   并发条数（默认 2 —— 配套"把池压到 1"的造法；要打默认池就设 20）
#   TC02_PROMPT        默认一句会引出长回答的中文提问（要"占住线程"，回答越长越稳）
#   TC02_TARGET        backend（默认）| frontend（走 nginx 8088）
#   TC02_MAX_SECONDS   每条请求的客户端上限（默认 120；被拒的那条是立刻返回的）
#
# 退出码（**不是判据**）：0 = 动作全部完成；2 = 用法错误；3 = 前提不成立；4 = 登录没拿到 token。
#
# 无害性（Test Harmlessness §2）：
#   ① 作用域声明 —— 会写 Redis 的**一个**键（nexus:auth:token:{jti}，TTL 7200s，与其它脚本同）；
#      另：Ollama 会被真实调用 N 次（读操作 + CPU 占用），不产生持久状态。
#   ② 幂等清理 —— 登出本次 token；异常退出（Ctrl-C / set -e）由 tc_begin 的 trap 兜底。
#      清理失败会怎样：最坏是该 jti 等 TTL（7200s）自然过期，无需人工修，不影响后续用例。
#   ③ 无害性自证 —— 跑前 / 跑后 Redis 白名单**键清单**指纹 + 本次 jti 的 EXISTS。
#
# 用法：
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc02-pool-full.sh
#   cd /mnt/c/wp/nexus-agent-workbench && TC02_CONCURRENCY=20 bash qa/scripts/tc02-pool-full.sh
# =============================================================================

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

readonly TC_DEV_ACCOUNT='{"username":"admin","password":"admin123"}'
readonly TC02_DEFAULT_PROMPT='请用大约 500 字、分四段介绍一下杭州的历史文化，每段之间换行。'

admin_token=''

tc_cleanup() {
  tc_post_logout 'tc02-pool' "$admin_token"
}

if [ "$#" -gt 0 ]; then
  printf '错误：未知参数 %s（本脚本只认环境变量）\n' "$1" >&2
  exit 2
fi

# ── 前端地址：与 tc02-chat-stream.sh 同一口径，只把"读哪个键"换成 FRONTEND_PORT ────────
# 仓库定位复用库解析出来的 TC_ENV_FILE（不重写路径推导）。
tc02_resolve_frontend_base_url() {
  local port='8088' from_env=''
  if [ -r "$TC_ENV_FILE" ]; then
    from_env="$(grep -E '^[[:space:]]*FRONTEND_PORT[[:space:]]*=' "$TC_ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d ' \r"' || true)"
    if [ -n "$from_env" ]; then
      port="$from_env"
    fi
  fi
  printf '%s' "${TC02_FRONTEND_BASE_URL:-http://localhost:${port}}"
}

TC02_TARGET="${TC02_TARGET:-backend}"
case "$TC02_TARGET" in
  backend)  TARGET_BASE_URL="$TC_BASE_URL" ;;
  frontend) TARGET_BASE_URL="$(tc02_resolve_frontend_base_url)" ;;
  *) printf '错误：TC02_TARGET 只支持 backend / frontend，收到 %s\n' "$TC02_TARGET" >&2; exit 2 ;;
esac
readonly TC02_TARGET TARGET_BASE_URL

readonly CONCURRENCY="${TC02_CONCURRENCY:-2}"
readonly MAX_SECONDS="${TC02_MAX_SECONDS:-120}"
readonly PROMPT="${TC02_PROMPT:-$TC02_DEFAULT_PROMPT}"
REQUEST_BODY="$(python3 -c 'import json,sys; print(json.dumps({"messages":[{"role":"user","content":sys.argv[1]}],"modelType":"OLLAMA"}, ensure_ascii=False))' "$PROMPT")"
readonly REQUEST_BODY

tc_begin tc_cleanup \
  '⑦ 清理：登出本次自建的 token（幂等，重复登出不报错）' \
  '⑧ 无害性自证：Redis 白名单指纹（跑后）'

tc_preflight_tools
tc_preflight_services

# ── ② 环境事实（只读）──────────────────────────────────────────────────────────
tc_section '② 环境事实（只读）：池的两个键在容器里的字节数'
printf '  （0 = 该环境变量没设 → 用 application.yml 的默认值 core-size=4 / max-size=16）\n'
for key in NEXUS_AI_EXECUTOR_CORE_SIZE NEXUS_AI_EXECUTOR_MAX_SIZE; do
  printf '  %s 的字节数 = ' "$key"
  docker exec nexus-backend printenv "$key" 2>/dev/null | wc -c | tr -d ' ' || printf '（取不到）'
  printf '\n'
done
printf '  本次并发条数 TC02_CONCURRENCY = %s，目标 = %s\n' "$CONCURRENCY" "$TARGET_BASE_URL"

# ── ③ 跑前指纹 ───────────────────────────────────────────────────────────────
tc_fingerprint_before '③ 指纹（跑前）：Redis 白名单 nexus:auth:token:* 键清单'

# ── ④ 真登录（写 1 个键；N 条请求共用它）─────────────────────────────────────────
tc_section '④ 真登录 admin（租户 default；写 1 个 Redis 白名单键）'
tc_post_login "$TC_DEV_ACCOUNT" "$tc_work_dir/login.json"
admin_token="$(tc_json_get "$tc_work_dir/login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '\n错误：登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去，先查登录失败原因。\n' >&2
  tc_finish 4
fi
admin_jti="$(tc_token_claim "$admin_token" jti || true)"
tc_note_jti 'tc02-pool' "$admin_jti"
printf '  token 长度 = %s，jti = %s\n' "${#admin_token}" "$admin_jti"

# ── ⑤ 同时打 N 条 ─────────────────────────────────────────────────────────────
tc_section "⑤ 同时发出 ${CONCURRENCY} 条 POST /api/chat/stream"
printf '  请求体（每条都一样）：%s\n' "$REQUEST_BODY"
i=1
while [ "$i" -le "$CONCURRENCY" ]; do
  (
    curl -s -m "$MAX_SECONDS" \
      -o "$tc_work_dir/r${i}.body" \
      -w '%{http_code} %{time_total}' \
      -X POST "${TARGET_BASE_URL}/api/chat/stream" \
      -H "Authorization: Bearer ${admin_token}" \
      -H 'Content-Type: application/json' \
      --data-binary "$REQUEST_BODY" > "$tc_work_dir/r${i}.code" 2> "$tc_work_dir/r${i}.err"
  ) &
  i=$((i + 1))
done
wait || true

tc_section '⑤ 逐条结果（HTTP 状态 / 耗时 / 响应体，正文截断显示）'
i=1
while [ "$i" -le "$CONCURRENCY" ]; do
  code_line="$(cat "$tc_work_dir/r${i}.code" 2>/dev/null || printf '（没拿到）')"
  size="$(wc -c < "$tc_work_dir/r${i}.body" 2>/dev/null | tr -d ' ' || printf '?')"
  printf '\n  #%s → HTTP/耗时 = %s；响应体共 %s 字节\n' "$i" "$code_line" "$size"
  printf '    正文前 300 字节（原样）：\n'
  head -c 300 "$tc_work_dir/r${i}.body" 2>/dev/null | sed 's/^/      /' || printf '      （没有响应体）\n'
  printf '\n'
  if [ -s "$tc_work_dir/r${i}.err" ]; then
    printf '    curl stderr（原样）：\n'
    sed 's/^/      /' "$tc_work_dir/r${i}.err"
  fi
  i=$((i + 1))
done

# ── ⑥ 后端日志（只读）────────────────────────────────────────────────────────
tc_section '⑥ 后端日志（只读）：[chat] 行 + 线程池拒绝行'
tc_backend_logs "${TC02_LOG_TAIL:-300}" | tc_grep_lines '[chat]' | sed 's/^/  /'
printf '  --- 含「模型线程池已满」的行 ---\n'
tc_backend_logs "${TC02_LOG_TAIL:-300}" | tc_grep_lines '模型线程池已满' | sed 's/^/  /'

tc_finish 0

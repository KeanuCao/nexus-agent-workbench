#!/usr/bin/env bash
# =============================================================================
# tc02-chat-stream.sh —— TC-02 的「真登录 → 发一次流式对话 → 原样打印每一帧」
#
# 服务于：docs/test-cases/TC-02.md 的多数用例（2.1-1 / 2.1-2 / 2.2-1 / 2.2-2 / 2.2-3 /
#         2.3-1 / 2.3-2 / 2.3-4 / 2.4-3 的 curl 对照）
# 契约依据：docs/api/README.md §6（帧格式 §6.2、失败形态 §6.3、curl 验证 §6.5）
#
# 为什么有它：这些用例的步骤都要「先登录拿 token，再把 token 喂给下一条命令」，而 WSL 里
#   没有 jq、取 JSON 字段只能靠拼引号 —— 手工拼装正是「人最不擅长、也最容易出错」的一环，
#   而拼接错误会被误读成产品缺陷。所以机械动作归脚本，**判据留在 TC 文档**：
#   本脚本只发请求、只打印原始观察（含每一行的到达时刻），不打 PASS/FAIL。
#
# 它做的事（每一步都只做动作、只打印观察）：
#   ① 前置（工具 / Redis / 后端 /api/health / 目标端口可达）—— 只读
#   ② 环境事实（只读）：目标容器的 nginx 缓冲配置行、后端容器里 DEEPSEEK_API_KEY 的字节数
#   ③ 跑前指纹：Redis 白名单键清单（只读）
#   ④ 真登录 admin（写 1 个白名单键），登出用同一个 token；打印登录响应原文
#   ⑤ 打印本次请求（地址 / 两个请求头带没带 / 请求体原文）
#   ⑥ 发 POST /api/chat/stream：响应头与事件流**逐行带到达时刻**打印，跑完给一段机械统计
#      （事件计数 / data 行首末时刻 / 严格 UTF-8 解码结果 / U+FFFD 次数 / delta 正文拼接）
#   ⑦ 后端日志里含 [chat] 的行（只读，原样）
#   ⑧ 清理：登出本次 token（幂等）→ 跑后指纹 + 差异
#
# 环境变量（全部可选；不设就用括号里的默认值）：
#   TC02_TARGET=backend|frontend  打哪个端口。backend = 直连后端（默认，端口取 .env 的 BACKEND_PORT）；
#                                 frontend = 走 nginx（取 .env 的 FRONTEND_PORT，即 8088）
#   TC02_MODEL_TYPE               默认 OLLAMA（另一取值 DEEPSEEK）
#   TC02_PROMPT                   默认一句会引出**长**中文回答的提问
#   TC02_BODY                     直接给原始请求体（给了它就忽略 MODEL_TYPE / PROMPT）——
#                                 用于造「未知 modelType」「空 content」「畸形 JSON」「连续两条 user」等形状
#   TC02_OMIT_CONTENT_TYPE=1      不发 Content-Type 头（观察缺这个头时的失败形态）
#   TC02_MAX_SECONDS              客户端主动放弃等待的上限（默认 300，与 emitter 硬超时同量级）
#   TC02_LOG_TAIL                 后端日志抓取行数（默认 300）
#   TC02_BASE_URL / TC02_FRONTEND_BASE_URL  直接覆盖地址（默认都从 docker-compose/.env 的端口推出来）
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   2 = 用法错误（未知的 TC02_TARGET）
#   3 = 前提不成立（工具缺失 / Redis 取不到指纹 / 后端 /api/health 非 200 / 前端不可达 / 过滤器自检不过）
#   4 = 登录没拿到 data.token（原始响应已打印，照它判断）
#   28 = curl 主动放弃（到达 TC02_MAX_SECONDS）—— 原始输出已打印
#   130 = 读取中被 Ctrl-C 打断（TC-02 的 2.3-3 就是靠它；清理与指纹仍会执行）
#   注：curl / 过滤器自身的非零退出码会被原样打印出来，脚本不会因此中断。
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 会写 Redis 的**一个**键：nexus:auth:token:{jti}（TTL = 7200s）。
#      为什么非写不可：/api/chat/stream 不在白名单（契约 §6.1），必须带一个**真登录**产生的
#        token 才可能开流 —— 流式对话本身不落库、不写任何业务数据。
#      谁在消费它：白名单是全局共享的（所有带 token 的请求都查它）→ 清理精确到本次 jti，
#        绝不做 FLUSHALL / 按前缀批量删。另：每次对话都会真实调用 Ollama（读操作、CPU 占用），
#        这是本项目演示链路的固有成本，不产生持久状态。
#   ② 幂等清理 —— 登出本次 token（后端 TokenStore.remove 幂等，重复删不报错）；异常退出
#      （set -e 触发 / Ctrl-C / kill）由 tc_begin 装的 trap 兜底，见 qa/scripts/lib/tc-common.sh 文件头。
#      残留的**最坏情况**：登录请求已发出、token 还没落到脚本手里就被中断 —— 那个 jti 登不掉，
#        只能等 TTL（7200s）自然过期；它不阻断任何后续用例，也不影响别人。
#      清理失败会怎样：最坏就是上面那条"等 TTL 自净"，无需人工修。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 白名单**键清单**指纹（不是只比个数），
#      打印差异清单 + 本次自建 jti 的 EXISTS（登出后应为 0）。
#
# 用法：
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc02-chat-stream.sh                 # 本地 Qwen，直连后端
#   cd /mnt/c/wp/nexus-agent-workbench && TC02_TARGET=frontend bash qa/scripts/tc02-chat-stream.sh
#   cd /mnt/c/wp/nexus-agent-workbench && TC02_MODEL_TYPE=DEEPSEEK bash qa/scripts/tc02-chat-stream.sh
#   cd /mnt/c/wp/nexus-agent-workbench && TC02_BODY='{"messages":[{"role":"user","content":"hi"}],"modelType":"XXX"}' \
#       bash qa/scripts/tc02-chat-stream.sh
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc02-chat-stream.sh --self-test          # 只跑工具自检
#
# 依赖：python3、curl、docker（取 Redis 指纹 / 打印后端日志）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# 公共机械动作段在 qa/scripts/lib/tc-common.sh；逐行打时刻的过滤器在
#   qa/scripts/lib/tc02-stamp-lines.py（它自己的 --self-test 见脚本头）。
# =============================================================================

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

readonly TC02_STAMP_FILTER="${TC_LIB_DIR}/tc02-stamp-lines.py"

readonly TC_DEV_ACCOUNT='{"username":"admin","password":"admin123"}'

# 默认提问：刻意要**长回答** —— 2.3-1/2.3-2 要看"分片是不是随打随出"，短回答看不出；
# 2.4-3 要看中文有没有被 UTF-8 边界劈坏，短回答碰巧不出问题。
readonly TC02_DEFAULT_PROMPT='请用大约 500 字、分四段介绍一下杭州的历史文化，每段之间换行。'

admin_token=''

# ── 清理 hook：只做清理动作（幂等），由库在正常收尾与异常兜底两条路径上调用 ──────────
tc_cleanup() {
  tc_post_logout 'tc02' "$admin_token"
}

# ── 前端地址：与后端同一套口径，只是换一个键（FRONTEND_PORT）────────────────────────
# 复用库解析出来的 TC_ENV_FILE（仓库定位不重写）；只有"读哪个键"这一步是本脚本自己的。
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

# ── 工具自检（--self-test）：只跑本地计算，不碰 docker / 网络 / Redis / 后端 ──────────
run_self_test() {
  printf '===== 工具自检（--self-test）：只跑本地计算 =====\n'
  printf '不碰 docker / 网络 / Redis / 后端。下面这些「通过 / 不通过」说的是**本脚本依赖的取值器与过滤器**，与 TC 判据无关。\n'
  tc_selftest_observers
  printf '\n[过滤器自检]\n'
  python3 "$TC02_STAMP_FILTER" --self-test
  printf '\n===== 工具自检结束 =====\n'
}

if [ "${1:-}" = '--self-test' ]; then
  run_self_test
  exit 0
fi
if [ "$#" -gt 0 ]; then
  printf '错误：未知参数 %s（只支持 --self-test）\n' "$1" >&2
  exit 2
fi

# ── 参数解析（只认两档 TARGET；其余靠环境变量）────────────────────────────────────
TC02_TARGET="${TC02_TARGET:-backend}"
case "$TC02_TARGET" in
  backend)  TARGET_BASE_URL="$TC_BASE_URL" ;;
  frontend) TARGET_BASE_URL="$(tc02_resolve_frontend_base_url)" ;;
  *) printf '错误：TC02_TARGET 只支持 backend / frontend，收到 %s\n' "$TC02_TARGET" >&2; exit 2 ;;
esac
readonly TC02_TARGET TARGET_BASE_URL

MODEL_TYPE="${TC02_MODEL_TYPE:-OLLAMA}"
if [ -n "${TC02_BODY:-}" ]; then
  REQUEST_BODY="$TC02_BODY"
  BODY_SOURCE='来自 TC02_BODY（原始请求体由调用方给定）'
else
  REQUEST_BODY="$(python3 -c 'import json,sys; print(json.dumps({"messages":[{"role":"user","content":sys.argv[1]}],"modelType":sys.argv[2]}, ensure_ascii=False))' "${TC02_PROMPT:-$TC02_DEFAULT_PROMPT}" "$MODEL_TYPE")"
  BODY_SOURCE="由 TC02_MODEL_TYPE=${MODEL_TYPE} + 提问拼出"
fi
readonly MODEL_TYPE REQUEST_BODY BODY_SOURCE
readonly OMIT_CONTENT_TYPE="${TC02_OMIT_CONTENT_TYPE:-0}"
readonly MAX_SECONDS="${TC02_MAX_SECONDS:-300}"
readonly LOG_TAIL="${TC02_LOG_TAIL:-300}"

# ── 主流程 ────────────────────────────────────────────────────────────────────
tc_begin tc_cleanup \
  '⑧ 清理：登出本次自建的 token（幂等，重复登出不报错）' \
  '⑨ 无害性自证：Redis 白名单指纹（跑后）'

tc_preflight_tools
tc_preflight_services

# ── ① 目标可达性（只读）──────────────────────────────────────────────────────────
tc_section '① 前置（只读）：目标可达性 + 过滤器自检'
printf '  TC02_TARGET = %s → 本次打 %s\n' "$TC02_TARGET" "$TARGET_BASE_URL"
if [ "$TC02_TARGET" = 'frontend' ]; then
  fe_code="$(curl -s -o /dev/null -w '%{http_code}' "${TARGET_BASE_URL}/" || true)"
  printf '  GET %s/ → HTTP %s（前端静态页；非 200 说明 nginx 没起来或产物缺失）\n' "$TARGET_BASE_URL" "$fe_code"
  if [ "$fe_code" != '200' ]; then
    printf '错误：前端不可达。先跑 ./scripts/sh/check-health.sh 看哪一项 DOWN，再回来。\n' >&2
    exit 3
  fi
fi
if ! python3 "$TC02_STAMP_FILTER" --self-test >/dev/null; then
  printf '错误：逐行打时刻的过滤器自检不通过（python3 %s --self-test）—— 先修它，别带着坏统计往下跑。\n' "$TC02_STAMP_FILTER" >&2
  exit 3
fi
printf '  过滤器自检：通过（python3 %s --self-test）\n' "$TC02_STAMP_FILTER"

# ── ② 环境事实（只读，不是判据；但排查时要看它们）────────────────────────────────
tc_section '② 环境事实（只读）'
if [ "$TC02_TARGET" = 'frontend' ]; then
  printf '  [nginx] 容器里 /api/ 段的缓冲配置行（应含 proxy_buffering off；取不到就原样看下面的输出）：\n'
  docker exec nexus-frontend grep -n 'proxy_buffering' /etc/nginx/conf.d/default.conf 2>&1 | sed 's/^/    /' || true
fi
printf '  [后端] 容器内 DEEPSEEK_API_KEY 的字节数（0 = 未配置 → 云端那半会直接走 20100）：'
docker exec nexus-backend printenv DEEPSEEK_API_KEY 2>/dev/null | wc -c | tr -d ' ' || printf '（取不到）'
printf '\n'

# ── ③ 跑前指纹 ───────────────────────────────────────────────────────────────
tc_fingerprint_before '③ 指纹（跑前）：Redis 白名单 nexus:auth:token:* 键清单'

# ── ④ 真登录（写 1 个键）──────────────────────────────────────────────────────
tc_section '④ 真登录 admin（租户 default；会写 1 个 Redis 白名单键）'
tc_post_login "$TC_DEV_ACCOUNT" "$tc_work_dir/login.json"
admin_token="$(tc_json_get "$tc_work_dir/login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '\n错误：登录响应里没有 data.token（原始响应见上）—— 机械动作做不下去，先查登录失败原因。\n' >&2
  tc_finish 4
fi
admin_jti="$(tc_token_claim "$admin_token" jti || true)"
tc_note_jti 'tc02' "$admin_jti"
printf '  token 长度 = %s，jti = %s（登出时按这个 jti 精确删）\n' "${#admin_token}" "$admin_jti"

# ── ⑤ 请求回显（把"到底发了什么"摆出来）──────────────────────────────────────────
tc_section '⑤ 本次请求（原样回显）'
printf '  POST %s/api/chat/stream\n' "$TARGET_BASE_URL"
printf '  Header Authorization: Bearer <上面那个 token，长度 %s>\n' "${#admin_token}"
if [ "$OMIT_CONTENT_TYPE" = '1' ]; then
  printf '  Header Content-Type: **刻意不带**（TC02_OMIT_CONTENT_TYPE=1）\n'
else
  printf '  Header Content-Type: application/json\n'
fi
printf '  请求体（%s）：\n%s\n' "$BODY_SOURCE" "$REQUEST_BODY"

# ── ⑥ 发流式请求：逐行带到达时刻 ─────────────────────────────────────────────────
tc_section '⑥ 响应（逐行，带到达时刻）'
curl_args=(-sN -i -m "$MAX_SECONDS" -X POST "${TARGET_BASE_URL}/api/chat/stream"
           -H "Authorization: Bearer ${admin_token}" --data-binary "$REQUEST_BODY")
if [ "$OMIT_CONTENT_TYPE" != '1' ]; then
  curl_args+=(-H 'Content-Type: application/json')
fi

stream_rc=0
curl "${curl_args[@]}" | python3 "$TC02_STAMP_FILTER" || stream_rc=$?
printf '\n  （curl/过滤器管道退出码 = %s；含义见本脚本头：0 正常、28 客户端放弃、130 Ctrl-C 中断）\n' "$stream_rc"

# ── ⑦ 后端日志（只读，原样；取不到不影响上面的观察）────────────────────────────────
tc_section "⑦ 后端日志里含 [chat] 的行（只取最后 ${LOG_TAIL} 行里的）"
tc_backend_logs "$LOG_TAIL" | tc_grep_lines '[chat]' | sed 's/^/  /'

tc_finish 0

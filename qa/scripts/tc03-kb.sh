#!/usr/bin/env bash
# =============================================================================
# tc03-kb.sh —— TC-03 的「真登录 → 知识库接口的一次机械动作 → 原样打印观察」
#
# 服务于：docs/test-cases/TC-03.md 的协议侧用例（3.1-③ / 3.2-①~⑦ / 3.3-②③ /
#         3.4-①②③⑤ / 3.5-①）
# 契约依据：docs/api/README.md §7（四个接口 §7.1~§7.4、两个响应结构 §7.5/§7.6、失败形态 §7.7）
#
# 为什么有它：这些用例的步骤都要「先登录拿 token，再把 token 喂给下一条命令」，而上传一份 1.6MB
#   的 PDF 要 **2 分钟出头**（2026-09-22 换 bge-m3 后端到端经 8088 实测 **131.9 秒**）、提问要 3~10 秒 ——
#   手工拼装正是「人最不擅长、也最容易出错」的一环，而拼接错误会被误读成产品缺陷。
#   ⚠️ **耗时是记录值、不是判据**：它随机器负载与 CPU 漂移（同一台机器上 2.1 s/批 → 3.6 s/批，见 TC-03 3.2-2 ④）。
#   本脚本把总耗时**打印出来供记录**；判据只有「必须 < 300 秒」那一条上界（与前端逐请求上传超时同源）。
#   所以机械动作归脚本，**判据留在 TC 文档**：
#   本脚本只发请求、只打印原始观察（响应原文 + 机械字段表 + [kb] 日志），不打 PASS/FAIL。
#
# 它做的事（每一步都只做动作、只打印观察）：
#   ① 前置（只读）：工具 / Redis / 后端 /api/health / **目标入口**可达 / upload 的夹具存在
#   ② 环境事实（只读）：目标入口、账户、夹具绝对路径与字节数
#   ③ 跑前指纹（只读）：Redis 白名单键清单
#   ④ 真登录（写 1 个白名单键）
#   ⑤ 再取一次跑前指纹：**当前账户的文档列表**（id 与文件名逐行）
#      （列表接口不在白名单里，所以这一步必须在登录之后 —— 先取只会拿到 401，指纹毫无意义）
#   ⑥ 按 TC03_ACTION 做动作，原样打印响应体 + 机械字段表（含 HTTP 码与总耗时）：
#        list   → GET  /api/kb/documents
#        upload → POST /api/kb/documents（multipart）
#        ask    → POST /api/kb/ask
#        delete → DELETE /api/kb/documents/{id}
#   ⑦ 后端日志里含 [kb] 的行（只读，原样；判据「六条链路日志」看它）
#   ⑧ 清理：upload 且未指定 TC03_KEEP_DOC 时**删掉本次上传的文档**（幂等）→ **趁 token 还在**取
#      跑后文档列表 + 差异 → 登出本次 token（幂等）
#   ⑨ 跑后指纹 + 差异（Redis 键清单；文档列表的差异在 ⑧ 段）
#
# 环境变量（全部可选；不设就用括号里的默认值）：
#   TC03_ACTION=list|upload|ask|delete   （默认 list）
#   TC03_ACCOUNT=admin|demo              （默认 admin）—— 跨租户用例靠它切账户
#   TC03_TARGET=backend|frontend         （默认 backend；frontend = 走 nginx，取 FRONTEND_PORT=8088）
#   TC03_FILE=<路径>                     upload 用，默认 qa/fixtures/rag/知识库说明.txt
#   TC03_FIELD=<part 名>                 upload 用，改 multipart 的字段名（默认 file；改成别的观察 40001）
#   TC03_FILENAME=<文件名>               upload 用，覆盖 multipart 里的 filename（不必真造文件：
#                                        改后缀造 10201、造超长名造 40001 都靠它）
#   TC03_CONTENT_TYPE=<值>               upload 用，**手工指定** Content-Type（默认不带：让 curl 自己带 boundary）
#   TC03_QUESTION=<文本>                 ask 用，默认「去年利润是多少」
#   TC03_TOPK=<整数>                     ask 用，不设则请求体里不带 topK（用配置值）
#   TC03_BODY=<原始 JSON>                ask 用，直接给请求体（给了就忽略 QUESTION / TOPK；用来造畸形 JSON 等）
#   TC03_DOC_ID=<documentId>             delete 用，**必须是本次测试自己上传的那份**
#   TC03_KEEP_DOC=1                      upload 后**不**自动删除（3.1-③ / 3.4-① / 3.5 的链路要留着它提问）
#   TC03_LOG_TAIL=<行数>                 后端日志抓取行数（默认 300）
#   TC03_MAX_SECONDS=<秒>                上传 / 问答的**客户端等待上限**（默认 600）。它只兜住"无限等"，
#                                        **不是判据**：判据的上界是 **300 s**（前端逐请求上传超时的同源值），
#                                        故意取 2 倍余量 —— 若脚本自己的上限更小，它会先于前端放弃，把一次
#                                        「用户其实能成功」的上传记成客户端放弃（退出码 28）而误读成失败。
#   TC03_BASE_URL / TC03_FRONTEND_BASE_URL  直接覆盖入口地址（默认从 docker-compose/.env 的端口推出）
#
# 退出码（**不是判据**，只表示"动作做没做成"；结论一律看它打印的原始响应）：
#   0 = 动作全部完成
#   2 = 用法错误（未知的 TC03_ACTION / 缺 TC03_DOC_ID / 未知的 TC03_TARGET）
#   3 = 前提不成立（工具缺失 / Redis 取不到指纹 / 目标入口 /api/health 非 200 / upload 的夹具不存在）
#   4 = 登录没拿到 data.token（原始响应已打印，照它判断）
#   28 = curl 主动放弃（到达 TC03_MAX_SECONDS，默认 600 s；服务端可能仍入库成功 —— 上传/问答的固有形态，
#        见契约 §7.1 第 3 条。**它不是判据**：判据的上界是 300 s，600 s 只防无限等）
#   130 = 被 Ctrl-C 打断（清理与指纹仍会执行）
#   注：动作自身的 HTTP 状态码（200 / 503 / …）只被**打印**，脚本不因它中断。
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 会写两类东西，都属「本次自建的一次性对象」：
#        · Redis **一个**键 nexus:auth:token:{jti}（TTL 7200s）—— 知识库四个接口都不在白名单
#          （契约 §1.4），必须带**真登录**产生的 token；
#        · 业务表 t_kb_document / t_kb_chunk —— **仅 upload 动作**会新建一行文档 + 它自己的分块，
#          且默认由本脚本在退出前删掉（§7.3：删文档即级联删分块）。
#          谁在消费它：列表接口与检索（同一租户）→ 故 upload 之后若不删，检索结果会被它污染，
#          而**它多出来的那份文档就是 TC-03 要观察的东西**（3.1-③ / 3.4-①）；用完必须删。
#      为什么非写不可：验收对象就是"上传 → 入库 → 检索"这条链路，只读观察拿不到等价证据。
#      delete 动作只允许删 TC03_DOC_ID 指定的那一份 —— **由人显式给出，脚本绝不自己挑 id**。
#   ② 幂等清理 —— upload 的文档删除（不存在时后端返 10202，动作仍完成）+ 本次 token 登出
#      （TokenStore.remove 幂等）；异常退出（set -e 触发 / Ctrl-C / kill）由 tc_begin 装的 trap 兜底。
#      清理失败会怎样：最坏是一份测试文档留在库里（列表里看得见、带 file_name，可手工删）+
#      一个白名单键等 TTL（7200s）自净；两者都不阻断任何后续用例，也不需要人工修。
#      ⚠️ **Ctrl-C 打断上传时**：后端可能仍在处理并在稍后入库成功 —— 那一次不会有 documentId 交给清理，
#      故会留下一份文档；脚本在 ⑦ 段会提示"若列表里出现本次文件名，请手工删掉它"。
#   ③ 无害性自证 —— 跑前 / 跑后各取一次 Redis 键清单指纹 + **文档列表指纹**（id + 文件名逐行），
#      打印差异清单；upload 明确保留（TC03_KEEP_DOC=1）时，差异里多出的就是那一份。
#
# 用法：
#   cd /mnt/c/wp/nexus-agent-workbench && bash qa/scripts/tc03-kb.sh                     # 列表（admin，直连后端）
#   cd /mnt/c/wp/nexus-agent-workbench && TC03_ACCOUNT=demo bash qa/scripts/tc03-kb.sh   # 列表（demo）
#   cd /mnt/c/wp/nexus-agent-workbench && TC03_ACTION=upload TC03_KEEP_DOC=1 bash qa/scripts/tc03-kb.sh
#   cd /mnt/c/wp/nexus-agent-workbench && TC03_ACTION=ask TC03_QUESTION='去年利润是多少' bash qa/scripts/tc03-kb.sh
#   cd /mnt/c/wp/nexus-agent-workbench && TC03_ACTION=delete TC03_DOC_ID=7 bash qa/scripts/tc03-kb.sh
#   cd /mnt/c/wp/nexus-agent-workbench && TC03_TARGET=frontend TC03_ACTION=ask bash qa/scripts/tc03-kb.sh
#
# 依赖：python3、curl、docker（取 Redis 指纹 / 打印后端日志）。
# 运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# 公共机械动作段在 qa/scripts/lib/tc-common.sh（路径/端口解析、JSON 取值、登出、指纹脚手架）。
# =============================================================================

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

readonly TC03_ACTION="${TC03_ACTION:-list}"
readonly TC03_ACCOUNT="${TC03_ACCOUNT:-admin}"
readonly TC03_TARGET="${TC03_TARGET:-backend}"
readonly TC03_FIELD="${TC03_FIELD:-file}"
readonly TC03_LOG_TAIL="${TC03_LOG_TAIL:-300}"
# 客户端等待上限（秒）：**不是判据**，只防"无限等"。判据的上界是 300 s（前端逐请求上传超时同源），
# 这里取 2 倍余量 —— 脚本的上限必须**明显大于**它，否则会把「用户其实能成功」的上传误报成客户端放弃（退出码 28）。
readonly TC03_MAX_SECONDS="${TC03_MAX_SECONDS:-600}"
readonly TC03_DEFAULT_FILE='qa/fixtures/rag/知识库说明.txt'

case "$TC03_ACCOUNT" in
  admin) TC03_LOGIN_BODY='{"username":"admin","password":"admin123"}' ;;
  demo)  TC03_LOGIN_BODY='{"username":"demo","password":"demo123"}' ;;
  *)     printf '错误：TC03_ACCOUNT 只支持 admin / demo，收到 %s\n' "$TC03_ACCOUNT" >&2; exit 2 ;;
esac

# ── 入口地址：backend = 直连后端（BACKEND_PORT）、frontend = 走 nginx（FRONTEND_PORT）──
# 复用库解析出来的 TC_ENV_FILE（仓库定位不重写）；只有"读哪个键"这一步是本脚本自己的。
tc03_resolve_base_url() {
  local key port
  case "$TC03_TARGET" in
    backend) key='BACKEND_PORT'; port='8089';;
    frontend) key='FRONTEND_PORT'; port='8088';;
    *) printf '错误：TC03_TARGET 只支持 backend / frontend，收到 %s\n' "$TC03_TARGET" >&2; exit 2 ;;
  esac
  if [ -r "$TC_ENV_FILE" ]; then
    local from_env
    from_env="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$TC_ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d ' \r"' || true)"
    if [ -n "$from_env" ]; then
      port="$from_env"
    fi
  fi
  printf '%s' "http://localhost:${port}"
}
TC03_BASE_URL="${TC03_BASE_URL:-$(tc03_resolve_base_url)}"
readonly TC03_BASE_URL

admin_token=''
tc03_uploaded_doc_id=''
tc03_file_resolved=''

# ── 清理 hook：只做清理动作（幂等），由库在正常收尾与异常兜底两条路径上调用 ──────────
# 顺序不能换：删文档 → **趁 token 还在**取一次跑后文档列表（这就是本条的无害性自证）→ 登出。
tc_cleanup() {
  if [ -n "$tc03_uploaded_doc_id" ] && [ "${TC03_KEEP_DOC:-0}" != '1' ]; then
    tc03_action_delete "$tc03_uploaded_doc_id" '⑧ 清理：删掉本次上传的文档'
  fi
  tc_section '⑧ 跑后（清理动作已完成、token 还在）：当前账户可见的文档列表'
  tc03_get_documents "跑后（${TC03_ACCOUNT}）" "$tc_work_dir/docs-after.json"
  tc03_documents_fingerprint "$tc_work_dir/docs-after.json" > "$tc_work_dir/docs-after.txt"
  cat "$tc_work_dir/docs-after.txt"
  printf '  文档列表差异（跑后 vs 跑前）：\n'
  printf '    跑后有、跑前没有的：\n'
  comm -13 <(sort "$tc_work_dir/docs-before.txt") <(sort "$tc_work_dir/docs-after.txt") | sed 's/^/      /'
  printf '    跑前有、跑后没有的：\n'
  comm -23 <(sort "$tc_work_dir/docs-before.txt") <(sort "$tc_work_dir/docs-after.txt") | sed 's/^/      /'
  tc_post_logout "tc03 ($TC03_ACCOUNT)" "$admin_token" "$TC03_BASE_URL"
}

# ── 机械动作 ──────────────────────────────────────────────────────────────────────
tc03_post_json() {  # <标签> <URL> <请求体> <响应文件>
  local label="$1" url="$2" body="$3" outfile="$4" http
  # --max-time 与上传同源（TC03_MAX_SECONDS，默认 600 s）：只防无限等，**不是判据** ——
  # 判据的上界是 300 s，脚本自己绝不先于前端放弃
  http="$(curl -s -o "$outfile" -w '%{http_code}' -X POST --max-time "$TC03_MAX_SECONDS" \
        -H "Authorization: Bearer ${admin_token}" \
        -H 'Content-Type: application/json' -d "$body" "$url" || true)"
  printf '\nPOST %s   [%s]\n  请求体(原样): %s\n  → HTTP %s\n  响应体(原样):\n' "$url" "$label" "$body" "$http"
  tc03_pretty "$outfile"
}

tc03_get_documents() {  # <标签> <响应文件> → 打印原始响应
  local label="$1" outfile="$2" http
  http="$(curl -s -o "$outfile" -w '%{http_code}' -H "Authorization: Bearer ${admin_token}" \
        "${TC03_BASE_URL}/api/kb/documents" || true)"
  printf '\nGET %s/api/kb/documents   [%s]\n  → HTTP %s\n  响应体(原样):\n' "$TC03_BASE_URL" "$label" "$http"
  tc03_pretty "$outfile"
}

tc03_action_upload() {  # 上传 TC03_FILE（multipart）
  local part="${TC03_FIELD}=@${tc03_file_resolved}"
  if [ -n "${TC03_FILENAME:-}" ]; then
    # 覆盖 multipart 里的 filename：改后缀（造 10201）与超长名（造 40001）都靠它，不必真造文件
    part="${part};filename=${TC03_FILENAME}"
  fi
  local field_args=(-F "$part")
  local header_args=()
  if [ -n "${TC03_CONTENT_TYPE:-}" ]; then
    # 手工指定 Content-Type：契约 §7.1 第 2 条说这会丢掉 boundary ⇒ 正是"非 multipart 请求"的形状
    header_args=(-H "Content-Type: ${TC03_CONTENT_TYPE}")
  fi
  local outfile="$tc_work_dir/upload.json" meta http
  printf '\nPOST %s/api/kb/documents   [上传：%s]\n' "$TC03_BASE_URL" "$tc03_file_resolved"
  printf '  multipart 字段名=%s  filename=%s  手工 Content-Type=%s\n' "$TC03_FIELD" \
    "${TC03_FILENAME:-（未设，取文件本身的路径名）}" "${TC03_CONTENT_TYPE:-（未设，由 curl 带 boundary）}"
  printf '  客户端等待上限=%s 秒（只防无限等；**判据**是那条「总耗时 < 300 秒」的上界，见 TC-03 3.2-2 ④）\n' \
    "$TC03_MAX_SECONDS"
  # -w 里的 %{time_total}：这个总耗时是**要记录的值**（判据只有「< 300 秒」那一条上界，不与任何窗口比对）——
  # 取值理由与"为什么不能写死耗时"见 TC-03 3.2-2 ④ / 契约 §7.1 第 3 条 / 设计 §5.1-4。
  meta="$(curl -s -o "$outfile" -w '%{http_code} %{time_total}' -X POST --max-time "$TC03_MAX_SECONDS" \
        -H "Authorization: Bearer ${admin_token}" "${header_args[@]+"${header_args[@]}"}" \
        "${field_args[@]}" "${TC03_BASE_URL}/api/kb/documents" || true)"
  http="$(printf '%s' "$meta" | cut -d' ' -f1)"
  printf '  → HTTP %s   总耗时 %s 秒\n  响应体(原样):\n' "$http" "$(printf '%s' "$meta" | cut -d' ' -f2)"
  tc03_pretty "$outfile"

  tc03_uploaded_doc_id="$(tc_json_get "$outfile" data.documentId || true)"
  if [ -n "$tc03_uploaded_doc_id" ]; then
    if [ "${TC03_KEEP_DOC:-0}" = '1' ]; then
      printf '\n  ⚠️ 本次上传的文档**被保留**（TC03_KEEP_DOC=1）：documentId=%s\n' "$tc03_uploaded_doc_id"
      printf '     用完必须删掉它（否则它会影响后续列表与检索）：\n'
      printf '     cd /mnt/c/wp/nexus-agent-workbench && TC03_ACTION=delete TC03_DOC_ID=%s bash qa/scripts/tc03-kb.sh\n' "$tc03_uploaded_doc_id"
    else
      printf '\n  本次上传的文档将被 ⑦ 段自动删除（想留着它提问，请加 TC03_KEEP_DOC=1）\n'
    fi
  fi
}

tc03_action_delete() {  # <documentId> <标签>
  local document_id="$1" label="$2" outfile="$tc_work_dir/delete-${1}.json" meta http
  meta="$(curl -s -o "$outfile" -w '%{http_code} %{time_total}' -X DELETE \
        -H "Authorization: Bearer ${admin_token}" \
        "${TC03_BASE_URL}/api/kb/documents/${document_id}" || true)"
  http="$(printf '%s' "$meta" | cut -d' ' -f1)"
  printf '\nDELETE %s/api/kb/documents/%s   [%s]\n  → HTTP %s   总耗时 %s 秒\n  响应体(原样):\n' \
    "$TC03_BASE_URL" "$document_id" "$label" "$http" "$(printf '%s' "$meta" | cut -d' ' -f2)"
  tc03_pretty "$outfile"
}

tc03_action_ask() {
  local outfile="$tc_work_dir/ask.json"
  if [ -n "${TC03_BODY:-}" ]; then
    tc03_post_json "问答（原始请求体）" "${TC03_BASE_URL}/api/kb/ask" "$TC03_BODY" "$outfile"
  elif [ -n "${TC03_TOPK:-}" ]; then
    tc03_post_json "问答（topK=${TC03_TOPK}）" "${TC03_BASE_URL}/api/kb/ask" \
      "{\"question\":\"${TC03_QUESTION:-去年利润是多少}\",\"topK\":${TC03_TOPK}}" "$outfile"
  else
    tc03_post_json "问答（topK 缺省）" "${TC03_BASE_URL}/api/kb/ask" \
      "{\"question\":\"${TC03_QUESTION:-去年利润是多少}\"}" "$outfile"
  fi
  tc03_ask_summary "$outfile"
}

# ── 只打印、不做判断的取值与展示（**不是判据**）────────────────────────────────────
# 响应体原样（能解析就缩进，解析不了就逐字打印 —— 「不是 JSON」本身也是一种观察）
tc03_pretty() {  # <响应文件>
  if [ ! -f "$1" ]; then
    printf '（curl 没拿到响应体 —— 入口不可达 / 连接被拒）\n'
    return 0
  fi
  python3 - "$1" <<'PY' || cat "$1"
import json
import sys

try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, UnicodeDecodeError, json.JSONDecodeError):
    sys.exit(1)
print(json.dumps(node, ensure_ascii=False, indent=2))
PY
}

# 文档列表的机械字段表（契约 §7.5 的七个字段，逐行打印；取不到的格打「（取不到）」）
tc03_documents_table() {  # <响应文件>
  python3 - "$1" <<'PY'
import json
import sys

try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    print("  （响应不是 JSON，逐字见上）")
    sys.exit(0)

data = node.get("data")
if node.get("code") != 0 or data is None:
    print("  （code != 0 或 data 为 null —— 逐字见上）")
    sys.exit(0)

items = data.get("items")
if items is None:
    print("  单份文档（上传响应，无 items 外壳）：")
    items = [data]
else:
    print("  total=%s  items 条数=%s" % (data.get("total"), len(items)))
for index, item in enumerate(items):
    print("  [%d] documentId=%s fileName=%s fileType=%s fileSize=%s charCount=%s chunkCount=%s createdAt=%s"
          % (index, item.get("documentId"), item.get("fileName"), item.get("fileType"), item.get("fileSize"),
             item.get("charCount"), item.get("chunkCount"), item.get("createdAt")))
PY
}

# 文档列表的指纹行（只留 id 与文件名：**差异清单要能一眼看出多了哪一份**）
tc03_documents_fingerprint() {  # <响应文件>
  python3 - "$1" <<'PY'
import json
import sys

try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    print("  （响应不是 JSON）")
    sys.exit(0)
if node.get("code") != 0 or node.get("data") is None:
    # ⚠️ 不能打「（空）」：那会被读成"列表是空的"。取不到列表与列表为空是两件事
    print("  （列表取不到：code=%s —— 见上面的原始响应）" % node.get("code"))
    sys.exit(0)
items = (node.get("data") or {}).get("items") or []
lines = sorted("%s\t%s" % (item.get("documentId"), item.get("fileName")) for item in items)
if not lines:
    print("  （空：当前账户可见 0 份文档）")
for line in lines:
    print("  " + line)
PY
}

# 问答响应的机械字段表（契约 §7.6）：观测块逐字段打印；正文长度打出来（正文本身不打印，避免刷屏）
tc03_ask_summary() {  # <响应文件>
  python3 - "$1" <<'PY'
import json
import sys

try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    print("  （响应不是 JSON，逐字见上）")
    sys.exit(0)

data = node.get("data")
if node.get("code") != 0 or data is None:
    print("  （code != 0 或 data 为 null —— 逐字见上）")
    sys.exit(0)

answer = data.get("answer") or ""
print("  grounded=%s  answer 字符数=%s" % (data.get("grounded"), len(answer)))
retrieval = data.get("retrieval")
if retrieval:
    print("  retrieval: topK=%s hits=%s threshold=%s durationMs=%s"
          % (retrieval.get("topK"), retrieval.get("hits"), retrieval.get("threshold"), retrieval.get("durationMs")))
generation = data.get("generation")
if generation is None:
    print("  generation: null")
else:
    print("  generation: modelType=%s model=%s durationMs=%s"
          % (generation.get("modelType"), generation.get("model"), generation.get("durationMs")))
sources = data.get("sources")
if sources is None:
    print("  sources: （字段缺失 —— 与契约 §7.6「必填」不符）")
    sys.exit(0)
print("  sources 条数=%s" % len(sources))
for index, source in enumerate(sources):
    content = source.get("content") or ""
    print("    [%d] documentId=%s fileName=%s chunkIndex=%s score=%s content 字符数=%s 前 60 字=%s"
          % (index, source.get("documentId"), source.get("fileName"), source.get("chunkIndex"),
             source.get("score"), len(content), content[:60].replace("\n", "⏎")))
PY
}

# ── 前置检查（只读）───────────────────────────────────────────────────────────────
tc03_preflight_target() {
  local health_code
  health_code="$(curl -s -o "$tc_work_dir/health.json" -w '%{http_code}' "${TC03_BASE_URL}/api/health" || true)"
  if [ "$health_code" != '200' ]; then
    printf '错误：GET %s/api/health → HTTP %s（预期 200）。原始响应：\n' "$TC03_BASE_URL" "$health_code" >&2
    tc_show_body "$tc_work_dir/health.json" >&2
    printf '\n      入口未就绪 —— 先跑 ./scripts/sh/check-health.sh 看哪一项 DOWN，再回来。\n' >&2
    exit 3
  fi
  printf '  入口：GET %s/api/health → HTTP 200（TC03_TARGET=%s）\n' "$TC03_BASE_URL" "$TC03_TARGET"
}

tc03_preflight_fixture() {
  if [ "$TC03_ACTION" != 'upload' ]; then
    return 0
  fi
  tc03_file_resolved="$(cd "$(dirname "${TC03_FILE:-$TC03_DEFAULT_FILE}")" && pwd)/$(basename "${TC03_FILE:-$TC03_DEFAULT_FILE}")"
  if [ ! -f "$tc03_file_resolved" ]; then
    printf '错误：TC03_FILE 指向的文件不存在：%s\n' "$tc03_file_resolved" >&2
    exit 3
  fi
  printf '  夹具：%s（%s 字节）\n' "$tc03_file_resolved" "$(wc -c < "$tc03_file_resolved" | tr -d ' ')"
}

# ── 主流程 ────────────────────────────────────────────────────────────────────────
tc_begin tc_cleanup '⑧ 清理（upload 的文档）+ 跑后文档列表 + 本次 token 登出' '⑨ 无害性自证（跑后 Redis 指纹与差异）'
tc_preflight_tools
tc_preflight_services
tc03_preflight_fixture
tc03_preflight_target

tc_section '② 环境事实（只读）'
printf '  目标入口：%s（TC03_TARGET=%s）\n' "$TC03_BASE_URL" "$TC03_TARGET"
printf '  账户：%s   动作：%s\n' "$TC03_ACCOUNT" "$TC03_ACTION"

tc_fingerprint_before '③ 指纹（跑前）：Redis 白名单键清单'

tc_section '④ 真登录（写 1 个白名单键）'
tc_post_login "$TC03_LOGIN_BODY" "$tc_work_dir/login.json" "$TC03_BASE_URL"
admin_token="$(tc_json_get "$tc_work_dir/login.json" data.token || true)"
if [ -z "$admin_token" ]; then
  printf '错误：登录响应里取不到 data.token（原始响应见上）。\n' >&2
  exit 4
fi
tc_note_jti 'tc03' "$(tc_token_claim "$admin_token" jti || true)"

# 文档列表指纹**必须在登录之后取**（它在白名单之外：先取只会拿到 401，指纹毫无意义 —— 本条已实测）
tc_section '⑤ 指纹（跑前）：当前账户可见的文档列表（登录之后）'
tc03_get_documents "跑前（${TC03_ACCOUNT}）" "$tc_work_dir/docs-before.json"
tc03_documents_fingerprint "$tc_work_dir/docs-before.json" | tee "$tc_work_dir/docs-before.txt"

tc_section "⑥ 动作：${TC03_ACTION}"
case "$TC03_ACTION" in
  list)   tc03_get_documents "动作（${TC03_ACCOUNT}）" "$tc_work_dir/docs-action.json"
          tc03_documents_table "$tc_work_dir/docs-action.json";;
  upload) tc03_action_upload
          tc03_documents_table "$tc_work_dir/upload.json";;
  ask)    tc03_action_ask;;
  delete) if [ -z "${TC03_DOC_ID:-}" ]; then
            printf '错误：delete 动作必须显式给出 TC03_DOC_ID（脚本不自己挑 id）。\n' >&2
            exit 2
          fi
          tc03_action_delete "$TC03_DOC_ID" '动作（显式指定的 documentId）';;
  *)      printf '错误：未知的 TC03_ACTION=%s（支持 list / upload / ask / delete）\n' "$TC03_ACTION" >&2
          exit 2;;
esac

tc_section "⑦ 后端日志里含 [kb] 的行（只读，最后 ${TC03_LOG_TAIL} 行）"
tc_backend_logs "$TC03_LOG_TAIL" | tc_grep_lines '[kb]'

tc_finish 0

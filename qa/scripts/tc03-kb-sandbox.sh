#!/usr/bin/env bash
# =============================================================================
# tc03-kb-sandbox.sh —— TC-03 的「建表语句 + 索引 + 约束 + 级联」一次性沙箱探针
#
# 服务于：docs/test-cases/TC-03.md 的 §3.1（两表与索引存在 / EXPLAIN 的三种写法 /
#         级联删除 / 唯一约束）
# 依据：db-patch/202609221000_初始化知识库表.sql（表与索引的**真源**）、
#       设计 03-RAG知识库.md §4.2（表设计理由）与 §4.6 第 1 条（ORDER BY 的写法决定索引是否生效）
#
# 为什么有它：这些判据全部要在**真实的 PostgreSQL + pgvector** 上跑 —— 单测与静态复核都碰不到
#   它们（索引可不可用由规划器按代价决定，看 SQL 看不出来）。而"跑在哪儿"决定了它无害与否：
#   **库结构类**的探针绝不落在真实 nexus 库上（那是业务库，见 Test Harmlessness）。
#
# 它做的事（每一步都只做动作、只打印结果，不打 PASS/FAIL —— 期望值写在 TC-03.md 里）：
#   ① 前置（只读）：docker / nexus-postgres 可达 + **沙箱库的存在性指纹（跑前）**
#   ② 建沙箱库 nexus_patch_probe（先 DROP IF EXISTS：上一次被打断留下的同名库在此清掉 —— 幂等）
#   ③ 重放三份真实补丁（补丁1 多租户基础表 → 补丁2 知识库表 → 维度补丁 768→1024），
#      打印各自的退出码与输出尾部（维度补丁在沙箱里 DROP + 重建两张表 —— 一次性数据，无所谓）
#   ④ 打印两张表与它们的全部索引（含唯一约束、含 HNSW）
#   ⑤ 造两行探针分块（同一向量、1024 维：让三种 ORDER BY 写法的计划都稳定可读）
#   ⑥ 三种写法的 EXPLAIN（都先 SET enable_seqscan=off —— 小表上规划器会直接选 Seq Scan，
#      那不是"索引不可用"，而是代价估算的结果；关掉顺序扫描才能问出"这条语句能不能走索引"）
#        B1 规范写法 ORDER BY c.embedding <=> <向量>        （本项目采用的形态）
#        B2 别名写法 ORDER BY score DESC                    （设计 §4.6 第 1 条的对照组）
#        B3 阈值放进 WHERE 1-(embedding<=>?)>=?             （设计 §4.6 第 2 条的对照组）
#   ⑦ 级联删除：删文档行 → 打印分块行数（ON DELETE CASCADE 是否真的接管）
#   ⑧ 唯一约束：同一 (document_id, chunk_index) 插两次 → 打印数据库返回的那一行错误
#   ⑨ 收尾：DROP 沙箱库 → 打印**沙箱库的存在性指纹（跑后，应为 0）**
#
# 无害性（Test Harmlessness §2，三样齐备）：
#   ① 作用域声明 —— 写的地方**只有**一次性沙箱库 nexus_patch_probe（CREATE DATABASE → 用完 DROP）。
#      真实库 nexus **零写操作**：本脚本一条语句都不连它（所有 psql 都带 -d nexus_patch_probe 或
#      -d postgres 且只对沙箱库名做 DDL）。谁在消费沙箱库：只有本脚本。
#      为什么非写不可：建表/索引/约束/级联的判据只能在真实数据库上得到 —— 只读观察拿不到等价证据。
#   ② 幂等清理 —— 开头 `DROP DATABASE IF EXISTS`（清掉上次中断的残留）+ 结尾 `DROP DATABASE`
#      （已带 FORCE 语义：psql 的 -d 连接在收尾前不会留着，故无需 FORCE）；EXIT trap 兜底。
#      清理失败会怎样：最坏是留下一个空的沙箱库 nexus_patch_probe（**不影响 nexus 库、不影响
#      任何后续用例**，重建一次即覆盖）。手工收拾的命令：
#        docker exec -i nexus-postgres psql -U nexus -d postgres -c 'DROP DATABASE IF EXISTS nexus_patch_probe'
#   ③ 无害性自证 —— 跑前 / 跑后各打印一次"沙箱库是否存在"（跑前应为 0 或由本脚本清掉、跑后应为 0）。
#
# 用法（**必须在 WSL 内以文件形式执行** —— 脚本里嵌了喂给 `docker exec -i` 的 heredoc，
# `bash -s < 脚本` 会让 heredoc 与脚本共用 stdin，症状是输出为空、脚本静默半途而废、沙箱库还留着）：
#   wsl -d nexus-agent-workbench -- bash /mnt/c/wp/nexus-agent-workbench/qa/scripts/tc03-kb-sandbox.sh
#
# 退出码（**不是判据**，只表示"动作做没做成"）：
#   0 = 动作全部完成   3 = 前提不成立（docker / nexus-postgres 不可达）   130 / 143 = 被信号打断（已清理）
#
# 依赖：docker、WSL。运行位置：WSL（发行版 nexus-agent-workbench），**不需要 push**（不进 builder 容器）。
# =============================================================================

set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/tc-common.sh"

readonly TC03_SANDBOX_DB='nexus_patch_probe'
readonly TC03_PATCH_TENANT="${TC_REPO_DIR}/db-patch/202609131000_初始化多租户基础表.sql"
readonly TC03_PATCH_KB="${TC_REPO_DIR}/db-patch/202609221000_初始化知识库表.sql"
# 维度补丁：768 → 1024（2026-09-22 晚新增；作者当天把名字里的空格去掉了 —— 带空格会让所有 shell 引用都得加引号）
readonly TC03_PATCH_KB_1024="${TC_REPO_DIR}/db-patch/202609221100_知识库向量维度改1024.sql"
readonly TC03_PG_CONTAINER='nexus-postgres'

# psql 的三条通道：Q = 连 postgres 库（做库级 DDL）；P = 连沙箱库（做表级动作）
readonly TC03_Q=(docker exec -i "$TC03_PG_CONTAINER" psql -U nexus -q -v ON_ERROR_STOP=1)
readonly TC03_P=(docker exec -i "$TC03_PG_CONTAINER" psql -U nexus -q -v ON_ERROR_STOP=1 -d "$TC03_SANDBOX_DB")

# 探针向量：1024 维常量向量（与 t_kb_chunk.embedding vector(1024) 同维）
# ⚠️ 这个维度是**常量、与补丁里的列定义是两处**（同 MAX_FILE_NAME_LENGTH↔VARCHAR(255) 那类取舍）：
#    换 embedding 模型时必须**它与补丁同改**。2026-09-22 晚：nomic-embed-text(768) → bge-m3(1024)，
#    补丁重建为 vector(1024) 并清空数据；此处若不同步，本脚本会在**类型不匹配**上失败。
readonly TC03_PROBE_VECTOR="('[' || repeat('0.02,',1023) || '0.02]')::vector"

# ── 清理脚手架：与 lib 同款口径（信号不会触发 EXIT trap，先转成一次正常退出）──────────
tc03_sandbox_cleanup() {
  "${TC03_Q[@]}" -d postgres -c "DROP DATABASE IF EXISTS ${TC03_SANDBOX_DB}" >/dev/null 2>&1 || true
}
trap tc03_sandbox_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

tc03_sandbox_existence() {  # <标签>
  local count
  count="$("${TC03_Q[@]}" -d postgres -tAc \
    "SELECT count(*) FROM pg_database WHERE datname = '${TC03_SANDBOX_DB}'" 2>/dev/null | tr -d ' \r')"
  printf '  %s：沙箱库 %s 的存在数 = %s\n' "$1" "$TC03_SANDBOX_DB" "${count:-（取不到）}"
}

tc_section '① 前置（只读）'
if ! command -v docker >/dev/null 2>&1; then
  printf '错误：找不到 docker。本脚本要在 WSL（发行版 nexus-agent-workbench）内跑。\n' >&2
  exit 3
fi
if ! docker ps --format '{{.Names}}' | tr -d '\r' | grep -qx "$TC03_PG_CONTAINER"; then
  printf '错误：容器 %s 不在运行中（docker ps 里没有它）。\n' "$TC03_PG_CONTAINER" >&2
  exit 3
fi
printf '  容器：%s 在运行\n' "$TC03_PG_CONTAINER"
printf '  补丁（按文件名字典序，与引擎同序）：\n    %s\n    %s\n    %s\n' \
  "$TC03_PATCH_TENANT" "$TC03_PATCH_KB" "$TC03_PATCH_KB_1024"
tc03_sandbox_existence '指纹（跑前）'

tc_section "② 建沙箱库 ${TC03_SANDBOX_DB}（真实 nexus 库零写操作）"
"${TC03_Q[@]}" -d postgres -c "DROP DATABASE IF EXISTS ${TC03_SANDBOX_DB}" >/dev/null 2>&1 || true
"${TC03_Q[@]}" -d postgres -c "CREATE DATABASE ${TC03_SANDBOX_DB}" >/dev/null 2>&1 || true
tc03_sandbox_existence '建库后'

tc_section '③ 重放真实补丁（补丁1 → 补丁2 → 维度补丁）'
tc03_replay_patch() {  # <标签> <补丁文件>
  local output code
  output="$("${TC03_P[@]}" -f - < "$2" 2>&1)"
  code=$?
  printf '  %s：%s → 退出码 %s\n' "$1" "$(basename "$2")" "$code"
  if [ -n "$output" ]; then
    printf '%s\n' "$output" | tail -n 8 | sed 's/^/      /'
  fi
}
tc03_replay_patch '补丁1 多租户基础表' "$TC03_PATCH_TENANT"
tc03_replay_patch '补丁2 知识库表' "$TC03_PATCH_KB"
# 维度补丁会 DROP + 重建两张表（vector(768) → vector(1024)）—— 不重放它，下面 1024 维的探针向量必然失败
tc03_replay_patch '维度补丁 768 → 1024' "$TC03_PATCH_KB_1024"

tc_section '④ 两张表与它们的全部索引（含唯一约束、含 HNSW）'
"${TC03_P[@]}" -P pager=off -c \
  "SELECT tablename, indexname, indexdef FROM pg_indexes
    WHERE tablename IN ('t_kb_document', 't_kb_chunk') ORDER BY tablename, indexname" 2>&1

tc_section '⑤ 造两行探针分块（同一向量）'
"${TC03_P[@]}" <<SQL
INSERT INTO t_kb_document (tenant_id, file_name, file_type, file_size, char_count, chunk_count)
SELECT tenant_id, 'tc03-probe.txt', 'TXT', 10, 8, 2 FROM t_tenant WHERE tenant_code = 'default';
INSERT INTO t_kb_chunk (tenant_id, document_id, chunk_index, content, char_count, embedding)
SELECT d.tenant_id, d.document_id, 0, '甲', 1, ${TC03_PROBE_VECTOR} FROM t_kb_document d WHERE d.file_name = 'tc03-probe.txt';
INSERT INTO t_kb_chunk (tenant_id, document_id, chunk_index, content, char_count, embedding)
SELECT d.tenant_id, d.document_id, 1, '乙', 1, ${TC03_PROBE_VECTOR} FROM t_kb_document d WHERE d.file_name = 'tc03-probe.txt';
SQL
"${TC03_P[@]}" -tAc \
  "SELECT '  文档行数=' || (SELECT count(*) FROM t_kb_document) || '  分块行数=' || (SELECT count(*) FROM t_kb_chunk)" 2>&1

tc_section '⑥ 三种写法的 EXPLAIN（都先 SET enable_seqscan = off）'
tc03_explain() {  # <标签> <SQL>
  printf '\n  ── %s ─\n' "$1"
  "${TC03_P[@]}" -P pager=off -c "SET enable_seqscan = off; EXPLAIN $2" 2>&1 | sed 's/^/      /'
}
tc03_explain 'B1 规范写法：ORDER BY c.embedding <=> <向量>' \
  "SELECT c.chunk_id FROM t_kb_chunk c ORDER BY c.embedding <=> ${TC03_PROBE_VECTOR} LIMIT 5"
tc03_explain 'B2 对照：ORDER BY 别名 score DESC' \
  "SELECT c.chunk_id, 1-(c.embedding <=> ${TC03_PROBE_VECTOR}) AS score FROM t_kb_chunk c ORDER BY score DESC LIMIT 5"
tc03_explain 'B3 对照：阈值放进 WHERE 1-(embedding<=>?) >= ?' \
  "SELECT c.chunk_id FROM t_kb_chunk c WHERE 1-(c.embedding <=> ${TC03_PROBE_VECTOR}) >= 0.5 ORDER BY c.embedding <=> ${TC03_PROBE_VECTOR} LIMIT 5"

tc_section '⑦ 级联删除：删文档行 → 分块行数（ON DELETE CASCADE）'
"${TC03_P[@]}" -c "DELETE FROM t_kb_document WHERE file_name = 'tc03-probe.txt'" >/dev/null 2>&1 || true
"${TC03_P[@]}" -tAc "SELECT '  删文档后：分块行数=' || count(*) FROM t_kb_chunk" 2>&1

tc_section '⑧ 唯一约束：同一 (document_id, chunk_index) 插两次'
# heredoc 用**非引号**形式：${TC03_PROBE_VECTOR} 必须被展开（引号形式会让它原样进 SQL、整段无声失败）
"${TC03_P[@]}" <<SQL
INSERT INTO t_kb_document (tenant_id, file_name, file_type, file_size, char_count, chunk_count)
SELECT tenant_id, 'tc03-dup.txt', 'TXT', 1, 1, 1 FROM t_tenant WHERE tenant_code = 'demo';
INSERT INTO t_kb_chunk (tenant_id, document_id, chunk_index, content, char_count, embedding)
SELECT d.tenant_id, d.document_id, 0, '甲', 1, ${TC03_PROBE_VECTOR}
FROM t_kb_document d WHERE d.file_name = 'tc03-dup.txt';
SQL
"${TC03_P[@]}" -c "INSERT INTO t_kb_chunk (tenant_id, document_id, chunk_index, content, char_count, embedding)
                   SELECT d.tenant_id, d.document_id, 0, '乙', 1, ${TC03_PROBE_VECTOR}
                   FROM t_kb_document d WHERE d.file_name = 'tc03-dup.txt'" 2>&1 | sed 's/^/  /'

tc_section '⑨ 收尾：DROP 沙箱库'
tc03_sandbox_cleanup
tc03_sandbox_existence '指纹（跑后）'
printf '\n（本脚本的期望值不在脚本里 —— 判据见 docs/test-cases/TC-03.md 的 §3.1）\n'

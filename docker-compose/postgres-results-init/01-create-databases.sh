#!/bin/bash
# =============================================================================
# 01-create-databases.sh —— postgres-results 首启建**两个空库**
#   · 结果库（RESULTS_PG_DB，pytest 写入；表结构留给 5.5）
#   · Metabase 应用库（METABASE_APP_DB，M4-②：跟结果库同一个实例）
#
# 触发时机：仅在数据目录为空、initdb 之后由 postgres 官方镜像的
#           /docker-entrypoint-initdb.d 机制执行一次。
# 边界（5.1 任务书）：**只建空库，不建任何表**——表结构/视图是 5.5 的交付物。
# 幂等：先查 pg_database 再建，重复执行（换卷重跑 / 手工补跑）不报错。
# 库名不硬编码：从容器 env 读（compose 从 .env.test 注入）——"一处改、处处生效"。
#
# 兼容性：官方入口在脚本可执行时直接运行、不可执行时 `source`。
#         本文件刻意**不自设 set -e**（source 时会污染入口 shell，官方入口自己已带 -Eeo pipefail）；
#         关键命令显式判错。
# =============================================================================

create_db_if_missing() {
    local db="$1"
    if [ -z "${db}" ]; then
        echo "[results-init] 失败：库名为空（检查 RESULTS_PG_DB / METABASE_APP_DB）" >&2
        return 1
    fi
    echo "[results-init] 确保数据库存在：${db}"
    if psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
            -tAc "SELECT 1 FROM pg_database WHERE datname = '${db}'" | grep -q 1; then
        echo "[results-init] 已存在，跳过：${db}"
    else
        psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
            -c "CREATE DATABASE \"${db}\" OWNER \"${POSTGRES_USER}\"" \
            || { echo "[results-init] 失败：建库 ${db} 出错" >&2; return 1; }
    fi
}

create_db_if_missing "${RESULTS_PG_DB}"
create_db_if_missing "${METABASE_APP_DB}"

echo "[results-init] 完成：${RESULTS_PG_DB} / ${METABASE_APP_DB}（空库，表结构由 5.5 落）"

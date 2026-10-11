-- =============================================================================
-- 02-results-schema.sql —— 结果库「nexus_test_results」的表与统计视图（5.5 交付，2026-10-10）
--
-- 落位（待拍板项，当前实现）：**测试环境自己的初始化目录** —— 与 01-create-databases.sh 同处，
--   由 postgres 官方入口按文件名顺序自动执行（对 POSTGRES_DB = 结果库执行）。
--   设计口吻与取舍见 docs/design/05-自动化测试.md §7.4.3。
--
-- ★ 存量卷纪律（本文件存在的第一理由）：
--   initdb 脚本只在**数据目录为空**时跑一次 —— 结果库卷在首跑时已初始化，此后往本目录加脚本
--   **不会再自动执行**。因此本文件有**两条应用路径**，两条都必须幂等：
--     ① 全新卷：postgres 入口按文件名顺序执行本目录（01 → 02）；
--     ② 存量卷：收尾脚本 scripts/py/finalize-run.py 每次运行前把**本文件**重放一遍
--        （psql -f -、同一个库、同一份文件）。两条路径收敛到同一 schema，无需手工干预。
--
-- 幂等纪律：本文件只允许**可重复执行**的语句（CREATE TABLE IF NOT EXISTS / CREATE OR REPLACE VIEW /
--   CREATE INDEX IF NOT EXISTS / COMMENT ON …）。将来改列必须**追加**显式幂等的 ALTER，
--   禁止改写已应用的语句（同 db-patch 的纪律：已应用的历史不可变）。
-- 使用方：① 收尾脚本 finalize-run.py 落库（唯一的写入方）；② Metabase 只读（M6 看板）。
-- 边界：只碰 nexus_test_results 库；同一实例上还有 Metabase 应用库 metabase_app，别动它。
-- =============================================================================

-- ── 运行表：一次冒烟运行一行 ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_e2e_run (
    run_id          text        PRIMARY KEY,          -- 与 run-results.json 的 run_id 同值（幂等的键）
    started_at      timestamptz,
    finished_at     timestamptz,
    exit_code       integer,                          -- pytest 退出码（0=全过；其它=有用例没过/运行本身出错）
    conclusion      text        NOT NULL,             -- pass / fail（口径见 finalize-run.py：只看 failed/error/unknown 是否为 0）
    total           integer     NOT NULL DEFAULT 0,
    passed          integer     NOT NULL DEFAULT 0,
    failed          integer     NOT NULL DEFAULT 0,
    skipped         integer     NOT NULL DEFAULT 0,
    error           integer     NOT NULL DEFAULT 0,
    unknown         integer     NOT NULL DEFAULT 0,
    revision_sha    text,                             -- 被测代码版本（revision.sha）
    trigger_kind    text,                             -- local / ci
    github_run_id   text,
    run_url         text,                             -- Actions 运行链接（有则记）
    base_url        text,                             -- 被测前端地址（environment.base_url）
    environment     jsonb,                            -- run-results.json 的 environment 块原样
    raw             jsonb       NOT NULL,             -- 整份 run-results.json 留档（用例行由它派生）
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT t_e2e_run_conclusion_check CHECK (conclusion IN ('pass', 'fail'))
);
COMMENT ON TABLE t_e2e_run IS '冒烟运行（qa/e2e）一次一行；写入方 = scripts/py/finalize-run.py（同 run_id 覆盖式幂等）';

-- ── 用例表：一次运行 × 一条用例一行 ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_e2e_case (
    id              bigserial   PRIMARY KEY,
    run_id          text        NOT NULL REFERENCES t_e2e_run(run_id) ON DELETE CASCADE,
    tc_id           text,                             -- S1~S5（来自 pytest.mark.tc）
    title           text,
    source_tc       text,                             -- 出处（如 TC-01-1.5-1）
    nodeid          text        NOT NULL,
    status          text        NOT NULL,             -- passed / failed / skipped / error / unknown
    duration_ms     integer,
    message         text,                             -- 失败原文（截断 4000 字符，与 run-results.json 一致）
    trace_path      text,                             -- 卷内相对路径在 /playwright/ 下（仅失败有）
    screenshot_path text,
    cleanup_login   text,                             -- 无害性自证：ok / warn / none
    cleanup_kb      text,
    UNIQUE (run_id, nodeid)
);
COMMENT ON TABLE t_e2e_case IS '冒烟用例级结果；写入方 = scripts/py/finalize-run.py（随运行行整批替换）';

-- ── 统计视图（Metabase 的取数面；M6 看板基于它们）────────────────────────────
CREATE OR REPLACE VIEW v_e2e_run_summary AS
SELECT
    run_id,
    started_at,
    finished_at,
    round(EXTRACT(EPOCH FROM (finished_at - started_at))::numeric, 1) AS duration_s,
    conclusion,
    total, passed, failed, skipped, error, unknown,
    CASE WHEN total > 0 THEN round(passed::numeric * 100 / total, 1) END AS pass_rate_pct,
    revision_sha,
    trigger_kind,
    run_url,
    created_at
FROM t_e2e_run
ORDER BY started_at DESC NULLS LAST, run_id DESC;
COMMENT ON VIEW v_e2e_run_summary IS '每次运行一行（倒序）；「最近 N 次运行的通过率」= SELECT run_id, pass_rate_pct FROM v_e2e_run_summary LIMIT N';

CREATE OR REPLACE VIEW v_e2e_case_recent AS
SELECT
    c.run_id,
    r.started_at,
    c.tc_id,
    c.title,
    c.source_tc,
    c.status,
    c.duration_ms,
    c.message,
    c.trace_path,
    c.screenshot_path,
    c.cleanup_login,
    c.cleanup_kb
FROM t_e2e_case c
JOIN t_e2e_run r USING (run_id)
ORDER BY r.started_at DESC NULLS LAST, c.tc_id;
COMMENT ON VIEW v_e2e_case_recent IS '用例级明细（按运行时间倒序）；「最近失败用例」= 取 status 不为 passed 的行';

CREATE OR REPLACE VIEW v_e2e_case_stats AS
SELECT
    tc_id,
    max(title)                                                                AS title,
    count(*)                                                                  AS runs,
    count(*) FILTER (WHERE status = 'passed')                                 AS passed_runs,
    count(*) FILTER (WHERE status = 'failed')                                 AS failed_runs,
    count(*) FILTER (WHERE status IN ('error', 'skipped', 'unknown'))         AS other_runs,
    round(100.0 * count(*) FILTER (WHERE status = 'passed') / GREATEST(count(*), 1), 1) AS pass_rate_pct,
    max(duration_ms)                                                          AS max_duration_ms
FROM t_e2e_case
GROUP BY tc_id
ORDER BY tc_id;
COMMENT ON VIEW v_e2e_case_stats IS '按用例（S1~S5）聚合的稳定性视图：跑了多少次 / 过多少次';

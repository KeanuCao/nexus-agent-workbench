-- =============================================================================
-- 209912310010_TC00探针B.sql —— TC-00 §2 的测试夹具（探针 A 的下一条）
--
-- 位置与用途同 209912310000_TC00探针A.sql（见其文件头）。本文件只多一件事：
-- **刻意依赖 A 的产物**。
--
-- 为什么必须依赖 A（而不是各建各的表）：
--   「补丁按文件名升序执行」这件事要能被数据库**证明**，而不是"日志看起来是对的"。
--   若引擎把 B 排在 A 前面执行，本文件的第一条 DDL 就会报
--     ERROR: schema "tc00_probe" does not exist
--   （schema 也是 A 建的；schema 若已存在的场景下，则会在外键或 SELECT 处报
--     ERROR: relation "tc00_probe.tc00_probe_a" does not exist）
--   两种情况都是：整条回滚、引擎以退出码 4 终止 —— 而不是静默地"先建表再插数据"却依然打印成功。
--   依赖在两处可见：① 外键引用 A 的主键；② 插入的取值来自 A 里的那一行。
--   判据侧对应 TC-00-0.2-3 的 ②：a_rows=1 且 b_rows=1 才算「按序执行」成立。
-- =============================================================================

-- 沙箱守卫：理由与 209912310000 完全相同 —— 防止误跑进真实库污染 t_db_patch
DO $$
BEGIN
    IF current_database() <> 'nexus_tc00_probe' THEN
        RAISE EXCEPTION 'TC-00 探针补丁只允许在一次性沙箱库 nexus_tc00_probe 中执行，当前数据库：%', current_database();
    END IF;
END
$$;

CREATE TABLE tc00_probe.tc00_probe_b (
    probe_id BIGSERIAL PRIMARY KEY,
    a_id     BIGINT    NOT NULL REFERENCES tc00_probe.tc00_probe_a (probe_id)
);

-- 这里**不写**任何容错（没有 IF NOT EXISTS、没有 WHERE EXISTS、没有 COALESCE）：
-- A 缺失时必须报错 —— 报错本身就是本文件存在的意义。
INSERT INTO tc00_probe.tc00_probe_b (a_id)
SELECT probe_id FROM tc00_probe.tc00_probe_a WHERE note = 'probe-A';

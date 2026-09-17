-- =============================================================================
-- 209912310000_TC00探针A.sql —— TC-00 §2 的**测试夹具**，不属于任何业务功能
--
-- 位置：qa/fixtures/db-patch/（测试资产目录，约定见 qa/README.md）
--   生产链路不会读到它：正式迁移的补丁目录是 /db-patch（up.sh 第 5 步）。
--   本文件只在用例把它拷进容器 /tmp/tc00-patches、并把 NEXUS_PATCH_DIR 指过去时才被执行，
--   而且执行目标是一次性沙箱库 nexus_tc00_probe（NEXUS_PG_DB）。
--   ——「测试资产与正式目录物理隔离」正是 Test Harmlessness 的落点：
--      旧口径把探针 SQL 直接丢进 /workspace/repo/db-patch/，忘了删就会让下一次
--      真实迁移判乱序 exit 3、up.sh 当场中止（延迟爆发，且没人会联想到测试）。
--
-- 为什么它与业务彻底无关：
--   本文件只在自有 schema tc00_probe 里建对象，**不碰任何业务表**
--   （t_tenant / t_user / t_db_patch 一律不读不写）。
--   它存在的意义是给 0.2-3（checksum 篡改）与 0.2-4（乱序保护）提供"已应用过的补丁"，
--   让这两个负路径不必再拿业务补丁开刀 —— 篡改业务补丁 = 改已发布契约物，
--   即使随后 git checkout 还原，中途失败也会留下一个"没人知道被改过"的仓库。
--
-- 为什么时间戳刻意用 2099 开头：
--   保证它永远晚于所有真实补丁 → 「乱序保护」用例的时间基准（已应用的最大文件名）
--   与跑测日期无关，永不过期。
--
-- 与 209912310010_TC00探针B.sql 的关系：B **依赖本文件的产物**，理由见 B 的文件头。
-- =============================================================================

-- 沙箱守卫（必须放在第一条，先于任何建对象动作）：
--   本文件若被误跑进真实库 nexus，会把 file_name 写进真实的 t_db_patch，
--   其 2099 时间戳会让**后续所有真实补丁**判乱序 exit 3 —— 一个延迟爆发的坑。
--   这里让它当场 raise：引擎的单事务包裹会回滚整条补丁，真实库零写入（引擎退出码 4）。
DO $$
BEGIN
    IF current_database() <> 'nexus_tc00_probe' THEN
        RAISE EXCEPTION 'TC-00 探针补丁只允许在一次性沙箱库 nexus_tc00_probe 中执行，当前数据库：%', current_database();
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS tc00_probe;

COMMENT ON SCHEMA tc00_probe IS 'TC-00 §2 的测试探针 schema —— 与业务无关，只应存在于一次性沙箱库';

-- 刻意**不写** IF NOT EXISTS：与业务补丁 202609131000_初始化多租户基础表.sql 的 t_tenant 同款
-- fail-fast —— "表已存在"意味着有引擎之外的路径动过它，报错比静默跳过更能暴露问题。
CREATE TABLE tc00_probe.tc00_probe_a (
    probe_id   BIGSERIAL   PRIMARY KEY,
    note       VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tc00_probe.tc00_probe_a (note) VALUES ('probe-A');

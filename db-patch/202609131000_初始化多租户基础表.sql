-- =============================================================================
-- 202609131000_初始化多租户基础表.sql
--
-- 内容：
--   ① t_db_patch 引导建表（冗余保险）
--   ② CREATE EXTENSION vector（pgvector 扩展启用）
--   ③ t_tenant 多租户基础表
--   ④ 两个种子租户（供"两租户数据互不可见"的实测）
--
-- ⚠️ 纪律（引擎会强制执行，不是靠自觉）：
--     本文件一旦执行过就**不得再修改**。引擎按 SHA-256 checksum 校验已应用补丁，
--     改动任何一个字符都会让下一次迁移报错终止。变更一律**新增补丁**。
--
-- 执行方式：由 builder 容器内的 PatchCli 执行，单事务包裹 —— 本文件里任何一条失败，
--          整个补丁回滚，且不会写入 t_db_patch 记录。
-- =============================================================================

-- ① 记录表引导建表（与引擎的 CREATE TABLE IF NOT EXISTS 互为冗余保险）
--    为什么这一条必须带 IF NOT EXISTS：引擎在扫描补丁**之前**已经建过它了，
--    这里再建一次是为了让"记录表自己"也有一条补丁溯源记录。不带 IF NOT EXISTS 必然报错。
CREATE TABLE IF NOT EXISTS t_db_patch (
    patch_id     BIGSERIAL    PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL UNIQUE,
    checksum     CHAR(64)     NOT NULL,
    applied_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    applied_by   VARCHAR(64)  NOT NULL DEFAULT 'nexus-builder',
    exec_time_ms INTEGER      NOT NULL
);

-- ② pgvector 扩展（阶段3 RAG 用；此处启用，避免到时候再补一次结构变更）
--    镜像 pgvector/pgvector:pg16 只保证扩展**可加载**，不等于已安装 —— 装是这一步做的。
--    IF NOT EXISTS 是必要的：允许有人手工装过之后补丁仍能跑通。
CREATE EXTENSION IF NOT EXISTS vector;

-- ③ 租户表
--    对比 ① 的 IF NOT EXISTS：这里刻意**不写** IF NOT EXISTS。
--    补丁由引擎保证只执行一次，"表已存在"意味着出了预期之外的事，
--    此时让 CREATE TABLE 直接报错、把问题暴露出来，比静默跳过更符合"fail-fast"。
CREATE TABLE t_tenant (
    tenant_id   BIGSERIAL    PRIMARY KEY,
    tenant_code VARCHAR(64)  NOT NULL UNIQUE,
    tenant_name VARCHAR(128) NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  t_tenant             IS '租户表：多租户隔离的根，业务表经 tenant_id 关联';
COMMENT ON COLUMN t_tenant.tenant_code IS '人类可读租户标识，供运维与未来"按租户编码登录"扩展';
COMMENT ON COLUMN t_tenant.status      IS '1=启用 0=停用（登录时校验）';

-- ④ 种子租户
--    两个租户是为了能现场演示"租户 A 的 token 查不到租户 B 的数据"。
--    同样是普通 INSERT：引擎保证只执行一次，重复插入本就不该发生。
INSERT INTO t_tenant (tenant_code, tenant_name, status) VALUES
    ('default', '默认租户', 1),
    ('demo',    '演示租户', 1);

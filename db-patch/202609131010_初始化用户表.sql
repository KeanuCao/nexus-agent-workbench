-- =============================================================================
-- 202609131010_初始化用户表.sql
--
-- 内容：
--   ① t_user 用户表（关联 tenant_id，username 全局唯一）
--   ② 每个租户一个种子用户
--
-- ⚠️ 纪律：本文件一旦执行过就不得再修改（引擎按 SHA-256 checksum 校验）。
--
-- 关于 username 为什么是**全局唯一**（而不是 (tenant_id, username) 联合唯一）：
--   登录接口只收 username + password，不要求用户先声明自己属于哪个租户。
--   因此检索键必须是全局唯一的 —— 查出用户之后才知道它的 tenant_id，
--   再由 tenant_id 签发 token、建立租户上下文。代价是多租户下不能有同名用户。
--   取舍记录见 docs/design/01-多租户与认证.md 决策 D2。
-- =============================================================================

CREATE TABLE t_user (
    user_id    BIGSERIAL    PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES t_tenant (tenant_id),
    username   VARCHAR(64)  NOT NULL UNIQUE,
    -- BCrypt 哈希固定 60 字符；留到 100 是为了将来换更长的哈希算法时不用改结构
    password   VARCHAR(100) NOT NULL,
    nickname   VARCHAR(64),
    status     SMALLINT     NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  t_user            IS '用户表：多租户隔离（tenant_id 由 TenantLineHandler 自动注入查询条件）';
COMMENT ON COLUMN t_user.username   IS '全局唯一 —— 登录的唯一检索键（见文件头说明）';
COMMENT ON COLUMN t_user.password   IS 'BCrypt 哈希，明文一律不入库';
COMMENT ON COLUMN t_user.status     IS '1=启用 0=停用（登录时校验）';

-- tenant_id 索引：多租户下每条业务查询都会带 tenant_id 条件，没有索引就是全表扫描
CREATE INDEX idx_user_tenant_id ON t_user (tenant_id);

-- ── 种子用户 ──────────────────────────────────────────────────────────────────
-- 口令为**演示用明文**（admin123 / demo123），随仓库公开、仅限 dev 环境。
--
-- 哈希生成方式（任何人可复现、可校验）：
--     python3 -c 'import bcrypt; print(bcrypt.hashpw(b"admin123", bcrypt.gensalt(rounds=10, prefix=b"2a")).decode())'
--   前缀刻意取 2a、rounds=10：与 Spring Security BCryptPasswordEncoder 的默认输出一致。
--   哈希的最终判据是**登录接口实跑成功**，不是"看起来像 BCrypt"。
INSERT INTO t_user (tenant_id, username, password, nickname, status)
SELECT t.tenant_id, v.username, v.password, v.nickname, 1
FROM t_tenant t
JOIN (VALUES
    ('default', 'admin', '$2a$10$mT1vtr8aav.yTB05wPIDuujmB3tZ8qt0soa0Z4CplL1.Bzk7ecWkq', '默认租户管理员'),
    ('demo',    'demo',  '$2a$10$DK75eWbJ1dLIU0koJ6OnjOVYuMuKXZ4C6VlI/ck7c94NIPbe9VD7W', '演示租户用户')
) AS v (tenant_code, username, password, nickname)
  ON t.tenant_code = v.tenant_code;

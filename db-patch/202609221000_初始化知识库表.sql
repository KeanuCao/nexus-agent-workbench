-- =============================================================================
-- 202609221000_初始化知识库表.sql
--
-- 内容：
--   ① t_kb_document 知识库文档表（一行 = 一份已入库的文档）
--   ② t_kb_chunk   文档分块表（一行 = 一个分块 + 它的向量）
--   ③ 索引：两张表的 tenant_id、分块的 document_id、(document_id, chunk_index) 唯一约束，
--      以及 embedding 上的 HNSW 近似索引（余弦）
--   ④ 两张表的 COMMENT（表 + 关键列）
--
-- ⚠️ 纪律（引擎会强制执行，不是靠自觉）：
--     本文件一旦执行过就**不得再修改**。引擎按 SHA-256 checksum 校验已应用补丁，
--     改动任何一个字符都会让下一次迁移报错终止。变更一律**新增补丁**。
--
-- 执行方式：由 builder 容器内的 PatchCli 执行，单事务包裹 —— 本文件里任何一条失败，
--          整个补丁回滚，且不会写入 t_db_patch 记录。
--          命令：docker compose exec -T builder db-patch-migrate（完整链路见设计 §6.5）
--
-- 为什么表结构不写 IF NOT EXISTS（与 db-patch/202609131000 的取舍一致，也是本仓库的既定风格）：
--   补丁由引擎保证只执行一次（checksum + 单事务 + 文件名乱序保护）。走到"表已存在"
--   意味着出了预期之外的事（有人手工建过、或补丁被绕开执行过），此时让 CREATE TABLE
--   直接报错、把问题暴露出来，比静默跳过更符合 fail-fast。唯一例外是引导表 t_db_patch 自己。
--   ⚠️ CLAUDE.md 的补丁规范建议写 IF NOT EXISTS，与本仓库实况不同；两者不是"谁忘了写"，
--   而是同一件事的两种口径（引擎保证 vs DDL 自证）。本次按设计 §6.3 与既有补丁执行，
--   若要改口径，应是一次性的全局决策 + 一个新补丁，不是单个补丁的事。
--
-- vector(768) 的 768 从哪来：nomic-embed-text 的输出维度，2026-09-22 探针 A 实测确认
--   （docs/design/03-RAG知识库.md §3.9；模型卡 architecture nomic-bert、embedding length 768）。
--   ⚠️ 维度写错时**建表是成功的**，第一次入库才报类型错误（容易被误判成代码 bug）。
--   将来换 embedding 模型 ⇒ 新增补丁改这个维度 + **重灌全部文档**（存量向量与新模型不同空间）。
--
-- 回滚（如需，**不在本文件内执行**）：历史补丁不得修改，回滚同样只能靠**新增**一个更晚的补丁：
--   DROP TABLE IF EXISTS t_kb_chunk;      -- 先删子表（它对 t_kb_document 有外键）
--   DROP TABLE IF EXISTS t_kb_document;
--   注意回滚会连数据一起丢掉，而本轮**不保存原始文件** ⇒ 删掉就重灌不出那一批分块。
-- =============================================================================

-- ① 文档表
--    char_count / chunk_count 是"审核与排查的落点"：解析质量差（char_count 离谱地小）与
--    分块异常（chunk_count 为 1）在列表页就能看出来，不必翻日志（设计 §4.2）。
CREATE TABLE t_kb_document (
    document_id BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES t_tenant (tenant_id),
    file_name   VARCHAR(255) NOT NULL,           -- 原始文件名，仅回显/展示用，不落盘
    file_type   VARCHAR(16)  NOT NULL,           -- TXT / PDF（大写，扩展名归一化而来）
    file_size   BIGINT       NOT NULL,           -- 上传字节数
    char_count  INTEGER      NOT NULL,           -- 解析出的正文字符数
    chunk_count INTEGER      NOT NULL,           -- 入库分块数（= 3000 上限判据的实测值）
    created_by  BIGINT,                          -- 上传者 user_id；**不加外键**（见下方 COMMENT）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- tenant_id 索引：多租户下"列表 / 删除"每条查询都会带 tenant_id 条件（由 TenantLineHandler
-- 自动注入），没有索引就是全表扫描 —— 与 t_user 的同名索引同源理由。
CREATE INDEX idx_kb_document_tenant_id ON t_kb_document (tenant_id);

COMMENT ON TABLE  t_kb_document IS '知识库文档表：一行 = 一份已入库的文档（本轮不保存原始文件，只保存解析出的分块）';
COMMENT ON COLUMN t_kb_document.tenant_id   IS '租户隔离列：查询条件由 TenantLineHandler 自动注入，业务 SQL 里不手写（设计 D11）';
COMMENT ON COLUMN t_kb_document.file_name   IS '原始文件名，仅用于展示与引用来源标注（本轮不落盘，见决策 D8）';
COMMENT ON COLUMN t_kb_document.file_type   IS 'TXT / PDF（大写）：由扩展名归一化，并与内容检测交叉校验（设计 D12）';
COMMENT ON COLUMN t_kb_document.char_count  IS '解析出的正文字符数：排查解析质量的第一眼数据（与预期量级差太远即解析出了问题）';
COMMENT ON COLUMN t_kb_document.chunk_count IS '入库的分块数；同时也是"单文档分块上限 3000"这条规则的实际取值';
COMMENT ON COLUMN t_kb_document.created_by  IS '上传者 user_id，取自 TenantContext（刻意不加外键：与 t_user 的耦合没有收益，本轮也没有按人过滤的需求）';

-- ② 分块表（含向量）
--    embedding 的维度 = 768，理由见文件头（探针 A 实测）。
CREATE TABLE t_kb_chunk (
    chunk_id    BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES t_tenant (tenant_id),
    -- ★ ON DELETE CASCADE：删文档 = 删其全部分块，级联由数据库保证。
    --   没有它就得靠业务代码记得删两次 —— 少一处"忘了删分块"的可能（设计 §3.3 第 2 条）。
    document_id BIGINT       NOT NULL REFERENCES t_kb_document (document_id) ON DELETE CASCADE,
    chunk_index INTEGER      NOT NULL,           -- 分块在文档内的序号，0 起（引用展示"第 N 段"用 index+1）
    content     TEXT         NOT NULL,           -- 分块原文：引用即原文，不截断、不摘要（可核对的前提）
    char_count  INTEGER      NOT NULL,           -- 本块字符数（Java String.length() 口径）
    embedding   vector(768)  NOT NULL,           -- 向量，由 nomic-embed-text 生成
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 唯一约束挡住"同一文档重灌出重复块"这类缺陷；索引顺带服务"按文档取块"的查询
    CONSTRAINT uk_kb_chunk_doc_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_kb_chunk_tenant_id   ON t_kb_chunk (tenant_id);
CREATE INDEX idx_kb_chunk_document_id ON t_kb_chunk (document_id);

-- ★ 向量索引：task.4 3.1 验收点名"检索有索引"。
--   操作符类 vector_cosine_ops 对应检索 SQL 里的余弦距离操作符 <=>（score = 1 - (embedding <=> q)）。
--   ⚠️ 操作符类必须与查询用的操作符一致，否则索引**建了也用不上**：
--       · vector_cosine_ops   ← <=>   余弦（本项目）
--       · vector_l2_ops       ← <->   欧氏距离
--       · vector_ip_ops       ← <#>   内积
--   为什么是 HNSW 而不是 IVFFlat：HNSW 不需要训练、不需要预先指定 list 数，
--     小数据量上召回更稳；写入慢一点的代价在"离线入库"场景下无所谓（设计 §4.2）。
--   依赖：pgvector ≥ 0.5.0（2026-09-22 探针 D 实测 0.8.6，满足）。
--   判据（建表后必须能看到它）：SELECT indexname FROM pg_indexes WHERE tablename='t_kb_chunk';
--   以及 EXPLAIN 走索引 —— ★ 2026-09-22 沙箱实测订正：**必须先关顺序扫描**，否则表小时规划器会按
--   代价选 Seq Scan（HNSW 是近似索引），照字面断言会**假失败**：
--       SET enable_seqscan = off;
--       EXPLAIN SELECT chunk_id FROM t_kb_chunk
--           ORDER BY embedding <=> (SELECT embedding FROM t_kb_chunk LIMIT 1) LIMIT 5;
--       → 必须出现 Index Scan using idx_kb_chunk_embedding_hnsw（沙箱库实测：确实是这个计划）。
--   ⚠️ 想走这个索引，检索 SQL 必须写成"操作符直接作用在列上"（ORDER BY embedding <=> ?）；
--      不能写成 ORDER BY score DESC（别名）或包一层函数 —— 实测这两种退化成 Sort + Seq Scan。
--      ⚠️ 但"相似度阈值放进 WHERE"是**例外**：2026-09-22 沙箱实测它仍是 Index Scan + Filter
--      （索引照样用于排序）⇒ 本项目仍走应用层过滤，理由是**语义**（TopK 的定义：最相似的 K 条再按
--      阈值收紧），**不是**索引。初版注释把三种写法一律说成"退化成全表扫描"，已按实测订正。
--      数据量小时这些差别都看不出来，正是最危险的那种坑 —— 判据一律先 SET enable_seqscan = off。
--      阈值改在应用层过滤（设计 §4.6 第 1、2 条）。
CREATE INDEX idx_kb_chunk_embedding_hnsw ON t_kb_chunk USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE  t_kb_chunk IS '知识库分块表：一行 = 一个分块 + 其向量（tenant_id 由 TenantLineHandler 自动注入查询条件；删文档时经外键级联删除）';
COMMENT ON COLUMN t_kb_chunk.tenant_id   IS '租户隔离列：即使它可由 document_id 推导出来也**必须冗余存** —— 拦截器只认本列名，靠 JOIN 推导等于放过注入';
COMMENT ON COLUMN t_kb_chunk.document_id IS '所属文档；ON DELETE CASCADE ⇒ 删文档即删分块（业务代码不做两次删除）';
COMMENT ON COLUMN t_kb_chunk.chunk_index IS '分块在文档内的序号，0 起；与 document_id 组成唯一约束';
COMMENT ON COLUMN t_kb_chunk.content     IS '分块原文：引用展示与喂给大模型的都是它，不做二次摘要';
COMMENT ON COLUMN t_kb_chunk.embedding   IS 'vector(768)：nomic-embed-text 的输出维度；写入走 VectorTypeHandler（setObject + Types.OTHER）';

-- -----------------------------------------------------------------------------
-- 一条已知取舍（写在这里，面试与排查都用得上；不影响本补丁的正确性）：
--
-- pgvector 的近似索引是"**先取近似 TopK、再过滤**"，而租户条件由拦截器加在 WHERE 上。
-- ⇒ 当"全表跨租户数据量"远大于"单租户数据量"时，过滤后可能凑不满 topK 条（召回下降）。
-- 本轮演示规模（单租户几百块）无影响；规模化方案（按租户分区 / tenant_id 前缀的部分索引 /
-- 把过滤改成前置过滤）记录在设计文档 §10.3，本轮**不做**。
--
-- 另外：本轮刻意**不给 content 建全文索引** —— 检索只用向量（决策 D9），
-- 混合检索（BM25 + 向量）是 §10.3 的 backlog。没有消费者的索引只是写入负担，不要顺手加。
-- -----------------------------------------------------------------------------

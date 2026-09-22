-- =============================================================================
-- 202609221100_知识库向量维度改1024.sql
--
-- ⛔⛔ 本补丁会清空知识库数据（两张表 DROP + 重建，表内全部行丢失）⛔⛔
--   原因：embedding 模型由 nomic-embed-text（768 维）换成 bge-m3（1024 维），**存量向量与新模型不在
--   同一个向量空间** —— 留着它们不是"暂时不准"，而是让检索给出错的分（比"空"更糟），必须整批重灌。
--   而本轮**不保存原始文件**（决策 D8）⇒ 库里那批分块**不可再生**，只能**重新上传**文档。
--   重灌代价很小：qa/fixtures/rag/公司年报.pdf 一份 587 块 ≈ 90 秒（587 块 ÷ 每批 16 条 ≈ 37 批；
--   耗时口径同设计 §0.3 的"验收夹具约 90 秒"）。
--   ⇒ 执行后知识库列表为**空**、问答答不出内容，都是**预期**，不是缺陷；验收要**重传文档**后再复测。
--
-- 为什么不用 ALTER COLUMN TYPE vector(1024)：
--   ① 列上已经躺着 768 维的数据，而 pgvector 的 vector 类型转换带维度校验（typmod），
--      768 → 1024 不是"补零/截断"这种能定义的动作，ALTER 必然失败；
--      （本轮**未实测**这条报错的原文 —— 补丁不允许"先试着跑一下"，但可以确定它不会成功）
--   ② 即便它能转，把 768 维向量凑成 1024 维也是废数据（不同模型的空间不能混）；
--   ③ embedding 上的 HNSW 索引与维度绑定，改维度必须连同索引一起重建。
--   ⇒ DROP + 按原样重建是唯一干净的做法。
--
-- 内容：
--   ① DROP TABLE t_kb_chunk    （子表先删 —— 它对 t_kb_document 有外键）
--   ② DROP TABLE t_kb_document （父表后删）
--   ③ 按 202609221000_初始化知识库表.sql **原样重建**两张表：列、约束、索引、COMMENT 全部一致，
--      只把 embedding 的 vector(768) 改成 vector(1024)
--   ④ 索引一并重建：tenant_id / document_id / (document_id, chunk_index) 唯一约束 / HNSW 余弦索引
--
-- ⚠️ 与代码的配对（三处必须**同一次发布**，缺一个就是"建表成功、第一次入库报类型错"或"排序错乱"）：
--     · OllamaEmbeddingService.EMBED_MODEL          nomic-embed-text → bge-m3
--     · OllamaEmbeddingService.EXPECTED_DIMENSION   768 → 1024（本文件就是这条真源的另一半）
--     · application.yml 的 rag.document-prefix / query-prefix → 空串（bge-m3 不需要任务前缀）
--   另有一处**不在本次改动范围内、但不改就跑不通**的：docker-compose 的 ollama-init 拉取清单里
--   仍只有 nomic-embed-text，需要加上 bge-m3（属 devops 的交付物）—— 否则每次向量化都是 20100。
--
-- ⚠️ 纪律（引擎会强制执行，不是靠自觉）：
--     本文件一旦执行过就**不得再修改** —— 引擎按 SHA-256 checksum 校验已应用补丁，改动任何一个
--     字符都会让下一次迁移报错终止。变更一律**新增补丁**。命名规则 YYYYMMDDHHmm_描述.sql
--     （文件名里的空格不影响引擎：它按目录扫描 + 文件名字典序排序，不走 shell 拼命令）。
--     CREATE TABLE 不写 IF NOT EXISTS（理由同 202609221000 的文件头：引擎保证只执行一次，
--     走到"表已存在"意味着出了预期之外的事，让 CREATE 直接报错更符合 fail-fast）。
--     DROP TABLE 同样**不写 IF EXISTS**，保持同一口径：这两张表一定存在 ——
--     首次迁移时 202609221000 按文件名字典序先于本文件执行，表必然已建好。
--
-- ⚠️ 执行顺序上的一个提醒：上面的 1024 是 bge-m3 的**公布值**（BAAI/bge-m3 的 config.json
--    hidden_size=1024），**本机尚未探针实测**（换模型时容器里还没有 bge-m3）。若 devops 的探针 A
--    量到的不是 1024，请**在本补丁执行之前**把本文件里的 vector(1024) 改成实测值（同时改代码里的
--    EXPECTED_DIMENSION）—— 未执行时能改，一旦执行过就不得再改，那时只能再新增一个补丁去修
--    （代价是一次多余的数据清空）。
--    探针需要模型已在容器里，所以正确顺序是：
--      改 ollama-init 清单 → 拉取 bge-m3 → 探针 A（期望 1024）→ 执行本补丁 → 重启后端 → 重传文档
--
-- 执行方式：docker compose exec -T builder db-patch-migrate（或 scripts/up.sh 第 5 步自动执行）
--          单事务包裹 —— 本文件里任何一条失败，整个补丁回滚，且不会写入 t_db_patch 记录。
--
-- 执行后值得当场看一眼的两条判据（都只读，不改数据；devops 验收用）：
--     docker exec nexus-postgres psql -U nexus -d nexus -P pager=off -c \
--       "SELECT format_type(atttypid, atttypmod) FROM pg_attribute
--         WHERE attrelid = 't_kb_chunk'::regclass AND attname = 'embedding'"   → 期望 vector(1024)
--     docker exec nexus-postgres psql -U nexus -d nexus -P pager=off -c \
--       "SELECT indexname FROM pg_indexes WHERE tablename = 't_kb_chunk'"      → 期望 5 条，含
--       t_kb_chunk_pkey / uk_kb_chunk_doc_index / idx_kb_chunk_tenant_id /
--       idx_kb_chunk_document_id / idx_kb_chunk_embedding_hnsw
--
-- 回滚（如需，**不在本文件内执行**）：历史补丁不得修改，回滚同样只能靠**新增**一个更晚的补丁
--   （把维度改回一个确认过的值 + 重灌数据）。注意回滚也是**清空数据**，代价与本补丁相同。
--
-- 附注：两张表重建后 document_id / chunk_id 的 BIGSERIAL 序列从 1 重新开始 —— 没有外部引用
--   （created_by 刻意不加外键），旧 id 也不会被任何地方记住，无影响。
-- =============================================================================

-- ①② 先删子表、再删父表（顺序不能反：t_kb_chunk.document_id 上有指向 t_kb_document 的外键）
DROP TABLE t_kb_chunk;
DROP TABLE t_kb_document;

-- ③ 重建两张表（内容与 202609221000 逐字一致，只有 embedding 的维度由 768 改为 1024）

-- 文档表
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

-- 分块表（含向量）
--    embedding 的维度 = 1024，来自 bge-m3：BAAI/bge-m3 的 config.json 是 hidden_size 1024
--    （xlm-roberta 架构，2026-09-22 取自此模型的 HuggingFace 镜像）。⚠️ 与 202609221000 里的
--    nomic-embed-text 768 一样，它是**公布值**而非本机探针实测值 —— 换模型时容器里还没有 bge-m3，
--    devops 拉取后按设计 §3.9 探针 A 复核一次。维度写错时**建表是成功的**，第一次入库才报类型错
--    （容易被误判成代码 bug）—— 这条坑 202609221000 的注释已经写过一次，换模型时正好又踩在同一个位置上。
CREATE TABLE t_kb_chunk (
    chunk_id    BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES t_tenant (tenant_id),
    -- ★ ON DELETE CASCADE：删文档 = 删其全部分块，级联由数据库保证。
    --   没有它就得靠业务代码记得删两次 —— 少一处"忘了删分块"的可能（设计 §3.3 第 2 条）。
    document_id BIGINT       NOT NULL REFERENCES t_kb_document (document_id) ON DELETE CASCADE,
    chunk_index INTEGER      NOT NULL,           -- 分块在文档内的序号，0 起（引用展示"第 N 段"用 index+1）
    content     TEXT         NOT NULL,           -- 分块原文：引用即原文，不截断、不摘要（可核对的前提）
    char_count  INTEGER      NOT NULL,           -- 本块字符数（Java String.length() 口径）
    embedding   vector(1024) NOT NULL,           -- 向量，由 bge-m3 生成
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 唯一约束挡住"同一文档重灌出重复块"这类缺陷；索引顺带服务"按文档取块"的查询
    CONSTRAINT uk_kb_chunk_doc_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_kb_chunk_tenant_id   ON t_kb_chunk (tenant_id);
CREATE INDEX idx_kb_chunk_document_id ON t_kb_chunk (document_id);

-- ★ 向量索引：操作符类 vector_cosine_ops 对应检索 SQL 里的余弦距离操作符 <=>（score = 1 - (embedding <=> q)）。
--   操作符类必须与查询用的操作符一致，否则索引**建了也用不上**：
--       · vector_cosine_ops   ← <=>   余弦（本项目）
--       · vector_l2_ops       ← <->   欧氏距离
--       · vector_ip_ops       ← <#>   内积
--   为什么是 HNSW 而不是 IVFFlat、判据怎么写（先 SET enable_seqscan = off 再 EXPLAIN）——
--   完整注释在 202609221000_初始化知识库表.sql 里，本补丁照搬同一条索引（索引定义与维度无关，
--   HNSW 不在意 768 还是 1024；但**重建表就得重建索引**，否则检索退化为全表扫描）。
CREATE INDEX idx_kb_chunk_embedding_hnsw ON t_kb_chunk USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE  t_kb_chunk IS '知识库分块表：一行 = 一个分块 + 其向量（tenant_id 由 TenantLineHandler 自动注入查询条件；删文档时经外键级联删除）';
COMMENT ON COLUMN t_kb_chunk.tenant_id   IS '租户隔离列：即使它可由 document_id 推导出来也**必须冗余存** —— 拦截器只认本列名，靠 JOIN 推导等于放过注入';
COMMENT ON COLUMN t_kb_chunk.document_id IS '所属文档；ON DELETE CASCADE ⇒ 删文档即删分块（业务代码不做两次删除）';
COMMENT ON COLUMN t_kb_chunk.chunk_index IS '分块在文档内的序号，0 起；与 document_id 组成唯一约束';
COMMENT ON COLUMN t_kb_chunk.content     IS '分块原文：引用展示与喂给大模型的都是它，不做二次摘要';
COMMENT ON COLUMN t_kb_chunk.embedding   IS 'vector(1024)：bge-m3 的输出维度（2026-09-22 换模型前是 nomic-embed-text 的 768）；写入走 VectorTypeHandler（setObject + Types.OTHER）';

-- -----------------------------------------------------------------------------
-- 表结构重建至此与 202609221000 完全一致（只差维度），那条尾注里的取舍照旧成立：
-- pgvector 的近似索引是"先取近似 TopK、再过滤"，而租户条件由拦截器加在 WHERE 上 ⇒ 跨租户数据量
-- 远大于单租户时召回会下降。本轮演示规模（单租户几百块）无影响，规模化方案记在设计 §10.3。
-- 同样照旧**不给 content 建全文索引**（检索只用向量，决策 D9；混合检索是 §10.3 的 backlog）。
-- -----------------------------------------------------------------------------

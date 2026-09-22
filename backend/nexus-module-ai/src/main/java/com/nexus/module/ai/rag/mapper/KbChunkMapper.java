package com.nexus.module.ai.rag.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.nexus.module.ai.rag.model.ChunkHit;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库分块 Mapper（{@code t_kb_chunk}）：<b>本阶段唯一"必须被看懂"的 SQL 住在这个文件里</b>。
 *
 * <h2>为什么<b>不继承</b> {@code BaseMapper}（设计 §4.1 的理由）</h2>
 * 通用 CRUD 在这里帮不上忙，反而会让人误以为"表映射是完整的"：
 * <ul>
 *     <li>本表的 {@code embedding} 列<b>不是 JDBC 标准类型</b>，实体面映射不了它（决策 D2 的代价）；
 *         若继承 {@code BaseMapper} 就必须再造一个 {@code KbChunk} 实体并解释"它为什么不映射向量"，
 *         而那个实体的每个通用方法（{@code selectById}/{@code updateById}/…）<b>都没有调用方</b>；</li>
 *     <li>真正需要的两条语句都是自定义的（批量插入 / 向量检索），通用 CRUD 一条都表达不了；</li>
 *     <li>因此本接口只有"薄薄一层"，全部内容都可被逐行读懂 —— 这正是设计 D1/D2 要展示的东西。</li>
 * </ul>
 *
 * <h2>租户隔离在两条语句上的形态<b>不一样</b>（★ 这是本段唯一的架构级偏离，务必读完）</h2>
 * <ul>
 *     <li>{@link #insertChunk}：语句里<b>没有</b> {@code tenant_id} —— 由 {@code TenantLineHandler}
 *         补列与值。<b>已实测</b>：改写后每个 VALUES 组都带上 {@code tenant_id}，且
 *         {@code CAST(? AS vector)} 不影响解析。<b>结构化保证成立</b>；</li>
 *     <li>{@link #search}：语句里<b>有一行手写的</b> {@code tenant_id}，且方法上标了 MP 的
 *         {@link InterceptorIgnore}。<b>这不是风格选择，是 jsqlparser 与 pgvector 的硬冲突逼出来的</b>
 *         —— 完整的实测证据与取舍见该方法的 javadoc。</li>
 * </ul>
 * 即：D11 的"SQL 里不手写 tenant_id"在本阶段<b>只对检索语句退让了一处</b>，退让的理由与代价都写在明处；
 * 其余三条写入/删除路径仍完全由拦截器保证。
 *
 * @author nexus
 */
public interface KbChunkMapper {

    /**
     * 批量插入一批分块（<b>一条语句插多行</b>，减少往返）。
     *
     * <p><b>一条 SQL 插一批而不是逐块插</b>：一份 3000 块的文档逐块插就是 3000 次往返，
     * 而这一段处在<b>持有数据库连接的事务里</b>（设计 D7 的已知代价），往返次数直接乘在事务时长上。
     * 每批 16 行由服务层决定（{@code KbDocumentServiceImpl} 的分批常量）。
     *
     * <p>⚠️ 全参数用 {@code #{}} 绑定，<b>绝不 {@code ${}}</b>：分块正文是用户上传的内容，
     * {@code ${}} 会把正文拼进 SQL 文本 —— 那既是注入面，也会因为正文里的引号直接语法错误。
     *
     * <p>⚠️ {@code tenant_id} <b>不在列清单里</b>：由拦截器补列与值（见类注释）。
     *
     * @param documentId 所属文档 ID（同一批内共用；文档 ID 由上传链路先插 {@code t_kb_document} 得到）
     * @param chunks     本批分块（非空；顺序即 {@code chunk_index} 的来源）
     * @return 实际插入行数（服务层会核对它是否等于 {@code chunks.size()}）
     */
    @Insert("""
            <script>
            INSERT INTO t_kb_chunk (document_id, chunk_index, content, char_count, embedding)
            VALUES
            <foreach collection="chunks" item="chunk" separator=",">
                (#{documentId}, #{chunk.chunkIndex}, #{chunk.content}, #{chunk.charCount},
                 CAST(#{chunk.vectorLiteral, typeHandler=com.nexus.module.ai.rag.mapper.VectorTypeHandler}
                     AS vector))
            </foreach>
            </script>
            """)
    int insertChunk(@Param("documentId") long documentId, @Param("chunks") List<ChunkRow> chunks);

    /**
     * 向量检索：取与查询向量最相似的 {@code topK} 条分块，<b>按相似度降序</b>。
     *
     * <h2>★ 为什么本方法要绕开租户拦截器（{@link InterceptorIgnore}），并手写 {@code tenant_id}</h2>
     * <b>起因</b>：pgvector 的余弦距离操作符 {@code <=>} 让 MP 3.5.9 内置的 jsqlparser 5.0 解析失败 ——
     * 它把 {@code <=>} 切成了 {@code <=}（{@code OP_MINORTHANEQUALS}）再遇到一个孤立的 {@code >}：
     * <pre>
     * net.sf.jsqlparser.parser.ParseException: Encountered unexpected token: "<=" &lt;OP_MINORTHANEQUALS&gt;
     * </pre>
     * 而 {@code TenantLineInnerInterceptor} 对<b>每一条</b>语句都要先
     * {@code CCJSqlParserUtil.parse(...)} 再改写 ⇒ 解析失败时它<b>不是"没注入"，而是直接抛
     * {@code MybatisPlusException}}</b>，整条问答链路每次请求都会 500。这是设计 §9 风险 4 的实测落地
     * （2026-09-22，离线探针：用真实的 MP 拦截器 + jsqlparser 5.0 驱动本语句）。
     *
     * <h2>实测结论（四条，决定了本方法现在的写法）</h2>
     * <ol>
     *     <li><b>元凶只有 {@code <=>}</b>：{@code CAST(? AS vector)} 单独出现时解析与注入都正常
     *         （对照用例：{@code SELECT CAST(? AS vector) AS v FROM t_kb_chunk c WHERE ...}
     *         → 注入成功）。故设计 §9 的退路①（把 {@code CAST} 换成 {@code ?::vector}）
     *         <b>不对症</b>：CAST 从来不是问题；</li>
     *     <li><b>换成别的距离操作符没有出路</b>：{@code <->}（L2）与 {@code <#>}（内积）确实能被
     *         jsqlparser 解析，但 HNSW 索引的操作符类必须与查询操作符一致
     *         （{@code idx_kb_chunk_embedding_hnsw} 建的是 {@code vector_cosine_ops}）——
     *         换操作符等于同时改契约里的 {@code score = 1 - (embedding <=> query)} 定义与已应用的补丁，
     *         两样都不能动；</li>
     *     <li><b>项目自己的 {@code @IgnoreTenant} 救不了这里</b>：它是 AOP + {@code ThreadLocal}
     *         的实现，效果是让 {@code TenantLineHandler.ignoreTable()} 返回 {@code true} ——
     *         而 MP 是<b>先解析、后咨询 ignoreTable</b>（{@code parserSingle} 在
     *         {@code processSelect} 之前），所以解析照旧失败。实测：把 handler 换成"ignoreTable 恒真"，
     *         本语句仍然抛 {@code MybatisPlusException}；</li>
     *     <li><b>只有 MP 自己的 {@link InterceptorIgnore} 能绕开</b>：它在
     *         {@code InterceptorIgnoreHelper.willIgnoreTenantLine(msId)} 处<b>短路在解析之前</b>，
     *         语句原样通过（实测：同一 SQL 加注解后不再抛异常、也不被改写）。
     *         这也是设计 §9 退路② 的形态，只是注解要用<b>MP 的那个</b>，不是项目的 {@code @IgnoreTenant}。</li>
     * </ol>
     *
     * <h2>这条退路的代价，明写在下面（拿它换来的是一条能跑通的 HNSW 检索）</h2>
     * <ol>
     *     <li><b>"SQL 里不手写 tenant_id"这条结构化保证在本方法上失效</b>：隔离改由
     *         {@link #search(long, String, int)} 的 {@code tenantId} 参数承载。两条护栏补上这个缺口：
     *         ① 参数类型是<b>基本类型 {@code long}</b>（不可能"忘了传"却编译通过）；
     *         ② 调用方（{@code KbAskServiceImpl}）从 {@code TenantContext} 取值，
     *         无上下文时<b>fail-closed 抛异常</b>，绝不退化成"查全部租户"。
     *         ⚠️ 但"参数对不对"仍是<b>人手写对</b>—— 所以 TC-03 <b>必须</b>有一条
     *         "A 租户上传的文档，B 租户提问不得命中"的用例（设计 §9 退路② 明确要求）；
     *     <li>本语句的 mapper debug 日志里 {@code tenant_id} 表现为<b>绑定参数</b>
     *         （{@code WHERE c.tenant_id = ? AND d.tenant_id = ?}），
     *         而其余语句（列表 / 插入 / 删除）里拦截器注入的是<b>字面量</b>
     *         （{@code WHERE tenant_id = 1}）—— 两者形态不同，别拿一种去核对另一种。</li>
     * </ol>
     *
     * <h2>三条写在 SQL 里的纪律（逐条对应设计 §4.6，改动前请先读完）</h2>
     * <ol>
     *     <li><b>{@code ORDER BY} 里操作符必须直接作用在列上</b>（{@code c.embedding <=> ...}）。
     *         写成 {@code ORDER BY score DESC}（别名）或包一层函数，HNSW 索引
     *         （{@code idx_kb_chunk_embedding_hnsw}，{@code vector_cosine_ops}）就<b>用不上</b>，
     *         退化成全表扫描 + 排序 —— 数据量小时看不出差别，正是最危险的那种坑。
     *         判据：{@code EXPLAIN} 必须出现 {@code Index Scan using idx_kb_chunk_embedding_hnsw};</li>
     *     <li><b>相似度阈值不进 {@code WHERE}</b>（{@code WHERE 1 - (embedding <=> ?) >= ?} 同样让索引失效）。
     *         阈值在应用层过滤（{@code KbAskServiceImpl}）：TopK 的定义就是"最相似的 K 条"，
     *         再按阈值收紧，语义完全正确；</li>
     *     <li><b>向量参数出现两次是刻意的</b>：SELECT 里算 {@code score}、ORDER BY 里排序。
     *         不要为了"只绑一次"改成派生表 / CTE —— 那会让 ORDER BY 依赖派生列，重新引入第 1 条的索引问题。
     *         两次绑定的是同一个字符串，无副作用。</li>
     * </ol>
     *
     * <p>{@code AS score} 这个别名只服务于映射（表达式列在结果集里叫 {@code ?column?}），
     * <b>不是</b>给 ORDER BY 用的 —— 两者别混。
     *
     * <p>{@code JOIN t_kb_document d} 只为取 {@code file_name}。两张表<b>都</b>判 {@code tenant_id}：
     * JOIN 键是全局唯一的 {@code document_id}，理论上判一侧就够 —— 但两侧都判意味着
     * "分块行或文档行任一写错租户"都不会漏出数据（多一个已建索引上的等值条件，代价为零）。
     *
     * <p>返回类型 {@link ChunkHit} 的形状即 SELECT 的列形状（列顺序与组件顺序一致），且用
     * {@link ConstructorArgs} 显式声明映射 —— record 不走"无参构造 + setter"那条路，
     * 显式声明可以让它<b>不依赖</b> {@code arg-name-based-constructor-auto-mapping} 这个全局开关的取值。
     *
     * @param tenantId    当前租户 ID（调用方取自 {@code TenantContext}；<b>本方法唯一的隔离依据</b>，
     *                    见上文"代价"第 1 条）
     * @param queryVector 查询向量的文本形态（{@code [0.1,0.2,...]}，由
     *                    {@link VectorTypeHandler#toVectorLiteral(float[])} 生成）
     * @param topK        取多少条（调用方已按 {@code 1..max-top-k} 校验）
     * @return 命中片段（按相似度降序，≤ {@code topK} 条）；无数据时为空列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @ConstructorArgs({
            @Arg(column = "document_id", javaType = long.class),
            @Arg(column = "file_name", javaType = String.class),
            @Arg(column = "chunk_index", javaType = int.class),
            @Arg(column = "content", javaType = String.class),
            @Arg(column = "char_count", javaType = int.class),
            @Arg(column = "score", javaType = double.class)
    })
    @Select("""
            SELECT c.document_id,
                   d.file_name,
                   c.chunk_index,
                   c.content,
                   c.char_count,
                   1 - (c.embedding <=>
                        CAST(#{queryVector, typeHandler=com.nexus.module.ai.rag.mapper.VectorTypeHandler}
                            AS vector)) AS score
            FROM t_kb_chunk c
            JOIN t_kb_document d ON d.document_id = c.document_id
            WHERE c.tenant_id = #{tenantId}
              AND d.tenant_id = #{tenantId}
            ORDER BY c.embedding <=>
                     CAST(#{queryVector, typeHandler=com.nexus.module.ai.rag.mapper.VectorTypeHandler}
                         AS vector)
            LIMIT #{topK}
            """)
    List<ChunkHit> search(@Param("tenantId") long tenantId,
                          @Param("queryVector") String queryVector,
                          @Param("topK") int topK);

    /**
     * 待插入的一行分块（{@link #insertChunk} 的入参元素）。
     *
     * <p><b>为什么不做成五个并列的 {@code List} 参数</b>：那要求调用方"五个列表长度一致、下标对齐"，
     * 是典型的一处写错就静默错位（正文与向量对不上，且日志里完全看不出来）的形态。
     * 一个不可变的小记录把"一行"钉成一个对象，长度天然一致。
     *
     * <p>{@code vectorLiteral} 由 {@link VectorTypeHandler#toVectorLiteral(float[])} 生成：
     * 本类<b>刻意不收 {@code float[]}</b> —— Mapper 的参数类型应当就是 SQL 需要的形态，
     * 让"浮点数组 → vector 文本"的转换只发生在一个地方（见该类注释）。
     *
     * @param chunkIndex    该块在文档内的序号，<b>0 起</b>（顺序即 {@code chunk_index}，设计 §4.4 规则 1）
     * @param content       分块原文（引用即原文，不截断、不摘要）
     * @param charCount     本块字符数（{@code String.length()} 口径，与 {@code TextChunker} 同源）
     * @param vectorLiteral 向量文本，形如 {@code [0.1,0.2,...]}（768 维）
     * @author nexus
     */
    record ChunkRow(int chunkIndex, String content, int charCount, String vectorLiteral) {
    }
}

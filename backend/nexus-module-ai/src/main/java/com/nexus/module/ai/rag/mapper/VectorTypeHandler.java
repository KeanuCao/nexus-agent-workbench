package com.nexus.module.ai.rag.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * {@code vector} 列与 JDBC 的对接（设计 §4.5）：<b>把向量的文本形态交给 PostgreSQL 自己解析</b>。
 *
 * <h2>写入为什么是 {@code setObject(i, text, Types.OTHER)}，而不是 {@code setString}</h2>
 * <ul>
 *     <li>{@code setString} 会把参数标成 {@code varchar}。varchar 能不能隐式转 {@code vector}
 *         取决于 pgvector 是否定义了该 cast —— <b>不确定 ⇒ 不赌</b>。赌错的形态是插入时报
 *         "column embedding is of type vector but expression is of type character varying"，
 *         而那要等到第一次上传才现形；</li>
 *     <li>{@code Types.OTHER} 让参数以"类型未定"发出去，由服务端按<b>上下文</b>推断：
 *         语句里配的是 {@code CAST(? AS vector)}（见 {@code KbChunkMapper} 的两条语句），
 *         于是服务端按 {@code vector} 推断并调用 {@code vector_in} 解析这段文本。</li>
 * </ul>
 * 备选是给连接串加 {@code stringtype=unspecified} —— 能做到同一件事，但它会改变<b>整条连接</b>
 * 上所有字符串参数的类型行为，代价远大于收益（本项目不用它）。
 *
 * <h2>为什么没有"读"方向</h2>
 * 检索<b>永不返回向量</b>：SQL 只选出 {@code content} 与算好的 {@code score}（{@code ChunkHit} 的
 * 六个字段里没有向量）。这也是 {@code KbChunk} 实体不存在的原因之一（决策 D2 的代价落地处）。
 * 因此三个 {@code getNullableResult} 一律<b>抛异常而不是返回 {@code null}</b>：
 * 一个静默的 {@code null} 会让"我们读了一个不该读的列"变成下游的 NPE，把排查方向带偏。
 *
 * <h2>{@link #toVectorLiteral(float[])} 为什么也放在这里</h2>
 * 写入方向上"浮点数组 → 文本"与"文本 → 参数"是同一件事的两半，放在一个类里可以保证
 * <b>格式只有一个出处</b>：入库（{@code KbDocumentServiceImpl}）与查询（{@code KbAskServiceImpl}）
 * 都调它，不可能出现"入库用 {} 而查询用 ()"这种两边都合法、只是检索全空的错。
 *
 * <h2>刻意<b>不</b>注册进 {@code TypeHandlerRegistry}</h2>
 * 本类只在 SQL 的 {@code #{..., typeHandler=...}} 里按全限定名被引用（两条语句共三处），
 * <b>没有全局生效的意图</b>：若按 {@code @MappedTypes(String.class)} 注册成全局处理器，
 * 它会接管全项目所有 {@code String} 参数的绑定 —— 那会把"向量列怎么写"变成一个影响面
 * 无声扩大的开关。
 *
 * @author nexus
 */
public class VectorTypeHandler extends BaseTypeHandler<String> {

    /**
     * 写入方向：把向量文本作为"类型未定"参数交给服务端（见类注释）。
     *
     * @param ps        语句
     * @param i         参数下标（1 起）
     * @param parameter 向量文本，形如 {@code [0.1,0.2,...]}
     * @param jdbcType  调用方声明的 JDBC 类型（本类不关心：类型由 {@code CAST} 决定）
     * @throws SQLException 驱动层失败
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /**
     * 不可达：检索不选向量列（见类注释）。
     *
     * @throws UnsupportedOperationException 总是抛出
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) {
        throw unsupported("getNullableResult(ResultSet, String)");
    }

    /**
     * 不可达：检索不选向量列（见类注释）。
     *
     * @throws UnsupportedOperationException 总是抛出
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) {
        throw unsupported("getNullableResult(ResultSet, int)");
    }

    /**
     * 不可达：检索不选向量列（见类注释）。
     *
     * @throws UnsupportedOperationException 总是抛出
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) {
        throw unsupported("getNullableResult(CallableStatement, int)");
    }

    /**
     * 浮点数组 → PostgreSQL {@code vector} 字面量（形如 {@code [0.1,0.2]}）。
     *
     * <p>用 {@code StringBuilder} 手工拼接而不是 {@code Arrays.toString} + 替换：
     * 后者的产物是 {@code [0.1, 0.2]}（带空格），虽然 pgvector 也接受，但"格式靠字符串替换凑对"
     * 正是下次改动最容易破的地方。这里逐个数追加，格式一目了然。
     *
     * <p>{@code float} 直接用 {@code append} 走 {@code Float.toString}：它是<b>最短可往返</b>表示
     * （{@code 0.1f} → {@code "0.1"}，再解析回来是同一个 float），既不会丢精度也不会出现
     * {@code 0.10000000149011612} 那种噪音。正文里的 {@code NaN}/{@code Infinity} 不在这里拦：
     * 上游向量已由 {@code OllamaEmbeddingService} 逐条校验形状，真出现 NaN 时 PostgreSQL 会拒绝
     * 并让整个事务回滚 —— 那是正确的 fail-fast，多一层检查只会多一处可能写错的判断。
     *
     * @param vector 向量（非 {@code null}）
     * @return 字面量文本
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12 + 2);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    /**
     * 构造"不该发生"的统一异常。
     *
     * @param method 被调用的方法签名
     * @return 异常实例
     */
    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                "VectorTypeHandler 只支持写入方向（设计 §4.5）：检索不返回向量列，"
                        + "走到 " + method + " 说明有人选了 embedding 列 —— 那是代码缺陷，不是数据问题");
    }
}

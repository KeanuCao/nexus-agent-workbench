package com.nexus.infrastructure.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.nexus.common.exception.SystemException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 多租户条件注入器：把当前租户拼成 {@code tenant_id = ?} 交给 MyBatis-Plus 改写 SQL。
 *
 * <p><b>核心决策：fail-closed（设计决策 D3）。</b>
 * 没有租户上下文、又没有 {@link IgnoreTenant} 豁免时，本类<b>抛异常</b>，
 * 而不是返回 {@code null}（MP 会拼出 {@code tenant_id = null}，等于查不到数据但也可能被优化成恒真）
 * 或跳过条件（等于返回全租户数据）。两种"温和"做法共同的后果是<b>静默漏租户</b> ——
 * 一条忘记加豁免的查询会跨租户返回全表数据，而这正是多租户最危险的失效形态。
 *
 * <p>fail-closed 的取舍：漏标 {@code @IgnoreTenant} 会让那条链路直接 500
 * （例如登录：按 username 查用户时还没有租户上下文）。暴露得早、暴露得响，
 * 换来的是"数据泄露"在结构上不可能发生 —— 这笔交换是划算的。
 *
 * <p>哪些表天然豁免（{@link #ignoreTable(String)}）：
 * <ul>
 *     <li>{@code t_tenant}：租户表本身。<b>它有 {@code tenant_id} 列，但那是主键</b>
 *         —— 表示"这个租户自己的身份"，不是"这行属于哪个租户"的判别列。
 *         注入 {@code tenant_id = 当前租户} 会把它变成"用自己筛自己"，
 *         语义从"查租户列表"塌缩成"只能查到自己"；</li>
 *     <li>{@code t_db_patch}：迁移记录表是部署期元数据，与租户无关，
 *         且由 {@code PatchCli} 在<b>应用启动之前</b>写入，那时更没有任何租户上下文。</li>
 * </ul>
 * 这两张表是<b>结构性豁免</b>（表本身无租户维度），与 {@link IgnoreTenant} 的<b>调用级豁免</b>
 * （同一张表，某些方法要跨租户查）是两件事，分开表达。
 *
 * @author nexus
 */
@Component
public class TenantLineHandlerImpl implements TenantLineHandler {

    /** 租户隔离列名 —— 与 db-patch/202609131010_初始化用户表.sql 的 {@code t_user.tenant_id} 一致。 */
    public static final String TENANT_ID_COLUMN = "tenant_id";

    /** 结构性豁免表（见类注释）：这些表不存在租户维度，永远不注入 tenant_id 条件。 */
    private static final Set<String> STRUCTURALLY_IGNORED_TABLES = Set.of("t_tenant", "t_db_patch");

    /**
     * 当前租户 ID 的 SQL 表达式 —— 由 MP 拼成 {@code tenant_id = <返回值>}。
     *
     * @return 当前租户的 {@code LongValue}
     * @throws SystemException 无租户上下文且未豁免时抛出（fail-closed，见类注释）
     */
    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 这里刻意抛"系统异常"而非"业务异常"：漏标 @IgnoreTenant 是编码缺陷，
            // 不是用户可以纠正的输入问题 —— 应当以 500 + 服务端堆栈暴露给开发/运维，
            // 而不是以 200 + 业务话术糊弄过去（设计 §3 D3 的"启动即报错的 500"）
            throw new SystemException("多租户 fail-closed：当前查询无租户上下文且未标注 @IgnoreTenant，"
                    + "已拒绝执行以避免跨租户数据泄露");
        }
        return new LongValue(tenantId);
    }

    /**
     * 租户列名。
     *
     * @return {@code tenant_id}
     */
    @Override
    public String getTenantIdColumn() {
        return TENANT_ID_COLUMN;
    }

    /**
     * 是否跳过该表的租户条件注入。
     *
     * <p>调用时机：MP 在拼条件时<b>先</b>问本方法、<b>后</b>取 {@link #getTenantId()} ——
     * 因此"调用级豁免"（{@link TenantContext#isIgnored()}）与"结构性豁免"都必须在这里返回 {@code true}，
     * 否则 {@link #getTenantId()} 会因无上下文而 fail-closed。
     *
     * @param tableName SQL 中出现的表名
     * @return {@code true} 表示本次查询不注入租户条件
     */
    @Override
    public boolean ignoreTable(String tableName) {
        if (TenantContext.isIgnored()) {
            return true;
        }
        return tableName != null
                && STRUCTURALLY_IGNORED_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
    }
}

/**
 * 多租户隔离（阶段1 已落地，设计依据：docs/design/01-多租户与认证.md §3 D3/D4、§6.1）。
 *
 * <p>本包内容与各自职责：
 * <ul>
 *     <li>{@code TenantLineHandlerImpl}：MyBatis-Plus 的 {@code TenantLineHandler} 实现，
 *         对 SQL 自动追加 {@code tenant_id} 条件；<b>fail-closed</b> ——
 *         无租户上下文且未豁免时抛异常，绝不静默放行（决策 D3）；</li>
 *     <li>{@code TenantContext}：请求级租户上下文的 {@code ThreadLocal} 持有者
 *         （userId / tenantId / tokenId + 豁免计数），<b>必须在 finally 清理</b>；</li>
 *     <li>{@code IgnoreTenant} + {@code IgnoreTenantAspect}：自研豁免注解与切面
 *         （决策 D4），豁免时 {@code log.warn} 留痕，使"每一次破例"可追溯；</li>
 *     <li>装配：{@code com.nexus.infrastructure.config.MybatisPlusConfig}。</li>
 * </ul>
 *
 * <p><b>两类豁免不要混用</b>：结构性豁免（表本身无 tenant_id 列，如 {@code t_tenant} /
 * {@code t_db_patch}）按表声明在 {@code TenantLineHandlerImpl} 里；
 * 调用级豁免（同一张表某些方法要跨租户查）才用 {@code @IgnoreTenant}，且必须写明原因。
 *
 * @author nexus
 */
package com.nexus.infrastructure.tenant;

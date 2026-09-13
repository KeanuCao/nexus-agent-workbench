package com.nexus.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.module.system.entity.Tenant;

/**
 * 租户 Mapper（{@code t_tenant}）。
 *
 * <p><b>为什么本接口没有任何 {@code @IgnoreTenant} 标注</b>：
 * {@code t_tenant} 属于 {@code TenantLineHandlerImpl} 里的<b>结构性豁免表</b> ——
 * 它没有 {@code tenant_id} 列（主键 {@code tenant_id} 是"租户自己的 ID"），
 * 注入租户条件会变成"用自己筛自己"。这类豁免按表声明一次即可，无需每个方法重复标注。
 *
 * <p>与<b>调用级豁免</b>（{@link UserMapper#selectByUsername}）的区别：
 * 那是"同一张表，某些方法要跨租户查"，必须逐方法表达并留痕 —— 两者不要混用。
 *
 * <p>阶段1 只用 {@code selectById}（登录时校验租户状态、取租户名称）；
 * 租户的增删改查属阶段2 之后的运维功能。
 *
 * @author nexus
 */
public interface TenantMapper extends BaseMapper<Tenant> {
}

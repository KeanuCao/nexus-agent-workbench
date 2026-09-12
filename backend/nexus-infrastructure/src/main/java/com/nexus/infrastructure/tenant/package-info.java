/**
 * 多租户隔离（阶段1 填充）。
 *
 * <p>计划内容（见 docs/task/task.2+多租户认证.md 与 CLAUDE.md 宪法约束）：
 * <ul>
 *     <li>{@code TenantLineHandler} 实现：基于 MyBatis-Plus 的租户行级插件，
 *         对 SQL 自动追加 {@code tenant_id} 条件；</li>
 *     <li>{@code @IgnoreTenant} 注解 + 拦截逻辑：标注在 Mapper 方法上跳过租户注入
 *         （如系统级字典表、补丁记录表 {@code t_db_patch}）；</li>
 *     <li>当前租户上下文持有者：从登录态（JWT / Redis 会话）取 {@code tenantId}，
 *         由拦截器在请求进入时绑定、请求结束时清理。</li>
 * </ul>
 *
 * <p>阶段0 状态：仅占位包结构，保证 {@code mvn clean install} 链路可编译，不含实现。
 *
 * @author nexus
 */
package com.nexus.infrastructure.tenant;

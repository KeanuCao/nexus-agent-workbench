/**
 * 认证与会话（阶段1 填充）。
 *
 * <p>计划内容（见 docs/task/task.2+多租户认证.md）：
 * <ul>
 *     <li>JWT 签发与校验工具：登录成功签发 Token，携带 {@code userId / tenantId / 角色}；</li>
 *     <li>认证过滤器：从 {@code Authorization: Bearer <token>} 解析登录态并写入上下文；</li>
 *     <li>会话与租户信息缓存：Redis 存储 Token 与租户元数据（Redis 7 已在 compose 就绪）。</li>
 * </ul>
 *
 * <p>阶段0 状态：仅占位包结构，不含实现；依赖已由本模块 POM 备齐（设计文档 §5.1）。
 *
 * @author nexus
 */
package com.nexus.infrastructure.security;

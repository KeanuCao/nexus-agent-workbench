/**
 * 认证与会话（阶段1 已落地，设计依据：docs/design/01-多租户与认证.md §3 D1/D5/D6、§6.1）。
 *
 * <p>本包内容与各自职责：
 * <ul>
 *     <li>{@code JwtUtil} + {@code JwtProperties}：JWT 签发与校验（jjwt 0.12.x）；
 *         token 带 {@code jti}、{@code tenantId}；解析失败一律抛异常而非返回 null；</li>
 *     <li>{@code TokenStore}：Redis 登录态白名单（{@code nexus:auth:token:{jti}}，TTL = token 有效期）
 *          —— 让"登出即失效"成为可能，这是纯 JWT 做不到的（决策 D1）；</li>
 *     <li>{@code JwtAuthenticationFilter}：{@code OncePerRequestFilter}，
 *         白名单放行、其余一律需要 token；鉴权通过后写入 {@code TenantContext}，
 *         并在 {@code finally} 清理；401 响应体在此手工序列化（MVC 之前，advice 看不到）；</li>
 *     <li>{@code SecurityProperties}：白名单配置（{@code nexus.security.whitelist}）。</li>
 * </ul>
 *
 * <p>本包<b>不依赖</b> {@code spring-boot-starter-security}（决策 D5）：
 * 只引 {@code spring-security-crypto} 取 BCrypt（在 nexus-module-system 使用），
 * 请求链完全由本包的自研过滤器掌控。
 *
 * @author nexus
 */
package com.nexus.infrastructure.security;

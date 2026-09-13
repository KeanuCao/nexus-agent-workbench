package com.nexus.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（jjwt 0.12.x 的 builder / parser API）。
 *
 * <p>token 里放什么、不放什么（设计决策 D1）：
 * <table border="1">
 *     <caption>claims 清单</caption>
 *     <tr><th>claim</th><th>内容</th><th>用途</th></tr>
 *     <tr><td>{@code sub}</td><td>userId</td><td>认人</td></tr>
 *     <tr><td>{@code jti}</td><td>UUID</td><td>Redis 白名单的键 —— 登出让 token "立刻失效"就靠它</td></tr>
 *     <tr><td>{@code tenantId}</td><td>租户 ID</td><td>建立租户上下文（多租户隔离的依据）</td></tr>
 *     <tr><td>{@code username}</td><td>登录名</td><td>排查与日志可读性</td></tr>
 *     <tr><td>{@code iat} / {@code exp}</td><td>签发/过期时刻</td><td>由 jjwt 生成并校验</td></tr>
 * </table>
 * <b>不放角色与权限</b>：那是阶段2 之后的事，且权限放 token 里会带来"改权限要等 token 过期"的经典问题。
 *
 * <p><b>为什么"解析失败必须抛异常"而不是返回 {@code null}</b>：
 * 返回 null 会让调用方（过滤器）多出一个可以忘记判断的分支 ——
 * 一旦漏判，伪造的 token 就成了有效登录态。抛异常则把"失败"变成不可忽略的路径。
 * 本类的 {@link #parse(String)} 因此<b>故意</b>把 jjwt 的异常往上抛（都是 RuntimeException）：
 * <ul>
 *     <li>{@link ExpiredJwtException}：已过期（过滤器翻成 40102，提示"重新登录"）；</li>
 *     <li>{@link MalformedJwtException} / jjwt 的 {@code security.SignatureException}：格式非法 / 签名不匹配
 *         （过滤器翻成 40101，是"有人在动 token"的信号）。</li>
 * </ul>
 *
 * <p>密钥在构造期一次性派生（{@link Keys#hmacShaKeyFor(byte[])}）：配错密钥要在<b>启动时</b>炸掉，
 * 而不是等到第一次登录才暴露。
 *
 * @author nexus
 */
@Component
public class JwtUtil {

    /** 租户 ID 的 claim 名。 */
    private static final String CLAIM_TENANT_ID = "tenantId";

    /** 用户名的 claim 名。 */
    private static final String CLAIM_USERNAME = "username";

    /** HS256 要求的最小密钥长度（位）—— jjwt 会强制校验，此处仅用于给出可读的错误提示。 */
    private static final int MIN_SECRET_BITS = 256;

    private final JwtProperties properties;

    /** HS256 签名密钥（由配置的 secret 派生，只在本对象内部使用，不外泄）。 */
    private final SecretKey secretKey;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        this.secretKey = buildSecretKey(properties.getSecret());
    }

    /**
     * 签发 token。
     *
     * @param userId   用户 ID（写入 {@code sub}）
     * @param tenantId 租户 ID（写入 {@code tenantId} claim）
     * @param username 登录名（写入 {@code username} claim，便于排查）
     * @param tokenId  token 唯一标识 {@code jti}（调用方生成 UUID，与 Redis 白名单的键一致）
     * @return 紧凑序列化后的 JWS 字符串
     */
    public String issue(Long userId, Long tenantId, String username, String tokenId) {
        Instant issuedAt = Instant.now();
        Instant expiration = issuedAt.plusSeconds(properties.getExpireSeconds());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(tokenId)
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验 token（签名、有效期均由 jjwt 完成）。
     *
     * @param token JWS 字符串
     * @return 解析出的登录态
     * @throws ExpiredJwtException token 已过期
     * @throws JwtException        token 非法（格式错、签名不匹配、算法不符等）
     * @throws IllegalArgumentException token 为 {@code null} 或空（jjwt 的入参校验）
     */
    public TokenPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = toLong(claims.getSubject());
        Long tenantId = toLong(claims.get(CLAIM_TENANT_ID));
        if (userId == null || tenantId == null) {
            // 签名正确却缺必需 claim：只能是"用同一密钥签了别的东西"，或我们自己签发时写漏了字段。
            // 两种情形都不该被当成合法登录态，统一按"非法 token"处理
            throw new MalformedJwtException("token 缺少必需 claim：sub / " + CLAIM_TENANT_ID);
        }

        return new TokenPayload(userId, tenantId, claims.get(CLAIM_USERNAME, String.class), claims.getId());
    }

    /**
     * 供 {@code AuthService} 计算响应体 {@code expiresIn} 与 Redis TTL。
     *
     * @return token 有效期（秒）
     */
    public long getExpireSeconds() {
        return properties.getExpireSeconds();
    }

    /**
     * 由配置的 secret 派生 HS256 密钥。
     *
     * @param secret 配置的密钥字符串
     * @return HS256 密钥
     */
    private static SecretKey buildSecretKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("nexus.jwt.secret 未配置：请在 application.yml 或环境变量 NEXUS_JWT_SECRET 中提供");
        }
        try {
            return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        } catch (WeakKeyException ex) {
            // 启动即失败是有意的：密钥太短属于配置错误，不该拖到第一次登录才以 500 的形式暴露
            throw new IllegalStateException("nexus.jwt.secret 长度不足：HS256 至少需要 " + (MIN_SECRET_BITS / 8) + " 字节", ex);
        }
    }

    /**
     * 把 claim 值归一化为 {@code Long}。
     *
     * <p>为什么不用 {@code claims.get(name, Long.class)}：JSON 数字经 Jackson 反序列化后，
     * 小数值可能是 {@code Integer}，依赖 jjwt 内部的类型转换不如自己显式归一化来得确定。
     *
     * @param raw claim 原始值
     * @return {@code Long} 值；无法转换或缺失时为 {@code null}
     */
    private static Long toLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Long.valueOf(text);
        }
        return null;
    }

    /**
     * 一份 token 携带的登录态（解析结果）。
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @param username 登录名
     * @param tokenId  JWT 的 {@code jti}
     * @author nexus
     */
    public record TokenPayload(Long userId, Long tenantId, String username, String tokenId) {
    }
}

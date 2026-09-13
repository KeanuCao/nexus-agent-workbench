package com.nexus.infrastructure.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JwtUtil} 单元测试。
 *
 * <p>测试策略：回环用被测类自己的 {@code issue}；<b>过期/篡改/伪造三种"坏 token"则用 jjwt 直接构造</b> ——
 * 理由是不让"签发逻辑"成为验证"解析逻辑"的前提（否则签发里的某个 bug 会同时污染两侧，
 * 测试照样全绿）。构造时用的密钥与配置里的 secret 相同或不同，分别对应"我们的 token"与"别人的 token"。
 *
 * <p>核心断言是设计文档 §8 点名的一条：<b>{@code parse} 对伪造 token 必须抛异常，而不是返回 null</b>
 * —— 返回 null 会给出一个"可以忘记判断"的分支，漏判即等于接受伪造凭据。
 *
 * @author nexus
 */
class JwtUtilTest {

    /** 测试密钥：必须 ≥ 32 字节（HS256 的硬要求）。 */
    private static final String SECRET = "nexus-unit-test-secret-0123456789-abcdefghij";

    /** 另一个密钥，用于伪造"别人签的 token"。 */
    private static final String OTHER_SECRET = "another-party-secret-9876543210-zyxwvutsrq";

    private JwtProperties properties;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireSeconds(7200L);
        jwtUtil = new JwtUtil(properties);
    }

    @Test
    @DisplayName("签发 → 解析回环：userId / tenantId / username / jti 一个不少")
    void shouldRoundTrip() {
        String token = jwtUtil.issue(1L, 10L, "admin", "jti-abc");

        JwtUtil.TokenPayload payload = jwtUtil.parse(token);

        assertEquals(1L, payload.userId());
        assertEquals(10L, payload.tenantId());
        assertEquals("admin", payload.username());
        assertEquals("jti-abc", payload.tokenId());
    }

    @Test
    @DisplayName("token 是三段式 JWS，且有效期取自配置")
    void shouldIssueCompactJws() {
        String token = jwtUtil.issue(2L, 20L, "demo", "jti-x");

        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "JWS 紧凑序列化应为 header.payload.signature 三段");
        assertEquals(7200L, jwtUtil.getExpireSeconds());
    }

    @Test
    @DisplayName("过期 token 抛 ExpiredJwtException（过滤器据此回 40102，与「无效」分开）")
    void shouldThrowWhenExpired() {
        // 用 jjwt 直接造：签发时刻与过期时刻都在过去
        String expired = Jwts.builder()
                .subject("1")
                .id("jti-expired")
                .claim("tenantId", 10L)
                .claim("username", "admin")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(secretKey(SECRET), Jwts.SIG.HS256)
                .compact();

        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parse(expired));
    }

    @Test
    @DisplayName("篡改签名后的 token 抛异常（不是返回 null）")
    void shouldThrowWhenSignatureTampered() {
        String token = jwtUtil.issue(1L, 10L, "admin", "jti-abc");
        int lastDot = token.lastIndexOf('.');
        char original = token.charAt(lastDot + 1);
        char replacement = original == 'x' ? 'y' : 'x';
        String tampered = token.substring(0, lastDot + 1) + replacement + token.substring(lastDot + 2);

        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    @DisplayName("篡改 payload（保留签名）同样抛异常")
    void shouldThrowWhenPayloadTampered() {
        String token = jwtUtil.issue(1L, 10L, "admin", "jti-abc");
        String[] parts = token.split("\\.");
        char original = parts[1].charAt(0);
        char replacement = original == 'a' ? 'b' : 'a';
        String tampered = parts[0] + "." + replacement + parts[1].substring(1) + "." + parts[2];

        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    @DisplayName("他人用别的密钥伪造的 token 抛异常（签名验证必须真的在跑）")
    void shouldThrowWhenSignedByAnotherSecret() {
        String forged = Jwts.builder()
                .subject("999")
                .id("jti-forged")
                .claim("tenantId", 999L)
                .claim("username", "attacker")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(secretKey(OTHER_SECRET), Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> jwtUtil.parse(forged));
    }

    @Test
    @DisplayName("完全不是 token 的字符串抛异常")
    void shouldThrowWhenMalformed() {
        assertThrows(JwtException.class, () -> jwtUtil.parse("not-a-jwt-token"));
    }

    @Test
    @DisplayName("签名正确但缺必需 claim（sub / tenantId）视为非法 token")
    void shouldThrowWhenRequiredClaimMissing() {
        String lackingClaims = Jwts.builder()
                .id("jti-no-subject")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(secretKey(SECRET), Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> jwtUtil.parse(lackingClaims));
    }

    @Test
    @DisplayName("密钥为空/过短时构造即失败（fail-fast，不留到第一次登录才 500）")
    void shouldFailFastOnWeakSecret() {
        JwtProperties blank = new JwtProperties();
        blank.setSecret("   ");
        assertThrows(IllegalStateException.class, () -> new JwtUtil(blank));

        JwtProperties shortSecret = new JwtProperties();
        shortSecret.setSecret("too-short");
        assertThrows(IllegalStateException.class, () -> new JwtUtil(shortSecret));
    }

    /**
     * 由原始密钥串派生 HS256 密钥（测试里直接造 token 时使用）。
     *
     * @param secret 密钥串
     * @return HS256 密钥
     */
    private static SecretKey secretKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}

package com.nexus.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthenticationFilter} 单元测试。
 *
 * <p>覆盖设计文档 §8 要求的四条 401 路径（无 token / 伪造 / 过期 / Redis 无记录）
 * 与白名单放行路径，另加两条同类路径：Redis 不可用（fail-closed）与"清理 ThreadLocal"。
 *
 * <p>断言的不只是 HTTP 状态码，还包括<b>响应体是 {@code Result} 结构且 {@code code} 正确</b> ——
 * 前端 401 分支读的就是响应体里的 {@code code}，只断状态码会让契约漂移漏网。
 *
 * <p>使用 {@code MockHttpServletRequest/Response}（spring-test）与 Mockito 假的
 * {@link JwtUtil} / {@link TokenStore}：本用例要验的是<b>过滤器的判定分支</b>，
 * 不是 jjwt 与 Redis 本身（那两者分别由 {@code JwtUtilTest} 与实机验收覆盖）。
 *
 * @author nexus
 */
class JwtAuthenticationFilterTest {

    private static final String PROTECTED_PATH = "/api/auth/me";
    private static final String LOGIN_PATH = "/api/auth/login";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JwtUtil jwtUtil;

    private TokenStore tokenStore;

    private JwtAuthenticationFilter filter;

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        tokenStore = mock(TokenStore.class);
        // 用真实配置对象：白名单取 SecurityProperties 的默认值（就是 yml 里那三条）
        filter = new JwtAuthenticationFilter(jwtUtil, tokenStore, new SecurityProperties(), objectMapper);

        request = new MockHttpServletRequest("GET", PROTECTED_PATH);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("无 token：401 + 40100，且不解析 token、不查 Redis")
    void shouldRejectWhenTokenMissing() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertUnauthorized(ResultCode.UNAUTHENTICATED);
        verifyNoInteractions(jwtUtil, tokenStore);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 头格式不对（缺 Bearer 前缀）：同样按 40100 处理")
    void shouldRejectWhenHeaderFormatInvalid() throws Exception {
        request.addHeader("Authorization", "token-without-bearer-prefix");

        filter.doFilter(request, response, mock(FilterChain.class));

        assertUnauthorized(ResultCode.UNAUTHENTICATED);
        verifyNoInteractions(jwtUtil);
    }

    @Test
    @DisplayName("伪造/格式非法的 token：401 + 40101（登录状态无效）")
    void shouldRejectWhenTokenInvalid() throws Exception {
        request.addHeader("Authorization", "Bearer forged.token.value");
        when(jwtUtil.parse(anyString())).thenThrow(new MalformedJwtException("签名不匹配"));

        filter.doFilter(request, response, mock(FilterChain.class));

        assertUnauthorized(ResultCode.TOKEN_INVALID);
        verifyNoInteractions(tokenStore);
    }

    @Test
    @DisplayName("过期 token：401 + 40102（与「无效」分开，前端文案不同）")
    void shouldRejectWhenTokenExpired() throws Exception {
        request.addHeader("Authorization", "Bearer expired.token.value");
        when(jwtUtil.parse(anyString())).thenThrow(new ExpiredJwtException(null, null, "已过期"));

        filter.doFilter(request, response, mock(FilterChain.class));

        assertUnauthorized(ResultCode.TOKEN_EXPIRED);
        verifyNoInteractions(tokenStore);
    }

    @Test
    @DisplayName("Redis 白名单无该 jti（已登出/被清）：401 + 40101")
    void shouldRejectWhenTokenRevoked() throws Exception {
        request.addHeader("Authorization", "Bearer valid-but-revoked");
        when(jwtUtil.parse(anyString())).thenReturn(new JwtUtil.TokenPayload(1L, 10L, "admin", "jti-1"));
        when(tokenStore.exists("jti-1")).thenReturn(false);

        filter.doFilter(request, response, mock(FilterChain.class));

        assertUnauthorized(ResultCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("Redis 不可用：fail-closed → 401（无法验证的凭据一律不认）")
    void shouldRejectWhenRedisUnavailable() throws Exception {
        request.addHeader("Authorization", "Bearer valid-token");
        when(jwtUtil.parse(anyString())).thenReturn(new JwtUtil.TokenPayload(1L, 10L, "admin", "jti-1"));
        when(tokenStore.exists("jti-1")).thenThrow(new RedisConnectionFailureException("连接被拒绝"));

        filter.doFilter(request, response, mock(FilterChain.class));

        assertUnauthorized(ResultCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("白名单路径（/api/auth/login）：直接放行，连 token 都不解析")
    void shouldPassThroughWhitelistedPath() throws Exception {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", LOGIN_PATH);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(loginRequest, response, chain);

        verify(chain).doFilter(loginRequest, response);
        verifyNoInteractions(jwtUtil, tokenStore);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("合法 token：放行，且放行期间上下文可用、放行之后被清理")
    void shouldPassThroughAndBindTenantContext() throws Exception {
        request.addHeader("Authorization", "Bearer valid-token");
        when(jwtUtil.parse(anyString())).thenReturn(new JwtUtil.TokenPayload(7L, 10L, "admin", "jti-7"));
        when(tokenStore.exists("jti-7")).thenReturn(true);

        // 在链内读出上下文：验证"业务代码执行时"上下文确实可用
        AtomicReference<Long> userIdInsideChain = new AtomicReference<>();
        AtomicReference<Long> tenantIdInsideChain = new AtomicReference<>();
        AtomicReference<String> jtiInsideChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> {
            userIdInsideChain.set(TenantContext.getUserId());
            tenantIdInsideChain.set(TenantContext.getTenantId());
            jtiInsideChain.set(TenantContext.getTokenId());
        };

        filter.doFilter(request, response, chain);

        assertEquals(7L, userIdInsideChain.get());
        assertEquals(10L, tenantIdInsideChain.get());
        assertEquals("jti-7", jtiInsideChain.get());

        // 关键：请求结束后必须清干净，否则线程池复用会把租户串给下一个请求
        assertNull(TenantContext.getUserId(), "userId 未清理");
        assertNull(TenantContext.getTenantId(), "tenantId 未清理");
        assertNull(TenantContext.getTokenId(), "tokenId 未清理");
    }

    @Test
    @DisplayName("拒绝路径同样不在本线程留下上下文")
    void shouldNotLeaveContextAfterRejection() throws Exception {
        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
    }

    /**
     * 断言：HTTP 401 + 响应体为统一 {@code Result} 结构且 code 符合预期。
     *
     * @param expected 期望的响应码
     */
    private void assertUnauthorized(ResultCode expected) throws Exception {
        assertEquals(401, response.getStatus());

        String body = response.getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(expected.getCode(), json.path("code").asInt(),
                "响应体 code 应为 " + expected.getCode() + "，实际响应体：" + body);
        assertEquals(expected.getMsg(), json.path("msg").asText());
        assertEquals("null", json.path("data").toString(), "失败响应体 data 应为 null");
    }
}

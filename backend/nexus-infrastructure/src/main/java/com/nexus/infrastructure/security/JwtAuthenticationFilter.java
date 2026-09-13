package com.nexus.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.result.Result;
import com.nexus.common.result.ResultCode;
import com.nexus.infrastructure.tenant.TenantContext;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证过滤器：白名单外的每一个请求都必须携带有效 token，否则直接以 HTTP 401 结束。
 *
 * <p><b>默认拒绝</b>（设计 §6.4）：白名单（{@code nexus.security.whitelist}）之外一律需要
 * {@code Authorization: Bearer <token>}；白名单默认只有 {@code /api/auth/login}、
 * {@code /api/health}、{@code /actuator/**} —— 漏配的后果是"多拦了请求"，
 * 而不是"漏放了请求"，方向刻意如此。
 *
 * <p><b>鉴权三连</b>（任一步失败都回到 401，但错误码不同，因为处置方式不同）：
 * <ol>
 *     <li>有没有 token → 没有：{@code 40100 UNAUTHENTICATED}（客户端没带，属接入问题）；</li>
 *     <li>token 本身是否合法 → 过期：{@code 40102 TOKEN_EXPIRED}（重新登录即可）；
 *         签名错/格式错：{@code 40101 TOKEN_INVALID}（有人在动 token，是异常信号）；</li>
 *     <li>token 是否仍被服务端承认 → Redis 白名单里没有该 {@code jti}（已登出 / Redis 被清）：
 *         {@code 40101 TOKEN_INVALID}。</li>
 * </ol>
 *
 * <p>第 3 步是"能登出"的代价：每个受保护请求多一次 Redis 读（见 {@link TokenStore}）。
 * <b>Redis 不可用时一律 401</b>（fail-closed）—— 宁可全员重新登录，也不放过无法验证的凭据。
 *
 * <p><b>401 响应体为什么要在此手工序列化</b>：本过滤器运行在 DispatcherServlet <b>之前</b>，
 * {@code @RestControllerAdvice} 根本看不到它 —— 若直接 {@code sendError}，前端拿到的会是
 * Tomcat 的错误页（HTML），与「所有出口都是 {@code Result}」的契约冲突。
 *
 * <p><b>ThreadLocal 清理</b>：鉴权通过后把登录态写入 {@link TenantContext}，
 * 并在 {@code finally} 中清理（成功、拒绝、抛异常三条路径都走同一处 finally）。
 * 线程池复用下漏清理会让上一个请求的租户串到下一个请求，详见 {@link TenantContext} 的类注释。
 *
 * <p>两个刻意的"不做"：
 * <ul>
 *     <li>不为 {@code OPTIONS} 预检开绿灯 —— 前端与 nginx / vite 代理同源（无跨域预检），
 *         而"按方法放行"是容易被滥用的旁路；</li>
 *     <li>不解析 token 里的角色做鉴权（阶段1 只解决"认人"，不解决"授权"）。</li>
 * </ul>
 *
 * @author nexus
 */
@Component
@Order(1)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** 标准鉴权头名。 */
    private static final String HEADER_AUTHORIZATION = HttpHeaders.AUTHORIZATION;

    /** Bearer 前缀（RFC 6750）。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    private final TokenStore tokenStore;

    private final SecurityProperties securityProperties;

    /** 由 Spring 注入的容器级 ObjectMapper（与 MVC 出口共用同一份序列化配置）。 */
    private final ObjectMapper objectMapper;

    /** Ant 风格路径匹配：白名单支持 {@code /actuator/**} 这类通配。 */
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   TokenStore tokenStore,
                                   SecurityProperties securityProperties,
                                   ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 过滤器初始化时把生效的白名单打进日志 —— 排查"为什么这个接口要 token"时先看这一行。
     */
    @Override
    protected void initFilterBean() {
        if (securityProperties.getWhitelist().isEmpty()) {
            log.warn("认证过滤器就绪：白名单为空，**所有**接口（含 /api/health 等探活口）都需要 token");
            return;
        }
        log.info("认证过滤器就绪：白名单={}", securityProperties.getWhitelist());
    }

    /**
     * 鉴权主流程：白名单直接放行；其余请求按「有无 token → token 是否合法 → 是否仍在 Redis 白名单」
     * 三步校验，全部通过后绑定租户上下文并放行。
     *
     * @param request     请求
     * @param response    响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 层异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        try {
            if (isWhitelisted(path)) {
                filterChain.doFilter(request, response);
                return;
            }

            // ① 有没有 token
            String token = resolveToken(request);
            if (token == null) {
                log.warn("鉴权失败：缺少 Authorization: Bearer 头，path={}", path);
                writeUnauthorized(response, ResultCode.UNAUTHENTICATED);
                return;
            }

            // ② token 本身是否合法（签名 + 有效期，由 jjwt 校验）
            JwtUtil.TokenPayload payload;
            try {
                payload = jwtUtil.parse(token);
            } catch (ExpiredJwtException ex) {
                log.warn("鉴权失败：token 已过期，path={}", path);
                writeUnauthorized(response, ResultCode.TOKEN_EXPIRED);
                return;
            } catch (JwtException | IllegalArgumentException ex) {
                log.warn("鉴权失败：token 非法（type={}），path={}", ex.getClass().getSimpleName(), path);
                writeUnauthorized(response, ResultCode.TOKEN_INVALID);
                return;
            }

            // ③ token 是否仍被服务端承认（登出/被清除后 jti 即不存在）
            if (!isTokenActive(payload.tokenId(), path, response)) {
                return;
            }

            TenantContext.set(payload.userId(), payload.tenantId(), payload.tokenId());
            filterChain.doFilter(request, response);
        } finally {
            // 无论放行、拒绝还是抛异常，本线程的上下文都必须清干净（见 TenantContext 类注释）
            TenantContext.clear();
        }
    }

    /**
     * 判断 token 是否仍在 Redis 白名单中；不在（或 Redis 不可用）时写出 401 响应。
     *
     * @param tokenId  token 的 jti
     * @param path     请求路径（仅用于日志）
     * @param response 响应（拒绝时写出统一响应体）
     * @return {@code true} 表示仍然有效、可以放行
     * @throws IOException 写响应失败
     */
    private boolean isTokenActive(String tokenId, String path, HttpServletResponse response) throws IOException {
        try {
            if (tokenStore.exists(tokenId)) {
                return true;
            }
            log.warn("鉴权失败：Redis 白名单无此登录态（已登出或被清除），path={} jti={}", path, tokenId);
            writeUnauthorized(response, ResultCode.TOKEN_INVALID);
            return false;
        } catch (DataAccessException ex) {
            // Redis 不可用 → fail-closed：无法验证的凭据一律不认（设计 §10 风险 1）
            log.warn("鉴权失败：Redis 不可用，按 fail-closed 拒绝本请求，path={}，原因={}", path, ex.getMessage());
            writeUnauthorized(response, ResultCode.TOKEN_INVALID);
            return false;
        }
    }

    /**
     * 是否为白名单路径（Ant 风格匹配）。
     *
     * @param path 请求路径
     * @return {@code true} 表示免鉴权
     */
    private boolean isWhitelisted(String path) {
        for (String pattern : securityProperties.getWhitelist()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code Authorization} 头取出 Bearer token。
     *
     * @param request 请求
     * @return token 字符串；缺失或格式不符时返回 {@code null}
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 写出 401 响应（统一响应体，HTTP 401）。
     *
     * @param response   响应
     * @param resultCode 响应码（40100 / 40101 / 40102）
     * @throws IOException 写响应失败
     */
    private void writeUnauthorized(HttpServletResponse response, ResultCode resultCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.failure(resultCode)));
    }
}

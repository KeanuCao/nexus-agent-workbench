package com.nexus.module.system.controller;

import com.nexus.common.result.Result;
import com.nexus.module.system.dto.LoginRequest;
import com.nexus.module.system.dto.LoginResponse;
import com.nexus.module.system.dto.UserInfoVO;
import com.nexus.module.system.service.AuthService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（契约：docs/api/README.md §5；OpenAPI：docs/api/openapi.yaml）。
 *
 * <p>路径不写在类级 {@code @RequestMapping} 上，而是完整写在方法注解里 ——
 * 与 {@code HealthController} 同一风格（CLAUDE.md 宪法：统一用组合注解，
 * 且必须显式声明 {@code produces} / {@code consumes}，以明确接口契约）。
 *
 * <p>三个接口的鉴权要求不同，容易记错，列在这：
 * <table border="1">
 *     <caption>鉴权矩阵</caption>
 *     <tr><th>接口</th><th>鉴权</th><th>谁在管</th></tr>
 *     <tr><td>POST /api/auth/login</td><td>无（白名单）</td><td>JwtAuthenticationFilter 直接放行</td></tr>
 *     <tr><td>POST /api/auth/logout</td><td>需要 token</td><td>同上（不在白名单）</td></tr>
 *     <tr><td>GET /api/auth/me</td><td>需要 token</td><td>同上</td></tr>
 * </table>
 *
 * <p>Controller 只做"接参 → 调服务 → 包 Result"，不含任何业务判断：
 * 校验、错误码、租户状态一类的决策全在 {@code AuthService}（可被单测覆盖，不必起 MVC 容器）。
 *
 * @author nexus
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * {@code POST /api/auth/login} —— 登录并签发 token。
     *
     * <p>失败也是 HTTP 200 + 非 0 业务码（{@code 10100 / 10101 / 10102}），
     * 与全局约定一致：业务失败走 {@code code}，HTTP 状态码只承载传输/可用性语义。
     *
     * @param request 登录请求体
     * @return token 与当前用户信息
     */
    @PostMapping(value = "/api/auth/login",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    /**
     * {@code POST /api/auth/logout} —— 登出，使当前 token 立刻失效。
     *
     * <p><b>刻意不声明 {@code consumes}</b>：本接口没有请求体。若声明了
     * {@code consumes = application/json}，不带 {@code Content-Type} 的合法请求会被 Spring
     * 判为 415（HttpMediaTypeNotSupportedException）—— 契约里 {@code msg} 的可读性
     * 换来了一个假的失败路径，不划算。
     *
     * @return 恒为成功的空响应（无 token 或已失效根本进不来，由过滤器拦成 401）
     */
    @PostMapping(value = "/api/auth/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * {@code GET /api/auth/me} —— 取当前用户（服务端校验版）。
     *
     * <p>存在的意义：登录态以 Redis 白名单为准，本地存着 token 不代表服务端仍认
     * （可能在别处登出、或 Redis 被清）。前端刷新页面后调一次，避免"假登录态"。
     *
     * @return 当前用户信息（与登录响应里的 {@code data.user} 同构）
     */
    @GetMapping(value = "/api/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<UserInfoVO> currentUser() {
        return Result.success(authService.getCurrentUser());
    }
}

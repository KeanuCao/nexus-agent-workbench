package com.nexus.module.system.service;

import com.nexus.module.system.dto.LoginRequest;
import com.nexus.module.system.dto.LoginResponse;
import com.nexus.module.system.dto.UserInfoVO;

/**
 * 认证服务：登录、登出、取当前用户。
 *
 * <p>阶段1 的边界（设计 §11 明确不做）：没有注册、改密、刷新 token、角色权限 ——
 * 只把"凭据换 token、token 换身份、登出即失效"这条链路做扎实。
 *
 * @author nexus
 */
public interface AuthService {

    /**
     * 登录：校验凭据与账号/租户状态，签发 token 并登记 Redis 白名单。
     *
     * <p>失败一律抛 {@link com.nexus.common.exception.BusinessException}
     * （HTTP 200 + 非 0 业务码，前端可直接展示 {@code msg}）：
     * 用户名或密码错误 → {@code 10100}；账号已停用 → {@code 10101}；租户已停用 → {@code 10102}。
     *
     * @param request 登录请求（用户名 + 明文口令）
     * @return token 与当前用户信息
     */
    LoginResponse login(LoginRequest request);

    /**
     * 登出：删除 Redis 白名单中该 token 的 {@code jti}，使同一个 token 立刻失效。
     *
     * <p>这是"JWT 无状态"之外必须引入 Redis 的原因（设计决策 D1）——
     * 纯 JWT 只能等它自然过期。
     *
     * @throws com.nexus.common.exception.UnauthorizedException 无登录态（正常流程下不可达）
     */
    void logout();

    /**
     * 取当前登录用户（服务端校验版，而非"前端本地存着 token 就算登录"）。
     *
     * <p>前端刷新页面后调它做一次真实校验：本地有 token ≠ 服务端仍认
     * （可能在别处登出、或 Redis 被清过）。
     *
     * @return 当前用户信息
     * @throws com.nexus.common.exception.UnauthorizedException 登录态已失效
     */
    UserInfoVO getCurrentUser();
}

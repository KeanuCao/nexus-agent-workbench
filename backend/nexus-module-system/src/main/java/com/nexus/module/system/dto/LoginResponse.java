package com.nexus.module.system.dto;

/**
 * 登录成功响应（{@code Result<LoginResponse>} 的 {@code data}，契约见 docs/api/README.md §5.1）。
 *
 * <pre>
 * {
 *   "token": "eyJhbGciOiJIUzI1NiJ9...",
 *   "tokenType": "Bearer",
 *   "expiresIn": 7200,
 *   "user": { "userId": 1, "username": "admin", ... }
 * }
 * </pre>
 *
 * <p>{@code tokenType} 固定为 {@code Bearer}：前端据此拼 {@code Authorization} 头。
 * 把它放进响应体（而不是让前端硬编码）是 OAuth 2.0 的通行做法，
 * 将来换签发方式时前端不必改。
 *
 * @param token     紧凑序列化的 JWS
 * @param tokenType 固定 {@code Bearer}
 * @param expiresIn 有效期（秒），前端可据此做过期前提示（阶段1 前端仅存储，不做续期）
 * @param user      当前用户信息
 * @author nexus
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        UserInfoVO user) {
}

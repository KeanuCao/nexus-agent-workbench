package com.nexus.module.system.dto;

/**
 * 当前用户信息（{@code POST /api/auth/login} 与 {@code GET /api/auth/me} 的 {@code data.user}）。
 *
 * <p>字段与契约逐字对齐（docs/api/README.md §5.1）：前端登录后把这份信息存进
 * Pinia store，用于导航栏展示"当前用户 + 所属租户"。
 *
 * <p>刻意<b>不含</b> {@code password}、{@code status} 等内部字段：
 * VO 与实体的分离就是为了让"哪些字段能出网关"成为一个显式决定，
 * 而不是"实体里有什么就吐什么"。
 *
 * @param userId     用户 ID
 * @param username   登录名
 * @param nickname   展示名（可能为空）
 * @param tenantId   租户 ID
 * @param tenantCode 租户编码（如 {@code default}）
 * @param tenantName 租户名称（如"默认租户"）
 * @author nexus
 */
public record UserInfoVO(
        Long userId,
        String username,
        String nickname,
        Long tenantId,
        String tenantCode,
        String tenantName) {
}

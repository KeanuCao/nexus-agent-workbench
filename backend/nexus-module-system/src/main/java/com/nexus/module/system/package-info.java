/**
 * 系统模块：用户、角色、菜单（阶段1 已落地认证链路：多租户 + 登录/登出/取当前用户）。
 *
 * <p>包结构（阶段1 实际落地情况）：
 * <pre>
 * com.nexus.module.system
 *  ├── controller    AuthController（/api/auth/login、/api/auth/logout、/api/auth/me）
 *  ├── service       AuthService + impl（登录/登出/取当前用户）
 *  ├── mapper        UserMapper（selectByUsername 标 &#64;IgnoreTenant）、TenantMapper
 *  ├── entity        User（t_user）、Tenant（t_tenant）
 *  ├── dto           LoginRequest / LoginResponse / UserInfoVO
 *  └── config        PasswordEncoderConfig（BCrypt）
 * </pre>
 *
 * <p>依赖方向：本模块 → nexus-common + nexus-infrastructure，禁止反向依赖；
 * 也禁止与 nexus-module-ai 平级互依。
 *
 * <p>阶段1 边界（设计 §11 明确不做）：注册、改密、刷新 token、角色与菜单。
 * 表结构由 db-patch 的补丁落地，本模块<b>不含</b>建表逻辑，也不含用户写入接口。
 *
 * @author nexus
 */
package com.nexus.module.system;

import { request } from './request'

/**
 * 认证接口 —— 契约来源：docs/api/README.md §5（唯一事实源，机器可读版见 openapi.yaml）。
 *
 * 三个接口的鉴权要求不同（§5）：
 *   · POST /api/auth/login   免鉴权（白名单）
 *   · POST /api/auth/logout  需要 Authorization（由 request.ts 请求拦截器统一注入）
 *   · GET  /api/auth/me      同上
 *
 * 两类失败的处置完全不同（§1.2 / §1.3），调用点不要混：
 *   · 登录失败（用户名或密码错误 / 账号停用 / 租户停用）是 **HTTP 200 + code 非 0**（10100/10101/10102），
 *     走 axios 成功分支 → 拦截器已弹 msg 并 reject BusinessError，调用点只需 catch 后取 message；
 *   · 未登录 / 登录态失效是 **HTTP 401**（40100/40101/40102），由响应拦截器统一清 token 并跳登录页（§5.1 第 4 条）。
 */

/** 当前用户信息（`UserInfoVO`）—— §5.2 的 `data.user` 与 §5.4 的 `data` 同构 */
export interface UserInfo {
  userId: number
  username: string
  /** 展示名；契约允许为 null，界面展示时回退到 username */
  nickname: string | null
  tenantId: number
  /** 租户编码（default / demo） */
  tenantCode: string
  /** 租户名称，前端展示用 */
  tenantName: string
}

/** 登录请求体（§5.2：两个字段都必填；登录只收用户名，不收租户编码） */
export interface LoginRequest {
  username: string
  password: string
}

/** 登录响应 `data`（`LoginResponse`，§5.2） */
export interface LoginData {
  /** JWT 紧凑序列化串 */
  token: string
  /** 固定 `Bearer`；前端据此拼 Authorization 头，**不要硬编码**（§5.2） */
  tokenType: string
  /** 有效期（秒），与 Redis 白名单 TTL 一致 */
  expiresIn: number
  user: UserInfo
}

/**
 * POST /api/auth/login —— 登录（免鉴权）
 *
 * 失败不经此处的失败分支：业务失败是 HTTP 200 + code 10100/10101/10102。
 * 前端**不得**按 msg 或 code 去区分"用户不存在"与"密码错误"——服务端刻意不区分（防账号枚举，§5.2）。
 */
export function login(payload: LoginRequest): Promise<LoginData> {
  return request<LoginData>({
    url: '/auth/login',
    method: 'post',
    data: payload
  })
}

/**
 * POST /api/auth/logout —— 登出（需 token）
 *
 * 无请求体（§5.3：接口未声明 consumes，不要发 body）。成功后服务端立即删除 Redis 中该 `jti` 的记录，
 * 同一个 token **立刻**失效 —— 这正是"纯 JWT 做不到登出"的补丁（§5.1 第 5 条）。
 */
export function logout(): Promise<null> {
  return request<null>({
    url: '/auth/logout',
    method: 'post'
  })
}

/**
 * GET /api/auth/me —— 取当前用户（需 token）
 *
 * 它不是"多余的一个接口"（§5.4）：登录态以 Redis 白名单为准，本地存有 token **不代表**服务端仍认
 * （可能在别处登出、或 Redis 被清）。刷新页面后调它做一次真实校验，避免"假登录态"。
 */
export function getCurrentUser(): Promise<UserInfo> {
  return request<UserInfo>({
    url: '/auth/me',
    method: 'get'
  })
}

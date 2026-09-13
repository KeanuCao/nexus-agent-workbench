import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { getCurrentUser, login as loginApi, logout as logoutApi, type UserInfo } from '@/api/auth'

/**
 * 登录态 store —— token + 当前用户信息 + 登录/登出/校验（设计 §7）。
 *
 * **持久化方式：手写 localStorage，不接 pinia-plugin-persistedstate**（设计决策 D7）。
 * 理由：为一个「存一个字符串」的需求引插件需联网 `npm install`；手写约 20 行，且逻辑全部收敛在本文件内。
 * 两条纪律：
 *   ① 读写一律 try/catch —— 隐私模式、站点数据被清、存储被禁用时 localStorage 会**抛异常**，
 *      不能让它把页面搞崩；写失败退化为"仅内存登录态"，本次会话仍可用。
 *   ② 读取到的用户信息做最小形状判定 —— 存储可能被手工改坏（1.5-3 的验收动作就是这个），
 *      半个对象宁可当作未登录，也不要渲染到界面上。
 *
 * 登录态以**服务端 Redis 白名单**为准（§1.4 / §5.4）：本地有 token ≠ 服务端仍认，
 * 故刷新页面后由路由守卫调 `restore()`（GET /api/auth/me）核实一次。
 */

/** localStorage 键名统一前缀，避免与同域下其它应用的键冲突 */
const TOKEN_KEY = 'nexus.auth.token'
const TOKEN_TYPE_KEY = 'nexus.auth.tokenType'
const USER_KEY = 'nexus.auth.user'

/** tokenType 的兜底值：契约固定为 `Bearer`（§5.2），仅在持久化值缺失时使用 */
const DEFAULT_TOKEN_TYPE = 'Bearer'

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    // 存储不可用（隐私模式 / 被禁用）：当作"没有持久化过"
    return null
  }
}

function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 写失败（配额满 / 隐私模式）不阻断登录流程本身：退化为仅内存登录态，刷新后需重新登录
  }
}

function removeStorage(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // 同上：删不掉也不能让登出失败，内存态清干净才是关键
  }
}

/** 解析持久化的用户信息；JSON 损坏或形状不符一律当作"没有" */
function readUser(): UserInfo | null {
  const raw = readStorage(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    const parsed = JSON.parse(raw) as Partial<UserInfo>
    // 最小形状判定：界面上要用的是标识与租户名，缺了就当未登录（其余字段由 /me 或下次登录补齐）
    if (typeof parsed.userId !== 'number' || typeof parsed.username !== 'string') {
      return null
    }
    return parsed as UserInfo
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  // 初始值直接来自 localStorage：刷新页面无需额外初始化即可保持登录态
  const token = ref<string>(readStorage(TOKEN_KEY) ?? '')
  const tokenType = ref<string>(readStorage(TOKEN_TYPE_KEY) ?? DEFAULT_TOKEN_TYPE)
  const user = ref<UserInfo | null>(readUser())

  /**
   * 本次页面会话是否已用 GET /api/auth/me 向服务端核实过登录态。
   * **刻意不持久化**：刷新后本地 token 可能是"假登录态"（在别处登出 / Redis 被清），必须重新核实一次。
   */
  const verified = ref(false)

  const isLoggedIn = computed<boolean>(() => token.value !== '')

  /** 界面展示名：昵称优先，无昵称回退用户名（`nickname` 契约允许为 null） */
  const displayName = computed<string>(() => user.value?.nickname || user.value?.username || '')

  const tenantName = computed<string>(() => user.value?.tenantName ?? '')

  /** 把当前内存态整体写入持久化（登录成功、核实成功后调用） */
  function persist(): void {
    writeStorage(TOKEN_KEY, token.value)
    writeStorage(TOKEN_TYPE_KEY, tokenType.value)
    writeStorage(USER_KEY, user.value ? JSON.stringify(user.value) : '')
  }

  /**
   * 只清本地登录态，不发起任何请求。
   * 供 401 自动登出（request.ts）与 `restore` 失败时使用 —— 那些场景下 token 已被服务端判为失效，
   * **不能**再走 `logout()`（会再发一次登出请求 → 又是 401 → 递归）。
   */
  function clear(): void {
    token.value = ''
    tokenType.value = DEFAULT_TOKEN_TYPE
    user.value = null
    verified.value = false
    removeStorage(TOKEN_KEY)
    removeStorage(TOKEN_TYPE_KEY)
    removeStorage(USER_KEY)
  }

  /**
   * 登录：调 POST /api/auth/login，成功后落地 token 与用户信息。
   * 失败时**不改动**当前登录态，并把 BusinessError 抛给调用点（页面据此展示后端 msg）。
   */
  async function login(username: string, password: string): Promise<void> {
    const data = await loginApi({ username, password })

    token.value = data.token
    // 用后端返回的 tokenType 拼鉴权头前缀，不硬编码（§5.2）
    tokenType.value = data.tokenType || DEFAULT_TOKEN_TYPE
    user.value = data.user
    // 登录响应本身就是服务端权威信息，无需紧接着再调一次 /me
    verified.value = true
    persist()
  }

  /**
   * 登出：先请服务端作废该 token（Redis 白名单 DEL），再清本地。
   * 服务端调用失败（网络不通 / token 已失效）**不阻断**本地登出 —— 否则界面停在"已登录"、
   * 而服务端可能已不认这个 token，那正是最坏的一种假登录态。
   * 跳转登录页由调用方负责（store 不反向依赖 router）。
   */
  async function logout(): Promise<void> {
    try {
      if (token.value) {
        await logoutApi()
      }
    } catch {
      // 有意吞掉：登出的目标是"本地一定清干净"，服务端失败不该让用户卡在登录态
    } finally {
      clear()
    }
  }

  /**
   * 用 GET /api/auth/me 向服务端核实本地 token 是否仍然有效（路由守卫在刷新后的首次导航调用）。
   * 返回是否仍然有效；**失败时一律清空本地登录态**（含网络不通这类非 401 失败）。
   *
   * 为什么要连"网络不通"也清：守卫的规则是"有 token 但未核实 → 去核实，失败就跳登录页"，
   * 若失败后仍留着 token，则登录页的"已登录直接回控制台"规则会把用户弹回受保护页，
   * 后者又再次核实失败……形成无限重定向。fail-closed 给出的是确定结果：重新登录。
   */
  async function restore(): Promise<boolean> {
    if (!token.value) {
      return false
    }
    try {
      const currentUser = await getCurrentUser()
      user.value = currentUser
      verified.value = true
      persist()
      return true
    } catch {
      clear()
      return false
    }
  }

  return {
    token,
    tokenType,
    user,
    verified,
    isLoggedIn,
    displayName,
    tenantName,
    login,
    logout,
    restore,
    clear
  }
})

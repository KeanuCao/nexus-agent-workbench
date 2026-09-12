import { createPinia } from 'pinia'

/**
 * Pinia 实例 —— 在 main.ts 中经 app.use(pinia) 装配。
 *
 * 阶段0：为空实例（骨架预留）。
 * 阶段1（多租户认证）在此目录新增 user.ts：useUserStore 管理 token 与用户信息，
 * 提供 login / logout actions，并接入持久化插件（pinia-plugin-persistedstate）
 * 将 token 落入 localStorage —— request.ts 的拦截器届时从这里取 token。
 */
const pinia = createPinia()

export default pinia

import { createPinia } from 'pinia'

/**
 * Pinia 实例 —— 在 main.ts 中经 app.use(pinia) 装配（**必须先于 router**：
 * 阶段1 的导航守卫要读 user store）。
 *
 * 阶段1（多租户认证）已在 user.ts 落地 useUserStore：token / 用户信息 + login / logout / restore / clear。
 * 持久化**由该文件内部手写 localStorage 完成**（设计决策 D7：为一个「存一个字符串」的需求引
 * pinia-plugin-persistedstate 需联网安装依赖，不划算），未接入任何持久化插件。
 * request.ts 的请求拦截器按需从中取 token，路由守卫按需取登录态。
 */
const pinia = createPinia()

export default pinia

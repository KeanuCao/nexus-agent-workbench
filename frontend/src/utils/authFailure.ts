/**
 * 401 统一处置 —— **axios 与 fetch 两条链路共用**（设计 `docs/design/02-统一AI网关.md` §5 文件表）。
 *
 * 为什么必须抽成独立模块，而不是各自写一份：
 *   ① 判据相同：契约 `docs/api/README.md` §5.1 第 4 条 —— 任一步校验失败 → HTTP 401 →
 *      **清 token → 跳 `/login?redirect=<当前路径>`**。axios 拦截器（request.ts）与
 *      fetch 流式接口（api/chat.ts）拿到的失败形态不同（前者是 AxiosError，后者是 Response），
 *      但**处置动作必须一致**，否则同一个 401 会因入口不同而表现不同。
 *   ② 防抖必须是**单例**：标志位 `redirectingToLogin` 是模块级的，所以它必须跟着本函数一起搬过来。
 *      留在 request.ts 里而函数搬走、或两个入口各留一个标志，都会让"并发 401 只跳一次"变成假命题
 *      —— 页面加载时若同时有 axios 请求与 SSE 请求 401，就会 push 两次登录页
 *      （第二次还会把 redirect 覆盖成本次失败请求所在页）。
 */

/**
 * 是否已有一次"因 401 触发的跳登录"正在进行中。
 * 覆盖"同一批并发请求在同一跳转落地之前陆续失败"的场景（典型是页面加载时多个受保护请求同时 401），
 * 跳转结束由 finally 复位。
 */
let redirectingToLogin = false

/**
 * 401 统一处置：清本地登录态 + 跳登录页（docs/api/README.md §1.2 / §5.1 第 4 条）。
 *
 * 防抖策略 —— **一次 401 风暴只跳一次**，用"标志位 + 落地路径判定"两道闸：
 *   · `redirectingToLogin`：覆盖"同一批并发请求在同一跳转落地之前陆续失败"；
 *   · `currentRoute.path === '/login'`：覆盖"跳转已落地之后的余波"，此时再 push 只会产生一条
 *     重复导航，并可能把 redirect 覆盖成本次失败请求所在页。
 * 两者叠加保证了复位之后（用户重新登录、再次过期）仍能正常触发新的跳转，不会一锤子失效。
 *
 * ⚠️ 两处依赖都用**动态 import**，不要改成顶层静态 import：
 *   request.ts → stores/user.ts → api/auth.ts → request.ts 是一个真环（见 request.ts 请求拦截器注释）；
 *   本模块位于该环之外，但 `@/router` 在初始化期就依赖 stores → 静态 import 会把边补回环上。
 *   动态 import 把求值推迟到"确有 401 发生"时，那时 main.ts 的 app.use(pinia) 早已执行。
 */
export async function handleUnauthorized(): Promise<void> {
  const { useUserStore } = await import('@/stores/user')
  const { default: router } = await import('@/router')

  if (redirectingToLogin || router.currentRoute.value.path === '/login') {
    return
  }
  redirectingToLogin = true

  // 只清本地登录态，**不能**调 store.logout()：那会再发一次 POST /api/auth/logout，
  // 而此刻 token 已被服务端判为失效 → 又是 401 → 递归
  useUserStore().clear()

  try {
    // redirect 带上当前路径，重新登录后可回到原页面（与路由守卫的写法保持一致）
    await router.push({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath }
    })
  } catch (error) {
    // 跳转被中止（如守卫返回 false）不应影响本次请求的错误传播：本函数只是"尽力而为"的副作用
    console.warn('[authFailure] 401 后跳转登录页未完成', error)
  } finally {
    redirectingToLogin = false
  }
}

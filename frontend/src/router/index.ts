import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useUserStore } from '@/stores/user'

/** 站点标题（与 index.html / package.json 保持一致） */
const PROJECT_TITLE = 'Nexus AI 数字员工平台'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题：写入 document.title */
    title?: string
    /** 免登录访问：阶段1 的路由守卫据此放行（如 /login） */
    public?: boolean
  }
}

/**
 * 路由表 —— 阶段0 为占位页，页面能力随各阶段填充：
 *   /login     → 阶段1 地基搭建（多租户 + 用户权限，交付 /api/auth/login）
 *   /dashboard → 阶段1+ 控制台（健康检查在此展示）
 *   /chat      → 阶段2 统一 AI 网关（流式对话，POST /api/chat/stream）
 *   /knowledge → 阶段3 大脑搭建（RAG 知识库）
 *   /agent     → 阶段4 灵魂搭建（Agent 编排）
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '控制台' }
  },
  {
    // 对话页（阶段2）。**不加 `public`**：流式接口不在白名单（契约 §6.1：漏 token → 401 + 40100），
    // 所以它必须受守卫保护，既有的「无 token → 带 redirect 跳登录」即可正确处理。
    // name 取 'chat' 而不是 'login' —— 守卫的第 ① 条是按 `to.name === 'login'` 判断的。
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '对话' }
  },
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('@/views/KnowledgeView.vue'),
    meta: { title: '知识库' }
  },
  {
    path: '/agent',
    name: 'agent',
    component: () => import('@/views/AgentView.vue'),
    meta: { title: 'Agent 编排' }
  },
  {
    // 兜底：未匹配路径回控制台（生产 nginx 亦配置了 SPA history 回退）
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 导航守卫 —— 阶段1 接通登录态（设计 §7；契约见 docs/api/README.md §1.4 / §5.4）。
 *
 * 三条规则，顺序有意（前两条互斥，第三条只对"有 token 但还没核实"生效）：
 *
 *   ① 已登录访问登录页 → 直接回控制台，避免出现"登录了还停在登录页"。
 *      刻意只判 /login，**不**写成 `to.meta.public`：将来若新增面向所有人的公开页（如分享页），
 *      不该因为本地有 token 就被劫持到控制台。
 *   ② 未登录访问受保护页 → 带 redirect 跳登录，登录成功后回到原路径（验收 1.5-2）。
 *   ③ 本地有 token 但本次会话尚未向服务端核实 → 调 GET /api/auth/me 做一次真实校验（§5.4）。
 *      这是"假登录态"的第二道闸：登录态以 Redis 白名单为准，本地存着 token 不代表服务端仍认
 *      （可能在别处登出、或 Redis 被清）。核实失败时 store 已自行清空本地登录态，这里只需引导去登录页。
 *
 * 关于 import：本文件在顶层引入 useUserStore 是安全的（无静态环）——
 *   request.ts 对 store / router 的引用都是**动态** import，静态依赖图里不存在回到本模块的边；
 *   而 useUserStore 只在守卫函数体内**调用**（模块顶层调用会拿不到 activePinia）。
 */
router.beforeEach(async (to) => {
  document.title = to.meta.title ? `${to.meta.title} - ${PROJECT_TITLE}` : PROJECT_TITLE

  const userStore = useUserStore()

  if (to.name === 'login' && userStore.token) {
    return { path: '/dashboard' }
  }

  if (!to.meta.public && !userStore.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (userStore.token && !userStore.verified) {
    const stillValid = await userStore.restore()
    if (!stillValid) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  return true
})

export default router

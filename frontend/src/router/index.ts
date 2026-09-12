import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

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
 * 导航守卫。
 * 阶段0 仅负责标题；登录态校验在阶段1 接入（保持既有结构，不在此处写假的鉴权逻辑）：
 *   const userStore = useUserStore()
 *   if (!userStore.token && !to.meta.public) {
 *     return { path: '/login', query: { redirect: to.fullPath } }
 *   }
 */
router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - ${PROJECT_TITLE}` : PROJECT_TITLE
  return true
})

export default router

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useUserStore } from '@/stores/user'

/**
 * 顶部导航 —— 阶段1 起承载登录态（设计 §7）：
 *   · 未登录：菜单位里有「登录」入口
 *   · 已登录：显示当前用户 + 租户名 + 「退出登录」，「登录」菜单项隐藏
 * 用户与租户都取自 user store（登录响应的 data.user，或 /me 的核实结果），不做占位假数据。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 高亮当前路由：与 router/index.ts 的 path 一一对应 */
const activePath = computed<string>(() => route.path)

/** 退出中：防重复点击 */
const loggingOut = ref(false)

/**
 * 退出登录：先请服务端作废该 token（Redis 白名单 DEL，§5.3），再清本地登录态，最后回登录页。
 * 服务端调用失败不阻断本地登出（见 stores/user.ts 的 logout）。
 * 跳转用 replace：退出后不该用返回键回到受保护页。
 */
async function onLogout(): Promise<void> {
  loggingOut.value = true
  try {
    await userStore.logout()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <div class="app-nav">
    <el-menu
      :default-active="activePath"
      mode="horizontal"
      router
      :ellipsis="false"
      class="app-nav-menu"
    >
      <el-menu-item index="/dashboard">控制台</el-menu-item>
      <el-menu-item index="/knowledge">知识库</el-menu-item>
      <el-menu-item index="/agent">Agent 编排</el-menu-item>
      <!-- 登录入口仅在未登录时出现；登录后的"退出登录"在右侧用户区 -->
      <el-menu-item v-if="!userStore.isLoggedIn" index="/login">登录</el-menu-item>
    </el-menu>

    <div v-if="userStore.isLoggedIn" class="app-nav-user">
      <span class="app-nav-username">{{ userStore.displayName }}</span>
      <el-tag size="small" type="info">{{ userStore.tenantName }}</el-tag>
      <el-button link type="primary" :loading="loggingOut" @click="onLogout">退出登录</el-button>
    </div>
  </div>
</template>

<style scoped>
.app-nav {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
}

.app-nav-menu {
  flex: 1;
  border-bottom: none;
}

.app-nav-user {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.app-nav-username {
  font-size: 14px;
}
</style>

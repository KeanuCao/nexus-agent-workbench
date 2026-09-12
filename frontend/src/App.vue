<script setup lang="ts">
import AppNav from '@/components/AppNav.vue'
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <span class="app-title">Nexus AI 数字员工平台</span>
      <AppNav />
    </el-header>
    <el-main>
      <!-- 路由出口：阶段0 为四个占位页，后续阶段逐页填充业务 -->
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color);
}

.app-title {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 高亮当前路由：与 router/index.ts 的 path 一一对应 */
const activePath = computed<string>(() => route.path)
</script>

<template>
  <el-menu :default-active="activePath" mode="horizontal" router :ellipsis="false" class="app-nav">
    <el-menu-item index="/dashboard">控制台</el-menu-item>
    <el-menu-item index="/knowledge">知识库</el-menu-item>
    <el-menu-item index="/agent">Agent 编排</el-menu-item>
    <el-menu-item index="/login">登录</el-menu-item>
  </el-menu>
</template>

<style scoped>
.app-nav {
  flex: 1;
  border-bottom: none;
}
</style>

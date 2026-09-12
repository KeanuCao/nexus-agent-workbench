<script setup lang="ts">
import HealthCheckPanel from '@/components/HealthCheckPanel.vue'
</script>

<template>
  <div class="page">
    <h2 class="page-title">控制台</h2>

    <!-- 阶段0 验收项：页面加载时经 dev 代理调用后端真实接口 GET /api/health -->
    <HealthCheckPanel />

    <el-card shadow="never">
      <template #header>阶段0 占位页</template>
      <p>本页当前只验证「前端 → Vite dev 代理 → 后端」这条链路是否打通。</p>
      <p>
        控制台业务内容（租户信息、模型调用记录等）随阶段1 之后接入真实接口再填充，
        阶段0 不放置任何假数据看板。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-title {
  margin: 0;
  font-size: 18px;
}
</style>

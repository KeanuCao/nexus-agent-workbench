<script setup lang="ts">
// 阶段0 占位页。阶段4（灵魂搭建 · Agent 编排）在此实现：
//   对话界面传入任务 → ReAct Agent 按序调用 Tools（如 getWeather / queryDatabase）
//   → 多 Agent 协作（搜索 / 总结）结果回显
</script>

<template>
  <div class="page">
    <h2 class="page-title">Agent 编排</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="阶段4 交付"
      description="Agent 编排（ReAct + 工具调用 + 多 Agent 协作）落地后，此页实现任务编排与执行回显界面；当前为占位页。"
    />
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

<script setup lang="ts">
// 阶段0 占位页：刻意不含任何登录逻辑，也不做假登录。
// 阶段1（地基搭建 · 多租户 + 用户权限）在此实现：
//   - 账号 / 密码表单 + 前端校验（非空、长度）
//   - 调用 POST /api/auth/login，经 useUserStore().login 落地 token
//   - 登录成功后跳转 query.redirect 或 /dashboard
</script>

<template>
  <div class="page">
    <h2 class="page-title">登录</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="阶段1 交付"
      description="多租户认证（JWT + 租户上下文）落地后，此页实现登录表单与校验；当前为占位页。"
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

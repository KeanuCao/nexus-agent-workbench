<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

import type { LoginRequest } from '@/api/auth'
import { toDisplayMessage } from '@/api/request'
import { useUserStore } from '@/stores/user'

/**
 * 登录页 —— 阶段1 · 1.5 交付物。契约来源：docs/api/README.md §5.2。
 *
 * 失败口径（§1.2 / §5.2），两种情况处置不同：
 *   · 用户名或密码错误 / 账号停用 / 租户停用 → HTTP **200** + code 10100/10101/10102，
 *     由 request.ts 统一弹 msg 并 reject BusinessError，此处再以页面内 alert 常驻展示这条 msg；
 *   · 未登录 / 登录态失效 → HTTP 401，由响应拦截器清 token 并跳登录页，本页无需处理。
 * 前端**不得**按 msg 或 code 区分"用户不存在"与"密码错误"——服务端刻意不区分（防账号枚举）。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()

/**
 * 表单模型：字段名与请求体逐字对齐（`username` / `password`，§5.2），不做本地改名或转换，
 * 提交时按用户输入原样发出。
 */
const form = reactive<LoginRequest>({
  username: '',
  password: ''
})

/**
 * 前端基础校验（非空 + 长度）——只是"少一次无谓请求"，最终判据始终是后端。
 * 长度上限对齐建表字段：`username` VARCHAR(64)；`password` 入库的是 BCrypt 哈希，128 仅为前端护栏。
 * `whitespace`（async-validator 选项）让"只输空格"也算未填。
 */
const rules: FormRules<LoginRequest> = {
  username: [
    { required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' },
    { max: 64, message: '用户名长度不能超过 64 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 128, message: '密码长度不能超过 128 个字符', trigger: 'blur' }
  ]
}

/** 提交中：按钮转圈并禁用，避免重复提交 */
const submitting = ref(false)

/** 页面内常驻的失败提示（后端 msg 原文）；无失败时为空串 */
const errorMessage = ref('')

/**
 * 登录成功后的回跳地址：只接受**站内绝对路径**，其余一律回控制台。
 * 拒绝 `//evil.com` 这类协议相对地址 —— `query.redirect` 来自 URL，不校验就是开放重定向。
 */
function resolveRedirect(raw: unknown): string {
  if (typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//')) {
    return raw
  }
  return '/dashboard'
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) {
    return
  }

  // validate() 在无回调时以 reject 报告校验失败，故用 catch 收敛成布尔值
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    await userStore.login(form.username, form.password)
  } catch (error) {
    // 文案由 request.ts 归一（业务失败取后端 msg），此处不自行改写、不按 msg 分支
    errorMessage.value = toDisplayMessage(error)
    return
  } finally {
    submitting.value = false
  }

  ElMessage.success('登录成功')
  // replace 而非 push：登录页不该留在历史里（返回键回到登录页会被守卫再弹回控制台）
  await router.replace(resolveRedirect(route.query.redirect))
}
</script>

<template>
  <div class="page">
    <h2 class="page-title">登录</h2>

    <el-card shadow="never" class="login-card">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="72px"
        status-icon
        @submit.prevent="onSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            clearable
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <!-- show-password 只影响显示；native-type=submit 让回车与点击走同一条提交路径 -->
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>

        <!-- 失败提示常驻页面（全局 ElMessage 会自动消失，页面内这条便于对照排查） -->
        <el-alert
          v-if="errorMessage"
          class="login-error"
          type="error"
          :title="errorMessage"
          :closable="false"
          show-icon
        />

        <el-form-item>
          <el-button
            type="primary"
            native-type="submit"
            class="login-submit"
            :loading="submitting"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
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

.login-card {
  width: 100%;
  max-width: 420px;
}

.login-error {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
}
</style>

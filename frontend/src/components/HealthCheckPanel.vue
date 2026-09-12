<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { getHealth, pickHealthReport, type HealthData, type HealthStatus } from '@/api/health'
import { BusinessError, resolveHttpStatus, toDisplayMessage } from '@/api/request'

/**
 * 健康检查面板 —— 阶段0 验收链路：前端 → Vite dev 代理 → 后端 GET /api/health → Result<T> 解包。
 * 展示的每个字段都来自后端真实响应，不做任何本地伪造。
 *
 * 两种响应都必须能展示（docs/api/README.md §2.2 / §2.3，二者响应体结构完全相同）：
 *   · HTTP 200 + code 0      → 三项全 UP
 *   · HTTP 503 + code 20000  → 依赖降级，`data` 仍是完整报告，逐项标出哪个依赖 DOWN
 * 这正是"探活是真的"最该被看见的时刻：页面要能指着 redis 说它 DOWN，
 * 而不是笼统地弹一句"请求失败"（那等于把后端特意保留的 data 又丢了一次）。
 */
const loading = ref(false)

/** 页面展示用的报告：200 的成功体、或 503 降级体，结构相同 */
const report = ref<HealthData | null>(null)

/** 失败提示；成功时为 null。report 非 null 表示"失败但拿到了逐项探活结果" */
const failure = ref<{ message: string; status: number | null } | null>(null)

function statusTagType(status: HealthStatus): 'success' | 'danger' | 'info' {
  if (status === 'UP') {
    return 'success'
  }
  if (status === 'DOWN') {
    return 'danger'
  }
  return 'info'
}

/** 失败提示的补充说明：HTTP 状态码 + 后端是否仍在正常应答 */
const failureDetail = computed((): string => {
  if (!failure.value) {
    return ''
  }
  const parts: string[] = []
  if (failure.value.status !== null) {
    parts.push(`HTTP ${failure.value.status}`)
  }
  if (report.value) {
    // 能拿到可解析的完整报告 = 后端进程是活的，挂的是依赖（§4.1 结论③）
    parts.push('后端进程正常，仅依赖不可用 —— 逐项结果见下方')
  }
  return parts.join(' · ')
})

/**
 * 把失败归一成面板要展示的信息（文案 / HTTP 状态 / 是否附带报告）。
 * 三类失败的区别（§1.2 / §4.2.1）：
 *   · 503 依赖降级 —— 后端进程正常，响应体是完整 Result，data 里有逐项探活结果 → 报告照常渲染；
 *   · 500 / 404 —— 后端能应答但系统或路由失败，data 为 null，只有 msg → 仅提示；
 *   · 502 / 超时 / 连接被拒 —— 请求没到后端（网关错误页 / 无响应），连 Result 都没有 → 仅提示。
 * 文案与状态码统一由 request.ts 归一，避免同一类失败在全局提示与页面里说法不一致。
 */
function describeFailure(error: unknown): {
  message: string
  status: number | null
  report: HealthData | null
} {
  return {
    message: toDisplayMessage(error),
    status: resolveHttpStatus(error),
    // 只有 BusinessError 才可能带着后端仍返回的 data；网络层异常没有 Result 可取
    report: error instanceof BusinessError ? pickHealthReport(error.data) : null
  }
}

async function loadHealth(): Promise<void> {
  loading.value = true
  failure.value = null
  try {
    report.value = await getHealth()
  } catch (error) {
    const described = describeFailure(error)
    // 关键：降级响应（503）里仍有逐项探活结果，必须保留到页面上；
    // 只有真的拿不到报告时才清空，避免把上一轮的过期结果当成当前状态展示
    report.value = described.report
    failure.value = { message: described.message, status: described.status }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadHealth()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="panel-header">
        <span>后端健康检查 · GET /api/health</span>
        <el-button :loading="loading" @click="loadHealth">刷新</el-button>
      </div>
    </template>

    <!-- 失败提示：文案直接用后端 msg（如「依赖服务不可用：redis」），不在前端改写 -->
    <el-alert
      v-if="failure"
      class="failure-alert"
      :title="failure.message"
      :description="failureDetail"
      type="error"
      :closable="false"
      show-icon
    />

    <!-- 成功体与降级体共用：503 时 status 为 DOWN、对应依赖标红，其余依赖照常显示 UP -->
    <el-descriptions v-if="report" :column="2" border>
      <el-descriptions-item label="服务状态">
        <el-tag :type="statusTagType(report.status)">{{ report.status }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="服务名">{{ report.service }}</el-descriptions-item>
      <el-descriptions-item label="版本">{{ report.version }}</el-descriptions-item>
      <el-descriptions-item label="时间戳">{{ report.timestamp }}</el-descriptions-item>
      <el-descriptions-item label="依赖探活" :span="2">
        <el-space wrap>
          <el-tag
            v-for="(status, name) in report.checks"
            :key="name"
            :type="statusTagType(status)"
          >
            {{ name }}: {{ status }}
          </el-tag>
        </el-space>
      </el-descriptions-item>
    </el-descriptions>

    <el-skeleton v-else-if="loading" :rows="4" animated />
  </el-card>
</template>

<style scoped>
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.failure-alert {
  margin-bottom: 16px;
}
</style>

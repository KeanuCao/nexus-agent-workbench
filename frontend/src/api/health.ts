import { request } from './request'

/**
 * 后端健康检查接口 —— 契约来源：docs/api/README.md §2（唯一事实源，机器可读版见 openapi.yaml）
 * 完整路径 = baseURL(/api) + url(/health) = GET /api/health
 * 依赖探活失败时后端返回 HTTP 503，响应体结构不变（checks 对应项为 DOWN），
 * 该分支由 request.ts 的响应拦截器统一按错误处理。
 */

/**
 * 探活状态：契约取值为 UP（连通）/ DOWN（不可用）。
 * 后端刻意不复用 Spring HealthStatus（其取值域另含 OUT_OF_SERVICE / UNKNOWN），
 * 故此处无需放宽为 string；出现第三值即为契约破坏（§4.2.2），不应静默兼容。
 */
export type HealthStatus = 'UP' | 'DOWN'

/** 各依赖的真实连通性探测结果（JDBC 探活 / redis ping / ollama /api/version） */
export interface HealthChecks {
  postgres: HealthStatus
  redis: HealthStatus
  ollama: HealthStatus
}

/** Result.data 结构（HealthReport），字段与 docs/api/README.md §2.2 逐项对应 */
export interface HealthData {
  status: HealthStatus
  service: string
  version: string
  timestamp: string
  checks: HealthChecks
}

/** GET /api/health —— 返回解包后的 Result.data */
export function getHealth(): Promise<HealthData> {
  return request<HealthData>({
    url: '/health',
    method: 'get'
  })
}

/**
 * 从失败响应携带的 `Result.data` 中提取后端仍返回的完整报告。
 *
 * 依据 §2.3：健康检查降级（HTTP 503 + code 20000）时响应体结构不变，`data` 仍是完整 HealthReport
 * —— 前端据此逐项展示哪个依赖 DOWN。但并非所有失败都带 data（如 §1.3 的 50000 系统异常 data 为 null），
 * 故此处做形状判定而非直接断言，避免把空对象渲染成一份"看起来正常"的报告。
 */
export function pickHealthReport(data: unknown): HealthData | null {
  if (typeof data !== 'object' || data === null) {
    return null
  }
  const report = data as Partial<HealthData>
  if (typeof report.checks !== 'object' || report.checks === null) {
    return null
  }
  return report as HealthData
}

import axios, {
  isAxiosError,
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from 'axios'
import { ElMessage } from 'element-plus'

import { handleUnauthorized } from '@/utils/authFailure'

/**
 * 统一返回体 —— 与后端 Result<T> 一一对应（docs/design/00-环境与部署.md §5.3，
 * 完整字段表见 docs/api/README.md §1.1）
 * code === 0 成功；非 0 失败（失败时 data 可能为 null，也可能仍有完整数据 —— 见 BusinessError.data）
 */
export interface Result<T = unknown> {
  code: number
  msg: string
  data: T
}

/**
 * 业务异常：由非 0 业务码封装，供调用点按需 catch 与区分（对应后端 BusinessException）。
 * `message`（继承自 Error）即后端 `msg`，可直接展示给用户。
 *
 * `data`：后端**失败时也可能仍返回**完整业务数据，不能丢。
 *   典型即健康检查降级 —— HTTP 503 + code 20000 时响应体仍是完整 `Result<HealthReport>`，
 *   "是哪个依赖 DOWN"只存在于 `data.checks` 里（msg 只给出依赖名，见 docs/api/README.md §2.3 / §4.2.1）。
 *   若在此丢 `data`，调用方就只能显示"请求失败" —— 而那正是最该展示探活证据的时刻。
 *   无数据时为 null（如 §1.3 的 50000 系统异常 data 就是 null）。
 */
export class BusinessError<T = unknown> extends Error {
  readonly code: number

  /** 后端失败时仍返回的业务数据；无数据时为 null */
  readonly data: T | null

  /** 触发失败的 HTTP 状态码：业务失败（HTTP 200 + code 非 0）为 200，依赖降级为 503 */
  readonly status: number

  constructor(code: number, msg: string, data: T | null = null, status = 200) {
    super(msg)
    this.name = 'BusinessError'
    this.code = code
    this.data = data
    this.status = status
  }
}

/**
 * API 前缀：后端 Controller 映射、生产 nginx 反代、dev 代理三处均以 /api 为准
 * （§5.3 契约 URL 为 GET /api/health，与 nginx `location /api/` 保持前缀一致）
 */
const API_BASE_URL = '/api'

const service: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  // 阶段0 为普通请求的超时；阶段2 起 SSE/大模型长耗时接口在各自请求中单独覆盖
  timeout: 15_000,
  headers: {
    'Content-Type': 'application/json'
  }
})

/**
 * 判定响应体是否为后端统一返回体 `Result<T>`。
 * 用来区分"后端明确告知的失败"（能取到 code / msg / data）与"请求没到后端或被网关拦下"
 * （超时、连接被拒、网关 502 错误页、SPA 回退的 HTML —— 见 docs/api/README.md §4.2.1）。
 *
 * **本函数是 export 的，不是顺手为之**：流式对话接口（`POST /api/chat/stream`）的每一帧
 * `data` 仍是同一个 `Result<T>` 信封（设计决策 D4），其解析器 `utils/sse.ts` 必须与普通接口
 * **复用同一个函数对象**才能保证"同一套成功判定"。在 sse.ts 里另写一份形状判定，
 * 等于把"复用"变成两个会各自漂移的副本 —— 那正是 D4 想避免的。
 */
export function isResult(body: unknown): body is Result {
  if (typeof body !== 'object' || body === null) {
    return false
  }
  const candidate = body as Partial<Result>
  return typeof candidate.code === 'number' && typeof candidate.msg === 'string'
}

/**
 * 网络层失败文案。这类失败没有 Result 可依，因此不使用后端 msg。
 * 注意两种"看起来一样"的失败必须分开：**有 response**（网关 502/504 错误页）说明请求到了代理层，
 * **没有 response** 说明请求根本没到服务（后端未启动 / 端口不对 / CORS 失败），二者处置完全不同。
 */
function describeTransportError(error: AxiosError): string {
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
    return '请求超时，后端未在超时时间内响应'
  }
  if (!error.response) {
    return '无法连接后端服务（服务未启动或网络不通）'
  }
  return `后端返回 HTTP ${error.response.status}，且响应体不是统一的 Result 结构`
}

/**
 * 把任意异常归一成**可直接展示**的文案，返回值永不为空串。
 * 供需要在页面内二次展示的调用点复用 —— 保证全局提示（ElMessage）与页面内提示对同一类失败说法一致，
 * 也避免把 axios 的英文原始文案（"Network Error" / "timeout of 15000ms exceeded"）直接摆到界面上。
 */
export function toDisplayMessage(error: unknown): string {
  if (error instanceof BusinessError) {
    return error.message || '请求失败'
  }
  if (isAxiosError(error)) {
    return describeTransportError(error)
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return '请求失败'
}

/**
 * 取异常对应的 HTTP 状态码；**请求没到服务**（超时 / 连接被拒）时返回 null。
 * 供调用点区分"后端应答了但失败"（503 / 500，有状态码）与"压根没连上"（无状态码）。
 */
export function resolveHttpStatus(error: unknown): number | null {
  if (error instanceof BusinessError) {
    return error.status
  }
  if (isAxiosError(error)) {
    return error.response?.status ?? null
  }
  return null
}

// ── Request 拦截器：鉴权头注入（阶段1）──
service.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    // 鉴权头（docs/api/README.md §1.4）：除白名单（/api/auth/login、/api/health、/actuator/**）外一律必需。
    // 前缀取后端返回的 `data.tokenType`，不硬编码 `Bearer`（§5.2 明确要求前端据此拼接）。
    //
    // ⚠️ 这里刻意用**动态 import** 取 store，而不是在文件顶层 import（设计 §7 的循环依赖约定）：
    //   request.ts → stores/user.ts → api/auth.ts → request.ts 是一个真环，顶部静态 import 会让它在
    //   模块初始化期闭合；动态 import 把求值推迟到"确有请求发出"时 —— 此时 main.ts 已 app.use(pinia)，
    //   useUserStore 依赖的 activePinia 也已就位（模块顶层调用才是那个会炸的写法）。
    //   下方 401 分支对 @/router 的引用出于同一考虑。
    const { useUserStore } = await import('@/stores/user')
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `${userStore.tokenType} ${userStore.token}`
    }
    return config
  },
  (error: AxiosError) => Promise.reject(error)
)

// ── 401 统一处置 ──
// `handleUnauthorized`（清本地登录态 + 跳登录页，含跨请求防抖）已抽到 `@/utils/authFailure`：
// 流式对话接口不走 axios，它那条链路也必须触发**同一个**防抖标志，否则"并发 401 只跳一次"是假命题。
// 该模块与本文件之间的依赖是单向的（本文件 → authFailure），不构成静态环。

// ── Response 拦截器：统一解包 Result<T> ──
service.interceptors.response.use(
  (response: AxiosResponse<Result>) => {
    const result = response.data

    // HTTP 200 但响应体不是 Result：多半是打到了前端的 SPA 回退（try_files → /index.html）
    // 或反代错误页。这种"假通过"必须显式失败，不能当成功、也不能当业务失败解析（§4.2.1）
    if (!isResult(result)) {
      const message = '响应体不是统一的 Result 结构，请检查接口路径与反向代理配置'
      ElMessage.error(message)
      return Promise.reject(new Error(message))
    }

    if (result.code === 0) {
      // 解包：调用方直接拿到 Result.data，不再感知 code/msg 外壳。
      // axios 拦截器的类型签名假定返回值仍是 AxiosResponse，这里的断言是为修正该假设
      // （运行期真实返回值就是 data，见下方 request<T> 的说明）
      return result.data as unknown as AxiosResponse
    }

    // 业务失败：统一提示 + 抛出业务异常，避免每个调用点重复判 code
    ElMessage.error(result.msg || '请求失败')
    return Promise.reject(
      new BusinessError(result.code, result.msg, result.data ?? null, response.status)
    )
  },
  (error: AxiosError<Result>) => {
    const status = error.response?.status
    const result = error.response?.data

    if (status === 401) {
      // 登录态失效（40100 未带 token / 40101 无效或被登出 / 40102 过期，§1.3）：
      // 清 token + 跳登录页。具体处置与并发防抖见 handleUnauthorized。
      // 这里不 await：跳转是副作用，不能拖住本次请求的错误传播（下面照常 reject 成 BusinessError）
      void handleUnauthorized()
    }

    // 后端返回的仍是完整 Result —— 典型就是健康检查降级：HTTP 503 + code 20000，
    // 且 **data 不为 null**（完整 HealthReport，见 §2.3 / §4.2.1）。
    // 这里必须把 data 一并封进 BusinessError：msg 只说明"依赖服务不可用：redis"，
    // 而"每个依赖各自 UP/DOWN"只存在于 data.checks 里，丢了 data 就无法逐项标红。
    if (isResult(result)) {
      ElMessage.error(result.msg || '请求失败')
      return Promise.reject(
        new BusinessError(result.code, result.msg, result.data ?? null, status ?? 200)
      )
    }

    // 非 Result 响应体 / 无响应：网关错误页、超时、连接被拒、CORS 失败等。
    // 这类异常没有 code / msg 可展示，保留原始 AxiosError（含 response.status）交给调用方按需处理
    ElMessage.error(describeTransportError(error))
    return Promise.reject(error)
  }
)

/**
 * 统一请求入口。
 * 类型约定：拦截器已把 Result<T> 解包为 data，因此返回的 Promise 直接解析为 T（即 Result.data 的类型）。
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const data = await service.request<Result<T>>(config)
  return data as unknown as T
}

export default service

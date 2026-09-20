import { isResult, toDisplayMessage } from './request'
import { handleUnauthorized } from '@/utils/authFailure'
import { useUserStore } from '@/stores/user'
import {
  SseFrameParser,
  describeRawFrame,
  parseChatStreamFrame,
  type RawSseFrame
} from '@/utils/sse'

/**
 * 对话接口 —— 契约来源：`docs/api/README.md` §6（唯一事实源，机器可读版见 openapi.yaml
 * 的 `paths./api/chat/stream` 与 `Chat*` schemas）。
 *
 * ⚠️ **本接口不走 axios**，因此 request.ts 的请求/响应拦截器对它**完全无效**，两件事必须自己做：
 *   ① 自己补请求头（`Authorization` + `Content-Type`）—— 见下方 `buildHeaders`；
 *   ② 自己判 HTTP 状态与响应类型 —— `fetch` **不对非 2xx 抛错**，只对"网络层失败 / 被 abort" reject。
 *
 * 为什么不用 `EventSource`（设计 §5.3，这不是选型偏好而是**能力限制**）：
 *   WHATWG 标准里 `EventSource` 的构造函数**没有 headers 选项**，带不了 `Authorization`；
 *   且它只能发 GET，而消息体必须放在 body 里（§6.1：不进 URL、不进访问日志）。
 *   本项目的鉴权是 `Authorization: Bearer`（阶段1 既定），`EventSource` 直接出局。
 *
 * 一切失败都通过 `handlers.onError` / `handlers.onAborted` 投递，**本函数不向外抛异常**
 * （页面只有一个入口，不需要再包一层 try/catch）。
 */

/** 消息角色。本阶段**不支持** `system`（契约 §6.1：传 `system` 也是 40001） */
export type ChatRole = 'user' | 'assistant'

/** 一条会话消息（契约 §6.1 `messages[]`；openapi `ChatMessage`） */
export interface ChatMessage {
  role: ChatRole
  /** 正文：非空、去空白后非空；单条 ≤ 8KB、合计 ≤ 64KB（超出 40001） */
  content: string
}

/** 模型类型（契约 §6.1、openapi `ChatRequest.modelType` 的枚举） */
export type ChatModelType = 'OLLAMA' | 'DEEPSEEK'

/** 对话请求体（契约 §6.1；openapi `ChatRequest`） */
export interface ChatRequest {
  /**
   * 会话历史**全量上送**（后端无状态、不落库 —— 设计决策 D9）。
   * 至少 1 条，且**最后一条必须是 `role=user`**；**允许连续同角色**（§6.1 补充约束 1）。
   */
  messages: ChatMessage[]
  /**
   * 模型类型。契约里**可不传**：缺省 / `null` = 由后端决定（设计决策 D6，本轮解析为后端默认模型，
   * 那时 `meta.servedBy` 会是 `default`）。本轮前端总是显式上送（下拉只有两项）。
   */
  modelType?: ChatModelType | null
}

/** `event: meta` 帧的载荷（契约 §6.2；openapi `ChatStreamMeta`）—— 第一帧、仅有且必有一帧 */
export interface ChatStreamMeta {
  /** 实际使用的模型类型（契约取值域见 openapi enum） */
  modelType: ChatModelType
  /** 上游**真实模型名**：`qwen2.5:7b` / `deepseek-chat`（验收 2.2-1 就是看它） */
  model: string
  /**
   * 谁选的模型：`user-selected`（请求带了 modelType）/ `default`（后端取默认）。
   * 刻意取 `string` 而不是字面量联合：设计 §10.1 预告接入自动路由后还会多出 `fallback`，
   * 那时它不该让前端连页面都打不开（本字段仅供展示，不参与任何分支）。
   */
  servedBy: string
}

/**
 * `event: delta` 帧的载荷（契约 §6.2 的帧表）。
 * ⚠️ 设计 §5 文件表与 openapi 的 `Chat*` schema 清单里都漏列了它（两者只点了 meta/done/error），
 * 但帧表明确要求 `data.content`，缺了它这一帧就没类型可用 —— 故补上并在此注明出处。
 */
export interface ChatStreamDelta {
  /** 增量文本片段，**不是累积全文** → 前端做追加，不做替换 */
  content: string
}

/** `event: done` 帧的载荷（契约 §6.2；openapi `ChatStreamDone`）—— 与 `error` 互斥，有且仅有一个 */
export interface ChatStreamDone {
  /** `stop` 正常结束 / `length` 触达 token 上限 / `timeout` 被服务端软上限截断 */
  finishReason: 'stop' | 'length' | 'timeout'
  /** 本次共发出多少帧 `delta` */
  deltaCount: number
  /** 从开始到结束的毫秒数 */
  durationMs: number
}

/** `event: error` 帧的载荷（契约 §6.2；openapi `ChatStreamError`）—— 形状定死为对象，不是 null */
export interface ChatStreamError {
  /** 出错前已经发出的 `delta` 帧数（可能是 0） */
  deltaCount: number
}

/**
 * 失败信息（已归一成**可直接展示**的文案）。
 * `code` 为 `null` 表示"没有后端 Result 可依"：网络层失败、EOF 无终止帧、响应体不是 Result。
 */
export interface ChatStreamFailure {
  /** 后端业务码（40001 / 10200 / 20100 / 50000 …）；无 Result 可依时为 null */
  code: number | null
  /** 可直接展示的文案 */
  msg: string
  /** 失败前已收到的 `delta` 帧数（error 帧原样带出；其余情况为本地计数） */
  deltaCount: number
}

/**
 * 流式回调。**三个出口**（契约 §6.3、设计 §5.4）：`onDone`、`onError`、`onAborted`（用户主动停止）——
 * 三者互斥；`onFinish` 则无论从哪个出口离开都会调用**恰好一次**，页面据此复位按钮。
 */
export interface ChatStreamHandlers {
  /** 第一帧：模型已定（真实模型名在 `model` 里），可用作展示 / 验收判据 */
  onMeta?: (meta: ChatStreamMeta) => void
  /** 增量片段：**追加**到当前气泡（写错的表现是"屏幕上永远只有最后一个字"，设计 §5.1-3） */
  onDelta?: (content: string) => void
  /** 正常结束（`finishReason` 可能是截断的 length / timeout，值得在界面上区分） */
  onDone?: (done: ChatStreamDone) => void
  /** 失败：`error` 帧 / 开流前的 `Result` 失败 / EOF 无终止帧 / 网络层失败，四种形态统一从这里出 */
  onError?: (failure: ChatStreamFailure) => void
  /**
   * 用户点「停止生成」。**它不是失败**，所以刻意独立于 `onError`：
   * `AbortController.abort()` 会让 `await reader.read()` 抛 `AbortError`，若把它并进失败分支，
   * 用户每次主动停止都会看到一条"无法连接后端服务"（设计 §5.4 点名的高频翻车点）。
   */
  onAborted?: () => void
  /** 收尾（无论成功、失败还是中止）：复位"生成中"状态、关掉停止按钮 */
  onFinish?: () => void
}

/**
 * 完整路径 = request.ts 的 `baseURL('/api')` + `url('/chat/stream')`（契约 §6.1）。
 * 这里写成整串而不是复用 axios 的 baseURL —— 本文件不引入 axios 实例。
 */
const CHAT_STREAM_URL = '/api/chat/stream'

/**
 * 判定"是不是用户主动停止"。
 * 按 `name` 而不是 `instanceof`：`abort()` 抛的是 `DOMException`，
 * 少数实现里它不是 `Error` 的子类；按 name 判在所有浏览器都成立。
 */
function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError'
  )
}

/**
 * 网络层失败的文案。这类失败没有 `Result` 可依，因此不使用后端 msg。
 * ⚠️ 不要直接把 `error.message` 摆到界面上：`fetch` 给的是英文的 `Failed to fetch` /
 * `NetworkError when attempting to fetch resource.`。文案与 request.ts 的 `describeTransportError`
 * 保持同口径，避免同一个"连不上后端"在两条链路上有两种说法。
 */
function describeTransportFailure(error: unknown): string {
  if (error instanceof TypeError) {
    return '无法连接后端服务（服务未启动或网络不通）'
  }
  return toDisplayMessage(error)
}

/**
 * `POST /api/chat/stream` —— 流式对话（SSE）。
 *
 * @param payload 请求体（契约 §6.1）
 * @param handlers 流式回调（见 `ChatStreamHandlers`）
 * @param signal 「停止生成」用的中断信号：`AbortController.signal`
 */
export async function streamChat(
  payload: ChatRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  /** 终止载荷是否已投递（done / error / aborted 三者互斥，只投递一次） */
  let settled = false
  /** onFinish 是否已调用（`finally` 与各出口都可能触发，必须保证恰好一次） */
  let finished = false
  /** 是否已收到终止帧 —— 决定 EOF 该按"正常结束"还是"连接中断"处理 */
  let terminalSeen = false
  /** 本地 delta 计数：仅用于 EOF / 网络失败时报告"已经吐了多少"，不参与业务判定 */
  let deltaCount = 0

  function finish(): void {
    if (finished) {
      return
    }
    finished = true
    handlers.onFinish?.()
  }

  function fail(failure: ChatStreamFailure): void {
    if (settled) {
      return
    }
    settled = true
    handlers.onError?.(failure)
  }

  function abort(): void {
    if (settled) {
      return
    }
    settled = true
    handlers.onAborted?.()
  }

  /** 把一批已切好的原始帧派发出去；返回后 `terminalSeen` 可判流是否已终止 */
  function dispatch(frames: RawSseFrame[]): void {
    for (const raw of frames) {
      if (terminalSeen) {
        return
      }

      const outcome = parseChatStreamFrame(raw)
      if (!outcome.ok) {
        if (outcome.fatal) {
          // 协议被破坏（如 delta 帧信封 code 非 0）：继续读下去只会静默丢内容，按失败收尾
          terminalSeen = true
          fail({ code: null, msg: `事件流协议异常：${outcome.reason}`, deltaCount })
          return
        }
        // 契约外的第五种事件 / 读不懂的帧：忽略并留痕（页面空白的排查线索就在这里）
        console.warn('[chat] 忽略无法解析的事件帧：', outcome.reason, describeRawFrame(raw))
        continue
      }

      const frame = outcome.frame
      if (frame.event === 'meta') {
        handlers.onMeta?.(frame.data)
        continue
      }
      if (frame.event === 'delta') {
        deltaCount += 1
        // ★ 追加，不是替换（设计 §5.1-3）
        handlers.onDelta?.(frame.data.content)
        continue
      }
      if (frame.event === 'done') {
        terminalSeen = true
        if (!settled) {
          settled = true
          handlers.onDone?.(frame.data)
        }
        continue
      }
      // frame.event === 'error'：终止帧之一（§6.2 规则 2：done xor error）
      terminalSeen = true
      fail({
        code: frame.code,
        msg: frame.msg || '模型服务暂时不可用，请稍后重试',
        deltaCount: frame.data.deltaCount || deltaCount
      })
    }
  }

  /** 读取字节流并切帧。EOF 且无终止帧时按失败收尾（三个出口的第三项） */
  async function readStream(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader()

    // ★★ 本文件最隐蔽的一条（设计 §5.1-4）：`TextDecoder` 必须带 `{stream: true}`。
    //    read() 返回的 Uint8Array 是**字节**分片，中文是多字节 UTF-8 —— 一个汉字被 TCP 分片劈成两半时，
    //    少这个参数会把两半各解成一个 U+FFFD 替换字符，**且不抛任何异常**。
    //    叠加两个放大因素：① 本项目 delta 是"你""好"级别的单字帧、帧数上千；② 分片边界撞在汉字中间
    //    是**必然而非偶然**。症状是屏幕随机出现乱码，而后端日志与 curl 一切正常。
    //    另外 `decode()` 收尾那一次也不能省 —— 它把解码器内部残留的半个字符冲出来。
    const decoder = new TextDecoder('utf-8')
    const parser = new SseFrameParser()

    try {
      while (!terminalSeen) {
        const { done, value } = await reader.read()

        if (done) {
          // ★ 第三个出口：EOF（设计 §5.4 / 契约 §6.3）。后端崩溃或 nginx 断流时，连接会在
          //   **没有任何终止帧**的情况下结束 —— 只认 done/error 的话，页面会永久停在"生成中"、
          //   停止按钮永远可点。
          dispatch(parser.push(decoder.decode()))
          break
        }

        dispatch(parser.push(decoder.decode(value, { stream: true })))
      }
    } finally {
      // 释放读锁。正常结束时 resolve；已被 abort 时 reject（AbortError），吞掉即可 ——
      // 这里不该再改变任何已投递的结论。
      void reader.cancel().catch(() => undefined)
    }

    if (!terminalSeen) {
      fail({
        code: null,
        msg: '连接已中断（未收到结束帧），本次回答可能不完整',
        deltaCount
      })
    }
  }

  try {
    // 取 store 放在 try 内：本函数承诺"永不 reject"，任何一行漏在 try 外都会破坏该承诺。
    // 在函数体内调用（而不是模块顶层）是硬要求 —— 顶层调用拿不到 activePinia（见 request.ts 的同类注释）。
    const userStore = useUserStore()

    // ── 两个请求头都得自己补（设计 §5.1-2）：axios 实例同时注入了它们，fetch 不会 ──
    const headers: Record<string, string> = {
      // ⚠️ 缺了它 fetch 会按 body 类型自动打上 `text/plain;charset=UTF-8`，而后端按契约声明了
      //    `consumes=application/json` → **第一条请求就 415**（响应体是统一 Result，`code=40002`）。
      //    ⚠️ 2026-09-20 之前这一格会落进兜底、报成 **500「系统繁忙」** —— 明明是调用方的请求格式问题
      //    却显示成服务端故障，把排查方向带偏成"网关问题"。已加专门的 415 出口修掉（契约 §1.2）。
      'Content-Type': 'application/json'
    }
    if (userStore.token) {
      // 前缀取后端返回的 `data.tokenType`，**不硬编码 `Bearer`**（契约 §5.2 明确要求；
      // 设计 §1 架构图里那个字面 `Bearer` 是示意图，照它写就是退回硬编码）。
      headers.Authorization = `${userStore.tokenType} ${userStore.token}`
    }

    const response = await fetch(CHAT_STREAM_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal
    })

    const contentType = response.headers.get('Content-Type') ?? ''

    // ★ 先看 Content-Type 再决定怎么读（契约 §6.3）：`20100` 有两种载体，
    //   503 + `Result`（本服务线程池满）与 200 + `error` 帧（上游不可达）。
    //   ⚠️ 判 `application/json` 必须用 **includes** 而不是全等：Spring 会给
    //   `text/event-stream` 带上 `;charset=UTF-8`，全等比较会误判。
    if (contentType.includes('application/json')) {
      // 开流前的失败（401 / 40001 / 10200 / 20100）：响应体是普通 Result，不是事件流
      if (response.status === 401) {
        // 与 axios 链路走**同一个**处置函数（共用同一个防抖标志），否则流式接口的 401 不会跳登录页
        void handleUnauthorized()
      }
      const body = await response.text()
      let parsed: unknown = null
      try {
        parsed = JSON.parse(body)
      } catch {
        parsed = null
      }
      if (isResult(parsed)) {
        fail({ code: parsed.code, msg: parsed.msg || '请求失败', deltaCount: 0 })
      } else {
        fail({
          code: null,
          msg: `后端返回 HTTP ${response.status}，且响应体不是统一的 Result 结构`,
          deltaCount: 0
        })
      }
      return
    }

    // `fetch` 不对非 2xx 抛错，必须自己判（走到这里说明失败响应不是 Result：网关错误页 / HTML）
    if (!response.ok) {
      fail({
        code: null,
        msg: `后端返回 HTTP ${response.status}，且响应体不是统一的 Result 结构`,
        deltaCount: 0
      })
      return
    }

    if (!response.body) {
      fail({ code: null, msg: '响应没有可读的事件流（response.body 为空）', deltaCount: 0 })
      return
    }

    await readStream(response.body)
  } catch (error) {
    if (isAbortError(error)) {
      // 用户点了「停止生成」：不是失败（设计 §5.4）
      abort()
    } else {
      fail({ code: null, msg: describeTransportFailure(error), deltaCount })
    }
  } finally {
    // 三个出口之外的第四种情况（本函数自身出错）也走到这里 —— onFinish 恰好一次是页面的硬依赖
    finish()
  }
}

import { isResult, type Result } from '@/api/request'
import type {
  ChatStreamDelta,
  ChatStreamDone,
  ChatStreamError,
  ChatStreamMeta
} from '@/api/chat'

/**
 * SSE 帧解析 —— 契约来源：`docs/api/README.md` §6.2（唯一事实源）。
 *
 * 分工：**本文件只做「已解码文本 → 帧」**。字节 → 文本那一步（`TextDecoder` 必须带
 * `{stream: true}`）在 `api/chat.ts` 的读取循环里，因为它与 `reader.read()` 的调用点绑在一起。
 *
 * 一条必须记住的解析前提（§6.2）：**`data:` 行里不会出现真换行**。
 * 因为每帧 `data` 都是 JSON（设计决策 D4），Jackson 会把正文里的换行序列化成 `\n` 两个字符、
 * 引号转成 `\"` —— 所以这里可以按行切、不需要处理跨行 data。两个前提一旦被破坏立刻出坑：
 *   ① 若 `delta` 改成发裸文本；② 若为调试开启 Jackson 美化输出（`indent-output` 会往 `data:` 里塞真换行）。
 */

/** 逐行读字段时用到的「字段名 + 冒号」前缀 */
const EVENT_PREFIX = 'event:'
const DATA_PREFIX = 'data:'

/**
 * 帧信封：每帧的 `data` 仍是统一响应体 `Result<T>`（设计决策 D4）。
 * 信封类型从 `@/api/request` 复用，不另立一份 —— 否则"复用同一套解包/成功判定"就只是纸面上的。
 */
export interface ChatStreamEnvelope {
  /** 帧信封的 code：0 = 成功；`error` 帧为 20100（上游不可达）/ 50000（内部异常） */
  code: number
  /** 帧信封的 msg：失败时为可直接展示给用户的文案 */
  msg: string
}

/**
 * 流式对话的帧 —— **判别联合**，按 `event` 收窄（设计 §5 文件表）。
 *
 * `data` 是**已解包的载荷**（即 `Result.data`），不是整个 `Result`：调用方拿到的就是
 * 契约 §6.2 里 `data` 字段表描述的那几个结构。信封的 code/msg 另挂在同层，原因只有一条 ——
 * `error` 帧要 `ElMessage(msg)`（§5.4），而 `msg` 在信封里，不在载荷里。
 */
export type ChatStreamFrame =
  | (ChatStreamEnvelope & { event: 'meta'; data: ChatStreamMeta })
  | (ChatStreamEnvelope & { event: 'delta'; data: ChatStreamDelta })
  | (ChatStreamEnvelope & { event: 'done'; data: ChatStreamDone })
  | (ChatStreamEnvelope & { event: 'error'; data: ChatStreamError })

/** 从文本里切出来的**原始帧**：事件名 + 未解析的 data 文本（还可能是非法 JSON） */
export interface RawSseFrame {
  event: string
  data: string
}

/**
 * 解析结果。为什么不直接返回 `ChatStreamFrame | null`：
 * 契约要求"页面一直空白而 curl 完全正常"这类故障必须能当场定位 —— 调用方需要拿到**原因**才能打日志。
 * `fatal` 区分"这一帧读不懂，丢掉继续"（前向兼容：将来多一种事件）与
 * "协议被破坏，整条流不能再信"（如非 `error` 帧的 `code` 非 0）。
 */
export type FrameParseOutcome =
  | { ok: true; frame: ChatStreamFrame }
  | { ok: false; fatal: boolean; reason: string }

/**
 * 取 `event:` / `data:` 后的值。
 *
 * ⚠️ **契约 §6.2 规则 3 原文**：取值时**先剥掉至多一个前导空格**再 trim。
 * 各家实现（含 Spring 的 `SseEmitter`）是否带空格并不统一 —— 按示例字面写 `slice(6)`
 * （`'event:'.length + 1`）会因那一个空格**匹配不到任何事件名**，表现为页面一直空白而 curl 正常。
 */
function readFieldValue(line: string, prefix: string): string {
  return line.slice(prefix.length).replace(/^ /, '').trim()
}

/** 把一块（一帧的文本）解析成原始帧；注释行 / 空块返回 null */
function parseBlock(block: string): RawSseFrame | null {
  let event = ''
  const dataLines: string[] = []

  for (const line of block.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      // `:` 开头是 SSE 的注释/心跳行（规范允许），忽略
      continue
    }
    if (line.startsWith(DATA_PREFIX)) {
      dataLines.push(readFieldValue(line, DATA_PREFIX))
      continue
    }
    if (line.startsWith(EVENT_PREFIX)) {
      event = readFieldValue(line, EVENT_PREFIX)
    }
  }

  if (event === '' && dataLines.length === 0) {
    return null
  }
  // 规范允许多行 data（以 \n 连接）；契约下 data 是 JSON、不会有真换行，这里只是照规范兜底
  return { event, data: dataLines.join('\n') }
}

/**
 * 增量式帧切分器：喂入**已解码文本**，吐出其中**完整**的帧。
 *
 * 为什么要有状态（而不是每块独立解析）：一个帧可能被 TCP 分片劈开，也可能一个 chunk 里含多帧。
 * 未以空行收尾的尾巴留在缓冲区里等下一块 —— 流结束时它若仍未收尾就**整个丢弃**，不做"尽力解析"：
 * 没有空行收尾的块按定义是不完整的，其 `data` 可能是被截断的 JSON，硬解析只会得到一条误导性的错误。
 * （而"EOF 且无终止帧"本身已按失败处理，见 `api/chat.ts` 的第三个出口。）
 */
export class SseFrameParser {
  private buffer = ''

  push(text: string): RawSseFrame[] {
    // 契约 §6.2 规则 3：行终止符允许 `\r\n` / `\r` / `\n` 三种，各家实现不统一。
    // 零成本兜底：入缓冲区时先归一（`\r\n?` 同时覆盖 `\r\n` 与单个 `\r`）。
    this.buffer += text.replace(/\r\n?/g, '\n')

    const frames: RawSseFrame[] = []
    let separator = this.buffer.indexOf('\n\n')
    while (separator !== -1) {
      const block = this.buffer.slice(0, separator)
      this.buffer = this.buffer.slice(separator + 2)
      const frame = parseBlock(block)
      if (frame) {
        frames.push(frame)
      }
      separator = this.buffer.indexOf('\n\n')
    }
    return frames
  }
}

/** 帧载荷的形状判定：`typeof x === 'object' && x !== null` 的类型安全版 */
function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  return value as Record<string, unknown>
}

/**
 * 原始帧 → 判别联合帧。
 *
 * 空 `event` 单独给一条原因：它正是"没剥前导空格"那个坑的唯一症状（页面空白而 curl 正常），
 * 把原因写进日志比让调用方自己猜便宜得多。
 */
export function parseChatStreamFrame(raw: RawSseFrame): FrameParseOutcome {
  let payload: unknown
  try {
    payload = JSON.parse(raw.data)
  } catch {
    return { ok: false, fatal: false, reason: `data 不是合法 JSON（event='${raw.event}'）` }
  }

  if (!isResult(payload)) {
    // 复用 request.ts 的形状判定：非 Result 多半是打到了反代错误页 / SPA 回退的 HTML
    return { ok: false, fatal: false, reason: `data 不是统一的 Result 结构（event='${raw.event}'）` }
  }

  const envelope = payload as Result<unknown>
  const data = asRecord(envelope.data)

  if (raw.event === '') {
    return {
      ok: false,
      fatal: false,
      reason: '帧里没有可识别的 event 字段（常见原因：解析时未剥掉 `event:` 后的前导空格）'
    }
  }

  if (raw.event === 'meta') {
    if (!data || typeof data.model !== 'string') {
      return { ok: false, fatal: false, reason: 'meta 帧的 data 缺少 model' }
    }
    // 取值域按契约（openapi `ChatStreamMeta.modelType` 的 enum）卡死，而不是放宽成 string：
    // 出现第三种取值即为契约破坏，宁可在控制台留一条原因，也不要让它悄悄流进界面。
    const modelType = data.modelType
    if (modelType !== 'OLLAMA' && modelType !== 'DEEPSEEK') {
      return { ok: false, fatal: false, reason: `meta 帧的 modelType 不在契约取值域内（'${String(modelType)}'）` }
    }
    return {
      ok: true,
      frame: {
        event: 'meta',
        code: envelope.code,
        msg: envelope.msg,
        data: {
          modelType,
          model: data.model,
          servedBy: typeof data.servedBy === 'string' ? data.servedBy : ''
        }
      }
    }
  }

  if (raw.event === 'delta') {
    if (!data || typeof data.content !== 'string') {
      return { ok: false, fatal: false, reason: 'delta 帧的 data 缺少 content' }
    }
    if (envelope.code !== 0) {
      // 契约里 `delta` 帧的 code 恒为 0；非 0 说明协议被破坏 —— 继续按"追加文本"处理会**静默丢内容**
      return {
        ok: false,
        fatal: true,
        reason: `delta 帧的信封 code 非 0（code=${envelope.code}，msg=${envelope.msg}）`
      }
    }
    return {
      ok: true,
      frame: { event: 'delta', code: envelope.code, msg: envelope.msg, data: { content: data.content } }
    }
  }

  if (raw.event === 'done') {
    if (!data) {
      return { ok: false, fatal: false, reason: 'done 帧的 data 不是对象' }
    }
    const finishReason = data.finishReason
    if (finishReason !== 'stop' && finishReason !== 'length' && finishReason !== 'timeout') {
      return { ok: false, fatal: false, reason: `done 帧的 finishReason 不在契约取值域内（'${String(finishReason)}'）` }
    }
    return {
      ok: true,
      frame: {
        event: 'done',
        code: envelope.code,
        msg: envelope.msg,
        data: {
          finishReason,
          deltaCount: typeof data.deltaCount === 'number' ? data.deltaCount : 0,
          durationMs: typeof data.durationMs === 'number' ? data.durationMs : 0
        }
      }
    }
  }

  if (raw.event === 'error') {
    if (!data) {
      // 契约 §6.2：error 帧的 data **形状定死为对象**（不是 null），否则前端无从知道"已经吐了多少"
      return { ok: false, fatal: true, reason: 'error 帧的 data 不是对象（契约要求 {"deltaCount": N}）' }
    }
    return {
      ok: true,
      frame: {
        event: 'error',
        code: envelope.code,
        msg: envelope.msg,
        data: { deltaCount: typeof data.deltaCount === 'number' ? data.deltaCount : 0 }
      }
    }
  }

  // 契约外的第五种事件：忽略而非失败（前向兼容），由调用方打一条 warn
  return { ok: false, fatal: false, reason: `未知事件名 '${raw.event}'` }
}

/** 供调用方拼日志用：把原始帧压成一行可读文本（正文可能很长，截断到 200 字符） */
export function describeRawFrame(raw: RawSseFrame): string {
  const data = raw.data.length > 200 ? `${raw.data.slice(0, 200)}…` : raw.data
  return `event=${raw.event} data=${data}`
}

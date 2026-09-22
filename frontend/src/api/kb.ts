import { request } from './request'

/**
 * 知识库接口 —— 契约来源：`docs/api/README.md` §7（**唯一事实源**；机器可读版见 `openapi.yaml`
 * 的 `paths./api/kb/**`）。设计依据：`docs/design/03-RAG知识库.md` §5。
 *
 * 与 `api/chat.ts` 的形态差别（**不要照搬那边的写法**）：
 *   · 本组四个接口**全部同步**（§7 开头那段说明）—— 没有 SSE、没有事件帧、没有"已受理"这类中间态；
 *     返回 200 就是"已入库" / "答案已生成完"。
 *   · 四个接口全部走 `request.ts` 的 axios 实例 ⇒ 鉴权头注入、`Result` 解包、401 跳登录、
 *     失败弹 `msg` 全由拦截器统一处理。**本文件不再解一层**（拦截器已把 `Result<T>` 解成 `data`，
 *     这里再取一次 `.data` 只会拿到 `undefined`）。
 *
 * 失败一律以 `BusinessError` reject，其 `message` 即后端 `msg`（§7.8 的五个新码文案都可直接展示）：
 *   40001 参数问题（缺 `file` / 请求不是 multipart / 文件名超 255 字符）、40003 文件超 10MB、
 *   10201 类型不支持、10202 文档不存在、10203 解析不出文本、10204 分块超上限、
 *   20100 上游不可达（**HTTP 503** —— 本组里唯一不是 200 的业务失败，见 §7.7）。
 */

/**
 * 文档视图对象（`KbDocumentVO`）—— 契约 §7.5。
 * 上传响应与列表项**同构**：同一个类型被 `uploadDocument` 与 `listDocuments` 复用，不是巧合。
 */
export interface KbDocumentVO {
  /** 文档 ID（删除接口的路径参数）。int64，量级远小于 2^53，用 number 承载 */
  documentId: number
  /** 原始文件名（**仅回显、不落盘** —— 本轮不保存原文件，见 §7 边界 3） */
  fileName: string
  /**
   * `TXT` / `PDF`（大写，由扩展名归一化而来）。
   * 刻意用 `string` 而不是字面量联合：本字段**仅供展示**，不参与任何分支；设计 §10.1 预告
   * 以后会加 Word，那时它不该让整个页面编译不过（与 `ChatStreamMeta.servedBy` 同一处置）。
   */
  fileType: string
  /** 上传字节数 */
  fileSize: number
  /**
   * 解析出的正文字符数 —— **排查解析质量的第一眼数据**（§7.5）：
   * 与预期量级差太远，问题在解析（扫描版 PDF / 编码），不在检索。
   */
  charCount: number
  /** 入库的分块数（即 `3000` 上限判据的实测值） */
  chunkCount: number
  /**
   * 入库时间，`yyyy-MM-dd'T'HH:mm:ssXXX`（秒级、无小数秒）。
   * ⚠️ **零偏移渲染成 `Z`、本地直跑渲染成 `+08:00`**（§7.5 实测）：两种都是合法 ISO-8601，
   * `new Date(...)` 都能解析 —— 展示层**不要**对字符串做裁剪或断言时区（见下方视图的格式化函数）。
   */
  createdAt: string
}

/** 列表响应的 `data`（`KbDocumentListVO`）—— 契约 §7.2 */
export interface KbDocumentListVO {
  /**
   * 当前租户的文档（`tenant_id` 由后端 `TenantLineHandler` 注入，§7.2）。
   * ⚠️ **顺序未约定**，前端不要依赖（§7.2 明写）—— 需要稳定顺序时在展示层自行排序。
   */
  items: KbDocumentVO[]
  /** 本轮与 `items.length` 恒等（无分页）；保留它是为了将来加分页时不必改契约 */
  total: number
}

/** 问答请求体（`KbAskRequest`）—— 契约 §7.4 */
export interface KbAskRequest {
  /** 非空、去空白后非空；**≤ 500 字符**（按字符计，不是字节；超出 → `40001`） */
  question: string
  /**
   * 检索条数上限，**可不传**：缺省 / `null` = 用后端配置值（`nexus.ai.rag.top-k`，默认 5）；
   * 取值域 `1..20`（越界 → `40001`）。本轮页面不暴露这个旋钮，因此整个字段都不发。
   */
  topK?: number | null
}

/**
 * 引用片段（`KbAnswerVO.sources[]`）—— 契约 §7.6。
 * ⚠️ `content` 是**用户上传的外部文本**（PDF/TXT 原文，不做二次摘要）：
 * 渲染必须用插值（`{{ }}` / `v-text`），**绝不用 `v-html`** —— 原文里出现 `<script>` 就是一次 XSS。
 */
export interface KbSourceVO {
  /** 来源文档 ID */
  documentId: number
  /** 来源文件名（展示用） */
  fileName: string
  /** 该分块在文档内的序号，**0 起** —— 展示为「第 N 段」时要 +1（§7.6） */
  chunkIndex: number
  /** 余弦相似度 `1 - (embedding <=> query)`，接口给**4 位小数**；取值域 `[-1, 1]` */
  score: number
  /** 该分块的原文；段落内的换行**原样保留** ⇒ 渲染要 `white-space: pre-wrap` */
  content: string
}

/** 检索侧观测值（`KbAnswerVO.retrieval`）—— 契约 §7.6 */
export interface KbRetrievalVO {
  /** 本次生效的 topK */
  topK: number
  /** 过阈值后的条数（`grounded=false` 时为 0） */
  hits: number
  /** 本次生效的相似度阈值 */
  threshold: number
  /** 检索耗时（毫秒） */
  durationMs: number
}

/** 生成侧观测值（`KbAnswerVO.generation`）—— 契约 §7.6；**`grounded=false` 时为 `null`**（没调模型） */
export interface KbGenerationVO {
  /** 实际使用的模型类型（后端枚举名，如 `DEEPSEEK` / `OLLAMA`） */
  modelType: string
  /** 上游**真实模型名**（如 `deepseek-chat` / `qwen2.5:7b`） */
  model: string
  /** 生成耗时（毫秒） */
  durationMs: number
}

/** 问答响应（`KbAnswerVO`）—— 契约 §7.6 */
export interface KbAnswerVO {
  /**
   * 模型生成的答案。
   * `grounded=false` 时是**后端常量文案**（"知识库中未找到相关内容，请换一种问法，或先上传相关文档。"）
   * —— 前端**不要自己拼**这句，直接展示本字段（§7.6）。
   */
  answer: string
  /** 答案是否**基于检索到的资料**；`false` = 阈值筛完后 0 条 ⇒ 未调用大模型 */
  grounded: boolean
  /** 引用片段，按 `score` 降序；`grounded=false` 时为空数组（**不是 `null`**） */
  sources: KbSourceVO[]
  /** 检索侧观测值（必有）—— 排查"答案不对"到底出在检索还是生成，这是唯一的可见证据 */
  retrieval: KbRetrievalVO
  /** 生成侧观测值；`grounded=false` 时为 `null` */
  generation: KbGenerationVO | null
}

/**
 * 上传超时（毫秒）—— **不是随手放大的数字**：本接口在请求线程上同步完成
 * 解析 → 分块 → 向量化 → 入库，实测夹具 `公司年报.pdf`（587 块、37 批 × 约 2.1s）约 **80~90 秒**。
 * 实例默认值是 `15_000`，用它会在**后端其实已经入库成功**时判失败 ⇒ 用户重传 ⇒
 * 列表里出现两份同名文档（重名不去重是**已知行为**，设计 §10.6）。
 */
const UPLOAD_TIMEOUT_MS = 300_000

/**
 * 问答超时（毫秒）：契约给的经验值是 3~10s（§7.4），取 `120_000` 是留足余量
 * —— 本地 CPU 跑 Ollama 比云端慢得多，用实例默认的 15s 会误杀真实成功但较慢的一次生成。
 */
const ASK_TIMEOUT_MS = 120_000

/**
 * `POST /api/kb/documents` —— 上传文档（契约 §7.1）
 *
 * 三条硬约束（每条都对应一次真实踩坑，见设计 §5.1）：
 *  ① **字段名必须是 `file`**：写成 `upload` 之类 → 后端 `MissingServletRequestPartException` → `40001`。
 *  ② **不要手工设置 `Content-Type`**：axios 1.x 在 `data` 是 `FormData` 时会主动**删掉**实例上的
 *     `application/json` 头、让浏览器自己带上 `boundary`；手工写死成 `multipart/form-data` 会
 *     **丢掉 boundary** → 后端 `MultipartException` → `40001`。所以下面**没有任何 headers 字段** ——
 *     这不是"忘了设置"，是刻意的。（`request.ts` 的实例默认头由 axios 自己处理，无需本文件干预。）
 *  ③ **逐请求覆盖超时**：见 `UPLOAD_TIMEOUT_MS` 的注释。
 *
 * 响应不是"已受理"而是"已入库"（§7.1）：返回 200 时入库**已经全部完成**，`data.chunkCount`
 * 即本次写入的分块数 —— 调用方拿到它就可以刷新列表了。
 */
export function uploadDocument(file: File): Promise<KbDocumentVO> {
  const formData = new FormData()
  // ★ 字段名 `file` 是契约（§7.1 的请求表只有这一个字段），写错就是 40001
  formData.append('file', file)

  return request<KbDocumentVO>({
    url: '/kb/documents',
    method: 'post',
    data: formData,
    // ★ 逐请求覆盖：上传是本组唯一可能跑到分钟级的接口（'把超时放在实例上'会连累其他接口）
    timeout: UPLOAD_TIMEOUT_MS
  })
}

/**
 * `GET /api/kb/documents` —— 文档列表（契约 §7.2）
 *
 * 只返回**当前租户**的文档：跨租户的表现是"看不见"而不是报错 ——
 * 用 `demo` 账号看不到 `admin` 上传的文档，这是**正确行为**（§7.2）。
 */
export function listDocuments(): Promise<KbDocumentListVO> {
  return request<KbDocumentListVO>({
    url: '/kb/documents',
    method: 'get'
  })
}

/**
 * `DELETE /api/kb/documents/{documentId}` —— 删除文档（契约 §7.3）
 *
 * 成功返回 `data=null`（文档行与其**全部分块**一并删除，级联由数据库的 `ON DELETE CASCADE` 保证）。
 * ⚠️ **删除无法恢复**（本轮不保存原始文件）—— 调用点必须做二次确认，文案要写明这一点。
 *
 * 两个刻意的约定（§7.3）：
 *   · 文档不存在 / 不属于当前租户 → **HTTP 200 + `10202`**（不是 404）：本项目的 HTTP 状态码只承载
 *     传输/可用性语义，业务拒绝一律 200 + 业务码；且"别人的文档"与"不存在"**完全同形**是有意的
 *     （区分开就等于给出一个"某 id 是否存在"的探测口）。
 *   · 因此调用点拿到 `10202` 时的正确处置是**刷新列表**（手上的数据过期了），不是"重试删除"。
 */
export async function deleteDocument(documentId: number): Promise<void> {
  await request<null>({
    url: `/kb/documents/${documentId}`,
    method: 'delete'
  })
}

/**
 * `POST /api/kb/ask` —— 知识库问答（契约 §7.4）
 *
 * **同步口径**（设计决策 D6）：返回 200 时答案**已经生成完**（一次提问约 3~10s）——
 * 调用点要有 loading 态，**不要按流式实现**（响应体是一次性 JSON；将来改流式是契约变更，§10.4）。
 *
 * ⚠️ 生成失败**不降级**：检索拿到片段但生成失败时，整个请求失败（HTTP **503** + `20100`），
 * 不会返回"有引用、没答案"的半成品（§7.7 的说明）—— 因为那与 `grounded=false` 长得一样，
 * 会把排查方向带偏。
 *
 * 问题**不带任何"文档范围"参数**：本轮检索范围 = 当前租户的全部文档（§7 边界 1、2）。
 */
export function askKb(req: KbAskRequest): Promise<KbAnswerVO> {
  return request<KbAnswerVO>({
    url: '/kb/ask',
    method: 'post',
    data: req,
    // ★ 逐请求覆盖：与上传同理，但量级不同（见 ASK_TIMEOUT_MS）
    timeout: ASK_TIMEOUT_MS
  })
}

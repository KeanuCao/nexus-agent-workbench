<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElMessage,
  genFileId,
  type UploadInstance,
  type UploadRawFile,
  type UploadRequestOptions
} from 'element-plus'

import {
  askKb,
  deleteDocument,
  listDocuments,
  uploadDocument,
  type KbAnswerVO,
  type KbDocumentVO,
  type KbSourceVO
} from '@/api/kb'
import { resolveHttpStatus, toDisplayMessage } from '@/api/request'

/**
 * 知识库页 —— 阶段3 · 3.5 交付物。契约来源：`docs/api/README.md` §7（四个同步接口）；
 * 页面结构与交互细节照 `docs/design/03-RAG知识库.md` §5.2 的表格逐行实现。
 *
 * 三段式：上传 → 文档表格 → 问答。网络细节全在 `api/kb.ts` 里，本页不碰 axios：
 * 鉴权头、`Result` 解包、401 跳登录、失败弹 `msg` 都由 `request.ts` 的拦截器统一做 ——
 * **本页因此有一条纪律：失败时默认不再弹第二次**（拦截器已弹过后端 `msg`），
 * 唯一的例外是"请求没到服务端"（超时 / 连接中断），见 `MAYBE_STILL_PROCESSING`。
 */

/**
 * 「请求超时，服务端可能仍在处理，请刷新列表确认后再重试」
 * —— 契约 §7.1 第 3 条规定的**原文**，问答超时同此口径（§7.4 末尾"超时文案同 §7.1 第 3 条"）。
 *
 * 为什么必须这么说：本链路全程同步，客户端断开时**服务端不会察觉**（§7.7 最后一行），
 * 上传照常完成。若把超时按"失败"提示，用户会立刻重传 ⇒ 列表里出现两份同名文档
 * （重名不去重是已知行为，设计 §10.6）—— 一次误导性文案的代价是数据被搞脏。
 */
const MAYBE_STILL_PROCESSING = '请求超时，服务端可能仍在处理，请刷新列表确认后再重试'

const documents = ref<KbDocumentVO[]>([])
const listLoading = ref(false)
const uploading = ref(false)

/** `el-upload` 实例：仅用于「成功后清空文件列表」与「limit=1 时替换旧文件」两件事 */
const uploadRef = ref<UploadInstance>()

/** 正在删除的文档 ID：只禁/loading 这一行的按钮，不锁整张表 */
const deletingId = ref<number | null>(null)

const question = ref('')
const asking = ref(false)

/** 最近一次问答的完整响应（含引用与两个观测块）；失败时清空 —— 见 `onAsk` 的注释 */
const lastAnswer = ref<KbAnswerVO | null>(null)

/**
 * 列表排序 —— 契约 §7.2 明说"**排序未约定**，前端不要依赖（需要固定顺序时自行排序）"，
 * 所以这里补上：按入库时间**倒序**（最新在上）。
 * 兜底用 `documentId` 倒序：`createdAt` 只有秒级精度（§7.5），同一秒内上传的两份文档靠它分不出先后。
 * 不做这一步的表现是：刷新后刚上传的文档可能出现在表格中间，看起来"像没上传成功"。
 */
function sortDocuments(items: KbDocumentVO[]): KbDocumentVO[] {
  return [...items].sort((a, b) => {
    const diff = Date.parse(b.createdAt) - Date.parse(a.createdAt)
    return Number.isNaN(diff) || diff === 0 ? b.documentId - a.documentId : diff
  })
}

async function loadDocuments(): Promise<void> {
  listLoading.value = true
  try {
    const data = await listDocuments()
    documents.value = sortDocuments(data.items)
  } catch (error) {
    // 失败已在拦截器里弹过后端 msg / 传输错误文案，这里**不重复弹**（设计 §5.2）。
    // 也不清空旧列表：保留上一份数据比"表格突然变空"更不容易被误读成"文档被删了"。
    console.warn('[kb] 文档列表加载失败', error)
  } finally {
    listLoading.value = false
  }
}

/** 字节数 → 人类可读（表格「大小」列）。1024 进制，与后端 `fileSize` 的口径一致 */
function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/**
 * `yyyy-MM-dd'T'HH:mm:ssXXX`（契约 §7.5）→ 本地时间 `yyyy-MM-dd HH:mm:ss`。
 *
 * ⚠️ 不做任何字符串裁剪、也不断言时区：容器内的零偏移渲染成 `Z`、Windows 本地直跑是 `+08:00`
 * （§7.5 实测），两种写法 `new Date(...)` 都能解析 —— 交给浏览器按偏移量换算成**用户本地时间**
 * 才是正解（这也是判据里"不要写死 `+00:00`"在展示层的落实）。
 * 解析不出来时**原样回显**：宁可显示原始串，也不要给用户一个 "Invalid Date"。
 */
function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number): string => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/**
 * 引用卡片标题：`文件名 · 第 N 段 · 相似度 x.xx`（设计 §5.2）。
 *   · `chunkIndex` **0 起**（§7.6）⇒ 展示的「第 N 段」必须 +1，直接用会让每一段都少 1，
 *     看起来像"引用对不上原文"；
 *   · `score` 接口给 4 位小数，展示保留 2 位（取值域 `[-1, 1]`，不需要千位分隔）。
 */
function sourceTitle(source: KbSourceVO): string {
  return `${source.fileName} · 第 ${source.chunkIndex + 1} 段 · 相似度 ${source.score.toFixed(2)}`
}

/**
 * 上传失败的**页面内**补处置。
 *
 * 默认什么都不做：`40001` / `40003` / `10201` / `10203` / `10204` / `20100` 的 `msg` 都已是
 * 可直接展示的文案，拦截器已弹过一次（设计 §5.2：页面内**不要**重复弹）。
 *
 * 唯一例外是**"请求没到服务端"**（超时 / 连接中断：`resolveHttpStatus` 为 `null`）：
 * 拦截器那条通用文案是"请求超时，后端未在超时时间内响应"，读起来就是"失败了"，
 * 会诱导用户**立刻重传** —— 而此刻后端可能正在入库、甚至已经入库完成。故：
 *   ① 先 `closeAll()` 关掉那条会误导的通用提示（同一次用户动作，不会误伤别的消息）；
 *   ② 换成契约规定的措辞（`MAYBE_STILL_PROCESSING`）；
 *   ③ **顺手刷新列表** —— 后端可能已经入库了，这正是"请刷新列表确认"最省事的落实方式。
 */
async function handleUploadFailure(error: unknown): Promise<void> {
  if (resolveHttpStatus(error) !== null) {
    console.warn('[kb] 上传失败（后端已应答，提示见全局消息）', error)
    return
  }
  ElMessage.closeAll()
  ElMessage.warning(MAYBE_STILL_PROCESSING)
  await loadDocuments()
}

/**
 * `el-upload` 的自定义上传 —— **必须走 `:http-request`**（设计 §5.1-1 / 契约 §7.1 第 2 条末句）：
 * `el-upload` 默认那套（`action` + 自带 XHR）**绕过 `request.ts`** ⇒ token 失效时 401 不跳登录页、
 * `Result` 不解包、失败文案不统一。
 * **判据**：把 localStorage 里的 token 改坏再上传 → 应当跳登录页（而不是只在控制台里看到一条错误）。
 *
 * 关于设计 §5.1-1 提到的"再手工调用 `onSuccess` / `onError`"：**不需要，而且不该手工调**。
 * `el-upload` 内部就是
 *   `const request = httpRequest(options); if (request instanceof Promise) request.then(options.onSuccess, options.onError)`
 * （element-plus 2.14.5 的 upload-content 实现）—— 只要本函数是 `async`（必然返回 Promise），
 * 成功/失败就**由"正常返回"与"抛异常"表达**。手工再调一次会让它的状态机收到两次同向事件，
 * 把同一个文件状态连写两遍。
 */
async function handleUploadRequest(options: UploadRequestOptions): Promise<KbDocumentVO> {
  uploading.value = true
  try {
    const document = await uploadDocument(options.file)

    // 成功（设计 §5.2）：`已入库 N 段` + 刷新列表 + 清空文件列表。
    // N 取响应里的 `chunkCount` —— 同步口径下它就是本次真正写入的分块数（§7.1）。
    ElMessage.success(`已入库 ${document.chunkCount} 段`)
    await loadDocuments()
    uploadRef.value?.clearFiles()
    return document
  } catch (error) {
    await handleUploadFailure(error)
    // 抛出 ⇒ el-upload 把该文件标成失败态（内部 `.then(onSuccess, onError)`）。
    // 归一成 Error 是为了让 EP 的 `onError` 钩子拿到它声明的类型；业务失败本就是 Error 子类。
    throw error instanceof Error ? error : new Error(toDisplayMessage(error))
  } finally {
    uploading.value = false
  }
}

/**
 * `limit=1` 的边界处置（`on-exceed`）。
 * EP 的默认行为是**静默忽略**新选的文件，表现是"选了文件什么都没发生"——
 * 尤其在上一次上传失败（列表里还留着那一条 failed 记录）、用户想换一个文件重试时，会以为页面坏了。
 * 处置：**用新文件替换旧文件** —— 清空列表 → 重新分配 uid → 加进列表 → 发起上传。
 *
 * ⚠️ 最后那句 `submit()` **不能省**（这是照抄 EP 示例最容易漏掉的一步）：
 * `handleStart` 只做"往文件列表里加一条 `status='ready'` 的记录"，
 * 真正发请求的是 upload-content 内部的 `upload()`，而它的调用点**只有两个** ——
 * change 事件（走 `uploadFiles()`）与 `submit()`（element-plus 2.14.5 源码可查）。
 * 少了 `submit()` 的表现是：文件出现在列表里、状态永远停在 ready、**永远不上传**，
 * 而控制台与网络面板都没有任何报错 —— 属于"看起来生效了其实没有"的那类故障。
 */
function handleExceed(files: File[]): void {
  const upload = uploadRef.value
  // `on-exceed` 给的 `File` 在运行期就是 EP 从 input 里取到、马上会交给 `handleStart` 的那个对象，
  // 类型上它只声明到 `File` —— 这里的断言是补这个缺口（EP 文档的示例同样这么写）
  const next = files[0] as UploadRawFile | undefined
  if (!upload || !next) {
    return
  }
  upload.clearFiles()
  next.uid = genFileId()
  upload.handleStart(next)
  upload.submit()
}

/**
 * 删除文档 —— 二次确认由模板里的 `el-popconfirm` 给出（文案写明"无法恢复"，设计 §5.2）。
 *
 * **成功与失败都刷新列表**，理由不同：
 *   · 成功：该文档与它的全部分块已被级联删除（§7.3）；
 *   · 失败：`10202` 的含义是"文档不存在或已被删除" —— 本地还留着这一行只是因为我们手上的列表过期了
 *     ⇒ 刷新是**纠正**，不是"重试删除"（这也解释了为什么它不该在页面上再弹一次：
 *     拦截器已经弹过后端 msg 了）。
 */
async function handleDelete(document: KbDocumentVO): Promise<void> {
  deletingId.value = document.documentId
  try {
    await deleteDocument(document.documentId)
    ElMessage.success('已删除')
  } catch (error) {
    console.warn('[kb] 删除文档失败（后端已应答，提示见全局消息）', error)
  } finally {
    deletingId.value = null
    // 放在 finally 里：`deletingId` 先复位，刷新期间那一行的 loading 不会一直转
    await loadDocuments()
  }
}

/**
 * 提问（回车或点按钮）。
 *
 * ⚠️ **无文档时不拦**：检索范围为空 ⇒ 后端返回 `grounded=false` + 固定文案，
 * 那是**正确行为**（设计 §5.2 空态行明确要求不要在前端拦）—— 拦掉反而会掩盖"知识库为空"这个真因。
 *
 * 失败时清空上一次的答案：契约 §7.7 刻意**不给"部分成功"**（生成失败就整体失败），
 * 页面保留一个不属于本次提问的旧答案只会让"这次到底答没答"变得含糊 —— 与它一致的处置是
 * "宁可什么都不显示，也不要显示错的东西"。
 */
async function onAsk(): Promise<void> {
  if (asking.value) {
    return
  }
  const text = question.value.trim()
  if (text === '') {
    ElMessage.warning('请输入问题后再提问')
    return
  }

  asking.value = true
  try {
    // 刻意不传 `topK`：缺省 = 用后端配置值（§7.4），页面不暴露这个旋钮
    lastAnswer.value = await askKb({ question: text })
  } catch (error) {
    lastAnswer.value = null
    if (resolveHttpStatus(error) !== null) {
      // 后端已应答（40001 / 20100 / 50000…）：拦截器已弹过 msg，这里不重复弹
      console.warn('[kb] 问答失败（后端已应答，提示见全局消息）', error)
    } else {
      // 超时 / 连接中断：文案同 §7.1 第 3 条（§7.4 末尾），同样要先关掉那条通用提示
      ElMessage.closeAll()
      ElMessage.warning(MAYBE_STILL_PROCESSING)
    }
  } finally {
    asking.value = false
  }
}

/**
 * 回车提问、Shift+回车换行。
 * 用 `@keydown` + `preventDefault` 而不是设计 §5.2 字面写的 `@keyup.enter`：`keyup` 时那个换行
 * **已经**被插进输入框了，要提交还得再去掉一个尾部换行（多一处状态修补），`keydown` 里拦掉才干净。
 * ⚠️ 必须放行输入法组合态（中文输入法下回车是"确认候选词"，不是发送）——与 `ChatView` 同一处置。
 */
function onQuestionKeydown(event: KeyboardEvent): void {
  if (event.isComposing || event.keyCode === 229) {
    return
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void onAsk()
  }
}

onMounted(() => {
  void loadDocuments()
})
</script>

<template>
  <div class="page">
    <h2 class="page-title">知识库</h2>

    <!--
      本轮边界（设计 §5.2 顶部提示）：三条各对应一个"用户以为坏了"的误报 ——
      检索范围、追问没有上下文、删了找不回来（且改分块参数后必须重传）。
    -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本轮边界"
      description="一个租户一个知识库（检索范围 = 当前租户的全部文档）；不做多轮对话（每次提问独立检索，不带上下文）；删除文档会同时删除其向量且无法恢复（本轮不保存原文件，改分块参数后需重新上传）。"
    />

    <!-- ① 上传区 -->
    <el-card shadow="never">
      <template #header>
        <div class="kb-card-header">
          <span>上传文档</span>
          <span class="kb-hint">仅支持 TXT / PDF，单个文件 ≤ 10MB</span>
        </div>
      </template>

      <!--
        ★ `:http-request` 是本页最要紧的一个属性（设计 §5.1-1）：
        不写它，el-upload 会用自己的 XHR 直连 `action`，绕过 request.ts 的全部拦截器 ——
        401 不跳登录页、Result 不解包、失败文案不统一。
      -->
      <el-upload
        ref="uploadRef"
        drag
        show-file-list
        :limit="1"
        accept=".txt,.pdf"
        :disabled="uploading"
        :http-request="handleUploadRequest"
        :on-exceed="handleExceed"
      >
        <div class="kb-upload-body">
          <div class="kb-upload-title">
            {{ uploading ? '解析并向量化中…' : '把文件拖到此处，或点击选择' }}
          </div>
          <div class="kb-hint">
            上传是同步的：返回即已解析、分块、向量化并入库（大文件实测约 2 分钟出头、随机器负载浮动，请勿关闭页面）
          </div>
        </div>
      </el-upload>
    </el-card>

    <!-- ② 文档表格 -->
    <el-card shadow="never">
      <template #header>
        <div class="kb-card-header">
          <span>文档（{{ documents.length }}）</span>
          <el-button text :loading="listLoading" @click="loadDocuments">刷新</el-button>
        </div>
      </template>

      <el-table
        v-loading="listLoading"
        :data="documents"
        row-key="documentId"
        empty-text="还没有文档，先上传一份 TXT 或 PDF"
      >
        <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="fileType" label="类型" width="90" />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <!-- 字符数与分块数是排查解析质量的两眼数据（契约 §7.5） -->
        <el-table-column prop="charCount" label="字符数" width="100" />
        <el-table-column prop="chunkCount" label="分块数" width="90" />
        <el-table-column label="入库时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-popconfirm
              width="260"
              title="删除后其向量一并移除，且无法恢复（本轮不保存原文件）。确定删除？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              confirm-button-type="danger"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button
                  type="danger"
                  text
                  size="small"
                  :loading="deletingId === row.documentId"
                >
                  删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ③ 问答区 -->
    <el-card shadow="never">
      <template #header>
        <div class="kb-card-header">
          <span>知识库问答</span>
          <span class="kb-hint">单轮问答：每次提问独立检索，不带上下文</span>
        </div>
      </template>

      <el-input
        v-model="question"
        type="textarea"
        :rows="3"
        resize="vertical"
        maxlength="500"
        show-word-limit
        :disabled="asking"
        placeholder="针对已入库的文档提问，回车提交（Shift + 回车换行）"
        @keydown="onQuestionKeydown"
      />

      <div class="kb-actions">
        <!-- 文档为空时**不禁用**：会得到 grounded=false，那是正确行为（设计 §5.2 空态行） -->
        <el-button type="primary" :loading="asking" @click="onAsk">
          {{ asking ? '检索并生成中…' : '提问' }}
        </el-button>
        <span class="kb-hint">答案只依据检索到的片段生成，引用见下方</span>
      </div>

      <!--
        ★★ 答案与引用片段一律**插值渲染**（`{{ }}`），**绝不用 `v-html`**（设计 §5.1-3 / 契约 §7.6）：
        它们都是用户上传的外部文本经模型生成/原文回显，PDF/TXT 里出现 `<script>` 或
        `<img onerror=...>` 在 v-html 下就是一次 XSS。
      -->
      <div v-if="lastAnswer && lastAnswer.grounded" class="kb-answer">
        <div class="kb-answer-label">答案</div>
        <!-- 模型输出里的换行是结构信息：CSS 默认会压成一行，看起来像"输出坏了" -->
        <div class="kb-answer-text">{{ lastAnswer.answer }}</div>

        <div class="kb-answer-label">引用片段（{{ lastAnswer.sources.length }} 条）</div>
        <el-collapse>
          <el-collapse-item
            v-for="source in lastAnswer.sources"
            :key="`${source.documentId}-${source.chunkIndex}`"
            :title="sourceTitle(source)"
          >
            <!-- 原文的换行原样保留（§7.6）⇒ pre-wrap，否则引用看起来像"解析坏了" -->
            <div class="kb-source-text">{{ source.content }}</div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!--
        `grounded=false`：只展示**后端给的** answer 文案（§7.6 的固定常量），前端不自己拼一句；
        且**不显示引用卡片** —— 此时 `sources` 是空数组（不是 null），渲染出来只会是一个空壳。
      -->
      <el-alert
        v-else-if="lastAnswer"
        class="kb-answer-info"
        type="info"
        :closable="false"
        show-icon
        :title="lastAnswer.answer"
      />

      <!--
        两个观测块（§7.6 称它们是"验收与现场排查的唯一可见证据"）：
        "答案不对"到底是检索没找到（hits=0）还是生成跑偏（hits>0 但答案不着调），看这一行就能分。
        放在答案下方的小字里，不占视觉主位。
      -->
      <div v-if="lastAnswer" class="kb-observability">
        检索：topK {{ lastAnswer.retrieval.topK }} · 命中 {{ lastAnswer.retrieval.hits }} 条 ·
        阈值 {{ lastAnswer.retrieval.threshold }} · {{ lastAnswer.retrieval.durationMs }}ms
        <template v-if="lastAnswer.generation">
          ｜生成：{{ lastAnswer.generation.model }}（{{ lastAnswer.generation.modelType }}）·
          {{ lastAnswer.generation.durationMs }}ms
        </template>
        <template v-else>｜未调用模型（检索无命中）</template>
      </div>
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

.kb-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.kb-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.kb-upload-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 0;
}

.kb-upload-title {
  font-size: 14px;
}

.kb-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.kb-answer {
  margin-top: 16px;
}

.kb-answer-label {
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.kb-answer-text {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  line-height: 1.7;
  /* 保留换行与空格，同时允许长文本断行（设计 §5.1-5） */
  white-space: pre-wrap;
  word-break: break-word;
}

.kb-source-text {
  padding: 4px 0;
  color: var(--el-text-color-regular);
  line-height: 1.7;
  /* ★ 引用即原文：换行是结构信息，压成一行会看起来像"解析坏了"（设计 §5.1-5） */
  white-space: pre-wrap;
  word-break: break-word;
}

.kb-answer-info {
  margin-top: 16px;
}

.kb-observability {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>

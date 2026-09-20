<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'

import {
  streamChat,
  type ChatMessage,
  type ChatModelType,
  type ChatRequest,
  type ChatStreamDone,
  type ChatStreamFailure,
  type ChatStreamMeta
} from '@/api/chat'

/**
 * 对话页 —— 阶段2 · 2.4 交付物。契约来源：`docs/api/README.md` §6（流式对话）。
 *
 * 本页只做三件事：攒历史（`bubbles`）、调 `streamChat`、把回调画到屏幕上。
 * 网络与协议细节全在 `api/chat.ts` + `utils/sse.ts` 里 —— 页面不碰 SSE 文本、不判状态码。
 *
 * 与 axios 链路的差别：流式接口不走拦截器，所以**错误提示也由本页自己弹**（`onError` 里那句
 * `ElMessage.error`），不像普通接口那样由 request.ts 统一弹。
 */

/** 气泡下方的状态标记类型（决定文案颜色） */
type NoteType = 'error' | 'warning' | 'info'

/**
 * 屏幕上的一个气泡。它**不是** `ChatMessage` —— 这一点是刻意的：
 * `message` 是发往后端的 wire 结构，`note` / `model` / `streaming` 只是**展示态**。
 * 混合的代价很实在：`messages` 是全量上送的（设计决策 D9），若把「（已停止）」这类标记
 * 写进 `message.content`，它下一轮就会被当作模型输出喂回模型（设计 §5.5 明确要避免的事）。
 */
interface ChatBubble {
  /** 上送后端的消息体，本页唯一的"内容真源" */
  message: ChatMessage
  /** 正文是否还在流式生成中（只有最后一条 assistant 气泡会为 true） */
  streaming?: boolean
  /** 展示用状态标记：失败 / 已停止 / 被截断；**永不写进 content** */
  note?: string
  noteType?: NoteType
  /** 实际生成该条的模型真实名（来自 `meta` 帧的 `model`，验收 2.2-1 看的就是它） */
  model?: string
}

const modelOptions: Array<{ label: string; value: ChatModelType }> = [
  { label: '本地 Qwen（Ollama）', value: 'OLLAMA' },
  { label: '云端 DeepSeek', value: 'DEEPSEEK' }
]

/**
 * 默认选本地 Qwen：演示不依赖外部密钥（云端 DeepSeek 需要 `docker-compose/.env.local` 里的
 * `DEEPSEEK_API_KEY`，见设计 §6.4）。没配密钥时应以一个明确的 `20100` 暴露出来，
 * 而不是让"点开就是本地模型"变成一次静默降级。
 */
const model = ref<ChatModelType>('OLLAMA')

const bubbles = ref<ChatBubble[]>([])
const input = ref('')

/** 是否正在流式生成：控制发送按钮禁用 / 停止按钮显示（设计 §5） */
const streaming = ref(false)

/** 「停止生成」用（设计 §5.4）；每次发送重建，收尾后置空 */
const abortController = ref<AbortController | null>(null)

/** 页面内常驻的最后一次失败原因（全局 ElMessage 会自动消失，这条便于对照排查） */
const lastError = ref('')

const listRef = ref<HTMLElement | null>(null)

/** 紧跟流式输出滚到底部：不自动滚的话长回答会"看着像卡住了" */
async function scrollToBottom(): Promise<void> {
  await nextTick()
  const list = listRef.value
  if (list) {
    list.scrollTop = list.scrollHeight
  }
}

/** 取最后一个气泡（约定：流式期间的最后一个气泡一定是本轮 assistant） */
function lastAssistant(): ChatBubble | null {
  const last = bubbles.value[bubbles.value.length - 1]
  return last && last.message.role === 'assistant' ? last : null
}

/**
 * 一轮结束（失败 / 停止）时对历史做收尾 —— 设计 §5.5：
 * **assistant 一个字都没吐 → 回滚整轮**（连带那条 user 消息），否则保留两条并标记不完整。
 *
 * 为什么空文本要连 user 一起回滚：一次没有任何回答的提问留在历史里毫无价值，
 * 还会让下一轮的上下文里多出一条悬空的 user（虽然契约允许连续同角色，但没有理由留着）。
 * 唯一不能接受的副作用是"用户打的字蒸发"——所以回滚时把正文放回输入框。
 */
function settleRound(note: string, noteType: NoteType): void {
  const assistant = lastAssistant()
  if (!assistant) {
    return
  }

  if (assistant.message.content === '') {
    const user = bubbles.value[bubbles.value.length - 2]
    bubbles.value.splice(-2, 2)
    if (user && user.message.role === 'user' && input.value === '') {
      input.value = user.message.content
    }
    return
  }

  assistant.note = note
  assistant.noteType = noteType
}

/** `done` 帧里非 `stop` 的两种截断原因（契约 §6.2：length / timeout） */
const TRUNCATED_NOTES: Record<string, string> = {
  length: '输出被截断：触达模型 token 上限',
  timeout: '输出被截断：服务端软上限超时'
}

function onMeta(meta: ChatStreamMeta): void {
  const assistant = lastAssistant()
  if (assistant) {
    assistant.model = meta.model
  }
}

function onDelta(content: string): void {
  const assistant = lastAssistant()
  if (!assistant) {
    return
  }
  // ★ 追加，不是替换（设计 §5.1-3）：delta 是**增量片段**，写错的表现是"屏幕上永远只有最后一个字"
  assistant.message.content += content
  void scrollToBottom()
}

function onDone(done: ChatStreamDone): void {
  const assistant = lastAssistant()
  if (assistant && done.finishReason !== 'stop') {
    assistant.note = TRUNCATED_NOTES[done.finishReason] ?? '输出被截断'
    assistant.noteType = 'warning'
  }
}

function onError(failure: ChatStreamFailure): void {
  // 保留已渲染文本 + 标记该气泡失败 + 弹提示（设计 §5.4）
  settleRound(`生成失败：${failure.msg}`, 'error')
  lastError.value = failure.msg
  ElMessage.error(failure.msg)
}

/**
 * 用户点「停止生成」。**这不是失败**（设计 §5.4）：
 * `abort()` 会让读取循环抛 `AbortError`，`api/chat.ts` 已按 `err.name === 'AbortError'` 分流，
 * 走到这里而不是 `onError`。所以这里的措辞是"已停止"，不弹错误提示。
 */
function onAborted(): void {
  settleRound('已停止生成（回答不完整）', 'info')
}

function onFinish(): void {
  streaming.value = false
  abortController.value = null
  void scrollToBottom()
}

/**
 * 组装请求体：历史**全量上送**（设计决策 D9，后端无状态）。
 *
 * 两个细节：
 *   ① 过滤掉正文为空的消息 —— 契约要求 `content` 去空白后非空（否则 40001）。
 *      "正常 done 但一帧 delta 都没发"这种合法情形就会留下一个空 assistant 气泡，
 *      不能让它把下一次提问整条打成 40001。
 *   ② 天然满足"最后一条必须是 user"：本函数在 push 本轮 assistant 占位气泡**之前**调用。
 */
function buildRequest(): ChatRequest {
  return {
    messages: bubbles.value
      .filter((bubble) => bubble.message.content.trim() !== '')
      .map((bubble) => bubble.message),
    modelType: model.value
  }
}

async function onSend(): Promise<void> {
  if (streaming.value) {
    // 流式期间发送按钮已置灰；这里再兜一层，避免回车等旁路重复发起
    return
  }
  const text = input.value.trim()
  if (text === '') {
    ElMessage.warning('请输入内容后再发送')
    return
  }

  lastError.value = ''
  bubbles.value.push({ message: { role: 'user', content: text } })

  // ★ 顺序要紧：请求体必须在 push assistant 占位气泡**之前**组装，否则最后一条会是 assistant（契约 §6.1）
  const payload = buildRequest()
  bubbles.value.push({ message: { role: 'assistant', content: '' }, streaming: true })

  input.value = ''
  streaming.value = true
  abortController.value = new AbortController()
  void scrollToBottom()

  // 本调用**不会 reject**：所有失败都经 handlers 投递，`onFinish` 保证恰好调用一次
  // （见 api/chat.ts 的收尾闸门），"生成中"状态因此不可能永久残留。
  await streamChat(payload, { onMeta, onDelta, onDone, onError, onAborted, onFinish }, abortController.value.signal)
}

/** 停止生成：中断 fetch（进而中断 reader.read()），后续由 onAborted → onFinish 收尾 */
function onStop(): void {
  abortController.value?.abort()
}

/**
 * 回车发送、Shift+回车换行。
 *
 * ⚠️ 必须放行输入法组合态：中文输入法下回车是"确认候选词"，不是"发送"——
 * 不做这个判断（`isComposing`）会把候选词框直接卡住，`keyCode === 229` 是老浏览器的同一信号。
 */
function onInputKeydown(event: KeyboardEvent): void {
  if (event.isComposing || event.keyCode === 229) {
    return
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void onSend()
  }
}
</script>

<template>
  <div class="page">
    <h2 class="page-title">对话</h2>

    <!-- 设计 §5.5 / 决策 D9：本阶段不落库。不说这一句，用户会以为聊天记录坏了 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本阶段对话不落库"
      description="多轮上下文由浏览器持有、每次全量上送后端（后端无状态）；刷新页面后上下文清空，属预期行为。"
    />

    <el-card shadow="never">
      <template #header>
        <div class="chat-toolbar">
          <span class="chat-toolbar-label">模型</span>
          <el-select v-model="model" class="chat-model" :disabled="streaming">
            <el-option
              v-for="item in modelOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <span class="chat-toolbar-hint">流式输出中，可随时点「停止生成」</span>
        </div>
      </template>

      <div ref="listRef" class="chat-list">
        <el-empty v-if="bubbles.length === 0" description="输入问题开始对话（当前历史为空）" />

        <div v-for="(bubble, index) in bubbles" :key="index" class="chat-bubble" :class="`chat-bubble--${bubble.message.role}`">
          <div class="chat-role">
            <span>{{ bubble.message.role === 'user' ? '我' : '模型' }}</span>
            <!-- 真实模型名来自 meta 帧（契约 §6.2），验收「选本地/云端分别命中对应实现」就看这里 -->
            <el-tag v-if="bubble.model" size="small" type="info">{{ bubble.model }}</el-tag>
          </div>

          <div class="chat-content">
            <span v-if="bubble.streaming && bubble.message.content === ''" class="chat-thinking">思考中…</span>
            <span class="chat-text">{{ bubble.message.content }}</span>
            <span v-if="bubble.streaming" class="chat-cursor">▍</span>
          </div>

          <!-- 状态标记单独一行：它不进 message.content，不会被回送给模型 -->
          <div v-if="bubble.note" class="chat-note" :class="`chat-note--${bubble.noteType}`">
            {{ bubble.note }}
          </div>
        </div>
      </div>

      <el-alert
        v-if="lastError"
        class="chat-last-error"
        type="error"
        :closable="false"
        show-icon
        :title="lastError"
      />

      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        resize="vertical"
        placeholder="输入问题，回车发送（Shift + 回车换行）"
        @keydown="onInputKeydown"
      />

      <div class="chat-actions">
        <el-button type="primary" :disabled="streaming" @click="onSend">
          {{ streaming ? '生成中…' : '发送' }}
        </el-button>
        <!-- 流式期间才出现「停止生成」：它同时是验收 2.3-2 的复现动作 -->
        <el-button v-if="streaming" type="danger" plain @click="onStop">停止生成</el-button>
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

.chat-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.chat-toolbar-label {
  font-size: 14px;
}

.chat-model {
  width: 220px;
}

.chat-toolbar-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.chat-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-height: 52vh;
  overflow-y: auto;
  padding-right: 8px;
}

.chat-bubble {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 80%;
}

.chat-bubble--user {
  align-self: flex-end;
  align-items: flex-end;
}

.chat-bubble--assistant {
  align-self: flex-start;
}

.chat-role {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.chat-content {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  line-height: 1.7;
  /* 保留模型输出里的换行与空格，同时允许长文本断行 */
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-bubble--user .chat-content {
  background: var(--el-color-primary-light-9);
}

.chat-thinking {
  color: var(--el-text-color-secondary);
}

.chat-cursor {
  animation: chat-cursor-blink 1s step-end infinite;
}

@keyframes chat-cursor-blink {
  50% {
    opacity: 0;
  }
}

.chat-note {
  font-size: 12px;
}

.chat-note--error {
  color: var(--el-color-danger);
}

.chat-note--warning {
  color: var(--el-color-warning);
}

.chat-note--info {
  color: var(--el-text-color-secondary);
}

.chat-last-error {
  margin-bottom: 12px;
}

.chat-actions {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}
</style>

<template>
  <div class="chat-shell">
    <!-- Sourcegraph 式搜索框 -->
    <div class="search-area" :class="{ 'has-results': messages.length > 0 }">
      <div class="search-container">
        <div class="search-bar">
          <svg class="search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
          </svg>
          <input
            ref="searchInput"
            v-model="question"
            @keydown.enter="send"
            placeholder="搜索代码、文档...  例如：createOrder 方法在哪？"
            :disabled="loading"
          />
          <button v-if="question.trim()" class="search-clear" @click="question = ''">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
          <button class="search-submit" @click="send" :disabled="loading || !question.trim()">
            <svg v-if="!loading" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polyline points="22 2 15 22 11 13 2 9 22 2"/></svg>
            <span v-else class="spinner"></span>
          </button>
        </div>
        <div v-if="currentProjectName" class="search-context">
          当前项目：<strong>{{ currentProjectName }}</strong>
        </div>
      </div>
    </div>

    <!-- 路由/阶段标签 -->
    <div v-if="currentRoute" class="route-indicator">
      <span class="route-badge">{{ currentRoute }}</span>
      <span v-if="currentStep" class="step-badge">步骤 {{ currentStep }}</span>
      <span v-if="fromCache" class="cache-badge">来自缓存</span>
    </div>

    <!-- 消息列表 -->
    <div class="messages-area" ref="messageList" v-if="messages.length > 0">
      <div v-for="(msg, i) in messages" :key="i" class="msg-group">
        <div v-if="msg.role === 'user'" class="msg msg-user">
          <div class="msg-avatar user-avatar">U</div>
          <div class="msg-bubble user-bubble">
            <p>{{ msg.content }}</p>
          </div>
        </div>

        <div v-if="msg.role === 'assistant'" class="msg msg-assistant">
          <div class="msg-avatar assistant-avatar">DK</div>
          <div class="msg-bubble assistant-bubble">
            <div class="msg-content" v-html="renderContent(msg.content)" />
            <div v-if="msg.sources && msg.sources.length" class="msg-sources">
              <span class="sources-label">来源</span>
              <span v-for="(src, j) in msg.sources" :key="j" class="source-chip" style="cursor:pointer" @click="reviewSource(src, msg)">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                {{ src.file }}#{{ src.seq }}
                <span class="review-badge">🔒</span>
              </span>
            </div>
            <!-- Feynman 检验入口 -->
            <div v-if="msg.completed && !msg.feynmanStarted" class="msg-actions">
              <button class="action-chip" @click="startFeynman(msg, i)">
                🧪 检验理解
              </button>
              <button class="action-chip" @click="extractKnowledge(msg, i)">
                📝 提炼笔记
              </button>
            </div>
            <!-- Feynman 面板 -->
            <FeynmanPanel
              v-if="msg.showFeynman"
              :visible="msg.showFeynman"
              :conversation-id="conversationId"
              :question="messages[i-1]?.content || ''"
              :answer="msg.content"
              @skip="msg.showFeynman = false"
              @complete="onFeynmanComplete(msg, $event)"
            />
            <!-- 安全审查报告 -->
            <SafetyReport
              v-if="msg.safetyReport"
              :report="msg.safetyReport"
              :loading="msg.safetyLoading"
            />
          </div>
        </div>
      </div>

      <div v-if="loading" class="msg msg-assistant">
        <div class="msg-avatar assistant-avatar">DK</div>
        <div class="msg-bubble assistant-bubble">
          <div class="typing-line">
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
          </div>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ddd8cc" stroke-width="1.5"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
      </div>
      <h3>搜索代码与知识</h3>
      <p class="text-muted">输入问题，从代码和文档中查找答案</p>
      <div class="hints">
        <span class="hint-tag" @click="question = '订单超时怎么处理'; send()">订单超时怎么处理</span>
        <span class="hint-tag" @click="question = 'createOrder 方法在哪'; send()">createOrder 方法在哪</span>
        <span class="hint-tag" @click="question = '支付网关为什么限流'; send()">支付网关为什么限流</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useProjectStore } from '../stores/useProjectStore.js'
import FeynmanPanel from '../components/FeynmanPanel.vue'
import SafetyReport from '../components/SafetyReport.vue'

const route = useRoute()
const projectStore = useProjectStore()

const messages = ref([])
const question = ref('')
const loading = ref(false)
const currentProjectId = ref(route.params.projectId || '')
const currentProjectName = ref('')
const currentRoute = ref('')
const currentStep = ref('')
const fromCache = ref(false)
const messageList = ref(null)
const searchInput = ref(null)

let es = null
const conversationId = ref(Date.now().toString())

function startFeynman(msg, index) {
  msg.feynmanStarted = true
  msg.showFeynman = true
}

function onFeynmanComplete(msg, result) {
  msg.showFeynman = false
  if (result.passed) {
    msg.feynmanPassed = true
  }
}

async function extractKnowledge(msg, index) {
  const prevMsg = messages.value[index - 1]
  const question = prevMsg?.content || ''
  const answer = msg.content

  try {
    msg.extracting = true
    const resp = await fetch('/api/study/extract', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question,
        answer,
        codeContext: null,
        projectId: currentProjectId.value || null
      })
    })
    if (!resp.ok) {
      const errData = await resp.json().catch(() => ({}))
      throw new Error(errData.message || `HTTP ${resp.status}`)
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const text = decoder.decode(value)
      const lines = text.split('\n').filter(l => l.startsWith('data: '))
      for (const line of lines) {
        const data = JSON.parse(line.slice(6))
        if (data.stage) {
          console.log('提取阶段:', data.stage)
        } else if (data.title) {
          msg.extractedNote = data
        }
      }
    }
  } catch (err) {
    console.error('知识提取失败', err)
    msg.extractError = err.message
  } finally {
    msg.extracting = false
  }
}

import { reviewCodeRange } from '../api/index.js'

async function reviewSource(src, msg) {
  if (msg.safetyLoading) return
  msg.safetyLoading = true
  msg.safetyReport = null
  try {
    const filePath = src.file
    const seq = src.seq || 1
    const result = await reviewCodeRange(
      currentProjectId.value || null,
      filePath, seq, seq + 10
    )
    msg.safetyReport = result.data || result
  } catch (err) {
    msg.safetyReport = {
      passed: false,
      summary: '审查失败: ' + err.message,
      score: 0, issues: [],
      reviewedFile: src.file
    }
  } finally {
    msg.safetyLoading = false
  }
}

onMounted(async () => {
  await projectStore.ensureLoaded()
  if (currentProjectId.value) {
    const p = projectStore.projects.find(pr => String(pr.id) === String(currentProjectId.value))
    if (p) currentProjectName.value = p.displayName || p.name || ''
  } else if (projectStore.projects.length > 0) {
    currentProjectName.value = projectStore.projects[0].name || ''
  }
  // 从 URL query 中获取初始问题
  if (route.query.question) {
    question.value = route.query.question
    nextTick(() => send())
  } else {
    nextTick(() => searchInput.value?.focus())
  }
})

async function send() {
  const q = question.value.trim()
  if (!q || loading.value) return
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  loading.value = true
  currentRoute.value = ''
  currentStep.value = ''
  fromCache.value = false

  const params = new URLSearchParams({
    question: q,
    conversationId: Date.now().toString()
  })
  if (currentProjectId.value) params.set('projectId', currentProjectId.value)

  es = new EventSource(`/api/chat/stream?${params}`)
  let answer = ''

  es.addEventListener('token', (e) => {
    answer += e.data
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant') {
      last.content = answer
    } else {
      messages.value.push({ role: 'assistant', content: answer, sources: [] })
    }
    scrollToBottom()
  })

  es.addEventListener('source', (e) => {
    try {
      const sources = JSON.parse(e.data)
      const last = messages.value[messages.value.length - 1]
      if (last?.role === 'assistant') last.sources = sources
    } catch {}
  })

  // 路由事件
  es.addEventListener('route', (e) => {
    currentRoute.value = e.data
  })

  // 阶段事件
  es.addEventListener('phase', (e) => {
    currentRoute.value = e.data
  })

  // 步骤进度（Agent 模式）
  es.addEventListener('step', (e) => {
    currentStep.value = e.data
  })

  // 缓存命中
  es.addEventListener('cache', (e) => {
    fromCache.value = e.data === 'true'
  })

  es.addEventListener('done', () => {
    es.close(); loading.value = false; scrollToBottom()
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant') last.completed = true
  })
  es.addEventListener('error', () => { es.close(); loading.value = false })
}

function scrollToBottom() {
  nextTick(() => {
    if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
  })
}

function renderContent(text) {
  if (!text) return ''
  return text
    .replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\n/g, '<br>')
    .replace(/```(\w*)\n?([\s\S]*?)```/g, '<pre class="code-block">$2</pre>')
    .replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')
    .replace(/\[文件:\s*([^\]]+)\]/g, '<span class="code-ref">📄 $1</span>')
    .replace(/\[片段(\d+)\]/g, '<span class="code-ref">📎 [$1]</span>')
}

onUnmounted(() => { if (es) es.close() })
</script>

<style scoped>
.chat-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #faf9f5;
}

/* ── Search Area ── */
.search-area {
  padding: 40px 24px 24px;
  display: flex;
  justify-content: center;
  transition: padding 0.2s;
}
.search-area.has-results {
  padding: 16px 24px;
  border-bottom: 1px solid #e8e3d8;
}

.search-container {
  width: 100%;
  max-width: 720px;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px 0 16px;
  border: 1.5px solid #e8e3d8;
  border-radius: 12px;
  background: #fff;
  transition: all 0.15s;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}
.search-bar:focus-within {
  border-color: #c15f3c;
  box-shadow: 0 0 0 3px #c15f3c18;
}

.search-icon {
  color: #c9c3b5;
  flex-shrink: 0;
}

.search-bar input {
  flex: 1;
  border: none;
  outline: none;
  padding: 12px 0;
  font-size: 15px;
  background: transparent;
  color: #2d2a24;
}
.search-bar input::placeholder { color: #c9c3b5; }

.search-clear {
  border: none;
  background: none;
  padding: 6px;
  border-radius: 6px;
  cursor: pointer;
  color: #c9c3b5;
}
.search-clear:hover { color: #8a857a; }

.search-submit {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: #c15f3c;
  color: #fff;
  cursor: pointer;
  transition: background 0.12s;
  flex-shrink: 0;
}
.search-submit:hover { background: #d47a4a; }
.search-submit:disabled { background: #ddd8cc; cursor: not-allowed; }

.spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.search-context {
  margin-top: 8px;
  font-size: 12px;
  color: #8a857a;
}

/* ── Route Indicator ── */
.route-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 24px;
  background: #f8f6f0;
  border-bottom: 1px solid #e8e3d8;
  font-size: 11px;
}
.route-badge {
  padding: 2px 8px;
  background: #f5ede8;
  color: #c15f3c;
  border-radius: 8px;
  font-weight: 500;
}
.step-badge {
  padding: 2px 8px;
  background: #e8f5e9;
  color: #2e7d32;
  border-radius: 8px;
}
.cache-badge {
  padding: 2px 8px;
  background: #e3f2fd;
  color: #1565c0;
  border-radius: 8px;
  animation: fadeIn 0.3s;
}
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

/* ── Messages ── */
.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px 80px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  align-items: center;
}

.msg-group {
  width: 100%;
  max-width: 720px;
}

.msg {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
  margin-top: 4px;
}
.user-avatar { background: #e8e3d8; color: #5a5548; }
.assistant-avatar { background: #c15f3c; color: #fff; }

.msg-bubble {
  flex: 1;
  min-width: 0;
}
.user-bubble p {
  padding: 10px 16px;
  background: #f5f0ea;
  border-radius: 12px 12px 4px 12px;
  color: #2d2a24;
  line-height: 1.5;
  display: inline-block;
}
.assistant-bubble {
  padding: 0;
}

.msg-content {
  line-height: 1.7;
  font-size: 14px;
  color: #2d2a24;
}

.msg-content :deep(.code-block) {
  background: #f5f0ea;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  overflow-x: auto;
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
}
.msg-content :deep(.inline-code) {
  background: #f5f0ea;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', monospace;
}
.msg-content :deep(.code-ref) {
  background: #f5ede8;
  color: #a94d2d;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.msg-sources {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.sources-label {
  font-size: 11px;
  color: #8a857a;
  font-weight: 500;
  margin-right: 2px;
}

.source-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  padding: 3px 8px;
  background: #f5f0ea;
  color: #5a5548;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s;
}
.source-chip:hover { background: #e8e3d8; }

.msg-actions {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.action-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: #f5ede8;
  border: 1px solid #e8e3d8;
  border-radius: 6px;
  font-size: 12px;
  color: #5a5548;
  cursor: pointer;
  transition: all 0.12s;
}
.action-chip:hover { background: #e8dcd4; border-color: #d4cfc2; color: #c15f3c; }

/* ── Typing ── */
.typing-line {
  display: flex;
  gap: 4px;
  padding: 8px 0;
}
.typing-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ddd8cc;
  animation: bounce 1.4s infinite ease-in-out;
}
.typing-dot:nth-child(1) { animation-delay: 0s; }
.typing-dot:nth-child(2) { animation-delay: 0.2s; }
.typing-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* ── Empty State ── */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px;
}
.empty-icon { opacity: 0.5; }
.empty-state h3 { font-size: 16px; font-weight: 600; color: #2d2a24; }
.empty-state p { font-size: 13px; }

.hints {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 8px;
}
.hint-tag {
  padding: 6px 14px;
  border: 1px solid #e8e3d8;
  border-radius: 20px;
  font-size: 12px;
  color: #5a5548;
  cursor: pointer;
  transition: all 0.12s;
  background: #fff;
}
.hint-tag:hover {
  border-color: #c15f3c;
  color: #c15f3c;
  background: #f5ede8;
}
</style>

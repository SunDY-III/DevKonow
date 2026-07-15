<template>
  <div class="chat-view">
    <header class="chat-header">
      <h3>{{ title }}</h3>
    </header>
    <div class="chat-messages" ref="messageList">
      <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
        <div class="message-content" v-html="renderContent(msg.content)" />
        <div v-if="msg.sources" class="message-sources">
          <span v-for="(src, j) in msg.sources" :key="j" class="source-tag">
            {{ src.file }}
          </span>
        </div>
      </div>
      <div v-if="loading" class="message assistant">
        <div class="typing-indicator">分析中...</div>
      </div>
    </div>
    <div class="chat-input">
      <div v-if="currentProjectId" class="project-context">
        当前项目: <strong>{{ currentProjectName }}</strong>
      </div>
      <div class="input-row">
        <input
          v-model="question"
          @keydown.enter="send"
          placeholder="输入问题，如 'createOrder 方法在哪？'"
          :disabled="loading"
        />
        <button @click="send" :disabled="loading || !question.trim()">发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { getProjects } from '../api/index.js'

const route = useRoute()
const messages = ref([])
const question = ref('')
const loading = ref(false)
const currentProjectId = ref(route.params.projectId || '')
const currentProjectName = ref('')
const messageList = ref(null)

let es = null

async function send() {
  const q = question.value.trim()
  if (!q || loading.value) return
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  loading.value = true

  const params = new URLSearchParams({
    question: q,
    conversationId: Date.now().toString(),
    token: 'dev'
  })
  if (currentProjectId.value) params.set('projectId', currentProjectId.value)

  es = new EventSource(`/api/chat/stream?${params}`)
  let answer = ''

  es.addEventListener('token', (e) => {
    answer += e.data
    if (messages.value[messages.value.length - 1]?.role === 'assistant') {
      messages.value[messages.value.length - 1].content = answer
    } else {
      messages.value.push({ role: 'assistant', content: answer })
    }
  })

  es.addEventListener('source', (e) => {
    try {
      const sources = JSON.parse(e.data)
      if (messages.value[messages.value.length - 1]?.role === 'assistant') {
        messages.value[messages.value.length - 1].sources = sources
      }
    } catch {}
  })

  es.addEventListener('done', () => {
    es.close()
    loading.value = false
    scrollToBottom()
  })

  es.addEventListener('error', () => {
    es.close()
    loading.value = false
  })
}

function scrollToBottom() {
  setTimeout(() => {
    if (messageList.value) {
      messageList.value.scrollTop = messageList.value.scrollHeight
    }
  }, 50)
}

function renderContent(text) {
  if (!text) return ''
  return text
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br>')
    .replace(/\[文件:\s*([^\]]+)\]/g, '<span class="code-ref">📄 $1</span>')
    .replace(/\[片段(\d+)\]/g, '<span class="code-ref">📎 [$1]</span>')
}

onUnmounted(() => {
  if (es) es.close()
})
</script>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.chat-header {
  padding: 16px 24px;
  border-bottom: 1px solid #e8e3d8;
  background: #faf9f5;
}
.chat-header h3 { margin: 0; font-size: 16px; color: #1f1e1c; }
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.message { max-width: 80%; padding: 12px 16px; border-radius: 12px; line-height: 1.6; font-size: 14px; }
.message.user {
  align-self: flex-end;
  background: #c15f3c;
  color: #fff;
}
.message.assistant {
  align-self: flex-start;
  background: #fff;
  color: #1f1e1c;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.message-sources { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }
.source-tag {
  font-size: 11px;
  padding: 2px 8px;
  background: #f1e3da;
  color: #a94d2d;
  border-radius: 4px;
}
.code-ref {
  background: #f1e3da;
  color: #a94d2d;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
}
.typing-indicator { color: #908979; font-style: italic; }
.chat-input {
  padding: 16px 24px;
  border-top: 1px solid #e8e3d8;
  background: #faf9f5;
}
.project-context { font-size: 12px; color: #908979; margin-bottom: 8px; }
.input-row { display: flex; gap: 8px; }
.input-row input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  font-size: 14px;
  background: #fff;
}
.input-row input:focus { outline: none; border-color: #c15f3c; }
.input-row button {
  padding: 10px 20px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}
.input-row button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>

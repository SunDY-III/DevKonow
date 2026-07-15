<template>
  <Teleport to="body">
    <div class="scip-overlay" @click.self="preventClose">
      <div class="scip-card">
        <!-- 标题 -->
        <div class="scip-header">
          <div class="scip-icon" :class="state">
            <template v-if="state === 'generating'">
              <svg viewBox="0 0 24 24" class="spin-icon" width="48" height="48">
                <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" fill="none"
                  stroke-dasharray="31.4 31.4" stroke-linecap="round" />
              </svg>
            </template>
            <template v-else-if="state === 'done'">
              <svg viewBox="0 0 24 24" class="check-icon" width="48" height="48">
                <circle cx="12" cy="12" r="10" fill="#2da44e" />
                <path d="M7 12l3 3 7-7" stroke="#fff" stroke-width="2" fill="none" stroke-linecap="round" />
              </svg>
            </template>
            <template v-else>
              <svg viewBox="0 0 24 24" class="fail-icon" width="48" height="48">
                <circle cx="12" cy="12" r="10" fill="#d1242f" />
                <path d="M8 8l8 8M16 8l-8 8" stroke="#fff" stroke-width="2" fill="none" stroke-linecap="round" />
              </svg>
            </template>
          </div>
          <h2 class="scip-title">{{ titleText }}</h2>
          <p class="scip-subtitle">{{ subtitleText }}</p>
        </div>

        <!-- 进度消息 -->
        <div class="scip-logs" ref="logContainer">
          <div v-for="(msg, i) in messages" :key="i" class="log-line">
            <span class="log-time">{{ msg.time }}</span>
            <span class="log-text" :class="msgClass(msg.text)">{{ msg.text }}</span>
          </div>
          <div v-if="state === 'generating'" class="log-line breathing">
            <span class="log-time">{{ currentTime }}</span>
            <span class="log-text processing">处理中<span class="dots"><span>.</span><span>.</span><span>.</span></span></span>
          </div>
        </div>

        <!-- 状态条 -->
        <div class="scip-footer">
          <div class="status-bar" :class="state">
            <template v-if="state === 'generating'">
              <div class="pulsing-bar"></div>
              <span>正在生成 SCIP 索引，请稍候...</span>
            </template>
            <template v-else-if="state === 'done'">
              <span>✅ 已切换至 SCIP 模式，即将关闭</span>
            </template>
            <template v-else>
              <span>❌ {{ errorMessage }}</span>
            </template>
          </div>
          <button v-if="state !== 'generating'" class="scip-close-btn" @click="close">
            {{ state === 'done' ? '开始使用' : '关闭' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { getCodeIndexMode, switchCodeIndexMode, subscribeCodeIndexProgress } from '../api/index.js'

const props = defineProps({
  projectDir: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const state = ref('generating')  // generating | done | fail
const messages = ref([])
const errorMessage = ref('')
const logContainer = ref(null)
let eventSource = null

const titleText = computed(() => {
  if (state.value === 'generating') return '正在切换至 SCIP 模式'
  if (state.value === 'done') return 'SCIP 模式已就绪'
  return '切换失败'
})

const subtitleText = computed(() => {
  if (state.value === 'generating') return '正在生成代码索引，这可能需要几秒钟到几分钟'
  if (state.value === 'done') return '所有代码已完成索引，你可以开始使用更精确的代码搜索了'
  return '请检查错误信息后重试'
})

const currentTime = computed(() => {
  const now = new Date()
  return now.getHours().toString().padStart(2, '0') + ':' +
    now.getMinutes().toString().padStart(2, '0') + ':' +
    now.getSeconds().toString().padStart(2, '0')
})

function msgClass(text) {
  if (text.includes('错误') || text.includes('失败') || text.includes('Error')) return 'log-error'
  if (text.includes('完成') || text.includes('成功')) return 'log-success'
  return 'log-info'
}

function addLog(text) {
  const now = new Date()
  const time = now.getHours().toString().padStart(2, '0') + ':' +
    now.getMinutes().toString().padStart(2, '0') + ':' +
    now.getSeconds().toString().padStart(2, '0')
  messages.value.push({ time, text })
  nextTick(() => {
    if (logContainer.value) {
      logContainer.value.scrollTop = logContainer.value.scrollHeight
    }
  })
}

function preventClose() {
  if (state.value === 'generating') {
    addLog('⛔ 索引生成中，请勿关闭窗口')
  }
}

function close() {
  if (eventSource) { eventSource.close(); eventSource = null }
  emit('close')
}

onMounted(async () => {
  addLog('🚀 正在启动 SCIP 索引生成...')

  // 订阅 SSE 进度
  eventSource = subscribeCodeIndexProgress()
  eventSource.addEventListener('progress', (e) => {
    try {
      const data = JSON.parse(e.data)
      addLog(data.message)

      if (data.status === 'generating') {
        state.value = 'generating'
      }
    } catch (err) {
      // ignore parse errors
    }
  })

  eventSource.onerror = () => {
    // SSE 断开后轮询检测模式是否已切换
    const pollTimer = setInterval(async () => {
      try {
        const resp = await getCodeIndexMode()
        if (resp.mode === 'scip' && !resp.switching) {
          state.value = 'done'
          addLog('✅ SCIP 索引生成完成，已自动切换模式')
          clearInterval(pollTimer)
          // 3 秒后自动关闭
          setTimeout(() => close(), 3000)
        } else if (resp.switching === false && resp.mode === 'tree-sitter') {
          state.value = 'fail'
          errorMessage.value = 'SCIP 索引生成未能完成'
          addLog('❌ SCIP 索引生成未能完成，请检查日志')
          clearInterval(pollTimer)
        }
      } catch (err) {
        // 继续轮询
      }
    }, 1000)
  }

  // 发起切换请求
  try {
    const result = await switchCodeIndexMode('scip', props.projectDir)
    addLog(result.async ? '⏳ 已提交索引任务，等待完成...' : result.message)

    if (!result.async) {
      // 立即完成（已有索引文件）
      state.value = 'done'
      addLog('✅ 已有 SCIP 索引文件，模式已切换')
      setTimeout(() => close(), 1500)
    }
  } catch (err) {
    state.value = 'fail'
    errorMessage.value = err.message || '请求失败'
    addLog('❌ 请求失败: ' + (err.message || '未知错误'))
  }
})

onUnmounted(() => {
  if (eventSource) { eventSource.close(); eventSource = null }
})
</script>

<style scoped>
.scip-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.65);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(4px);
}

.scip-card {
  background: #1f1e1c;
  border-radius: 16px;
  width: 560px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid #3a3834;
  color: #e8e3d8;
}

.scip-header {
  text-align: center;
  padding: 32px 32px 16px;
}

.scip-icon {
  margin-bottom: 16px;
}

.spin-icon {
  color: #c15f3c;
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.scip-title {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
  color: #fff;
}

.scip-subtitle {
  margin: 0;
  font-size: 13px;
  color: #908979;
}

.scip-logs {
  flex: 1;
  overflow-y: auto;
  padding: 12px 24px;
  margin: 16px 0;
  max-height: 300px;
  background: #141312;
  border-top: 1px solid #3a3834;
  border-bottom: 1px solid #3a3834;
  font-family: 'Cascadia Code', 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.8;
}

.log-line {
  padding: 1px 0;
  opacity: 0;
  animation: fadeIn 0.3s forwards;
}

@keyframes fadeIn {
  to { opacity: 1; }
}

.log-time {
  color: #5a5548;
  margin-right: 12px;
  user-select: none;
}

.log-text { color: #b4aea0; }
.log-info { color: #88a9c4; }
.log-success { color: #57ab5a; }
.log-error { color: #d9534f; }

.processing { color: #c15f3c; }

.dots span {
  animation: dotPulse 1.4s infinite;
  opacity: 0;
}
.dots span:nth-child(1) { animation-delay: 0s; }
.dots span:nth-child(2) { animation-delay: 0.2s; }
.dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes dotPulse {
  0%, 60%, 100% { opacity: 0; }
  30% { opacity: 1; }
}

.breathing {
  animation: breathe 2s ease-in-out infinite;
}

@keyframes breathe {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

.scip-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  gap: 12px;
}

.status-bar {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #908979;
}

.status-bar.generating {
  color: #c15f3c;
}

.pulsing-bar {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c15f3c;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.5); opacity: 0.5; }
}

.scip-close-btn {
  padding: 8px 20px;
  border-radius: 8px;
  border: none;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  background: #c15f3c;
  color: #fff;
  white-space: nowrap;
}

.scip-close-btn:hover {
  background: #d47a4a;
}
</style>

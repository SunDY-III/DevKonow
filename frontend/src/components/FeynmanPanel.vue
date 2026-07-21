<template>
  <Transition name="fade-slide">
    <div v-if="visible" class="feynman-panel">
      <div class="feynman-header">
        <span class="feynman-badge">🧪 Feynman 检验</span>
        <span class="feynman-round">第 {{ round }} / 3 轮</span>
        <button v-if="!submitted" class="skip-btn" @click="$emit('skip')">跳过</button>
      </div>

      <!-- 等待问题 -->
      <div v-if="loading" class="feynman-loading">
        <div class="loading-spinner"></div>
        <span>正在生成检验问题...</span>
      </div>

      <!-- 问题 -->
      <div v-else-if="question && !submitted" class="feynman-question">
        <p class="question-text">{{ question }}</p>
        <textarea
          v-model="userAnswer"
          class="answer-input"
          placeholder="用你自己的话解释..."
          rows="3"
          :disabled="submitting"
          @paste.prevent
        ></textarea>
        <div class="feynman-actions">
          <button class="submit-btn" :disabled="submitting || !userAnswer.trim()" @click="submit">
            {{ submitting ? '评判中...' : '提交回答' }}
          </button>
        </div>
      </div>

      <!-- 评判结果 -->
      <div v-if="result" class="feynman-result" :class="{ correct: result.correct, incorrect: !result.correct }">
        <div class="result-icon">{{ result.correct ? '✅' : '🤔' }}</div>
        <div class="result-content">
          <p class="result-feedback">{{ result.feedback }}</p>
          <p v-if="result.gapAnalysis" class="result-gap">{{ result.gapAnalysis }}</p>
          <p v-if="result.hint" class="result-hint">💡 {{ result.hint }}</p>
          <button v-if="!result.passed && !result.failed" class="next-btn" @click="nextRound">
            继续下一轮
          </button>
        </div>
      </div>

      <!-- 最终结果 -->
      <div v-if="finalResult" class="final-result" :class="{ passed: finalResult.passed }">
        <div class="final-icon">{{ finalResult.passed ? '🎉' : '📚' }}</div>
        <p class="final-text">
          {{ finalResult.passed ? '理解验证通过！' : '已记录待复习清单' }}
        </p>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, watch } from 'vue'
import { createFeynmanSSE, submitFeynmanAnswer } from '../api/feynman.js'

const props = defineProps({
  visible: Boolean,
  conversationId: String,
  question: String,
  answer: String
})

const emit = defineEmits(['skip', 'complete'])

const loading = ref(false)
const submitted = ref(false)
const submitting = ref(false)
const userAnswer = ref('')
const round = ref(1)
const result = ref(null)
const finalResult = ref(null)
const verifyQuestion = ref('')

watch(() => props.visible, (val) => {
  if (val && props.conversationId) {
    startSession()
  }
})

async function startSession() {
  loading.value = true
  submitted.value = false
  result.value = null
  finalResult.value = null
  round.value = 1

  const es = createFeynmanSSE(props.conversationId, props.question, props.answer)
  es.addEventListener('question', (e) => {
    const data = JSON.parse(e.data)
    verifyQuestion.value = data.question
    loading.value = false
  })
  es.addEventListener('error', () => {
    loading.value = false
    es.close()
  })
  es.onerror = () => { loading.value = false; es.close() }
}

async function submit() {
  submitting.value = true
  submitted.value = true
  try {
    const data = await submitFeynmanAnswer(props.conversationId, userAnswer.value)
    result.value = data
    if (data.passed) {
      finalResult.value = { passed: true }
      emit('complete', { passed: true })
    } else if (data.failed) {
      finalResult.value = { passed: false }
      emit('complete', { passed: false })
    }
  } catch (err) {
    result.value = { correct: false, feedback: '提交失败: ' + err.message }
  } finally {
    submitting.value = false
  }
}

function nextRound() {
  round.value++
  result.value = null
  submitted.value = false
  userAnswer.value = ''
  // 下一轮将 verifyQuestion 作为新的问题
}
</script>

<style scoped>
.feynman-panel {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 16px;
  margin-top: 12px;
}
.feynman-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.feynman-badge {
  font-size: 12px;
  font-weight: 600;
  color: #c15f3c;
  padding: 2px 8px;
  background: #f5ede8;
  border-radius: 4px;
}
.feynman-round { font-size: 12px; color: #8a857a; margin-left: auto; }
.skip-btn {
  font-size: 12px; color: #8a857a; background: none; border: none; cursor: pointer;
}
.skip-btn:hover { color: #c15f3c; }
.feynman-loading { display: flex; align-items: center; gap: 8px; color: #8a857a; font-size: 13px; }
.loading-spinner {
  width: 14px; height: 14px;
  border: 2px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.question-text { font-size: 14px; color: #2d2a24; margin: 0 0 10px; font-weight: 500; }
.answer-input {
  width: 100%;
  padding: 10px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  font-size: 13px;
  resize: vertical;
  box-sizing: border-box;
}
.answer-input:focus { outline: none; border-color: #c15f3c; }
.feynman-actions { margin-top: 10px; }
.submit-btn {
  padding: 8px 16px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.submit-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.submit-btn:hover:not(:disabled) { background: #a84e2e; }
.feynman-result {
  display: flex;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  margin-top: 10px;
}
.feynman-result.correct { background: #f0faf0; }
.feynman-result.incorrect { background: #fef5f0; }
.result-icon { font-size: 20px; }
.result-content { flex: 1; }
.result-feedback { font-size: 13px; font-weight: 500; margin: 0 0 4px; }
.result-gap { font-size: 12px; color: #c15f3c; margin: 4px 0; }
.result-hint { font-size: 12px; color: #8a857a; margin: 4px 0; }
.next-btn {
  margin-top: 8px;
  padding: 6px 14px;
  background: #f5ede8;
  color: #c15f3c;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.final-result { text-align: center; padding: 16px; }
.final-icon { font-size: 32px; margin-bottom: 8px; }
.final-text { font-size: 14px; font-weight: 600; color: #2d2a24; }
.final-result.passed .final-text { color: #57ab5a; }
.fade-slide-enter-active, .fade-slide-leave-active { transition: all 0.3s ease; }
.fade-slide-enter-from, .fade-slide-leave-to { opacity: 0; transform: translateY(-8px); }
</style>

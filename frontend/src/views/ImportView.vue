<template>
  <div class="import-page">
    <div class="page-header">
      <h2>导入项目</h2>
      <p class="text-muted">粘贴 Git 仓库地址，系统自动拉取代码并建立索引</p>
    </div>

    <div class="import-card">
      <div class="form-row">
        <div class="form-group">
          <label>Git 仓库地址</label>
          <div class="input-with-icon">
            <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
            <input v-model="repoUrl" placeholder="https://github.com/user/repo.git" />
          </div>
        </div>
        <div class="form-group">
          <label>Token <span class="text-muted text-sm">（私有仓库需要）</span></label>
          <div class="input-with-icon">
            <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            <input v-model="token" type="password" placeholder="可选（ghp_xxxxx）" />
          </div>
        </div>
      </div>

      <button class="btn-verify" @click="verifyRepo" :disabled="verifying || !repoUrl.trim()">
        <svg v-if="!verifying" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 11 12 14 22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
        <span v-else class="spinner-sm"></span>
        {{ verifying ? '验证中...' : '验证仓库' }}
      </button>

      <transition name="fade">
        <div v-if="verifyResult" :class="['verify-box', verifyResult.valid ? 'success' : 'error']">
          <p>{{ verifyResult.message }}</p>
          <p v-if="verifyResult.valid" class="verify-detail">{{ verifyResult.detail }}</p>
          <button v-if="verifyResult.valid" class="btn-import" @click="startImport" :disabled="importing">
            {{ importing ? '导入中...' : '确认导入' }}
          </button>
        </div>
      </transition>
    </div>

    <!-- 进度 -->
    <div v-if="progress.stage" class="progress-card">
      <div class="progress-head">
        <span class="progress-stage">{{ stageLabel }}</span>
        <span class="progress-pct">{{ progress.percent }}%</span>
      </div>
      <div class="progress-track">
        <div class="progress-fill" :style="{ width: progress.percent + '%' }"></div>
      </div>
      <p class="progress-msg">{{ progress.message }}</p>

      <div v-if="errorMsg" :class="['error-box', errorCode === 'NETWORK_ERROR' ? 'retry' : '']">
        <p>{{ errorMsg }}</p>
        <p v-if="errorCode === 'REPO_NOT_FOUND'" class="error-hint">请检查仓库地址是否正确</p>
        <p v-if="errorCode === 'PERMISSION_DENIED'" class="error-hint">请检查 Token 是否有效</p>
        <button v-if="errorCode === 'NETWORK_ERROR'" class="btn-retry" @click="startImport">重试</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { verifyRepo as verifyApi, createImportSSE } from '../api/index.js'

const repoUrl = ref('')
const token = ref('')
const verifying = ref(false)
const importing = ref(false)
const verifyResult = ref(null)
const progress = ref({})
const errorMsg = ref('')
const errorCode = ref('')
let es = null

const stageLabel = computed(() => {
  const map = { cloning: '克隆中', scanning: '扫描中', indexing: '索引中', done: '完成' }
  return map[progress.value.stage] || progress.value.stage
})

async function verifyRepo() {
  verifying.value = true
  verifyResult.value = null
  try {
    const res = await verifyApi(repoUrl.value, token.value)
    verifyResult.value = res.data || res
  } catch (e) {
    verifyResult.value = { valid: false, message: e.message }
  } finally {
    verifying.value = false
  }
}

function startImport() {
  importing.value = true
  progress.value = { stage: 'cloning', message: '正在连接...', percent: 0 }
  errorMsg.value = ''
  errorCode.value = ''

  es = createImportSSE(repoUrl.value, false, token.value)

  es.addEventListener('progress', (e) => {
    try { progress.value = JSON.parse(e.data) } catch {}
  })

  es.addEventListener('error', (e) => {
    try {
      const err = JSON.parse(e.data)
      errorCode.value = err.errorCode
      errorMsg.value = err.message
    } catch { errorMsg.value = '导入失败' }
    importing.value = false
    es.close()
  })

  es.addEventListener('project', () => {
    progress.value = { stage: 'done', message: '导入完成！', percent: 100 }
    importing.value = false
    es.close()
  })
}
</script>

<style scoped>
.import-page {
  max-width: 640px;
  margin: 0 auto;
  padding: 32px 24px;
  width: 100%;
}
.page-header { margin-bottom: 24px; }
.page-header h2 { font-size: 20px; font-weight: 600; margin-bottom: 4px; }

.import-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 12px;
  padding: 24px;
}

.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; color: #5a5548; margin-bottom: 6px; }

.input-with-icon {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  background: #faf9f5;
  transition: border-color 0.12s;
}
.input-with-icon:focus-within { border-color: #c15f3c; }

.input-icon { color: #c9c3b5; flex-shrink: 0; }

.input-with-icon input {
  flex: 1;
  border: none;
  outline: none;
  padding: 10px 0;
  font-size: 14px;
  background: transparent;
  color: #2d2a24;
}
.input-with-icon input::placeholder { color: #c9c3b5; }

.btn-verify {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  background: #fff;
  color: #5a5548;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.12s;
}
.btn-verify:hover { border-color: #c15f3c; color: #c15f3c; }
.btn-verify:disabled { opacity: 0.5; cursor: not-allowed; }

.verify-box {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 8px;
  font-size: 13px;
}
.verify-box.success { background: #f0faf0; color: #2e7d32; border: 1px solid #c8e6c9; }
.verify-box.error { background: #fef2f2; color: #c62828; border: 1px solid #ffcdd2; }
.verify-detail { font-size: 12px; margin-top: 4px; opacity: 0.7; }

.btn-import {
  margin-top: 10px;
  padding: 8px 20px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.12s;
}
.btn-import:hover { background: #d47a4a; }
.btn-import:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── Progress ── */
.progress-card {
  margin-top: 16px;
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 12px;
  padding: 20px;
}
.progress-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}
.progress-stage { font-size: 13px; font-weight: 600; }
.progress-pct { font-size: 13px; color: #8a857a; }

.progress-track {
  height: 6px;
  background: #f0ece4;
  border-radius: 3px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: #c15f3c;
  border-radius: 3px;
  transition: width 0.3s ease;
}
.progress-msg { font-size: 13px; color: #5a5548; margin-top: 8px; }

.error-box { margin-top: 12px; padding: 12px; border-radius: 8px; background: #fef2f2; }
.error-box p { margin: 0; font-size: 13px; color: #c62828; }
.error-hint { margin-top: 4px !important; color: #8a857a !important; font-size: 12px !important; }
.error-box.retry { border: 1px solid #ffcdd2; }
.btn-retry { margin-top: 8px; padding: 6px 16px; border: none; border-radius: 6px; background: #c15f3c; color: #fff; cursor: pointer; font-size: 12px; }

.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.spinner-sm {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>

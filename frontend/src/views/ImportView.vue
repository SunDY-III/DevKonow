<template>
  <div class="import-view">
    <h3>导入项目</h3>
    <p class="desc">粘贴 Git 仓库地址，系统自动拉取代码并建立索引。</p>

    <div class="import-form">
      <div class="form-group">
        <label>Git 仓库地址</label>
        <input v-model="repoUrl" placeholder="https://github.com/user/repo.git" />
      </div>
      <div class="form-group">
        <label>Token（私有仓库需要）</label>
        <input v-model="token" type="password" placeholder="ghp_xxxxxxxx（可选）" />
      </div>
      <button @click="verifyRepo" :disabled="verifying || !repoUrl.trim()" class="btn-verify">
        {{ verifying ? '验证中...' : '验证仓库' }}
      </button>

      <div v-if="verifyResult" :class="['verify-result', verifyResult.valid ? 'success' : 'error']">
        <p>{{ verifyResult.message }}</p>
        <p v-if="verifyResult.valid" class="repo-detail">{{ verifyResult.detail }}</p>
        <button v-if="verifyResult.valid" @click="startImport" class="btn-import">
          {{ importing ? '导入中...' : '确认导入' }}
        </button>
      </div>
    </div>

    <div v-if="progress.stage" class="import-progress">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: progress.percent + '%' }"></div>
      </div>
      <p>{{ progress.message }}</p>
      <div v-if="errorMsg" :class="['error-box', errorCode === 'NETWORK_ERROR' ? 'retry' : '']">
        <p>{{ errorMsg }}</p>
        <button v-if="errorCode === 'NETWORK_ERROR'" @click="startImport" class="btn-retry">重试</button>
        <p v-if="errorCode === 'REPO_NOT_FOUND'" class="error-hint">请检查仓库地址是否正确</p>
        <p v-if="errorCode === 'PERMISSION_DENIED'" class="error-hint">请检查 Token 是否有效，或配置 SSH Key</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
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

async function verifyRepo() {
  verifying.value = true
  verifyResult.value = null
  try {
    const res = await verifyApi(repoUrl.value, token.value)
    verifyResult.value = res.data
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
    try {
      progress.value = JSON.parse(e.data)
    } catch {}
  })

  es.addEventListener('error', (e) => {
    try {
      const err = JSON.parse(e.data)
      errorCode.value = err.errorCode
      errorMsg.value = err.message
    } catch {
      errorMsg.value = '导入失败'
    }
    importing.value = false
    es.close()
  })

  es.addEventListener('project', (e) => {
    try {
      const p = JSON.parse(e.data)
      progress.value = { stage: 'done', message: '导入完成！', percent: 100 }
      importing.value = false
      es.close()
    } catch {}
  })
}
</script>

<style scoped>
.import-view {
  padding: 32px;
  max-width: 640px;
}
.import-view h3 { margin: 0 0 8px; color: #1f1e1c; font-size: 20px; }
.desc { color: #908979; font-size: 14px; margin-bottom: 24px; }
.import-form { background: #fff; padding: 24px; border-radius: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; color: #56524a; margin-bottom: 6px; font-weight: 500; }
.form-group input {
  width: 100%; padding: 10px 12px;
  border: 1px solid #e8e3d8; border-radius: 8px;
  font-size: 14px; box-sizing: border-box;
}
.form-group input:focus { outline: none; border-color: #c15f3c; }
.btn-verify, .btn-import, .btn-retry {
  padding: 10px 20px;
  background: #c15f3c; color: #fff;
  border: none; border-radius: 8px;
  cursor: pointer; font-size: 14px;
}
.btn-verify:disabled, .btn-import:disabled { opacity: 0.5; cursor: not-allowed; }
.verify-result { margin-top: 16px; padding: 16px; border-radius: 8px; font-size: 14px; }
.verify-result.success { background: #e8f5e9; color: #2e7d32; }
.verify-result.error { background: #ffebee; color: #c62828; }
.repo-detail { font-size: 12px; margin-top: 4px; opacity: 0.7; }
.btn-import { margin-top: 12px; }
.import-progress { margin-top: 24px; }
.progress-bar {
  height: 8px; background: #e8e3d8; border-radius: 4px; overflow: hidden;
}
.progress-fill { height: 100%; background: #c15f3c; transition: width 0.3s; }
.import-progress p { margin-top: 8px; font-size: 14px; color: #56524a; }
.error-box { margin-top: 12px; padding: 12px; background: #ffebee; border-radius: 8px; }
.error-box p { margin: 0; color: #c62828; font-size: 14px; }
.error-hint { margin-top: 4px !important; font-size: 12px !important; color: #908979 !important; }
.error-box.retry { border: 1px solid #ffcdd2; }
.btn-retry { margin-top: 8px; }
</style>

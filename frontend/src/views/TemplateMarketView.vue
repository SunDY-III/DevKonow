<template>
  <div class="template-shell">
    <div class="template-header">
      <h2 class="template-title">模板市场</h2>
      <p class="template-subtitle">选择项目脚手架模板，快速开始新项目</p>
    </div>

    <!-- 模板列表 -->
    <div v-if="!selectedTemplate" class="template-grid">
      <div
        v-for="tmpl in templates"
        :key="tmpl.id"
        class="template-card"
        @click="selectTemplate(tmpl)"
      >
        <div class="template-card-header">
          <span class="template-icon">{{ techIcon(tmpl.techStack) }}</span>
          <h3 class="template-name">{{ tmpl.name }}</h3>
        </div>
        <p class="template-desc">{{ tmpl.description }}</p>
        <div class="template-tags">
          <span v-for="tag in (tmpl.tags || [])" :key="tag" class="tag">{{ tag }}</span>
        </div>
        <div class="template-stack">
          <span v-for="tech in (tmpl.techStack || [])" :key="tech" class="tech-badge">{{ tech }}</span>
        </div>
      </div>
    </div>

    <!-- 模板详情 + 变量填写 -->
    <div v-else class="template-detail">
      <button class="back-btn" @click="selectedTemplate = null">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
        返回模板列表
      </button>

      <div class="detail-card">
        <h3>{{ selectedTemplate.name }}</h3>
        <p class="detail-desc">{{ selectedTemplate.description }}</p>

        <div class="form-section">
          <h4>项目配置</h4>
          <div class="form-group" v-for="v in (selectedTemplate.variables || [])" :key="v.key">
            <label :for="v.key">{{ v.label }} <span v-if="v.required" class="required">*</span></label>
            <input
              :id="v.key"
              v-model="formValues[v.key]"
              :placeholder="v.defaultValue || v.description"
              class="form-input"
            />
            <p v-if="v.description" class="form-hint">{{ v.description }}</p>
          </div>
        </div>

        <button class="btn btn-primary btn-generate" @click="generateProject" :disabled="generating">
          <span v-if="!generating">生成项目</span>
          <span v-else class="spinner"></span>
        </button>
      </div>

      <!-- 生成进度 -->
      <div v-if="generating" class="progress-panel">
        <h4>生成进度</h4>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
        <p class="progress-message">{{ progressMessage }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listTemplates, generateScaffold } from '../api/templates.js'

const templates = ref([])
const selectedTemplate = ref(null)
const formValues = ref({})
const generating = ref(false)
const progressPercent = ref(0)
const progressMessage = ref('')

onMounted(async () => {
  try {
    templates.value = await listTemplates()
  } catch (err) {
    console.error('加载模板列表失败', err)
  }
})

function selectTemplate(tmpl) {
  selectedTemplate.value = tmpl
  // 初始化表单值
  const values = {}
  for (const v of (tmpl.variables || [])) {
    values[v.key] = v.defaultValue || ''
  }
  formValues.value = values
  progressPercent.value = 0
  progressMessage.value = ''
}

function techIcon(techStack) {
  if (!techStack || techStack.length === 0) return '📦'
  const stack = techStack.join(' ').toLowerCase()
  if (stack.includes('spring') || stack.includes('java')) return '☕'
  if (stack.includes('go')) return '🔷'
  if (stack.includes('nestjs') || stack.includes('node')) return '🟢'
  if (stack.includes('react') || stack.includes('vue')) return '⚛️'
  return '📦'
}

async function generateProject() {
  if (!selectedTemplate.value) return
  generating.value = true
  progressPercent.value = 0
  progressMessage.value = '准备中...'

  try {
    const response = await generateScaffold(
      selectedTemplate.value.id,
      formValues.value,
      formValues.value.projectName || 'my-project'
    )

    const reader = response.body.getReader()
    const decoder = new TextDecoder()

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const chunk = decoder.decode(value)
      const lines = chunk.split('\n')

      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const data = JSON.parse(line.slice(5).trim())
            if (data.percent !== undefined) progressPercent.value = data.percent
            if (data.message) progressMessage.value = data.message
          } catch {}
        }
        if (line.startsWith('event:done')) {
          progressMessage.value = '项目生成完成！'
        }
      }
    }
  } catch (err) {
    console.error('生成失败', err)
    progressMessage.value = '生成失败: ' + err.message
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.template-shell {
  padding: 24px 32px;
  max-width: 960px;
  margin: 0 auto;
}

.template-header {
  margin-bottom: 24px;
}

.template-title {
  font-size: 20px;
  font-weight: 700;
  color: #2d2a24;
  margin: 0;
}

.template-subtitle {
  font-size: 13px;
  color: #8a857a;
  margin: 4px 0 0 0;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.template-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.12s;
}
.template-card:hover { border-color: #c15f3c; box-shadow: 0 2px 12px rgba(193,95,60,0.08); }

.template-card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.template-icon { font-size: 24px; }

.template-name {
  font-size: 15px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0;
}

.template-desc {
  font-size: 12px;
  color: #5a5548;
  line-height: 1.5;
  margin: 0 0 12px 0;
}

.template-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.tag {
  font-size: 10px;
  padding: 2px 6px;
  background: #f0ece4;
  color: #5a5548;
  border-radius: 4px;
}

.template-stack {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.tech-badge {
  font-size: 10px;
  padding: 2px 6px;
  background: #f5ede8;
  color: #a94d2d;
  border-radius: 4px;
}

/* Detail */
.template-detail {
  max-width: 640px;
}

.back-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  color: #8a857a;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 0;
  margin-bottom: 16px;
}
.back-btn:hover { color: #c15f3c; }

.detail-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 24px;
}

.detail-card h3 {
  font-size: 18px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 4px 0;
}

.detail-desc {
  font-size: 13px;
  color: #5a5548;
  margin: 0 0 20px 0;
}

.form-section h4 {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 12px 0;
}

.form-group {
  margin-bottom: 14px;
}

.form-group label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: #5a5548;
  margin-bottom: 4px;
}

.required { color: #c62828; }

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ddd8cc;
  border-radius: 6px;
  font-size: 13px;
  color: #2d2a24;
  background: #fff;
  box-sizing: border-box;
}
.form-input:focus { outline: none; border-color: #c15f3c; box-shadow: 0 0 0 2px #c15f3c18; }

.form-hint {
  font-size: 11px;
  color: #8a857a;
  margin: 2px 0 0 0;
}

.btn-generate {
  margin-top: 16px;
  width: 100%;
  padding: 10px;
}

.btn { padding: 8px 16px; border-radius: 8px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.12s; border: none; }
.btn-primary { background: #c15f3c; color: #fff; }
.btn-primary:hover { background: #d47a4a; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Progress */
.progress-panel {
  margin-top: 16px;
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 16px;
}

.progress-panel h4 {
  font-size: 13px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 8px 0;
}

.progress-bar {
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

.progress-message {
  font-size: 12px;
  color: #8a857a;
  margin-top: 8px;
}
</style>

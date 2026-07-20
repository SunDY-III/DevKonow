<template>
  <div class="admin-shell">
    <div class="admin-header">
      <h2 class="admin-title">Prompt 模板管理</h2>
      <p class="admin-subtitle">管理 LLM 提示模板，支持热更新</p>
    </div>

    <!-- 提示列表表格 -->
    <div class="table-container">
      <table class="prompt-table" v-if="prompts.length > 0">
        <thead>
          <tr>
            <th>ID</th>
            <th>类型</th>
            <th>名称</th>
            <th>内容 (前 80 字符)</th>
            <th>变量</th>
            <th>版本</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in prompts" :key="p.id">
            <td class="cell-id">{{ p.id }}</td>
            <td><span class="type-badge" :class="p.type">{{ typeLabel(p.type) }}</span></td>
            <td>{{ p.name }}</td>
            <td class="cell-content" :title="p.content">{{ truncate(p.content, 80) }}</td>
            <td class="cell-vars">{{ p.variables }}</td>
            <td class="cell-version">v{{ p.version }}</td>
            <td class="cell-time">{{ formatTime(p.updatedAt) }}</td>
            <td>
              <button class="btn-edit" @click="editPrompt(p)">编辑</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-else class="table-empty">
        <p>暂无 prompt 模板</p>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="editing" class="modal-overlay" @click.self="editing = null">
      <div class="modal-card">
        <div class="modal-header">
          <h3>编辑 Prompt 模板</h3>
          <button class="modal-close" @click="editing = null">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
        </div>

        <div class="modal-body">
          <div class="info-row">
            <span class="info-label">类型:</span>
            <span class="type-badge" :class="editing.type">{{ typeLabel(editing.type) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">名称:</span>
            <span>{{ editing.name }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">版本:</span>
            <span>v{{ editing.version }}</span>
          </div>

          <div class="form-group">
            <label for="edit-content">模板内容</label>
            <textarea
              id="edit-content"
              v-model="editContent"
              rows="10"
              class="form-textarea"
              placeholder="输入 prompt 模板内容..."
            ></textarea>
          </div>

          <div v-if="saveError" class="save-error">{{ saveError }}</div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-outline" @click="editing = null">取消</button>
          <button class="btn btn-primary" @click="savePrompt" :disabled="saving">
            <span v-if="!saving">保存</span>
            <span v-else class="spinner"></span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listPrompts, updatePrompt } from '../api/admin.js'

const prompts = ref([])
const editing = ref(null)
const editContent = ref('')
const saving = ref(false)
const saveError = ref('')

onMounted(async () => {
  try {
    prompts.value = await listPrompts()
  } catch (err) {
    console.error('加载 prompt 列表失败', err)
  }
})

function editPrompt(p) {
  editing.value = { ...p }
  editContent.value = p.content
  saveError.value = ''
}

async function savePrompt() {
  if (!editing.value) return
  saving.value = true
  saveError.value = ''
  try {
    const updated = await updatePrompt(editing.value.id, {
      content: editContent.value,
      version: editing.value.version
    })
    // 更新本地列表
    const idx = prompts.value.findIndex(p => p.id === editing.value.id)
    if (idx !== -1) {
      prompts.value[idx] = { ...prompts.value[idx], ...updated }
    }
    editing.value = null
  } catch (err) {
    saveError.value = '保存失败: ' + (err.message || '版本冲突')
  } finally {
    saving.value = false
  }
}

function typeLabel(type) {
  const map = { teaching: '教学', mentoring: '护航', system: '系统', review: '审查' }
  return map[type] || type || '未知'
}

function truncate(text, len) {
  if (!text) return ''
  return text.length > len ? text.slice(0, len) + '...' : text
}

function formatTime(dateStr) {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return dateStr
  }
}
</script>

<style scoped>
.admin-shell {
  padding: 24px 32px;
  max-width: 1200px;
  margin: 0 auto;
}

.admin-header {
  margin-bottom: 24px;
}

.admin-title {
  font-size: 20px;
  font-weight: 700;
  color: #2d2a24;
  margin: 0;
}

.admin-subtitle {
  font-size: 13px;
  color: #8a857a;
  margin: 4px 0 0 0;
}

.table-container {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  overflow: hidden;
}

.prompt-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.prompt-table th {
  text-align: left;
  padding: 10px 12px;
  background: #faf9f5;
  color: #5a5548;
  font-weight: 600;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid #e8e3d8;
}

.prompt-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #f0ece4;
  color: #2d2a24;
}
.prompt-table tr:last-child td { border-bottom: none; }
.prompt-table tr:hover { background: #faf9f5; }

.cell-id { font-weight: 600; color: #8a857a; font-size: 11px; width: 40px; }
.cell-content { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: 'SF Mono', monospace; font-size: 12px; color: #5a5548; }
.cell-vars { font-family: 'SF Mono', monospace; font-size: 11px; color: #8a857a; }
.cell-version { font-weight: 600; color: #57ab5a; }
.cell-time { font-size: 12px; color: #8a857a; }

.type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
}
.type-badge.teaching { background: #e8f5e9; color: #2e7d32; }
.type-badge.mentoring { background: #fff3e0; color: #e65100; }
.type-badge.system { background: #e3f2fd; color: #1565c0; }
.type-badge.review { background: #fbe9e7; color: #bf360c; }

.btn-edit {
  padding: 4px 12px;
  border: 1px solid #ddd8cc;
  border-radius: 6px;
  font-size: 12px;
  background: #fff;
  color: #5a5548;
  cursor: pointer;
  transition: all 0.12s;
}
.btn-edit:hover { border-color: #c15f3c; color: #c15f3c; }

.table-empty {
  padding: 40px;
  text-align: center;
  color: #8a857a;
}

/* Modal */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}

.modal-card {
  background: #fff;
  border-radius: 12px;
  width: 640px;
  max-width: 90vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 16px 48px rgba(0,0,0,0.15);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e8e3d8;
}

.modal-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0;
}

.modal-close {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  color: #8a857a;
}
.modal-close:hover { background: #f0ece4; }

.modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}

.info-label {
  font-weight: 500;
  color: #8a857a;
  min-width: 60px;
}

.form-group {
  margin-top: 16px;
}

.form-group label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: #5a5548;
  margin-bottom: 6px;
}

.form-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd8cc;
  border-radius: 6px;
  font-size: 13px;
  font-family: 'SF Mono', monospace;
  color: #2d2a24;
  background: #faf9f5;
  resize: vertical;
  box-sizing: border-box;
  line-height: 1.5;
}
.form-textarea:focus { outline: none; border-color: #c15f3c; box-shadow: 0 0 0 2px #c15f3c18; }

.save-error {
  margin-top: 8px;
  padding: 8px 12px;
  background: #fbe9e7;
  color: #c62828;
  border-radius: 6px;
  font-size: 12px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid #e8e3d8;
}

.btn { padding: 8px 16px; border-radius: 8px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.12s; }
.btn-primary { background: #c15f3c; color: #fff; border: none; }
.btn-primary:hover { background: #d47a4a; }
.btn-outline { background: #fff; color: #5a5548; border: 1px solid #ddd8cc; }
.btn-outline:hover { background: #f8f6f0; }
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
</style>

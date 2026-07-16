<template>
  <div class="projects-page">
    <div class="page-header">
      <h2>项目</h2>
      <p class="text-muted">已导入的代码仓库</p>
    </div>

    <div v-if="loading" class="loading-state">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="projects.length === 0" class="empty-state">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ddd8cc" stroke-width="1.5"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
      </div>
      <h3>还没有项目</h3>
      <p class="text-muted">导入一个 Git 仓库开始使用</p>
      <router-link to="/import" class="btn-primary">导入项目</router-link>
    </div>

    <div v-else class="project-grid">
      <div v-for="p in projects" :key="p.id" class="project-card">
        <div class="card-top">
          <div class="card-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          </div>
          <div class="card-info">
            <h4>{{ p.name || p.repoName || '未命名' }}</h4>
            <p class="text-sm text-muted">{{ p.repoUrl || '' }}</p>
          </div>
        </div>
        <div class="card-stats" v-if="p.stats">
          <span class="stat"><strong>{{ p.stats.fileCount }}</strong> 文件</span>
          <span class="stat"><strong>{{ p.stats.methodCount }}</strong> 方法</span>
          <span class="stat"><strong>{{ p.stats.languages?.length || 0 }}</strong> 语言</span>
        </div>
        <div class="card-actions">
          <button class="btn-outline btn-sm" @click="reindex(p.id)" :disabled="p.reindexing">
            {{ p.reindexing ? '重建中...' : '重建索引' }}
          </button>
          <button class="btn-ghost btn-sm" @click="checkCommits(p.id)">检查更新</button>
          <button class="btn-ghost btn-sm text-coral" @click="deleteProject(p.id)">删除</button>
        </div>
        <div v-if="p.behind > 0" class="card-notice">
          落后 {{ p.behind }} 个提交
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getProjects, deleteProject as deleteProjectApi, reindexProject, checkNewCommits } from '../api/index.js'

const projects = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    const data = await getProjects()
    projects.value = (data || []).map(p => ({ ...p, reindexing: false, behind: 0 }))
  } catch (err) { console.error(err) }
  finally { loading.value = false }
})

async function reindex(id) {
  const p = projects.value.find(x => x.id === id)
  if (!p || p.reindexing) return
  p.reindexing = true
  try {
    const resp = await reindexProject(id)
    if (resp.ok) p.behind = 0
  } catch {}
  finally { p.reindexing = false }
}

async function checkCommits(id) {
  try {
    const resp = await checkNewCommits(id)
    const p = projects.value.find(x => x.id === id)
    if (p && resp.behind) p.behind = resp.behind
  } catch {}
}

async function deleteProject(id) {
  if (!confirm('确定删除此项目？')) return
  try {
    await deleteProjectApi(id)
    projects.value = projects.value.filter(p => p.id !== id)
  } catch {}
}
</script>

<style scoped>
.projects-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 32px 24px;
  width: 100%;
}

.page-header { margin-bottom: 24px; }
.page-header h2 { font-size: 20px; font-weight: 600; margin-bottom: 4px; }

.loading-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  gap: 12px;
  color: #8a857a;
}
.empty-icon { opacity: 0.5; }
.empty-state h3 { font-size: 16px; font-weight: 600; color: #2d2a24; }

.btn-primary {
  display: inline-flex;
  align-items: center;
  padding: 8px 20px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  text-decoration: none;
  transition: background 0.12s;
}
.btn-primary:hover { background: #d47a4a; }

.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.project-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 16px;
  transition: box-shadow 0.12s;
}
.project-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.06); }

.card-top {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.card-icon {
  width: 36px;
  height: 36px;
  background: #f5f0ea;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c15f3c;
  flex-shrink: 0;
}
.card-info h4 { font-size: 14px; font-weight: 600; margin-bottom: 2px; }
.card-info p { font-size: 12px; word-break: break-all; }

.card-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.stat { font-size: 12px; color: #8a857a; }
.stat strong { color: #2d2a24; }

.card-actions {
  display: flex;
  gap: 6px;
}

.btn-sm { padding: 5px 12px; font-size: 12px; border-radius: 6px; cursor: pointer; transition: all 0.12s; }
.btn-outline {
  border: 1px solid #e8e3d8;
  background: #fff;
  color: #5a5548;
}
.btn-outline:hover { border-color: #c15f3c; color: #c15f3c; }
.btn-ghost {
  border: none;
  background: none;
  color: #5a5548;
}
.btn-ghost:hover { background: #f5f0ea; }
.text-coral { color: #c15f3c; }

.card-notice {
  margin-top: 8px;
  padding: 6px 10px;
  background: #fef8e7;
  border-radius: 6px;
  font-size: 12px;
  color: #9a7a2a;
}

.spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>

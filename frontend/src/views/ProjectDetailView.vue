<template>
  <div class="project-detail">
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>加载项目详情...</p>
      <!-- 骨架屏 -->
      <div class="skeleton-detail">
        <div class="skeleton-row" style="width: 60%"></div>
        <div class="skeleton-grid">
          <div class="skeleton-card" v-for="n in 4" :key="n"></div>
        </div>
      </div>
    </div>

    <div v-else-if="error" class="error-state">
      <p>{{ error }}</p>
      <button class="retry-btn" @click="loadDetail">重试</button>
    </div>

    <template v-else-if="project">
      <div class="detail-header">
        <button class="back-btn" @click="$router.push('/projects')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5"/><polyline points="12 19 5 12 12 5"/></svg>
          返回
        </button>
        <h1 class="project-title">{{ project.displayName || project.name }}</h1>
        <span class="project-status" :class="project.status?.toLowerCase()">{{ project.status }}</span>
      </div>

      <div class="detail-meta">
        <div class="meta-item">
          <span class="meta-label">语言</span>
          <span class="meta-value">
            <span class="lang-dot" :style="{ background: langColor(project.language) }"></span>
            {{ project.language }}
          </span>
        </div>
        <div class="meta-item">
          <span class="meta-label">框架</span>
          <span class="meta-value">{{ project.framework || '-' }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">构建工具</span>
          <span class="meta-value">{{ project.buildTool || '-' }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">文件数</span>
          <span class="meta-value">{{ project.totalFiles }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">方法数</span>
          <span class="meta-value">{{ project.totalMethods }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">索引状态</span>
          <span class="meta-value index-status" :class="(project.indexedStatus || '').toLowerCase()">
            {{ project.indexedStatus || 'IDLE' }}
          </span>
        </div>
      </div>

      <div class="detail-actions">
        <router-link :to="`/learn/${project.id}`" class="action-btn primary">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          研读
        </router-link>
        <router-link :to="`/chat/${project.id}`" class="action-btn secondary">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
          对话提问
        </router-link>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getProjectDetail } from '../api/index.js'
import { langColor } from '../utils/colors.js'

const route = useRoute()
const project = ref(null)
const loading = ref(true)
const error = ref('')

onMounted(loadDetail)

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    const res = await getProjectDetail(route.params.id)
    project.value = res.data || res
  } catch (err) {
    error.value = err.message || '加载失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.project-detail {
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
  overflow-y: auto;
  height: 100%;
}

.loading-state,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;
  color: #8a857a;
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.retry-btn {
  padding: 8px 16px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

/* 骨架屏 */
.skeleton-detail {
  width: 100%;
  max-width: 800px;
  margin-top: 24px;
}
.skeleton-row {
  height: 24px;
  margin-bottom: 16px;
  background: linear-gradient(90deg, #f0ece4 25%, #e8e3d8 50%, #f0ece4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 4px;
}
.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.skeleton-card {
  height: 80px;
  background: linear-gradient(90deg, #f0ece4 25%, #e8e3d8 50%, #f0ece4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 8px;
}
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }

.detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.back-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: 1px solid #e8e3d8;
  border-radius: 6px;
  background: #fff;
  color: #5a5548;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.12s;
}
.back-btn:hover { border-color: #d4cfc2; background: #f8f6f0; }

.project-title {
  font-size: 22px;
  font-weight: 700;
  color: #2d2a24;
  margin: 0;
  flex: 1;
}

.project-status {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 10px;
  font-weight: 500;
  background: #f0ece4;
  color: #5a5548;
}
.project-status.active { background: #e8f5e9; color: #2e7d32; }
.project-status.archived { background: #fce4ec; color: #c62828; }
.project-status.inactive { background: #f5f5f5; color: #9e9e9e; }
.project-status.pending { background: #fff3e0; color: #e65100; }

.detail-meta {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 24px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
}

.meta-label {
  font-size: 11px;
  color: #8a857a;
  font-weight: 500;
  text-transform: uppercase;
}

.meta-value {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  display: flex;
  align-items: center;
  gap: 4px;
}

.lang-dot { width: 8px; height: 8px; border-radius: 50%; }

.index-status.indexing { color: #c15f3c; }
.index-status.idle { color: #57ab5a; }

.detail-actions {
  display: flex;
  gap: 12px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.12s;
}
.action-btn.primary { background: #c15f3c; color: #fff; border: none; }
.action-btn.primary:hover { background: #a84e2e; }
.action-btn.secondary { background: transparent; color: #5a5548; border: 1px solid #e8e3d8; }
.action-btn.secondary:hover { border-color: #d4cfc2; background: #f8f6f0; }
</style>

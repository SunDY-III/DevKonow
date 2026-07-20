<template>
  <div class="learn">
    <!-- 头部：项目选择 -->
    <div class="learn-header">
      <div class="learn-header-left">
        <h1 class="learn-title">项目研读</h1>
        <p class="learn-subtitle" v-if="currentProjectName">{{ currentProjectName }}</p>
      </div>
      <div class="learn-header-right">
        <select v-model="selectedProjectId" class="project-select" @change="onProjectChange">
          <option value="">-- 选择项目 --</option>
          <option v-for="p in projectStore.projects" :key="p.id" :value="p.id">
            {{ p.displayName || p.name }}
          </option>
        </select>
      </div>
    </div>

    <!-- 内容区 -->
    <div v-if="!selectedProjectId" class="learn-empty">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ddd8cc" stroke-width="1.5">
          <path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/>
        </svg>
      </div>
      <h3>选择一个项目开始研读</h3>
      <p class="text-muted">了解项目的架构、核心设计和实现模式</p>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="learn-loading">
      <div class="spinner"></div>
      <p>正在分析项目结构，请稍候...</p>
    </div>

    <!-- 错误态 -->
    <div v-else-if="error" class="learn-error">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c62828" stroke-width="1.5">
        <circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>
      </svg>
      <h3>加载失败</h3>
      <p>{{ error }}</p>
      <button class="retry-btn" @click="loadOverview">重试</button>
    </div>

    <!-- 研读内容 -->
    <div v-if="overview && !loading" class="learn-content">
      <!-- 架构概览 -->
      <section class="learn-section">
        <h2 class="section-title">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          架构概览
        </h2>
        <div class="section-body">
          <p class="arch-summary" v-if="overview.architecture?.summary">{{ overview.architecture.summary }}</p>
          <ArchitectureMap
            :diagramData="overview.architecture?.diagramData"
            title="项目架构图"
            :modules="overview.architecture?.modules?.length"
          />
          <!-- 模块列表 -->
          <div v-if="overview.architecture?.modules?.length" class="module-grid">
            <div v-for="(mod, i) in overview.architecture.modules" :key="i" class="card-base module-card">
              <h4 class="module-name">{{ mod.name }}</h4>
              <p class="module-desc" v-if="mod.description">{{ mod.description }}</p>
              <div v-if="mod.responsibilities?.length" class="module-responsibilities">
                <span v-for="(r, j) in mod.responsibilities" :key="j" class="topic-tag">{{ r }}</span>
              </div>
              <span v-if="mod.techStack" class="module-tech">{{ mod.techStack }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 代码高亮 -->
      <section class="learn-section">
        <h2 class="section-title">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          代码亮点
        </h2>
        <div class="section-body">
          <CodeHighlights :highlights="overview.highlights" />
        </div>
      </section>

      <!-- 设计模式 -->
      <section class="learn-section">
        <h2 class="section-title">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
          设计模式
        </h2>
        <div class="section-body">
          <div v-if="overview.patterns?.length" class="pattern-list">
            <div v-for="(p, i) in overview.patterns" :key="i" class="card-base pattern-card">
              <div class="pattern-header">
                <PatternBadge :name="p.name" :category="p.category" :description="p.description" />
              </div>
              <p class="pattern-desc" v-if="p.description">{{ p.description }}</p>
              <div v-if="p.participants?.length" class="pattern-participants">
                <span class="p-label">参与类:</span>
                <span v-for="(part, j) in p.participants" :key="j" class="topic-tag">{{ part }}</span>
              </div>
              <p class="pattern-benefit" v-if="p.benefit">
                <span class="p-label">收益:</span> {{ p.benefit }}
              </p>
            </div>
          </div>
          <p v-else class="text-muted">未检测到明显设计模式</p>
        </div>
      </section>

      <!-- L1-L5 学习路线 -->
      <section class="learn-section">
        <h2 class="section-title">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="20" x2="12" y2="10"/><line x1="18" y1="20" x2="18" y2="4"/><line x1="6" y1="20" x2="6" y2="16"/></svg>
          学习路线 (L1~L5)
        </h2>
        <div class="section-body">
          <div v-if="overview.roadmap?.length" class="roadmap">
            <div v-for="(level, i) in overview.roadmap" :key="i" class="roadmap-item">
              <div class="roadmap-number" :class="'l' + level.level">{{ level.level }}</div>
              <div class="roadmap-content">
                <h4 class="roadmap-name">{{ level.name }}</h4>
                <p class="roadmap-advice" v-if="level.advice">{{ level.advice }}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 选段提问 -->
      <section class="learn-section">
        <h2 class="section-title">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
          关于本项目提问
        </h2>
        <div class="section-body">
          <div class="ask-bar">
            <input
              v-model="question"
              @keydown.enter="askQuestion"
              placeholder="就此项目提问... 例如：订单模块的设计思路是什么？"
              class="ask-input"
            />
            <button class="ask-btn" @click="askQuestion" :disabled="!question.trim()">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polyline points="22 2 15 22 11 13 2 9 22 2"/></svg>
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '../stores/useProjectStore.js'
import { useStudyStore } from '../stores/useStudyStore.js'
import ArchitectureMap from '../components/ArchitectureMap.vue'
import CodeHighlights from '../components/CodeHighlights.vue'
import PatternBadge from '../components/PatternBadge.vue'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const studyStore = useStudyStore()

const selectedProjectId = ref(route.params.projectId || '')
const overview = ref(null)
const loading = ref(false)
const error = ref('')
const question = ref('')
const navLock = ref(false)

const currentProjectName = computed(() => {
  if (!selectedProjectId.value) return ''
  const p = projectStore.projects.find(p => String(p.id) === String(selectedProjectId.value))
  return p?.displayName || p?.name || ''
})

onMounted(async () => {
  await projectStore.ensureLoaded()
  if (route.params.projectId) {
    selectedProjectId.value = route.params.projectId
    await loadOverview()
  }
})

async function onProjectChange() {
  if (!selectedProjectId.value) {
    overview.value = null
    return
  }
  router.replace(`/learn/${selectedProjectId.value}`)
  await loadOverview()
}

async function loadOverview() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await studyStore.loadOverview(selectedProjectId.value)
    window.scrollTo(0, 0)
  } catch (err) {
    console.error('加载研读失败:', err)
    error.value = err.message || '加载项目研读数据失败，请检查网络后重试'
  } finally {
    loading.value = false
  }
}

function askQuestion() {
  if (!question.value.trim() || !selectedProjectId.value || navLock.value) return
  const q = question.value.trim()
  question.value = ''
  navLock.value = true
  router.push(`/chat/${selectedProjectId.value}?question=${encodeURIComponent(q)}`)
}
</script>

<style scoped>
.learn {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.learn-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid #e8e3d8;
  flex-shrink: 0;
}

.learn-title {
  font-size: 20px;
  font-weight: 700;
  color: #2d2a24;
  margin: 0;
}

.learn-subtitle {
  font-size: 13px;
  color: #8a857a;
  margin: 2px 0 0;
}

.project-select {
  padding: 8px 12px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  font-size: 13px;
  background: #fff;
  color: #2d2a24;
  cursor: pointer;
  min-width: 200px;
}
.project-select:focus { border-color: #c15f3c; outline: none; }

.learn-empty,
.learn-loading,
.learn-error {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px;
  color: #8a857a;
}

.learn-error h3 { font-size: 16px; font-weight: 600; color: #2d2a24; margin: 0; }
.learn-error p { font-size: 13px; color: #8a857a; max-width: 360px; text-align: center; }

.retry-btn {
  padding: 8px 16px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}
.retry-btn:hover { background: #d47a4a; }

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.learn-content {
  flex: 1;
  padding: 24px;
  max-width: 960px;
  margin: 0 auto;
  width: 100%;
}

.learn-section { margin-bottom: 32px; }

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 16px;
}

.section-body { padding-left: 4px; }

/* 架构 */
.arch-summary {
  font-size: 14px;
  color: #5a5548;
  line-height: 1.6;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: #f8f6f0;
  border-radius: 8px;
}

.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.module-card { padding: 16px; }

.module-name {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 4px;
}

.module-desc {
  font-size: 12px;
  color: #5a5548;
  margin: 0 0 8px;
  line-height: 1.4;
}

.module-responsibilities {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}

.module-tech {
  font-size: 11px;
  padding: 2px 8px;
  background: #f0ece4;
  border-radius: 4px;
  color: #5a5548;
}

/* 模式 */
.pattern-list { display: flex; flex-direction: column; gap: 12px; }

.pattern-card { padding: 16px; }

.pattern-header { margin-bottom: 8px; }

.pattern-desc {
  font-size: 13px;
  color: #5a5548;
  margin: 0 0 8px;
  line-height: 1.5;
}

.pattern-participants {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  margin-bottom: 6px;
}

.pattern-benefit {
  font-size: 13px;
  color: #5a5548;
  margin: 0;
}

.p-label {
  font-size: 12px;
  font-weight: 500;
  color: #8a857a;
}

/* 路线 */
.roadmap { display: flex; flex-direction: column; gap: 8px; }

.roadmap-item {
  display: flex;
  gap: 14px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  align-items: center;
  transition: box-shadow 0.12s;
}
.roadmap-item:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.04); }

.roadmap-number {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
}
.roadmap-number.l1 { background: #f5ede8; color: #c15f3c; }
.roadmap-number.l2 { background: #f3e8d8; color: #b87a3c; }
.roadmap-number.l3 { background: #e8edd8; color: #5a7a3c; }
.roadmap-number.l4 { background: #d8e8ed; color: #3c6a7a; }
.roadmap-number.l5 { background: #e8d8ed; color: #7a3c6a; }

.roadmap-name {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 2px;
}

.roadmap-advice {
  font-size: 12px;
  color: #5a5548;
  margin: 0;
}

/* 提问 */
.ask-bar {
  display: flex;
  gap: 8px;
}

.ask-input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  font-size: 13px;
  color: #2d2a24;
  background: #fff;
  outline: none;
  transition: border-color 0.12s;
}
.ask-input:focus { border-color: #c15f3c; }
.ask-input::placeholder { color: #c9c3b5; }

.ask-btn {
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
}
.ask-btn:hover { background: #d47a4a; }
.ask-btn:disabled { background: #ddd8cc; cursor: not-allowed; }
</style>

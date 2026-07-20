<template>
  <div class="discover">
    <!-- 头部 -->
    <div class="hero" :class="{ collapsed: hasResults }">
      <h1 class="hero-title">发现值得学习的开源项目</h1>
      <p class="hero-subtitle">告诉我你想学什么，DevKnow 去 GitHub 帮你找合适的项目</p>

      <!-- 搜索输入区 - 参考 Clew Quest 的问答式交互 -->
      <div class="search-box">
        <div class="search-input-wrapper">
          <svg class="search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
          <input
            ref="inputRef"
            v-model="query"
            class="search-input"
            placeholder="例如：微服务网关、Spring Cloud、Go 并发编程、React 组件库..."
            @keydown.enter="search"
            @input="clearError"
          />
          <button v-if="query" class="clear-btn" @click="query = ''; clearError()">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <button class="search-btn" :disabled="loading || !query.trim()" @click="search">
          <svg v-if="loading" class="spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10" stroke-dasharray="32" stroke-dashoffset="32"><animate attributeName="stroke-dashoffset" values="32;0;32" dur="1.5s" repeatCount="indefinite"/></svg>
          <span>{{ loading ? '探索中...' : '探索' }}</span>
        </button>
      </div>

      <!-- 提示标签 -->
      <div v-if="!hasResults" class="hints">
        <span class="hint-tag" @click="query = '想学微服务网关和 API 网关设计'; search()">🌐 微服务网关</span>
        <span class="hint-tag" @click="query = '想学习 Go 语言并发编程实战'; search()">⚡ Go 并发编程</span>
        <span class="hint-tag" @click="query = '想学 React 组件库设计和开发'; search()">⚛️ React 组件库</span>
        <span class="hint-tag" @click="query = '想学 Spring Boot + 云原生部署'; search()">☁️ Spring Cloud</span>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner"></div>
      <p>正在分析你的学习意图，搜索 GitHub ...</p>
    </div>

    <!-- 错误提示 -->
    <div v-if="error" class="error-box">
      <p>{{ error }}</p>
      <button class="retry-btn" @click="search">重试</button>
    </div>

    <!-- 推荐列表 -->
    <div v-if="result && !loading" class="results">
      <div class="results-header">
        <h2>推荐项目</h2>
        <span class="results-count">找到 {{ result.totalFound }} 个项目，推荐 {{ result.recommendations.length }} 个</span>
      </div>

      <!-- 意图摘要 -->
      <div v-if="result.intent && result.intent.summary" class="intent-summary">
        <span class="intent-label">学习目标：</span>
        {{ result.intent.summary }}
      </div>

      <!-- 项目卡片 -->
      <div class="repo-grid">
        <div v-for="repo in result.recommendations" :key="repo.id" class="repo-card">
          <div class="repo-card-header">
            <h3 class="repo-name">
              <a :href="repo.htmlUrl" target="_blank" rel="noopener">{{ repo.fullName }}</a>
            </h3>
            <div class="repo-stars">⭐ {{ repo.stars.toLocaleString() }}</div>
          </div>

          <p class="repo-desc">{{ repo.description || '暂无描述' }}</p>

          <div class="repo-meta">
            <span v-if="repo.language" class="repo-lang">
              <span class="lang-dot" :style="{ background: langColor(repo.language) }"></span>
              {{ repo.language }}
            </span>
            <span class="repo-stat">⑂ {{ repo.forks.toLocaleString() }}</span>
            <span v-if="repo.license" class="repo-license">{{ repo.license }}</span>
            <span class="repo-score" :title="'学习适龄评分: ' + repo.score + '/100'">
              评分 {{ repo.score }}
            </span>
          </div>

          <div v-if="repo.topics" class="repo-topics">
            <span v-for="topic in repo.topics.split(', ').slice(0, 4)" :key="topic" class="topic-tag">{{ topic }}</span>
          </div>

          <div class="repo-actions">
            <button class="action-btn primary" :disabled="importingRepo === repo.fullName" @click="startLearning(repo)">
              <template v-if="importingRepo === repo.fullName">导入中...</template>
              <template v-else>开始学习</template>
            </button>
            <a :href="repo.htmlUrl" target="_blank" rel="noopener" class="action-btn secondary">
              在 GitHub 查看
            </a>
          </div>

          <!-- 学习导入进度 -->
          <Transition name="fade-slide">
            <div v-if="importProgress[repo.fullName]" class="import-progress">
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: importProgress[repo.fullName].percent + '%' }"></div>
            </div>
            <span class="progress-text">{{ importProgress[repo.fullName].message }}</span>
          </div>
          </Transition>
        </div>
      </div>
    </div>

    <!-- 空结果 -->
    <div v-if="searched && !loading && result && result.recommendations.length === 0" class="empty-state">
      <p>没有找到合适的项目</p>
      <p class="empty-hint">试试换一个搜索关键词，或描述得更具体一些</p>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { discoverProjects, createLearningImportSSE } from '../api/index.js'

const query = ref('')
const loading = ref(false)
const searched = ref(false)
const error = ref('')
const result = ref(null)
const hasResults = ref(false)
const importingRepo = ref('')
const importProgress = ref({})
const inputRef = ref(null)

onMounted(() => { inputRef.value?.focus() })

function clearError() { error.value = '' }

async function search() {
  const q = query.value.trim()
  if (!q || loading.value) return

  loading.value = true
  error.value = ''
  result.value = null

  try {
    const data = await discoverProjects(q)
    result.value = data
    hasResults.value = true
    searched.value = true
  } catch (err) {
    error.value = err.message || '搜索失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function startLearning(repo) {
  const repoUrl = repo.htmlUrl
  const key = repo.fullName
  importingRepo.value = key
  importProgress.value = { ...importProgress.value, [key]: { percent: 0, message: '正在准备...' } }

  const es = createLearningImportSSE(repoUrl)
  es.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      importProgress.value = {
        ...importProgress.value,
        [key]: { percent: data.percent || 0, message: data.message || '' }
      }
    } catch {}
  }
  es.addEventListener('progress', (event) => {
    try {
      const data = JSON.parse(event.data)
      importProgress.value = {
        ...importProgress.value,
        [key]: { percent: data.percent || 0, message: data.message || '' }
      }
    } catch {}
  })
  es.addEventListener('error', (event) => {
    try {
      const data = JSON.parse(event.data)
      importProgress.value = {
        ...importProgress.value,
        [key]: { percent: 0, message: '导入失败: ' + (data.message || '未知错误') }
      }
    } catch {}
    es.close()
    importingRepo.value = ''
  })
  es.addEventListener('project', () => {
    importProgress.value = {
      ...importProgress.value,
      [key]: { percent: 100, message: '✅ 导入完成，可以去 Chat 中提问了！' }
    }
    es.close()
    importingRepo.value = ''
  })
  es.onerror = () => {
    importProgress.value = {
      ...importProgress.value,
      [key]: { percent: 0, message: '连接断开，请重试' }
    }
    es.close()
    importingRepo.value = ''
  }
}

function langColor(lang) {
  const colors = {
    JavaScript: '#f1e05a', TypeScript: '#3178c6', Python: '#3572A5',
    Java: '#b07219', Go: '#00ADD8', Rust: '#dea584',
    'C++': '#f34b7d', C: '#555555', Ruby: '#701516',
    PHP: '#4F5D95', Swift: '#F05138', Kotlin: '#A97BFF',
    Vue: '#41b883', Dart: '#00B4AB', Scala: '#c22d40'
  }
  return colors[lang] || '#8a857a'
}
</script>

<style scoped>
.discover {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

/* ── Hero ── */
.hero {
  text-align: center;
  padding: 60px 20px 40px;
  transition: all 0.3s;
  flex-shrink: 0;
}
.hero.collapsed {
  padding: 24px 20px;
  border-bottom: 1px solid #e8e3d8;
  margin-bottom: 20px;
}
.hero.collapsed .hero-title { font-size: 18px; }
.hero.collapsed .hero-subtitle { display: none; }
.hero.collapsed .hints { display: none; }

.hero-title {
  font-size: 28px;
  font-weight: 700;
  color: #2d2a24;
  margin: 0 0 8px;
}
.hero-subtitle {
  color: #8a857a;
  font-size: 14px;
  margin: 0 0 28px;
}

/* ── Search ── */
.search-box {
  display: flex;
  gap: 8px;
  max-width: 600px;
  margin: 0 auto;
}
.search-input-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 0 12px;
  transition: border-color 0.2s;
}
.search-input-wrapper:focus-within {
  border-color: #c15f3c;
  box-shadow: 0 0 0 3px rgba(193,95,60,0.1);
}
.search-icon {
  color: #8a857a;
  flex-shrink: 0;
}
.search-input {
  flex: 1;
  border: none;
  outline: none;
  padding: 11px 10px;
  font-size: 14px;
  background: transparent;
  color: #2d2a24;
}
.search-input::placeholder { color: #b5b0a2; }
.clear-btn {
  background: none;
  border: none;
  color: #8a857a;
  cursor: pointer;
  padding: 4px;
  display: flex;
}
.clear-btn:hover { color: #2d2a24; }
.search-btn {
  padding: 11px 20px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.12s;
  white-space: nowrap;
}
.search-btn:hover { background: #a84e2e; }
.search-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── Hints ── */
.hints {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 20px;
}
.hint-tag {
  padding: 6px 14px;
  background: #f5ede8;
  border-radius: 20px;
  font-size: 13px;
  color: #5a5548;
  cursor: pointer;
  transition: all 0.12s;
}
.hint-tag:hover { background: #e8dcd4; color: #c15f3c; }

/* ── Loading ── */
.loading-state {
  text-align: center;
  padding: 60px 20px;
  color: #8a857a;
}
.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin: 0 auto 12px;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* ── Error ── */
.error-box {
  text-align: center;
  padding: 40px 20px;
  color: #c15f3c;
}
.retry-btn {
  margin-top: 12px;
  padding: 8px 16px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}

/* ── Results ── */
.results {
  flex: 1;
  padding: 0 24px 40px;
  max-width: 960px;
  margin: 0 auto;
  width: 100%;
}
.results-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}
.results-header h2 { font-size: 16px; font-weight: 600; color: #2d2a24; margin: 0; }
.results-count { font-size: 12px; color: #8a857a; }

.intent-summary {
  background: #f5ede8;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  color: #5a5548;
  margin-bottom: 20px;
}
.intent-label { font-weight: 600; color: #2d2a24; }

/* ── Repo Grid ── */
.repo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 16px;
}
.repo-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 16px;
  transition: box-shadow 0.2s;
}
.repo-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.06); }

.repo-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.repo-name {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.repo-name a { color: #c15f3c; text-decoration: none; }
.repo-name a:hover { text-decoration: underline; }
.repo-stars { font-size: 13px; color: #8a857a; white-space: nowrap; }

.repo-desc {
  font-size: 13px;
  color: #5a5548;
  line-height: 1.5;
  margin: 0 0 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.repo-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  color: #8a857a;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.repo-lang { display: flex; align-items: center; gap: 4px; }
.lang-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.repo-score {
  margin-left: auto;
  background: #f0ece4;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.repo-topics {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 12px;
}
.topic-tag {
  font-size: 11px;
  padding: 2px 8px;
  background: #f0ece4;
  border-radius: 4px;
  color: #5a5548;
}

.repo-actions {
  display: flex;
  gap: 8px;
}
.action-btn {
  padding: 7px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s;
  text-decoration: none;
  text-align: center;
}
.action-btn.primary {
  background: #c15f3c;
  color: #fff;
  border: none;
}
.action-btn.primary:hover { background: #a84e2e; }
.action-btn.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.secondary {
  background: transparent;
  color: #5a5548;
  border: 1px solid #e8e3d8;
}
.action-btn.secondary:hover { border-color: #d4cfc2; background: #f8f6f0; }

/* ── Import Progress ── */
.import-progress {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0ece4;
}
.progress-bar {
  height: 4px;
  background: #f0ece4;
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 6px;
}
.progress-fill {
  height: 100%;
  background: #57ab5a;
  border-radius: 2px;
  transition: width 0.3s;
}
.progress-text { font-size: 12px; color: #8a857a; }

/* ── Empty ── */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #8a857a;
}
.empty-hint { font-size: 13px; margin-top: 8px; }

/* ── Transitions ── */
.fade-slide-enter-active, .fade-slide-leave-active {
  transition: all 0.3s ease;
}
.fade-slide-enter-from, .fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>

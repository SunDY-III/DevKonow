<template>
  <div class="mentor-shell">
    <!-- 顶部: 项目上下文 -->
    <div class="mentor-header">
      <div class="mentor-header-left">
        <h2 class="mentor-title">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#c15f3c" stroke-width="2">
            <path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/>
          </svg>
          护航学习
        </h2>
        <span v-if="projectName" class="project-badge">{{ projectName }}</span>
      </div>
      <div class="mentor-header-right">
        <button class="btn btn-outline refresh-btn" @click="refreshPlan" :disabled="loading">
          <span v-if="!loading">刷新计划</span>
          <span v-else class="spinner"></span>
        </button>
      </div>
    </div>

    <div class="mentor-body">
      <!-- 左侧: 学习进度 + 成就 -->
      <div class="mentor-sidebar">
        <!-- 进度总览 -->
        <div class="progress-card">
          <h3 class="section-title">学习进度</h3>
          <div class="progress-ring">
            <svg width="100" height="100" viewBox="0 0 100 100">
              <circle cx="50" cy="50" r="42" fill="none" stroke="#f0ece4" stroke-width="8"/>
              <circle
                cx="50" cy="50" r="42"
                fill="none" stroke="#c15f3c" stroke-width="8"
                stroke-linecap="round"
                :stroke-dasharray="circumference"
                :stroke-dashoffset="circumference * (1 - progressPercent / 100)"
                transform="rotate(-90 50 50)"
                style="transition: stroke-dashoffset 0.6s ease"
              />
            </svg>
            <div class="progress-text">
              <span class="progress-num">{{ Math.round(progressPercent) }}%</span>
              <span class="progress-label">已完成</span>
            </div>
          </div>
          <div class="progress-stats">
            <div class="stat">
              <span class="stat-value">{{ chapters.length }}</span>
              <span class="stat-label">章节</span>
            </div>
            <div class="stat">
              <span class="stat-value">{{ totalTodos }}</span>
              <span class="stat-label">任务</span>
            </div>
            <div class="stat">
              <span class="stat-value">{{ completedTodos }}</span>
              <span class="stat-label">已完成</span>
            </div>
          </div>
        </div>

        <!-- 成就展示 -->
        <div class="achievements-card">
          <h3 class="section-title">成就</h3>
          <div class="achievement-list">
            <div
              v-for="ach in achievements"
              :key="ach.id"
              class="achievement-item"
              :class="{ locked: !ach.unlocked }"
            >
              <div class="achievement-icon" :class="ach.icon || 'star'" v-html="achIcon(ach.icon)">
              </div>
              <div class="achievement-info">
                <span class="achievement-title">{{ ach.title }}</span>
                <span class="achievement-desc">{{ ach.description }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧: 章节化学习计划 + TODO -->
      <div class="mentor-content">
        <!-- 章节列表 -->
        <div v-if="chapters.length > 0" class="chapters">
          <div
            v-for="(chapter, ci) in chapters"
            :key="ci"
            class="chapter-card"
            :class="{ 'chapter-active': activeChapter === ci }"
          >
            <div class="chapter-header" @click="toggleChapter(ci)">
              <div class="chapter-index">{{ ci + 1 }}</div>
              <div class="chapter-info">
                <h4 class="chapter-title">{{ chapter.title }}</h4>
                <p class="chapter-desc">{{ chapter.description }}</p>
              </div>
              <svg
                width="16" height="16"
                viewBox="0 0 24 24"
                fill="none" stroke="#8a857a" stroke-width="2"
                :class="{ rotated: activeChapter === ci }"
                class="chapter-arrow"
              >
                <path d="m6 9 6 6 6-6"/>
              </svg>
            </div>
            <div v-if="activeChapter === ci" class="chapter-todos">
              <TodoBoard
                :todos="chapter.todos || []"
                @update="onTodoUpdate"
                @delete="onTodoDelete"
              />
            </div>
          </div>
        </div>

        <!-- 加载中 -->
        <div v-else-if="loading" class="loading-state">
          <div class="spinner-lg"></div>
          <p>正在生成护航计划...</p>
        </div>

        <!-- 错误态 -->
        <div v-else-if="error" class="error-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c62828" stroke-width="1.5">
            <circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>
          </svg>
          <h3>加载失败</h3>
          <p>{{ error }}</p>
          <button class="btn btn-primary" @click="refreshPlan">重试</button>
        </div>

        <!-- 空状态 -->
        <div v-else class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ddd8cc" stroke-width="1.5">
            <path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/>
          </svg>
          <h3>开始护航学习</h3>
          <p>点击下方按钮，AI 将根据项目代码自动生成个性化学习计划</p>
          <button class="btn btn-primary" @click="refreshPlan">生成学习计划</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProjects, getMentorPlan, getMentorAchievements } from '../api/index.js'
import TodoBoard from '../components/TodoBoard.vue'
import { listTodos, updateTodo as apiUpdateTodo, deleteTodo as apiDeleteTodo } from '../api/todo.js'

const route = useRoute()
const router = useRouter()

const projectId = ref(route.params.projectId || '')
const projectName = ref('')
const loading = ref(false)
const error = ref('')
const chapters = ref([])
const achievements = ref([])
const activeChapter = ref(null)
const todos = ref([])

const progressPercent = computed(() => {
  if (totalTodos.value === 0) return 0
  return (completedTodos.value / totalTodos.value) * 100
})

const totalTodos = computed(() => {
  return chapters.value.reduce((sum, ch) => sum + (ch.todos?.length || 0), 0)
})

const completedTodos = computed(() => {
  return chapters.value.reduce((sum, ch) => {
    return sum + (ch.todos?.filter(t => t.status === 'completed').length || 0)
  }, 0)
})

const circumference = 2 * Math.PI * 42 // r=42

onMounted(async () => {
  try {
    const projects = await getProjects()
    if (projects && projects.length > 0) {
      projectName.value = projects[0].name || projects[0].repoName || ''
      if (!projectId.value) {
        projectId.value = projects[0].id
      }
    }
  } catch {}

  if (projectId.value) {
    await loadPlan()
    await loadAchievements()
  }
})

async function loadPlan() {
  loading.value = true
  error.value = ''
  try {
    const plan = await getMentorPlan(projectId.value)
    chapters.value = (plan.chapters || []).map(ch => ({
      ...ch,
      todos: (ch.todos || []).map(t => ({ ...t, status: 'pending' }))
    }))
    // 尝试加载已有 TODO
    try {
      const existing = await listTodos(projectId.value)
      if (existing && existing.length > 0) {
        // 合并已有进度
        for (const todo of existing) {
          for (const ch of chapters.value) {
            const match = ch.todos.find(t => t.title === todo.title)
            if (match) {
              match.status = todo.status
              match.id = todo.id
            }
          }
        }
      }
    } catch {}
  } catch (err) {
    console.error('加载护航计划失败', err)
    error.value = err.message || '加载护航计划失败，请检查网络后重试'
  } finally {
    loading.value = false
  }
}

async function loadAchievements() {
  try {
    achievements.value = await getMentorAchievements(projectId.value)
  } catch (err) {
    console.warn('加载成就失败', err)
  }
}

async function refreshPlan() {
  await loadPlan()
  if (chapters.value.length > 0) {
    activeChapter.value = 0
  }
}

function toggleChapter(ci) {
  activeChapter.value = activeChapter.value === ci ? null : ci
}

function onTodoUpdate(todo) {
  // 乐观更新：先保存旧状态
  const snapshots = []
  for (const ch of chapters.value) {
    const idx = ch.todos.findIndex(t => t.id === todo.id || t.title === todo.title)
    if (idx !== -1) {
      snapshots.push({ ch, idx, old: { ...ch.todos[idx] } })
      ch.todos[idx] = { ...ch.todos[idx], ...todo }
      break
    }
  }
  // 持久化到后端，失败时回滚
  const method = todo.id ? 'PUT' : 'POST'
  const url = todo.id ? `/api/study/${projectId.value}/todo/${todo.id}` : `/api/study/${projectId.value}/todo`
  fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(todo)
  }).then(async res => {
    if (!res.ok) {
      const errText = await res.text().catch(() => '')
      throw new Error(errText || `HTTP ${res.status}`)
    }
    return res.json().catch(() => {})
  }).catch(err => {
    console.error('TODO 更新失败，回滚', err)
    // 回滚到操作前状态
    for (const snap of snapshots) {
      snap.ch.todos[snap.idx] = snap.old
    }
  })
}

function onTodoDelete(todo) {
  // 乐观更新：先保存旧状态
  const deletedFrom = []
  for (const ch of chapters.value) {
    const idx = ch.todos.findIndex(t => t.id === todo.id)
    if (idx !== -1) {
      deletedFrom.push({ ch, idx, old: ch.todos[idx] })
      ch.todos.splice(idx, 1)
      break
    }
  }
  if (todo.id) {
    apiDeleteTodo(projectId.value, todo.id).catch(err => {
      console.error('TODO 删除失败，回滚', err)
      // 回滚：恢复被删除的项
      for (const item of deletedFrom) {
        item.ch.todos.splice(item.idx, 0, item.old)
      }
    })
  }
}

function achIcon(icon) {
  const map = {
    star: '<svg width="16" height="16" viewBox="0 0 24 24" fill="#c15f3c" stroke="#c15f3c" stroke-width="1"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>',
    compass: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c15f3c" stroke-width="2"><circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/></svg>',
    trophy: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c15f3c" stroke-width="2"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/><path d="M4 22h16"/><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22"/><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22"/><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/></svg>'
  }
  return map[icon] || map.star
}
</script>

<style scoped>
.mentor-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #faf9f5;
}

.mentor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid #e8e3d8;
  background: #fff;
}

.mentor-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mentor-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0;
}

.project-badge {
  padding: 3px 10px;
  background: #f0ece4;
  border-radius: 6px;
  font-size: 12px;
  color: #5a5548;
  font-weight: 500;
}

.mentor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.mentor-sidebar {
  width: 280px;
  flex-shrink: 0;
  padding: 16px;
  border-right: 1px solid #e8e3d8;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mentor-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}

/* Progress Card */
.progress-card, .achievements-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  padding: 16px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 12px 0;
}

.progress-ring {
  position: relative;
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

.progress-text {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
}

.progress-num {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: #c15f3c;
}

.progress-label {
  font-size: 11px;
  color: #8a857a;
}

.progress-stats {
  display: flex;
  justify-content: space-around;
}

.stat {
  text-align: center;
}

.stat-value {
  display: block;
  font-size: 18px;
  font-weight: 600;
  color: #2d2a24;
}

.stat-label {
  font-size: 11px;
  color: #8a857a;
}

/* Achievements */
.achievement-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.achievement-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 8px;
  background: #faf9f5;
}
.achievement-item.locked { opacity: 0.5; }

.achievement-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  background: #fff3e0;
  flex-shrink: 0;
}

.achievement-info {
  display: flex;
  flex-direction: column;
}

.achievement-title {
  font-size: 12px;
  font-weight: 600;
  color: #2d2a24;
}

.achievement-desc {
  font-size: 11px;
  color: #8a857a;
}

/* Chapters */
.chapters {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chapter-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  overflow: hidden;
  transition: box-shadow 0.15s;
}
.chapter-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.chapter-card.chapter-active { border-color: #c15f3c; }

.chapter-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  cursor: pointer;
  user-select: none;
}

.chapter-index {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #c15f3c;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.chapter-info { flex: 1; }

.chapter-title {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0;
}

.chapter-desc {
  font-size: 12px;
  color: #8a857a;
  margin: 2px 0 0 0;
}

.chapter-arrow {
  transition: transform 0.2s;
  flex-shrink: 0;
}
.chapter-arrow.rotated { transform: rotate(180deg); }

.chapter-todos {
  border-top: 1px solid #f0ece4;
}

/* Loading & Empty & Error */
.loading-state, .empty-state, .error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  gap: 12px;
  color: #8a857a;
}

.spinner-lg {
  width: 32px;
  height: 32px;
  border: 3px solid #f0ece4;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.empty-state h3 { font-size: 16px; font-weight: 600; color: #2d2a24; }
.empty-state p { font-size: 13px; text-align: center; max-width: 360px; }

.error-state h3 { font-size: 16px; font-weight: 600; color: #2d2a24; margin: 0; }
.error-state p { font-size: 13px; text-align: center; max-width: 360px; color: #8a857a; }

/* Buttons */
.btn { padding: 8px 16px; border-radius: 8px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.12s; border: 1px solid #e8e3d8; background: #fff; color: #5a5548; }
.btn-primary { background: #c15f3c; color: #fff; border-color: #c15f3c; }
.btn-primary:hover { background: #d47a4a; }
.btn-outline:hover { background: #f8f6f0; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

.refresh-btn { min-width: 90px; display: inline-flex; align-items: center; justify-content: center; }

.spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
</style>

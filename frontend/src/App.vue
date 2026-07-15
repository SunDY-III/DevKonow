<template>
  <div class="app-layout">
    <aside class="sidebar">
      <div class="sidebar-header">
        <h2>DevKnow</h2>
        <div class="mode-badge" :class="currentMode" @click="showModeMenu = !showModeMenu">
          <span class="mode-dot"></span>
          <span class="mode-label">{{ modeLabel }}</span>
          <span class="mode-arrow">▼</span>
        </div>
        <!-- 模式切换下拉 -->
        <div v-if="showModeMenu" class="mode-menu" @click.stop>
          <div class="mode-option"
               :class="{ active: currentMode === 'tree-sitter' }"
               @click="switchToTreeSitter">
            <div class="mode-option-header">
              <strong>🌳 Tree-sitter</strong>
              <span v-if="currentMode === 'tree-sitter'" class="mode-check">✓</span>
            </div>
            <p class="mode-desc">轻量模式，零外部依赖，即时可用</p>
          </div>
          <div class="mode-option"
               :class="{ active: currentMode === 'scip' }"
               @click="switchToScip">
            <div class="mode-option-header">
              <strong>⚡ SCIP</strong>
              <span v-if="currentMode === 'scip'" class="mode-check">✓</span>
            </div>
            <p class="mode-desc">性能模式，精确符号索引，首次需生成索引</p>
          </div>
        </div>
      </div>
      <nav class="sidebar-nav">
        <router-link to="/chat" class="nav-item" active-class="active">
          <span class="nav-icon">💬</span>
          <span class="nav-label">对话</span>
        </router-link>
        <router-link to="/projects" class="nav-item" active-class="active">
          <span class="nav-icon">📁</span>
          <span class="nav-label">项目</span>
        </router-link>
        <router-link to="/import" class="nav-item" active-class="active">
          <span class="nav-icon">📥</span>
          <span class="nav-label">导入</span>
        </router-link>
      </nav>
    </aside>
    <main class="main-content">
      <router-view />
    </main>

    <!-- SCIP 模式切换阻塞弹窗 -->
    <ScipModal v-if="showScipModal" :project-dir="selectedProjectDir" @close="onScipModalClose" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getCodeIndexMode, getProjects } from './api/index.js'
import ScipModal from './components/ScipModal.vue'

const currentMode = ref('tree-sitter')
const showModeMenu = ref(false)
const showScipModal = ref(false)
const selectedProjectDir = ref('')

const modeLabel = computed(() => {
  if (currentMode.value === 'scip') return 'SCIP'
  return 'Tree-sitter'
})

onMounted(async () => {
  try {
    const resp = await getCodeIndexMode()
    currentMode.value = resp.mode
  } catch (err) {
    // 默认 tree-sitter
  }
  // 点击外部关闭菜单
  document.addEventListener('click', () => { showModeMenu.value = false })
})

async function switchToTreeSitter() {
  showModeMenu.value = false
  if (currentMode.value === 'tree-sitter') return
  try {
    // 先弹 SCIP 窗口走关闭流程，再切回
    await fetch('/api/codeindex/mode', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: 'tree-sitter' })
    })
    currentMode.value = 'tree-sitter'
  } catch (err) {
    console.error('切换失败', err)
  }
}

async function switchToScip() {
  showModeMenu.value = false
  if (currentMode.value === 'scip') return

  // 获取第一个项目的目录（或者让用户选择）
  try {
    const projects = await getProjects()
    if (projects && projects.length > 0) {
      // 用第一个项目的目录作为 SCIP 索引目标
      selectedProjectDir.value = projects[0].repoPath || projects[0].dir || ''
    }
  } catch (err) {
    // 没有项目也可以生成，用空目录会提示
    selectedProjectDir.value = ''
  }

  // 打开阻塞弹窗
  showScipModal.value = true
}

function onScipModalClose() {
  showScipModal.value = false
  // 刷新当前模式
  getCodeIndexMode().then(r => { currentMode.value = r.mode }).catch(() => {})
}
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  background: #f4f3ee;
}

.sidebar {
  width: 220px;
  background: #1f1e1c;
  color: #e8e3d8;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid #3a3834;
  position: relative;
}

.sidebar-header h2 {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 600;
  color: #c15f3c;
}

/* 模式徽章 */
.mode-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid #3a3834;
  user-select: none;
}

.mode-badge:hover { background: #2a2825; }
.mode-badge.tree-sitter { border-color: #4a7a5a; }
.mode-badge.scip { border-color: #c15f3c; }

.mode-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.mode-badge.tree-sitter .mode-dot { background: #57ab5a; }
.mode-badge.scip .mode-dot { background: #c15f3c; }

.mode-label { color: #b4aea0; flex: 1; }
.mode-arrow { color: #5a5548; font-size: 10px; }

/* 下拉菜单 */
.mode-menu {
  position: absolute;
  top: 100%;
  left: 20px;
  right: 20px;
  margin-top: 4px;
  background: #2a2825;
  border-radius: 8px;
  border: 1px solid #3a3834;
  z-index: 100;
  overflow: hidden;
  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
}

.mode-option {
  padding: 12px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid #3a3834;
}

.mode-option:last-child { border-bottom: none; }
.mode-option:hover { background: #363430; }
.mode-option.active { background: #363430; }

.mode-option-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.mode-option-header strong { font-size: 13px; color: #e8e3d8; }
.mode-check { color: #57ab5a; font-size: 14px; }
.mode-desc { margin: 0; font-size: 11px; color: #908979; }

.sidebar-nav {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  color: #908979;
  text-decoration: none;
  font-size: 14px;
  transition: all 0.15s;
}

.nav-item:hover { background: #2a2825; color: #e8e3d8; }
.nav-item.active { background: #c15f3c; color: #fff; }
.nav-icon { font-size: 16px; }

.main-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>

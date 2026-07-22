<template>
  <div class="app-shell">
    <!-- Sourcegraph 式顶栏 -->
    <header class="topbar">
      <div class="topbar-left">
        <router-link to="/chat" class="logo">
          <span class="logo-mark">DK</span>
          <span class="logo-text">DevKnow</span>
        </router-link>
        <nav class="top-nav">
          <router-link to="/chat" class="nav-link" active-class="active">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
            搜索
          </router-link>
          <router-link to="/discover" class="nav-link" active-class="active">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><circle cx="12" cy="8" r="1"/></svg>
            发现
          </router-link>
          <router-link to="/learn" class="nav-link" active-class="active">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
            研读
          </router-link>
          <router-link to="/projects" class="nav-link" active-class="active">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
            项目
          </router-link>
          <router-link to="/import" class="nav-link" active-class="active">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            导入
          </router-link>
        </nav>
      </div>
      <div class="topbar-right">
        <!-- 项目选择器 -->
        <select v-if="projectStore.projects.length > 0" v-model="selectedProjectId" class="project-selector" @change="onProjectChange">
          <option value="">所有项目</option>
          <option v-for="p in projectStore.projects" :key="p.id" :value="p.id">
            {{ p.displayName || p.name }}
          </option>
        </select>
        <div class="mode-selector" @click="showModeMenu = !showModeMenu">
          <span class="mode-indicator" :class="currentMode"></span>
          <span class="mode-name">{{ modeLabel }}</span>
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="m6 9 6 6 6-6"/></svg>
        </div>
        <div v-if="showModeMenu" class="mode-dropdown" @click.stop>
          <div class="mode-item" :class="{ active: currentMode === 'tree-sitter' }" @click="switchToTreeSitter">
            <div class="mode-item-icon">🌳</div>
            <div>
              <div class="mode-item-title">Tree-sitter</div>
              <div class="mode-item-desc">轻量模式，零外部依赖</div>
            </div>
            <span v-if="currentMode === 'tree-sitter'" class="mode-check">✓</span>
          </div>
          <div class="mode-item" :class="{ active: currentMode === 'scip' }" @click="switchToScip">
            <div class="mode-item-icon">⚡</div>
            <div>
              <div class="mode-item-title">SCIP</div>
              <div class="mode-item-desc">性能模式，精确符号索引</div>
            </div>
            <span v-if="currentMode === 'scip'" class="mode-check">✓</span>
          </div>
        </div>
        <div class="auth-area">
          <template v-if="authStore.isLoggedIn">
            <span class="auth-username">{{ authStore.username }}</span>
            <button class="auth-btn" @click="authStore.logout()">登出</button>
          </template>
          <button v-else class="auth-btn" @click="showAuthDialog = true">登录</button>
        </div>
      </div>
    </header>

    <main class="main-area">
      <router-view />
    </main>

    <ScipModal v-if="showScipModal" :project-dir="selectedProjectDir" @close="onScipModalClose" />
    <AuthDialog v-if="showAuthDialog" @close="showAuthDialog = false" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCodeIndexMode, getProjects } from './api/index.js'
import { useProjectStore } from './stores/useProjectStore.js'
import { useAuthStore } from './stores/useAuthStore.js'
import ScipModal from './components/ScipModal.vue'
import AuthDialog from './components/AuthDialog.vue'

const router = useRouter()
const projectStore = useProjectStore()
const authStore = useAuthStore()

const currentMode = ref('tree-sitter')
const showModeMenu = ref(false)
const showScipModal = ref(false)
const selectedProjectDir = ref('')
const selectedProjectId = ref('')
const showAuthDialog = ref(false)

const modeLabel = computed(() => {
  if (currentMode.value === 'scip') return 'SCIP'
  return 'Tree-sitter'
})

/** 监听 auth:expired 事件（由 api/index.js 在 401 时触发）*/
function onAuthExpired() {
  authStore.logout()
  showAuthDialog.value = true
}

onMounted(async () => {
  authStore.initFromStorage()
  // 无 token 时弹出登录框
  if (!authStore.isLoggedIn) {
    showAuthDialog.value = true
  }
  try {
    const resp = await getCodeIndexMode()
    currentMode.value = resp.mode
  } catch {}
  try {
    await projectStore.ensureLoaded()
  } catch {}
  document.addEventListener('click', () => { showModeMenu.value = false })
  window.addEventListener('auth:expired', onAuthExpired)
})

onUnmounted(() => {
  window.removeEventListener('auth:expired', onAuthExpired)
})

function onProjectChange() {
  if (!selectedProjectId.value) {
    router.push('/chat')
    return
  }
  router.push(`/chat/${selectedProjectId.value}`)
}

async function switchToTreeSitter() {
  showModeMenu.value = false
  if (currentMode.value === 'tree-sitter') return
  try {
    await fetch('/api/codeindex/mode', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: 'tree-sitter' })
    })
    currentMode.value = 'tree-sitter'
  } catch (err) { console.error('切换失败', err) }
}

async function switchToScip() {
  showModeMenu.value = false
  if (currentMode.value === 'scip') return
  try {
    const projects = await getProjects()
    if (projects && projects.length > 0) {
      selectedProjectDir.value = projects[0].repoPath || projects[0].dir || ''
    }
  } catch {}
  showScipModal.value = true
}

function onScipModalClose() {
  showScipModal.value = false
  getCodeIndexMode().then(r => { currentMode.value = r.mode }).catch(() => {})
}
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #faf9f5;
}

/* ── Top Bar ── */
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #e8e3d8;
  flex-shrink: 0;
  position: relative;
  z-index: 100;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 24px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  color: #2d2a24;
}

.logo-mark {
  width: 28px;
  height: 28px;
  background: #c15f3c;
  color: #fff;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: -0.5px;
}

.logo-text {
  font-weight: 600;
  font-size: 16px;
  color: #2d2a24;
}

.top-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #5a5548;
  text-decoration: none;
  transition: all 0.12s;
}
.nav-link:hover {
  background: #f5ede8;
  color: #c15f3c;
}
.nav-link.active {
  background: #f5ede8;
  color: #c15f3c;
}

/* ── Topbar Right ── */
.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  position: relative;
}

.project-selector {
  padding: 5px 10px;
  border: 1px solid #e8e3d8;
  border-radius: 6px;
  font-size: 12px;
  background: #fff;
  color: #5a5548;
  cursor: pointer;
  max-width: 160px;
}
.project-selector:focus { border-color: #c15f3c; outline: none; }

/*
 * ── Mode Selector ──
 * (keep existing mode-selector block)
 */
.mode-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid #e8e3d8;
  color: #5a5548;
  transition: all 0.12s;
  user-select: none;
}
.mode-selector:hover { border-color: #d4cfc2; background: #f8f6f0; }

/* ── Auth Area ── */
.auth-area {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  padding-left: 12px;
  border-left: 1px solid #e8e3d8;
}

.auth-username {
  font-size: 13px;
  font-weight: 500;
  color: #5a5548;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.auth-btn {
  padding: 4px 10px;
  border: 1px solid #e8e3d8;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #5a5548;
  background: #fff;
  cursor: pointer;
  transition: all 0.12s;
}
.auth-btn:hover { border-color: #c15f3c; color: #c15f3c; }

.mode-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.mode-indicator.tree-sitter { background: #57ab5a; }
.mode-indicator.scip { background: #c15f3c; }

.mode-name { font-weight: 500; }

/* ── Mode Dropdown ── */
.mode-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  width: 260px;
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  box-shadow: 0 8px 30px rgba(0,0,0,0.1);
  overflow: hidden;
  z-index: 200;
}

.mode-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: background 0.12s;
  border-bottom: 1px solid #f0ece4;
}
.mode-item:last-child { border-bottom: none; }
.mode-item:hover { background: #f8f6f0; }
.mode-item.active { background: #f5ede8; }

.mode-item-icon { font-size: 18px; flex-shrink: 0; }
.mode-item-title { font-size: 13px; font-weight: 600; color: #2d2a24; }
.mode-item-desc { font-size: 11px; color: #8a857a; margin-top: 1px; }
.mode-check { margin-left: auto; color: #57ab5a; font-weight: 700; }

/* ── Main ── */
.main-area {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>

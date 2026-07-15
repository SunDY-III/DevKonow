<template>
  <div class="app-layout">
    <aside class="sidebar">
      <div class="sidebar-header">
        <h2>DevKnow</h2>
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
  </div>
</template>

<script setup>
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
}
.sidebar-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #c15f3c;
}
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
.nav-item:hover {
  background: #2a2825;
  color: #e8e3d8;
}
.nav-item.active {
  background: #c15f3c;
  color: #fff;
}
.nav-icon { font-size: 16px; }
.main-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>

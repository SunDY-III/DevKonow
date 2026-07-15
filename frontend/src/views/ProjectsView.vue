<template>
  <div class="projects-view">
    <header class="page-header">
      <h3>项目列表</h3>
      <router-link to="/import" class="btn-new">+ 导入项目</router-link>
    </header>

    <div v-if="loading" class="loading">加载中...</div>

    <div v-else-if="projects.length === 0" class="empty">
      还没有项目，<router-link to="/import">导入第一个项目</router-link>
    </div>

    <div v-else class="project-grid">
      <div v-for="p in projects" :key="p.id" class="project-card" @click="selectProject(p)">
        <div class="card-header">
          <h4>{{ p.displayName || p.name }}</h4>
          <span class="lang-badge">{{ p.language }}</span>
        </div>
        <div class="card-body">
          <p v-if="p.framework" class="framework">{{ p.framework }}</p>
          <div class="stats">
            <span class="stat">{{ p.totalFiles || 0 }} 文件</span>
            <span class="stat">{{ p.totalMethods || 0 }} 方法</span>
          </div>
        </div>
        <div class="card-footer">
          <span class="status" :class="p.status">{{ p.status }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getProjects } from '../api/index.js'

const router = useRouter()
const projects = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    const res = await getProjects()
    projects.value = res.data || []
  } catch (e) {
    console.error('加载项目列表失败', e)
  } finally {
    loading.value = false
  }
})

function selectProject(p) {
  router.push(`/chat/${p.id}`)
}
</script>

<style scoped>
.projects-view { padding: 32px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-header h3 { margin: 0; font-size: 20px; color: #1f1e1c; }
.btn-new {
  padding: 8px 16px; background: #c15f3c; color: #fff;
  text-decoration: none; border-radius: 8px; font-size: 14px;
}
.loading, .empty { text-align: center; color: #908979; padding: 64px 0; font-size: 14px; }
.empty a { color: #c15f3c; }
.project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
.project-card {
  background: #fff; border-radius: 12px; padding: 20px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06); cursor: pointer;
  transition: box-shadow 0.15s;
}
.project-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
.card-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.card-header h4 { margin: 0; font-size: 16px; color: #1f1e1c; }
.lang-badge { font-size: 11px; padding: 2px 8px; background: #f1e3da; color: #a94d2d; border-radius: 4px; }
.framework { font-size: 13px; color: #56524a; margin: 0 0 8px; }
.stats { display: flex; gap: 16px; }
.stat { font-size: 12px; color: #908979; }
.card-footer { margin-top: 12px; padding-top: 12px; border-top: 1px solid #f4f3ee; }
.status { font-size: 11px; padding: 2px 8px; border-radius: 4px; }
.status.ACTIVE { background: #e8f5e9; color: #2e7d32; }
</style>

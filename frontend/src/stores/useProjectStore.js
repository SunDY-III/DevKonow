import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getProjects, getProjectDetail } from '../api/index.js'

/**
 * 全局项目上下文 Store。
 *
 * 在多个视图间共享当前项目状态。
 * 刷新后数据从后端重新加载。
 */
export const useProjectStore = defineStore('project', () => {
  // 项目列表
  const projects = ref([])
  // 当前项目 ID
  const currentProjectId = ref(null)
  // 加载状态
  const loaded = ref(false)
  const loading = ref(false)

  // 当前项目详情
  const currentProject = computed(() => {
    if (!currentProjectId.value) return null
    return projects.value.find(p => p.id === currentProjectId.value) || null
  })

  const currentProjectName = computed(() => {
    return currentProject.value?.displayName || currentProject.value?.name || ''
  })

  async function loadProjects() {
    if (loading.value) return
    loading.value = true
    try {
      const data = await getProjects()
      projects.value = Array.isArray(data) ? data : (data.data || [])
      loaded.value = true
    } catch (err) {
      console.error('加载项目列表失败:', err)
    } finally {
      loading.value = false
    }
  }

  function setCurrentProject(id) {
    currentProjectId.value = id
  }

  async function ensureLoaded() {
    if (!loaded.value) {
      await loadProjects()
    }
  }

  return {
    projects,
    currentProjectId,
    currentProject,
    currentProjectName,
    loaded,
    loading,
    loadProjects,
    setCurrentProject,
    ensureLoaded
  }
})

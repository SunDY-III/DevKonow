import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getStudyOverview, getStudyArchitecture, getStudyPatterns, getStudyHighlights, getStudyRoadmap } from '../api/index.js'

/**
 * 学习进度共享 Store。
 *
 * 缓存项目的研读结果，避免重复请求。
 * 刷新后数据从后端重新加载。
 */
export const useStudyStore = defineStore('study', () => {
  // 按 projectId 缓存研读结果
  const overviews = ref({})
  const architectures = ref({})
  const patterns = ref({})
  const highlights = ref({})
  const loading = ref(false)

  async function loadOverview(projectId) {
    if (overviews.value[projectId]) return overviews.value[projectId]
    loading.value = true
    try {
      const res = await getStudyOverview(projectId)
      const data = res.data || res
      overviews.value[projectId] = data
      return data
    } catch (err) {
      console.error('加载研读概览失败:', err)
      return null
    } finally {
      loading.value = false
    }
  }

  async function loadArchitecture(projectId) {
    if (architectures.value[projectId]) return architectures.value[projectId]
    loading.value = true
    try {
      const res = await getStudyArchitecture(projectId)
      const data = res.data || res
      architectures.value[projectId] = data
      return data
    } catch (err) {
      console.error('加载架构分析失败:', err)
      return null
    } finally {
      loading.value = false
    }
  }

  async function loadPatterns(projectId) {
    if (patterns.value[projectId]) return patterns.value[projectId]
    loading.value = true
    try {
      const res = await getStudyPatterns(projectId)
      const data = res.data || res
      patterns.value[projectId] = data
      return data
    } catch (err) {
      console.error('加载模式检测失败:', err)
      return null
    } finally {
      loading.value = false
    }
  }

  async function loadHighlights(projectId) {
    if (highlights.value[projectId]) return highlights.value[projectId]
    loading.value = true
    try {
      const res = await getStudyHighlights(projectId)
      const data = res.data || res
      highlights.value[projectId] = data
      return data
    } catch (err) {
      console.error('加载高亮失败:', err)
      return null
    } finally {
      loading.value = false
    }
  }

  function clearCache(projectId) {
    if (projectId) {
      delete overviews.value[projectId]
      delete architectures.value[projectId]
      delete patterns.value[projectId]
      delete highlights.value[projectId]
    } else {
      overviews.value = {}
      architectures.value = {}
      patterns.value = {}
      highlights.value = {}
    }
  }

  return {
    overviews,
    architectures,
    patterns,
    highlights,
    loading,
    loadOverview,
    loadArchitecture,
    loadPatterns,
    loadHighlights,
    clearCache
  }
})

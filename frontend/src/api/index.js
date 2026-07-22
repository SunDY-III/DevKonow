const API_BASE = '/api'

/** 从 localStorage 获取 JWT token */
function getToken() {
  return localStorage.getItem('auth_token') || ''
}

/** 统一请求函数：自动注入 Authorization header、401 自动登出 */
async function request(url, options = {}) {
  const token = getToken()
  const headers = {
    'Content-Type': 'application/json',
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    ...options.headers
  }
  const res = await fetch(API_BASE + url, { headers, ...options })
  if (res.status === 401) {
    localStorage.removeItem('auth_token')
    window.dispatchEvent(new CustomEvent('auth:expired'))
    const err = await res.json().catch(() => ({ message: '登录已过期，请重新登录' }))
    throw new Error(err.message || '登录已过期，请重新登录')
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(err.message || `HTTP ${res.status}`)
  }
  return res.json()
}

/** 创建带 Auth Token 的 EventSource（SSE 不支持自定义 Header，通过 query 参数传递） */
function createAuthEventSource(path, params = {}) {
  const token = getToken()
  if (token) params.set('token', token)
  return new EventSource(`${API_BASE}${path}?${params}`)
}

// ================== 项目管理 ==================

export { createAuthEventSource, getToken, request }

export function getProjects() {
  return request('/project/list')
}

export function getProjectSummary(id) {
  return request(`/project/${id}/summary`)
}

export function deleteProject(id) {
  return request(`/project/${id}`, { method: 'DELETE' })
}

export function verifyRepo(repoUrl, gitToken) {
  const params = new URLSearchParams({ repoUrl })
  if (gitToken) params.set('gitToken', gitToken)
  return request(`/project/verify?${params}`)
}

export function createImportSSE(repoUrl, force, gitToken) {
  const params = new URLSearchParams({ repoUrl, force: String(force) })
  if (gitToken) params.set('gitToken', gitToken)
  return createAuthEventSource('/project/import', params)
}

export function reindexProject(id) {
  return fetch(`${API_BASE}/project/${id}/reindex`, { method: 'POST' })
}

export function checkNewCommits(id) {
  return request(`/project/${id}/reindex/check`)
}

// ================== 代码索引模式管理 ==================

export function getCodeIndexMode() {
  return request('/codeindex/mode')
}

export function switchCodeIndexMode(mode, projectDir) {
  return request('/codeindex/mode', {
    method: 'PUT',
    body: JSON.stringify({ mode, projectDir })
  })
}

export function subscribeCodeIndexProgress() {
  const params = new URLSearchParams()
  return createAuthEventSource('/codeindex/mode/progress', params)
}

// ================== 护航学习 (Mentor) ==================

export function getMentorPlan(projectId) {
  return request(`/mentor/${projectId}/plan`, { method: 'POST' })
}

export function getMentorAchievements(projectId) {
  return request(`/mentor/${projectId}/achievements`)
}

// ================== 发现推荐 (Discover) ==================

export function discoverProjects(query) {
  return request('/discover/search', {
    method: 'POST',
    body: JSON.stringify({ query })
  })
}

export function createLearningImportSSE(repoUrl) {
  const params = new URLSearchParams({ repoUrl })
  return createAuthEventSource('/discover/import', params)
}

// ================== Feynman 检验 ==================
export { createFeynmanSSE, submitFeynmanAnswer, skipFeynman } from './feynman.js'

// ================== 研读（知识提取 + 面试 + 代码评分） ==================
export {
  createExtractSSE, getKnowledgePoints, getSkillTree, getStudyStats,
  generateInterviewQuestions, generateFollowUp, generateFeedback,
  scoreCodeQuality, getProgressOverview, reviewCodeRange
} from './study.js'

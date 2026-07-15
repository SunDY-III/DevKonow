const API_BASE = '/api'

async function request(url, options = {}) {
  const res = await fetch(API_BASE + url, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(err.message || `HTTP ${res.status}`)
  }
  return res.json()
}

export function getProjects() {
  return request('/project/list')
}

export function getProjectSummary(id) {
  return request(`/project/${id}/summary`)
}

export function deleteProject(id) {
  return request(`/project/${id}`, { method: 'DELETE' })
}

export function verifyRepo(repoUrl, token) {
  const params = new URLSearchParams({ repoUrl })
  if (token) params.set('token', token)
  return request(`/project/verify?${params}`)
}

export function createImportSSE(repoUrl, force, token) {
  const params = new URLSearchParams({ repoUrl, force })
  if (token) params.set('token', token)
  return new EventSource(`${API_BASE}/project/import?${params}`)
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
  return new EventSource('/api/codeindex/mode/progress')
}

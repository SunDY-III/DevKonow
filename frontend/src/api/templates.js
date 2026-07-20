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

/**
 * 模板与脚手架 API。
 */

/** 获取模板列表 */
export function listTemplates() {
  return request('/templates/list')
}

/** 获取模板详情 */
export function getTemplate(id) {
  return request(`/templates/${id}`)
}

/**
 * 生成脚手架（返回 SSE）。
 * 使用 EventSource 无法 POST，因此用 fetch + ReadableStream。
 */
export function generateScaffold(templateId, variables, projectName) {
  return fetch(`${API_BASE}/scaffold/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ templateId, variables, projectName })
  })
}

import { request, getToken } from './index.js'

const API_BASE = '/api'

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
  const token = getToken()
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  return fetch(`${API_BASE}/scaffold/generate`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ templateId, variables, projectName })
  })
}

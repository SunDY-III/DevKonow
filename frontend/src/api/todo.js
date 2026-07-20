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
 * TODO CRUD API。
 * 路径: /api/study/{projectId}/todo
 */

export function listTodos(projectId) {
  return request(`/study/${projectId}/todo`)
}

export function createTodo(projectId, item) {
  return request(`/study/${projectId}/todo`, {
    method: 'POST',
    body: JSON.stringify(item)
  })
}

export function updateTodo(projectId, todoId, update) {
  return request(`/study/${projectId}/todo/${todoId}`, {
    method: 'PUT',
    body: JSON.stringify(update)
  })
}

export function deleteTodo(projectId, todoId) {
  return request(`/study/${projectId}/todo/${todoId}`, {
    method: 'DELETE'
  })
}

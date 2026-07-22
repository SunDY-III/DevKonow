import { request } from './index.js'

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

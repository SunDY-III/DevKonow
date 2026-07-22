import { request } from './index.js'

/**
 * 管理后台 API。
 * 路径: /api/admin/prompts
 */

/** 获取 prompt 模板列表 */
export function listPrompts() {
  return request('/admin/prompts')
}

/** 获取单个 prompt 模板 */
export function getPrompt(id) {
  return request(`/admin/prompts/${id}`)
}

/** 更新 prompt 模板（携带版本号用于并发控制） */
export function updatePrompt(id, data) {
  return request(`/admin/prompts/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

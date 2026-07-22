import { request, getToken } from './index.js'

const API_BASE = '/api'

// 知识提取（fetch 直接调用，非 request 封装）
export function createExtractSSE(question, answer, codeContext, projectId) {
  const token = getToken()
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  return fetch(`${API_BASE}/study/extract`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ question, answer, codeContext, projectId })
  })
}

export function getKnowledgePoints(projectId) {
  return request(`/study/${projectId}/knowledge-points`)
}

export function getSkillTree(projectId) {
  return request(`/study/${projectId}/skill-tree`)
}

export function getStudyStats(projectId) {
  return request(`/study/${projectId}/stats`)
}

// 面试演练
export function generateInterviewQuestions(projectId, projectName, architecture, patterns, style) {
  return request('/study/interview/questions', {
    method: 'POST',
    body: JSON.stringify({ projectId, projectName, architecture, patterns, style })
  })
}

export function generateFollowUp(question, userAnswer, expectedAnswer, depth, style) {
  return request('/study/interview/follow-up', {
    method: 'POST',
    body: JSON.stringify({ question, userAnswer, expectedAnswer, depth, style })
  })
}

export function generateFeedback(question, userAnswer, expectedAnswer, followUpQAs) {
  return request('/study/interview/feedback', {
    method: 'POST',
    body: JSON.stringify({ question, userAnswer, expectedAnswer, followUpQAs })
  })
}

// 代码质量评分
export function scoreCodeQuality(code, language, context) {
  return request('/study/quality/score', {
    method: 'POST',
    body: JSON.stringify({ code, language, context })
  })
}

// 学习概览
export function getProgressOverview() {
  return request('/study/progress-overview')
}

// 安全审查
export function reviewCodeRange(projectId, filePath, startLine, endLine) {
  return request('/study/safety/review-range', {
    method: 'POST',
    body: JSON.stringify({ projectId, filePath, startLine, endLine })
  })
}

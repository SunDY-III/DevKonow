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

// 知识提取
export function createExtractSSE(question, answer, codeContext, projectId) {
  return fetch(`${API_BASE}/study/extract`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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

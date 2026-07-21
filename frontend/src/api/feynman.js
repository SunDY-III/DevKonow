const API_BASE = '/api'

export function createFeynmanSSE(conversationId, question, answer) {
  return new EventSource(
    `${API_BASE}/feynman/session?conversationId=${encodeURIComponent(conversationId)}&question=${encodeURIComponent(question)}&answer=${encodeURIComponent(answer)}`
  )
}

export async function submitFeynmanAnswer(conversationId, answer) {
  const res = await fetch(`${API_BASE}/feynman/answer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, answer })
  })
  return res.json()
}

export async function skipFeynman(conversationId) {
  const res = await fetch(`${API_BASE}/feynman/skip`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId })
  })
  return res.json()
}

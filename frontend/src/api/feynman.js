import { request, getToken } from './index.js'

const API_BASE = '/api'

export function createFeynmanSSE(conversationId, question, answer) {
  const token = getToken()
  let url = `${API_BASE}/feynman/session?conversationId=${encodeURIComponent(conversationId)}&question=${encodeURIComponent(question)}&answer=${encodeURIComponent(answer)}`
  if (token) url += `&token=${encodeURIComponent(token)}`
  return new EventSource(url)
}

export async function submitFeynmanAnswer(conversationId, answer) {
  const token = getToken()
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${API_BASE}/feynman/answer`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ conversationId, answer })
  })
  return res.json()
}

export async function skipFeynman(conversationId) {
  const token = getToken()
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${API_BASE}/feynman/skip`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ conversationId })
  })
  return res.json()
}

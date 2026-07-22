import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const userId = ref('')
  const username = ref('')
  const role = ref('')

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  /** 从 localStorage 恢复 token */
  function initFromStorage() {
    token.value = localStorage.getItem('auth_token') || ''
    userId.value = localStorage.getItem('auth_userId') || ''
    username.value = localStorage.getItem('auth_username') || ''
    role.value = localStorage.getItem('auth_role') || ''
  }

  /** 登录成功后保存认证信息 */
  function saveAuth(data) {
    token.value = data.token
    userId.value = String(data.userId)
    username.value = data.username
    role.value = data.role || 'USER'
    localStorage.setItem('auth_token', data.token)
    localStorage.setItem('auth_userId', String(data.userId))
    localStorage.setItem('auth_username', data.username)
    localStorage.setItem('auth_role', data.role || 'USER')
  }

  /** 登录：POST /api/auth/login */
  async function login(credentials) {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials)
    })
    const data = await res.json()
    if (data.code !== 0) throw new Error(data.message || '登录失败')
    // 登录返回 token，再请求 profile 获取 role
    await saveAuth(data.data)
    // 获取 profile 确认 role
    try {
      const profileRes = await fetch('/api/auth/profile', {
        headers: { 'Authorization': `Bearer ${token.value}` }
      })
      const profileData = await profileRes.json()
      if (profileData.code === 0 && profileData.data) {
        role.value = profileData.data.role || 'USER'
        localStorage.setItem('auth_role', role.value)
      }
    } catch {}
  }

  /** 注册：POST /api/auth/register */
  async function register(credentials) {
    const res = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials)
    })
    const data = await res.json()
    if (data.code !== 0) throw new Error(data.message || '注册失败')
    // 注册后自动登录
    await saveAuth(data.data)
  }

  /** 登出：清除所有状态 */
  function logout() {
    token.value = ''
    userId.value = ''
    username.value = ''
    role.value = ''
    localStorage.removeItem('auth_token')
    localStorage.removeItem('auth_userId')
    localStorage.removeItem('auth_username')
    localStorage.removeItem('auth_role')
  }

  return {
    token, userId, username, role,
    isLoggedIn, isAdmin,
    initFromStorage, login, register, logout
  }
})

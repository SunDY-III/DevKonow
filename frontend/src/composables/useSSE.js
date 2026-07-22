import { ref, onUnmounted } from 'vue'

/**
 * 公共 SSE composable。
 *
 * 统一管理 EventSource 生命周期。支持：
 * - 自动附加 JWT token（query 参数，EventSource 不支持自定义 Header）
 * - 自定义 URL 查询参数
 * - 指数退避断线重连（最多 3 次）
 * - 标准事件分发和生命周期管理
 *
 * @param {string} baseUrl SSE URL 路径（不含 query）
 * @param {Object} handlers 事件处理器 { eventName: (data) => void }
 * @param {Object} options
 * @param {Object} options.params - 额外 URL 查询参数 key-value
 * @param {boolean} options.autoReconnect - 是否启用断线重连（默认 true）
 * @returns {Object} { loading, error, connect, close, reconnect }
 */
export function useSSE(baseUrl, handlers = {}, options = {}) {
  const loading = ref(false)
  const error = ref('')
  let eventSource = null
  let retryCount = 0
  const maxRetries = 3
  let reconnectTimer = null

  // ==================== URL 构建 ====================

  function buildUrl() {
    const params = new URLSearchParams()
    // JWT token
    const token = localStorage.getItem('auth_token') || ''
    if (token) params.set('token', token)
    // 自定义参数
    if (options.params) {
      for (const [k, v] of Object.entries(options.params)) {
        if (v != null) params.set(k, v)
      }
    }
    const qs = params.toString()
    return qs ? `${baseUrl}?${qs}` : baseUrl
  }

  // ==================== 连接管理 ====================

  function connect() {
    cleanup()

    loading.value = true
    error.value = ''

    eventSource = new EventSource(buildUrl())

    // 标准事件绑定（Chat SSE 使用的全部事件）
    const standardEvents = [
      'phase', 'chunk', 'done', 'error', 'cache',
      'route', 'step', 'token', 'source', 'corrected',
      'agent', 'project', 'progress'
    ]

    for (const evt of standardEvents) {
      eventSource.addEventListener(evt, (e) => {
        const handler = handlers[evt]
        if (handler) handler(e.data, e)
      })
    }

    if (handlers.onmessage) {
      eventSource.onmessage = (e) => handlers.onmessage(e.data, e)
    }

    eventSource.onerror = () => {
      loading.value = false
      eventSource = null

      if (options.autoReconnect !== false) {
        scheduleReconnect()
      } else {
        error.value = 'SSE 连接断开'
        if (handlers.onerror) handlers.onerror()
      }
    }
  }

  // ==================== 断线重连 ====================

  function scheduleReconnect() {
    if (retryCount >= maxRetries) {
      error.value = '连接已断开，请检查网络后重试'
      if (handlers.onerror) handlers.onerror()
      return
    }
    retryCount++
    const delay = Math.min(30000, 1000 * Math.pow(2, retryCount - 1))
    reconnectTimer = setTimeout(() => connect(), delay)
  }

  // ==================== 清理 ====================

  function cleanup() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function close() {
    cleanup()
    retryCount = 0
    loading.value = false
    error.value = ''
  }

  function reconnect() {
    retryCount = 0
    connect()
  }

  onUnmounted(() => close())

  return { loading, error, connect, close, reconnect }
}

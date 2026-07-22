import { ref, onUnmounted } from 'vue'

/**
 * 公共 SSE composable。
 *
 * 统一管理 EventSource 生命周期，支持自动附加 JWT token。
 *
 * @param {string} url SSE URL
 * @param {Object} handlers 事件处理器
 * @param {Object} options 可选项，{ token: 'xxx' } 附加 JWT
 * @returns {Object} { loading, error, connect, close }
 */
export function useSSE(url, handlers = {}, options = {}) {
  const loading = ref(false)
  const error = ref('')
  let eventSource = null

  /** 如果提供了 token，自动追加到 URL */
  function buildAuthUrl(baseUrl) {
    if (!options.token) return baseUrl
    const separator = baseUrl.includes('?') ? '&' : '?'
    return `${baseUrl}${separator}token=${encodeURIComponent(options.token)}`
  }

  function connect() {
    if (eventSource) {
      eventSource.close()
    }
    loading.value = true
    error.value = ''

    const authUrl = buildAuthUrl(url)
    eventSource = new EventSource(authUrl)

    // 标准事件绑定
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

    eventSource.onerror = () => {
      loading.value = false
      error.value = 'SSE 连接断开'
      if (handlers.onerror) handlers.onerror()
    }

    // 通用 message 处理
    if (handlers.onmessage) {
      eventSource.onmessage = (e) => handlers.onmessage(e.data, e)
    }
  }

  function close() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    loading.value = false
  }

  onUnmounted(() => close())

  return { loading, error, connect, close }
}

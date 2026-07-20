import { ref, onUnmounted } from 'vue'

/**
 * 公共 SSE composable。
 *
 * 统一管理 EventSource 生命周期，按标准事件名分发：
 * - phase: 当前阶段（如 code:method, doc:architecture）
 * - chunk: 数据块/项目上下文
 * - done: 完成信号
 * - error: 错误信号
 * - cache: 缓存命中标识
 * - route: 路由信息
 * - step: Agent 步骤进度
 * - token: 流式 token
 * - source: 来源引用
 * - corrected: 修正后文本
 * - agent: Agent 回复
 * - project: 项目事件
 * - progress: 进度事件
 *
 * @param {string} url SSE URL
 * @param {Object} handlers 事件处理器
 * @returns {Object} { loading, error, close }
 */
export function useSSE(url, handlers = {}) {
  const loading = ref(false)
  const error = ref('')
  let eventSource = null

  function connect() {
    if (eventSource) {
      eventSource.close()
    }
    loading.value = true
    error.value = ''

    eventSource = new EventSource(url)

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

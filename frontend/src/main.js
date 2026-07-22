import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/style.css'

const app = createApp(App)

// 全局错误边界：捕获组件渲染错误和未处理的异步异常
app.config.errorHandler = (err, vm, info) => {
  console.error('[全局错误]', err, info)
  // 可以将错误上报到后端日志
  // fetch('/api/log/error', { method: 'POST', body: JSON.stringify({ msg: err.message, stack: err.stack }) })
}

// 全局未处理 Promise 拒绝
window.addEventListener('unhandledrejection', (e) => {
  console.error('[未处理的 Promise 拒绝]', e.reason)
  e.preventDefault()
})

app.use(createPinia())
app.use(router)
app.mount('#app')

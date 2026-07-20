<template>
  <div class="arch-map" :class="{ loading: !contentReady }">
    <Transition name="fade" mode="out-in">
      <!-- 骨架屏 -->
      <div v-if="!contentReady" class="skeleton" key="skeleton">
        <div class="skeleton-header">
          <div class="skeleton-block" style="width: 40%; height: 18px;"></div>
          <div class="skeleton-block" style="width: 80px; height: 20px; border-radius: 10px;"></div>
        </div>
        <div class="skeleton-body">
          <div class="skeleton-block" style="width: 100%; height: 160px;"></div>
        </div>
      </div>

      <!-- Mermaid / 文本降级 -->
      <div v-else class="mermaid-wrapper" key="content">
        <div class="diagram-header">
          <h3 class="diagram-title">{{ title }}</h3>
          <span class="diagram-badge" v-if="modules">模块 {{ modules }} 个</span>
        </div>
        <div ref="mermaidRef" class="mermaid-content">
          {{ diagramData }}
        </div>
        <!-- 文本模式降级 -->
        <div v-if="!mermaidReady" class="text-fallback">
          <pre class="diagram-text">{{ diagramData }}</pre>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed } from 'vue'

const props = defineProps({
  diagramData: { type: String, default: '' },
  title: { type: String, default: '架构图' },
  modules: { type: Number, default: 0 }
})

const mermaidRef = ref(null)
const mermaidReady = ref(false)
const contentReady = ref(false)

// 动态导入 Mermaid
async function renderMermaid() {
  contentReady.value = false
  mermaidReady.value = false
  if (!props.diagramData) return

  await nextTick()
  if (!mermaidRef.value) {
    // 无 mermaid 容器，直接显示文本降级
    contentReady.value = true
    return
  }

  try {
    const mermaid = await import('mermaid')
    mermaid.default.initialize({
      startOnLoad: false,
      theme: 'base',
      themeVariables: {
        primaryColor: '#f5ede8',
        primaryBorderColor: '#c15f3c',
        primaryTextColor: '#2d2a24',
        lineColor: '#c9c3b5',
        secondaryColor: '#faf9f5',
        tertiaryColor: '#fff'
      }
    })
    mermaidRef.value.textContent = props.diagramData
    await mermaid.default.run({ nodes: [mermaidRef.value] })
    mermaidReady.value = true
  } catch (e) {
    console.warn('Mermaid 渲染失败，使用文本降级:', e.message)
    mermaidReady.value = false
  } finally {
    contentReady.value = true
  }
}

watch(() => props.diagramData, (val) => {
  if (val) {
    renderMermaid()
  } else {
    contentReady.value = false
    mermaidReady.value = false
  }
}, { immediate: true })
</script>

<style scoped>
.arch-map {
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e3d8);
  border-radius: 10px;
  padding: 20px;
  min-height: 200px;
}

.arch-map.loading { display: flex; align-items: center; justify-content: center; }

/* 骨架屏 */
.skeleton {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.skeleton-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.skeleton-body {
  width: 100%;
}
.skeleton-block {
  height: 20px;
  background: linear-gradient(90deg, #f0ece4 25%, #e8e3d8 50%, #f0ece4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 4px;
}
@keyframes shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }

/* 过渡动画 */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.25s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

.diagram-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.diagram-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #2d2a24);
  margin: 0;
}
.diagram-badge {
  font-size: 11px;
  padding: 2px 8px;
  background: var(--accent-bg, #f5ede8);
  color: var(--accent, #c15f3c);
  border-radius: 10px;
}

.mermaid-content {
  overflow-x: auto;
}
.mermaid-content svg { max-width: 100%; height: auto; }

.text-fallback {
  margin-top: 8px;
}
.diagram-text {
  background: var(--code-bg, #faf9f5);
  border: 1px solid var(--border-color, #e8e3d8);
  border-radius: 8px;
  padding: 12px;
  font-size: 12px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  overflow-x: auto;
  white-space: pre;
  color: var(--text-secondary, #5a5548);
}
</style>

<template>
  <div class="code-highlights">
    <div v-if="!highlights || highlights.length === 0" class="empty-hl">
      <p class="text-muted">暂无代码高亮</p>
    </div>

    <div v-else class="hl-list">
      <div v-for="(hl, i) in highlights" :key="i" class="card-base hl-card" :class="'hl-' + hl.relevance">
        <div class="hl-header">
          <span class="hl-relevance-badge" :class="hl.relevance">{{ relevanceLabel(hl.relevance) }}</span>
          <span class="hl-file" v-if="hl.filePath">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            <router-link :to="`/code?file=${encodeURIComponent(hl.filePath)}`" class="hl-file-link">{{ hl.filePath }}</router-link>
          </span>
        </div>

        <h4 class="hl-title">{{ hl.title }}</h4>
        <p class="hl-desc" v-if="hl.description">{{ hl.description }}</p>

        <div v-if="hl.codeSnippet" class="hl-code" :class="{ 'hl-code-collapsed': collapsed[i] }">
          <pre><code :ref="el => { if (el) highlightCode(el) }">{{ hl.codeSnippet }}</code></pre>
          <button v-if="hl.codeSnippet.length > 300" class="hl-expand-btn" @click="toggleCollapse(i)">
            {{ collapsed[i] ? '展开全文' : '收起' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const props = defineProps({
  highlights: { type: Array, default: () => [] }
})

const collapsed = ref({})

function toggleCollapse(i) {
  collapsed.value[i] = !collapsed.value[i]
}

onMounted(() => {
  // 初始状态下，所有超过长度的代码段折叠
  if (props.highlights) {
    for (let i = 0; i < props.highlights.length; i++) {
      const hl = props.highlights[i]
      if (hl.codeSnippet && hl.codeSnippet.length > 300) {
        collapsed.value[i] = true
      }
    }
  }
})

function relevanceLabel(rel) {
  const labels = { critical: '核心', important: '重要', good_to_know: '参考' }
  return labels[rel] || rel
}

function highlightCode(el) {
  // 尝试使用 hljs 进行语法高亮
  if (typeof hljs !== 'undefined') {
    hljs.highlightElement(el)
  }
}
</script>

<style scoped>
.hl-list { display: flex; flex-direction: column; gap: 12px; }

.empty-hl { text-align: center; padding: 40px 20px; }

.hl-card { padding: 16px; }
.hl-card.hl-critical { border-left: 3px solid #c15f3c; }
.hl-card.hl-important { border-left: 3px solid #d47a4a; }
.hl-card.hl-good_to_know { border-left: 3px solid #c9c3b5; }

.hl-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.hl-relevance-badge {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 8px;
  font-weight: 500;
  text-transform: uppercase;
}
.hl-relevance-badge.critical { background: #f5ede8; color: #c15f3c; }
.hl-relevance-badge.important { background: #f8f6f0; color: #d47a4a; }
.hl-relevance-badge.good_to_know { background: #f0ece4; color: #8a857a; }

.hl-file {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: #8a857a;
  margin-left: auto;
}

.hl-file-link {
  color: #c15f3c;
  text-decoration: none;
  border-bottom: 1px dashed #ddd8cc;
}
.hl-file-link:hover { border-bottom-color: #c15f3c; }

.hl-title {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0 0 4px;
}

.hl-desc {
  font-size: 13px;
  color: #5a5548;
  line-height: 1.5;
  margin: 0 0 8px;
}

.hl-code {
  background: #faf9f5;
  border: 1px solid #e8e3d8;
  border-radius: 6px;
  padding: 8px 12px;
  overflow-x: auto;
  position: relative;
}

.hl-code-collapsed {
  max-height: 200px;
  overflow-y: hidden;
}

.hl-code-collapsed::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 40px;
  background: linear-gradient(transparent, #faf9f5);
  pointer-events: none;
}

.hl-code pre { margin: 0; }
.hl-code code {
  font-size: 12px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  color: #2d2a24;
  white-space: pre-wrap;
}

.hl-expand-btn {
  display: block;
  width: 100%;
  padding: 6px;
  margin-top: 4px;
  background: none;
  border: 1px solid #e8e3d8;
  border-radius: 4px;
  font-size: 11px;
  color: #8a857a;
  cursor: pointer;
  transition: all 0.12s;
}
.hl-expand-btn:hover { background: #f0ece4; color: #5a5548; }
</style>

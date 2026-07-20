<template>
  <span class="pattern-badge" :class="categoryClass" :title="description">
    <span class="pattern-icon" v-html="categoryIcon"></span>
    <span class="pattern-name">{{ name }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  category: { type: String, default: 'behavioral' },
  description: { type: String, default: '' }
})

const categoryMap = {
  creational: {
    icon: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/></svg>',
    cls: 'creational'
  },
  structural: {
    icon: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/></svg>',
    cls: 'structural'
  },
  behavioral: {
    icon: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
    cls: 'behavioral'
  },
  architectural: {
    icon: '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>',
    cls: 'architectural'
  }
}

const categoryInfo = computed(() => categoryMap[props.category] || categoryMap.behavioral)
const categoryIcon = computed(() => categoryInfo.value.icon)
const categoryClass = computed(() => categoryInfo.value.cls)
</script>

<style scoped>
.pattern-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 500;
  cursor: default;
  transition: all 0.12s;
}
.pattern-badge:hover { opacity: 0.85; }

/* 珊瑚主题变体色系：使用 #c15f3c 的深浅变体 + 中性辅助色 */
.pattern-badge.creational {
  background: #f5ede8;
  color: #a84e2e;
  border: 1px solid #e8d5c8;
}
.pattern-badge.structural {
  background: #f0ece4;
  color: #8a7a6a;
  border: 1px solid #ddd8cc;
}
.pattern-badge.behavioral {
  background: #faf0ed;
  color: #c15f3c;
  border: 1px solid #f0dad0;
}
.pattern-badge.architectural {
  background: #f5e8f0;
  color: #8a5a7a;
  border: 1px solid #e8d0e0;
}

.pattern-icon { display: inline-flex; align-items: center; }
.pattern-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>

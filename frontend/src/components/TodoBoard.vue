<template>
  <div class="todo-board">
    <div class="todo-header">
      <h3 class="todo-title">学习 TODO</h3>
      <span class="todo-count">{{ filteredTodos.length }} 项</span>
    </div>

    <!-- 状态过滤 -->
    <div class="todo-filters">
      <button
        v-for="f in filters"
        :key="f.value"
        class="filter-btn"
        :class="{ active: activeFilter === f.value }"
        @click="activeFilter = f.value"
      >{{ f.label }}</button>
    </div>

    <!-- TODO 列表 -->
    <div class="todo-list" v-if="filteredTodos.length > 0">
      <div
        v-for="(todo, i) in filteredTodos"
        :key="todo.id || i"
        class="todo-card"
        :class="{ completed: todo.status === 'completed' }"
      >
        <button class="todo-check" @click="toggleTodo(todo)" :title="todo.status === 'completed' ? '标记未完成' : '标记完成'">
          <svg v-if="todo.status === 'completed'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#57ab5a" stroke-width="2.5">
            <circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>
          </svg>
          <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#c9c3b5" stroke-width="2">
            <circle cx="12" cy="12" r="10"/>
          </svg>
        </button>
        <div class="todo-body">
          <span class="todo-title-text">{{ todo.title }}</span>
          <div class="todo-meta">
            <span class="difficulty-tag" :class="todo.difficulty || 'beginner'">
              {{ diffLabel(todo.difficulty) }}
            </span>
            <span v-if="todo.chapter" class="chapter-tag">{{ todo.chapter }}</span>
          </div>
        </div>
        <div class="todo-actions">
          <button class="todo-move" @click="moveTodo(todo, -1)" :disabled="isMoveDisabled(todo, -1)" title="上移">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m18 15-6-6-6 6"/></svg>
          </button>
          <button class="todo-move" @click="moveTodo(todo, 1)" :disabled="isMoveDisabled(todo, 1)" title="下移">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m6 9 6 6 6-6"/></svg>
          </button>
          <button class="todo-delete" @click="$emit('delete', todo)" title="删除">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="todo-empty">
      <p>暂无 TODO 项</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  todos: { type: Array, default: () => [] }
})
const emit = defineEmits(['update', 'delete'])

const activeFilter = ref('all')
const filters = [
  { label: '全部', value: 'all' },
  { label: '待处理', value: 'pending' },
  { label: '进行中', value: 'in_progress' },
  { label: '已完成', value: 'completed' }
]

const filteredTodos = computed(() => {
  if (activeFilter.value === 'all') return props.todos
  return props.todos.filter(t => t.status === activeFilter.value)
})

function diffLabel(difficulty) {
  const map = { beginner: '入门', intermediate: '进阶', advanced: '挑战' }
  return map[difficulty] || difficulty || '入门'
}

function toggleTodo(todo) {
  const newStatus = todo.status === 'completed' ? 'pending' : 'completed'
  emit('update', { ...todo, status: newStatus })
}

function moveTodo(todo, direction) {
  const idx = props.todos.findIndex(t => t.id === todo.id)
  if (idx === -1) return
  const target = idx + direction
  if (target < 0 || target >= props.todos.length) return
  // 交换 sortOrder
  const newTodos = [...props.todos]
  const temp = newTodos[idx].sortOrder
  newTodos[idx] = { ...newTodos[idx], sortOrder: newTodos[target].sortOrder }
  newTodos[target] = { ...newTodos[target], sortOrder: temp }
  emit('update', newTodos[idx])
  emit('update', newTodos[target])
}

/** 使用原始数组索引判断移动按钮是否禁用（与 filtered 索引解耦） */
function isMoveDisabled(todo, direction) {
  const idx = props.todos.findIndex(t => t.id === todo.id)
  if (idx === -1) return true
  const target = idx + direction
  return target < 0 || target >= props.todos.length
}
</script>

<style scoped>
.todo-board {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  overflow: hidden;
}

.todo-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid #f0ece4;
}

.todo-title {
  font-size: 14px;
  font-weight: 600;
  color: #2d2a24;
  margin: 0;
}

.todo-count {
  font-size: 12px;
  color: #8a857a;
}

.todo-filters {
  display: flex;
  gap: 4px;
  padding: 8px 16px;
  border-bottom: 1px solid #f0ece4;
}

.filter-btn {
  padding: 4px 10px;
  border: 1px solid #e8e3d8;
  border-radius: 6px;
  font-size: 11px;
  background: #fff;
  color: #5a5548;
  cursor: pointer;
  transition: all 0.12s;
}
.filter-btn:hover { background: #f8f6f0; }
.filter-btn.active { background: #c15f3c; color: #fff; border-color: #c15f3c; }

.todo-list {
  padding: 8px;
}

.todo-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  transition: background 0.15s;
  border-bottom: 1px solid #f5f0ea;
}
.todo-card:last-child { border-bottom: none; }
.todo-card:hover { background: #faf9f5; }
.todo-card.completed { opacity: 0.7; }

.todo-check {
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px;
  flex-shrink: 0;
  margin-top: 2px;
}

.todo-body {
  flex: 1;
  min-width: 0;
}

.todo-title-text {
  font-size: 13px;
  color: #2d2a24;
  font-weight: 500;
  line-height: 1.4;
  transition: text-decoration-color 0.2s ease, color 0.2s ease;
}
.todo-card.completed .todo-title-text {
  text-decoration: line-through;
  text-decoration-color: #8a857a;
  color: #8a857a;
}

.todo-card {
  transition: opacity 0.2s ease;
}

.todo-meta {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.difficulty-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.difficulty-tag.beginner { background: #e8f5e9; color: #2e7d32; }
.difficulty-tag.intermediate { background: #fff3e0; color: #e65100; }
.difficulty-tag.advanced { background: #fbe9e7; color: #bf360c; }

.chapter-tag {
  font-size: 10px;
  padding: 1px 6px;
  background: #f0ece4;
  color: #5a5548;
  border-radius: 4px;
}

.todo-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0.35;
  transition: opacity 0.12s;
}
.todo-card:hover .todo-actions { opacity: 1; }
/* 触屏设备始终显示操作按钮 */
@media (hover: none) {
  .todo-actions { opacity: 0.6; }
}

.todo-move, .todo-delete {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  color: #8a857a;
}
.todo-move:hover { background: #f0ece4; color: #5a5548; }
.todo-move:disabled { opacity: 0.3; cursor: default; }
.todo-delete:hover { background: #fbe9e7; color: #c62828; }

.todo-empty {
  padding: 32px;
  text-align: center;
  color: #8a857a;
  font-size: 13px;
}
</style>

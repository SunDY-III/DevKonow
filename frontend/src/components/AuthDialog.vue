<template>
  <div class="auth-overlay" @click.self="$emit('close')">
    <div class="auth-card">
      <div class="auth-tabs">
        <button :class="['tab', { active: tab === 'login' }]" @click="tab = 'login'">登录</button>
        <button :class="['tab', { active: tab === 'register' }]" @click="tab = 'register'">注册</button>
      </div>

      <form @submit.prevent="submit" class="auth-form">
        <div class="field">
          <label>用户名</label>
          <input v-model="form.username" type="text" placeholder="输入用户名" required :disabled="busy" />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="form.password" type="password" placeholder="输入密码" required :disabled="busy" minlength="4" />
        </div>

        <p v-if="errorMsg" class="auth-error">{{ errorMsg }}</p>

        <button type="submit" class="auth-submit" :disabled="busy">
          <span v-if="busy" class="spinner"></span>
          <span v-else>{{ tab === 'login' ? '登录' : '注册并登录' }}</span>
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useAuthStore } from '../stores/useAuthStore.js'

const emit = defineEmits(['close'])
const authStore = useAuthStore()

const tab = ref('login')
const busy = ref(false)
const errorMsg = ref('')
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username.trim() || !form.password.trim()) return
  busy.value = true
  errorMsg.value = ''
  try {
    if (tab.value === 'login') {
      await authStore.login({ username: form.username, password: form.password })
    } else {
      await authStore.register({ username: form.username, password: form.password })
    }
    emit('close')
  } catch (err) {
    errorMsg.value = err.message || '操作失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.auth-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.auth-card {
  background: #fff;
  border-radius: 12px;
  padding: 32px;
  width: 380px;
  max-width: 90vw;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.15);
}

.auth-tabs {
  display: flex;
  gap: 0;
  margin-bottom: 24px;
  border-bottom: 2px solid #f0ece4;
}

.tab {
  flex: 1;
  padding: 10px 16px;
  border: none;
  background: none;
  font-size: 15px;
  font-weight: 500;
  color: #8a857a;
  cursor: pointer;
  transition: all 0.12s;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
}

.tab.active {
  color: #c15f3c;
  border-bottom-color: #c15f3c;
}

.tab:hover { color: #2d2a24; }

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field label {
  font-size: 13px;
  font-weight: 500;
  color: #5a5548;
}

.field input {
  padding: 10px 12px;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  font-size: 14px;
  color: #2d2a24;
  background: #fff;
  transition: border-color 0.12s;
}

.field input:focus {
  border-color: #c15f3c;
  outline: none;
}

.auth-error {
  color: #d1453b;
  font-size: 13px;
  margin: 0;
}

.auth-submit {
  padding: 10px 20px;
  background: #c15f3c;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.12s;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
}

.auth-submit:hover { background: #a84e2f; }
.auth-submit:disabled { background: #d4cfc2; cursor: not-allowed; }

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }
</style>

import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import ImportView from '../views/ImportView.vue'
import ProjectsView from '../views/ProjectsView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/chat/:projectId', name: 'chat-project', component: ChatView },
  { path: '/import', name: 'import', component: ImportView },
  { path: '/projects', name: 'projects', component: ProjectsView },
  // Phase 3: 护航路由
  { path: '/mentor', name: 'mentor', component: () => import('../views/MentorView.vue') },
  { path: '/mentor/:projectId', name: 'mentor-project', component: () => import('../views/MentorView.vue') },
  // Phase 3: 模板市场
  { path: '/templates', name: 'templates', component: () => import('../views/TemplateMarketView.vue') },
  // Phase 3: 管理后台
  { path: '/admin/prompts', name: 'admin-prompts', component: () => import('../views/AdminPromptView.vue') },
  // 404 兜底
  { path: '/:pathMatch(.*)*', name: 'not-found', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router

import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import ImportView from '../views/ImportView.vue'
import ProjectsView from '../views/ProjectsView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/chat/:projectId', name: 'chat-project', component: ChatView },
  { path: '/import', name: 'import', component: ImportView },
  { path: '/projects', name: 'projects', component: ProjectsView }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router

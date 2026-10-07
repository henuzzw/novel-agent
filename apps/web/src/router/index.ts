import { createRouter, createWebHistory } from 'vue-router'

import CreateProjectView from '@/views/CreateProjectView.vue'
import ProjectListView from '@/views/ProjectListView.vue'
import ProjectWorkspaceView from '@/views/ProjectWorkspaceView.vue'
import PromptSettingsView from '@/views/PromptSettingsView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path !== from.path || ['section', 'planning', 'materials', 'chapter'].some(key => to.query[key] !== from.query[key])) return { top: 0 }
    return false
  },
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/projects', name: 'projects', component: ProjectListView },
    { path: '/projects/new', name: 'project-new', component: CreateProjectView },
    { path: '/settings/prompts', name: 'prompt-settings', component: PromptSettingsView },
    {
      path: '/projects/:projectId',
      name: 'project-workspace',
      component: ProjectWorkspaceView,
      props: true,
    },
  ],
})

export default router

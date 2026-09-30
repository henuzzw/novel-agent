import { createRouter, createWebHistory } from 'vue-router'

import CreateProjectView from '@/views/CreateProjectView.vue'
import ProjectListView from '@/views/ProjectListView.vue'
import ProjectWorkspaceView from '@/views/ProjectWorkspaceView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/projects', name: 'projects', component: ProjectListView },
    { path: '/projects/new', name: 'project-new', component: CreateProjectView },
    {
      path: '/projects/:projectId',
      name: 'project-workspace',
      component: ProjectWorkspaceView,
      props: true,
    },
  ],
})

export default router

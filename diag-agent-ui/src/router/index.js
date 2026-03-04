import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Diagnosis',
    component: () => import('../views/DiagnosisView.vue'),
    meta: { title: '智能诊断' }
  },
  {
    path: '/history',
    name: 'History',
    component: () => import('../views/HistoryView.vue'),
    meta: { title: '收藏夹' }
  },
  {
    path: '/knowledge',
    name: 'Knowledge',
    component: () => import('../views/KnowledgeView.vue'),
    meta: { title: '知识库' }
  },
  {
    path: '/system',
    name: 'System',
    component: () => import('../views/SystemView.vue'),
    meta: { title: '系统状态' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.title) {
    document.title = `${to.meta.title} - DiagAgent 智能诊断系统`
  }
  next()
})

export default router

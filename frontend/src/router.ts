import { createRouter, createWebHistory } from 'vue-router'

import DashboardView from './views/DashboardView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView, meta: { title: '대시보드' } },
    // '/marketplace/fulfillment'는 백엔드 경로(프록시)이므로 시뮬레이터 화면은 다른 경로를 쓴다
    {
      path: '/aws-marketplace',
      name: 'marketplace',
      component: () => import('./views/MarketplaceView.vue'),
      meta: { title: 'AWS Marketplace (시뮬레이터)' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('./views/RegisterView.vue'),
      meta: { title: '계정 등록' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '')} · Marketplace Billing Mock`
})

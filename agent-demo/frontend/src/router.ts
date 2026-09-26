import { createRouter, createWebHashHistory } from 'vue-router';
import { auth } from './state/auth';
import LoginPage from './features/auth/LoginPage.vue';
import WorkbenchPage from './features/WorkbenchPage.vue';
import NotFound from './features/NotFound.vue';
export function safeReturn(value: unknown): string {
  return typeof value === 'string' && (/^\/sessions\/[A-Za-z0-9_-]{1,128}$/.test(value) || value === '/new') ? value : '/new';
}
export const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', redirect: '/new' }, { path: '/login', component: LoginPage },
  { path: '/new', component: WorkbenchPage }, { path: '/sessions/:sessionId', component: WorkbenchPage },
  { path: '/:pathMatch(.*)*', component: NotFound },
] });
router.beforeEach(to => {
  if (to.path !== '/login' && !auth.user.value) return { path: '/login', query: { return: safeReturn(to.path) } };
  if (to.path === '/login' && auth.user.value) return safeReturn(to.query.return);
});
auth.onReset(() => { if (router.currentRoute.value.path !== '/login') void router.replace({ path: '/login', query: { return: safeReturn(router.currentRoute.value.path) } }); });

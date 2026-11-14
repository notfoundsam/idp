import { ApiError, type AccountClient, type AdminClient, type Release } from '@idp/api-client'
import { MutationCache, QueryCache, QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import { createApp, type App, type Plugin } from 'vue'
import { createRouter, type RouterHistory } from 'vue-router'
import ConsoleApp from './App.vue'
import { CONSOLE, LOGIN_PATH, type Browser } from './context'
import { resettable } from './store'

export interface ConsoleDependencies {
  client: AccountClient
  admin: AdminClient
  release: Release
  browser: Browser
  history: RouterHistory
}

/** The pages share the layout with the logout, except the one that follows a logout. */
export const routes = [
  {
    path: '/',
    component: () => import('./AccountLayout.vue'),
    children: [
      { path: '', name: 'security', component: () => import('./views/SecurityView.vue') },
      { path: 'sessions', name: 'sessions', component: () => import('./views/SessionsView.vue') },
      {
        path: 'admin',
        component: () => import('./admin/AdminSection.vue'),
        children: [
          { path: '', redirect: { name: 'users' } },
          { path: 'users', name: 'users', component: () => import('./admin/users/UsersView.vue') },
          { path: 'users/:id', name: 'user', component: () => import('./admin/users/UserView.vue'), props: true },
          { path: 'policy', name: 'policy', component: () => import('./admin/policies/PolicyView.vue') },
          {
            path: 'service-providers',
            name: 'service-providers',
            component: () => import('./admin/service-providers/ServiceProvidersView.vue'),
          },
          {
            path: 'service-providers/:id',
            name: 'service-provider',
            component: () => import('./admin/service-providers/ServiceProviderView.vue'),
            props: true,
          },
          { path: 'signing-keys', name: 'signing-keys', component: () => import('./admin/service-providers/SigningKeysView.vue') },
          { path: 'audit', name: 'audit', component: () => import('./admin/audit/AuditView.vue') },
        ],
      },
    ],
  },
  { path: '/signed-out', name: 'signed-out', component: () => import('./views/SignedOutView.vue') },
  { path: '/:rest(.*)*', redirect: '/' },
]

export function createConsole({ client, admin, release, browser, history }: ConsoleDependencies) {
  // The page sends several requests at once; a second navigation to the login would abort the first.
  let leaving = false
  const signIn = (error: unknown) => {
    if (leaving || !(error instanceof ApiError && error.status === 401)) return
    leaving = true
    browser.assign(LOGIN_PATH)
  }
  const queries = new QueryClient({
    queryCache: new QueryCache({ onError: signIn }),
    mutationCache: new MutationCache({ onError: signIn }),
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const pinia = createPinia()
  const resetStores = resettable(pinia)
  const router = createRouter({ history, routes })

  // A new release loads on the next page with a full navigation, so a form being filled in is never lost.
  router.beforeEach((to, from) => {
    if (from.matched.length === 0 || !release.newer) return true
    browser.assign(router.resolve(to).href)
    return false
  })

  const plugin: Plugin = {
    install(app: App) {
      app.use(pinia)
      app.use(router)
      app.use(VueQueryPlugin, { queryClient: queries })
      app.provide(CONSOLE, { client, admin, release, browser, resetStores })
    },
  }
  return { plugin, router, queries, pinia }
}

export function createConsoleApp(dependencies: ConsoleDependencies) {
  return createApp(ConsoleApp).use(createConsole(dependencies).plugin)
}

import { ApiError, type AccountClient, type Release } from '@idp/api-client'
import { MutationCache, QueryCache, QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import { createApp, type App, type Plugin } from 'vue'
import { createRouter, type RouterHistory } from 'vue-router'
import ConsoleApp from './App.vue'
import { CONSOLE, LOGIN_PATH, type Browser } from './context'
import { resettable } from './store'

export interface ConsoleDependencies {
  client: AccountClient
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
    ],
  },
  { path: '/signed-out', name: 'signed-out', component: () => import('./views/SignedOutView.vue') },
  { path: '/:rest(.*)*', redirect: '/' },
]

export function createConsole({ client, release, browser, history }: ConsoleDependencies) {
  const signIn = (error: unknown) => {
    if (error instanceof ApiError && error.status === 401) browser.assign(LOGIN_PATH)
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
      app.provide(CONSOLE, { client, release, browser, resetStores })
    },
  }
  return { plugin, router, queries, pinia }
}

export function createConsoleApp(dependencies: ConsoleDependencies) {
  return createApp(ConsoleApp).use(createConsole(dependencies).plugin)
}

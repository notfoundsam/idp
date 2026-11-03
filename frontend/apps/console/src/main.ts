import { browserPage, createAccountClient, createReloader, Release, reloadOnChunkError } from '@idp/api-client'
import { passkeysSupported } from '@idp/ui'
import { reactive } from 'vue'
import { createWebHistory } from 'vue-router'
import { createConsoleApp } from './console'

const release = reactive(new Release(__APP_VERSION__))
reloadOnChunkError(window, createReloader(browserPage(window)))

const client = createAccountClient({
  version: release.version,
  onAppVersion: (version) => release.observe(version),
  onUpgradeRequired: () => release.upgradeRequired(),
})

createConsoleApp({
  client,
  release,
  history: createWebHistory('/console/'),
  browser: {
    host: window.location.hostname,
    credentials: passkeysSupported(window) ? window.navigator.credentials : null,
    assign: (url) => window.location.assign(url),
    reload: () => window.location.reload(),
  },
}).mount('#app')

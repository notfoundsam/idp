import { browserPage, createAccountClient, createAdminClient, createReloader, Release, reloadOnChunkError } from '@idp/api-client'
import { passkeysSupported } from '@idp/ui'
import { reactive } from 'vue'
import { createWebHistory } from 'vue-router'
import { createConsoleApp } from './console'

const release = reactive(new Release(__APP_VERSION__))
reloadOnChunkError(window, createReloader(browserPage(window)))

const options = {
  version: release.version,
  onAppVersion: (version: string) => release.observe(version),
  onUpgradeRequired: () => release.upgradeRequired(),
}

createConsoleApp({
  client: createAccountClient(options),
  admin: createAdminClient(options),
  release,
  history: createWebHistory('/console/'),
  browser: {
    host: window.location.hostname,
    credentials: passkeysSupported(window) ? window.navigator.credentials : null,
    assign: (url) => window.location.assign(url),
    reload: () => window.location.reload(),
  },
}).mount('#app')

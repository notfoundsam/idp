import { browserPage, createClient, createReloader, Release, reloadOnChunkError } from '@idp/api-client'
import { passkeysSupported } from '@idp/ui'
import { createApp, reactive } from 'vue'
import App from './App.vue'
import { browserNavigation, createLogin } from './login'
import { pageAt, takeToken } from './pages'

const token = takeToken(window)
const release = reactive(new Release(__APP_VERSION__))
const reload = createReloader(browserPage(window))
reloadOnChunkError(window, reload)

const client = createClient({
  version: release.version,
  onAppVersion: (version) => release.observe(version),
  onUpgradeRequired: () => release.upgradeRequired(),
})

createApp(App, {
  login: createLogin({ client, release, reload, navigation: browserNavigation(window) }),
  release,
  credentials: passkeysSupported(window) ? window.navigator.credentials : null,
  host: window.location.hostname,
  reloadPage: () => window.location.reload(),
  page: pageAt(window.location.pathname),
  token,
}).mount('#app')

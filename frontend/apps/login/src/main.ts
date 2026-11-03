import { browserPage, createClient, createReloader, Release, reloadOnChunkError } from '@idp/api-client'
import { createApp, reactive } from 'vue'
import App from './App.vue'
import { browserNavigation, createLogin } from './login'
import { passkeysSupported } from './webauthn'

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
}).mount('#app')

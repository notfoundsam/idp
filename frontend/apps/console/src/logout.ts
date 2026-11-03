import { useQueryClient } from '@tanstack/vue-query'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useConsole } from './context'
import { useAccountStore } from './store'

/**
 * The page forgets the account whether or not the server answers, since the user meant to leave. It first leaves the pages that
 * show the account, so nothing on screen holds on to the cached answers, then drops the cache and every store.
 */
export function useLogout() {
  const { client, resetStores } = useConsole()
  const queries = useQueryClient()
  const router = useRouter()
  const store = useAccountStore()
  const busy = ref(false)

  async function logout(): Promise<void> {
    if (busy.value) return
    busy.value = true
    let confirmed = true
    try {
      await client.logout()
    } catch {
      confirmed = false
    }
    await router.replace({ name: 'signed-out' })
    await queries.cancelQueries()
    queries.clear()
    resetStores()
    store.signedOut = confirmed ? 'done' : 'unconfirmed'
    busy.value = false
  }

  return { logout, busy }
}

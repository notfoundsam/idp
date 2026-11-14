import { defineStore, type Pinia } from 'pinia'

export type SignedOut = 'done' | 'unconfirmed'

/** What the console keeps between pages: codes shown once, and what the user is offered after a change. */
export const useAccountStore = defineStore('account', {
  state: () => ({
    recoveryCodes: [] as string[],
    offerToEndSessions: false,
    notice: null as string | null,
    signedOut: null as SignedOut | null,
  }),
  actions: {
    factorChanged(recoveryCodes: string[] = []) {
      if (recoveryCodes.length > 0) this.recoveryCodes = recoveryCodes
      this.offerToEndSessions = true
      this.notice = null
    },
  },
})

/** Every store goes back to its first state, so nothing of the account stays in memory after a logout. */
export function resettable(pinia: Pinia): () => void {
  const stores = new Set<{ $reset(): void }>()
  pinia.use(({ store }) => {
    stores.add(store)
  })
  return () => {
    for (const store of stores) store.$reset()
  }
}

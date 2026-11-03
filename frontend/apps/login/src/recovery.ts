import { reactive } from 'vue'
import type { Problem } from './login'
import { troubleOf } from './messages'

export interface RecoveryState {
  done: boolean
  busy: boolean
  problem: Problem | null
  retryAfter: number | null
}

/** One request a page sends outside a flow, such as setting a password with the token from a mail. */
export function createRecovery() {
  const state = reactive<RecoveryState>({ done: false, busy: false, problem: null, retryAfter: null })

  async function send(request: () => Promise<void>): Promise<void> {
    if (state.busy || state.done) return
    state.busy = true
    try {
      await request()
      state.done = true
      state.problem = null
      state.retryAfter = null
    } catch (error) {
      const trouble = troubleOf(error)
      state.problem = trouble.problem
      state.retryAfter = trouble.retryAfter
    } finally {
      state.busy = false
    }
  }

  return { state, send }
}

export type Recovery = ReturnType<typeof createRecovery>

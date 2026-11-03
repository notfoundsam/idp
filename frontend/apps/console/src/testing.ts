import { ApiError, Release, type AccountClient, type Factors, type Sessions } from '@idp/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { vi } from 'vitest'
import { reactive } from 'vue'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { createConsole } from './console'

export const refused = (status: number, error: string | null = null) => new ApiError(status, null, error)

export const EMAIL = 'alice@acme.test'

export const factorsWith = (overrides: Partial<Factors> = {}): Factors => ({
  totp: false,
  passkeys: [],
  recoveryCodesLeft: 0,
  ...overrides,
})

export const sessionsOf = (...others: string[]): Sessions => ({
  email: EMAIL,
  sessions: [
    { id: 'current', current: true, authenticatedAt: '2026-11-03T08:00:00Z', lastSeenAt: '2026-11-03T09:00:00Z', ip: '192.0.2.1', userAgent: 'This one' },
    ...others.map((id) => ({
      id,
      current: false,
      authenticatedAt: '2026-11-02T08:00:00Z',
      lastSeenAt: '2026-11-02T09:00:00Z',
      ip: null,
      userAgent: `Browser ${id}`,
    })),
  ],
})

type Answer = unknown

/** A client that answers each operation from a queue the test fills, repeating the last answer, and records what it was asked. */
export function scriptedClient() {
  const answers = new Map<string, Answer[]>()
  const calls: { operation: string; args: unknown[] }[] = []

  function answer(operation: keyof AccountClient, ...values: Answer[]) {
    answers.set(operation, [...(answers.get(operation) ?? []), ...values])
  }

  const client = new Proxy({} as AccountClient, {
    get: (_, operation: string) =>
      async (...args: unknown[]) => {
        calls.push({ operation, args })
        const queue = answers.get(operation) ?? []
        if (queue.length === 0) throw new Error(`No answer for ${operation}`)
        const next = queue.length > 1 ? queue.shift() : queue[0]
        if (next instanceof Error) throw next
        return next
      },
  })
  const called = (operation: keyof AccountClient) => calls.filter((it) => it.operation === operation)
  return { client, answer, calls, called }
}

export async function consoleAt(path: string, options: { credentials?: CredentialsContainer | null; version?: string } = {}) {
  const scripted = scriptedClient()
  const release = reactive(new Release(options.version ?? '1.0.0'))
  const browser = {
    host: 'acme.idp.test',
    credentials: options.credentials ?? null,
    assigned: [] as string[],
    reloads: 0,
    assign(url: string) {
      browser.assigned.push(url)
    },
    reload() {
      browser.reloads++
    },
  }
  const history = createMemoryHistory('/console/')
  const built = createConsole({ client: scripted.client, release, browser, history })
  return {
    ...scripted,
    ...built,
    release,
    browser,
    async start() {
      await built.router.push(path)
      const wrapper = mount(App, { global: { plugins: [built.plugin] }, attachTo: document.body })
      await settle()
      return wrapper
    },
  }
}

/** Lazy pages and queries resolve over several ticks. */
export async function settle() {
  for (let i = 0; i < 5; i++) {
    await vi.dynamicImportSettled()
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

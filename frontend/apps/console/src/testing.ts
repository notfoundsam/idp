import { ApiError, Release, type AccountClient, type AdminClient, type Factors, type Sessions } from '@idp/api-client'
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
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
  role: 'USER',
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

export const adminSessions = (): Sessions => ({ ...sessionsOf(), role: 'TENANT_ADMIN' })

type Answer = unknown

/** The two clients name no operation alike, so one script answers both. */
export type Operation = keyof AccountClient | keyof AdminClient

/** A client that answers each operation from a queue the test fills, repeating the last answer, and records what it was asked. */
export function scriptedClient() {
  const answers = new Map<string, Answer[]>()
  const calls: { operation: string; args: unknown[] }[] = []

  function answer(operation: Operation, ...values: Answer[]) {
    answers.set(operation, [...(answers.get(operation) ?? []), ...values])
  }

  const client = new Proxy({} as AccountClient & AdminClient, {
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
  const called = (operation: Operation) => calls.filter((it) => it.operation === operation)
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
  const built = createConsole({ client: scripted.client, admin: scripted.client, release, browser, history })
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

/** The console of an admin, whose layout reads the sessions and the first page the factors. */
export async function adminAt(path: string) {
  const page = await consoleAt(path)
  page.answer('getSessions', adminSessions())
  page.answer('getFactors', factorsWith())
  return page
}

export async function submit(wrapper: VueWrapper, form: string) {
  await wrapper.get(`[data-testid="${form}"]`).trigger('submit')
  await settle()
}

export function button(wrapper: VueWrapper, name: string): DOMWrapper<HTMLButtonElement> {
  const found = wrapper.findAll('button').find((it) => it.text() === name)
  if (found === undefined) throw new Error(`No button "${name}" in ${wrapper.text()}`)
  return found as DOMWrapper<HTMLButtonElement>
}

export const hasButton = (wrapper: VueWrapper, name: string) => wrapper.findAll('button').some((it) => it.text() === name)

export async function click(wrapper: VueWrapper, name: string) {
  await button(wrapper, name).trigger('click')
  await settle()
}

/** Lazy pages and queries resolve over several ticks. */
export async function settle() {
  for (let i = 0; i < 5; i++) {
    await vi.dynamicImportSettled()
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

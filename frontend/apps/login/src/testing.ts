import { ApiError, Release, type Client, type Flow } from '@idp/api-client'
import { reactive } from 'vue'
import { createLogin } from './login'

export const FLOW_ID = '3f0c2b4e-8a51-4c8e-b8a4-5d0f9e7b1a20'

export const flowAt = (step: string, factors: Flow['factors'] = []): Flow => ({ id: FLOW_ID, step: step as Flow['step'], factors })

export const refused = (status: number, retryAfter: number | null = null) => new ApiError(status, retryAfter)

type Answer<T> = T | ApiError | Error

/** A client whose every operation answers from a queue the test fills, and which records what it was asked. */
export function scriptedClient() {
  const answers = new Map<string, Answer<unknown>[]>()
  const calls: { operation: string; args: unknown[] }[] = []

  function answer(operation: keyof Client, ...values: Answer<unknown>[]) {
    answers.set(operation, [...(answers.get(operation) ?? []), ...values])
  }

  const client = new Proxy({} as Client, {
    get: (_, operation: string) =>
      async (...args: unknown[]) => {
        calls.push({ operation, args })
        const queue = answers.get(operation) ?? []
        const next = queue.shift()
        if (next === undefined) throw new Error(`No answer for ${operation}`)
        if (next instanceof Error) throw next
        return next
      },
  })
  return { client, answer, calls }
}

export function fakeNavigation(search = '') {
  const navigation = {
    search,
    assigned: [] as string[],
    replaced: null as string | null,
    assign: (url: string) => navigation.assigned.push(url),
    replace: (url: string) => {
      navigation.replaced = url
    },
  }
  return navigation
}

export function loginWith(options: { search?: string; version?: string; reloads?: boolean } = {}) {
  const scripted = scriptedClient()
  const release = reactive(new Release(options.version ?? '1.0.0'))
  const navigation = fakeNavigation(options.search)
  const reloads = { count: 0 }
  const login = createLogin({
    client: scripted.client,
    release,
    navigation,
    reload: () => {
      if (options.reloads === false) return false
      reloads.count++
      return true
    },
  })
  return { ...scripted, release, navigation, reloads, login }
}

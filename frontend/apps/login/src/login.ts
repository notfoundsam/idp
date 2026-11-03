import { ApiError, type Client, type EnrolledFlow, type Flow, type Release, type Step } from '@idp/api-client'
import { reactive } from 'vue'
import { troubleOf } from './messages'
import { LOGIN_PATH } from './pages'

export const DIRECT_LOGIN_TARGET = '/console/'

const KNOWN_STEPS: ReadonlySet<string> = new Set<Step>(['IDENTIFY', 'PASSWORD', 'SECOND_FACTOR', 'ENROLL_MFA', 'COMPLETE'])

export type Phase = 'loading' | 'invitation' | 'step' | 'recovery-codes' | 'leaving' | 'expired' | 'failed'

export type Problem = 'rejected' | 'throttled' | 'outdated' | 'unreachable' | 'broken-link' | 'unexpected'

export interface Navigation {
  search: string
  assign(url: string): void
  replace(url: string): void
}

export interface LoginDependencies {
  client: Client
  release: Release
  reload: () => boolean
  navigation: Navigation
}

export interface LoginState {
  phase: Phase
  flow: Flow | null
  recoveryCodes: string[]
  problem: Problem | null
  retryAfter: number | null
  busy: boolean
  login: string | null
}

export function browserNavigation(window: Window): Navigation {
  return {
    search: window.location.search,
    assign: (url) => window.location.assign(url),
    replace: (url) => window.history.replaceState(window.history.state, '', url),
  }
}

const flowUrl = (id: string) => `${LOGIN_PATH}?${new URLSearchParams({ flow: id }).toString()}`

/**
 * Walks a flow the way the server leads it. A new release is loaded with a full reload when the page moves to another step, so a
 * form being filled in is never lost: the flow is in the URL and the reload goes on from where it stands.
 */
export function createLogin({ client, release, reload, navigation }: LoginDependencies) {
  const state = reactive<LoginState>({
    phase: 'loading',
    flow: null,
    recoveryCodes: [],
    problem: null,
    retryAfter: null,
    busy: false,
    login: null,
  })
  let afterCodes: Flow | null = null
  let invitation: string | null = null
  let invitationFlow: string | null = null

  /** The console sends `?reauthenticate` when a change needs a fresh login, which proves every factor anew. */
  async function begin(): Promise<void> {
    const query = new URLSearchParams(navigation.search)
    const id = query.get('flow')
    await attempt(async () => {
      if (id !== null) return show(await client.getFlow(id))
      const started = query.has('reauthenticate') ? await client.startFlow({ reauthenticate: true }) : await client.startFlow()
      navigation.replace(flowUrl(started.id))
      return show(started)
    })
  }

  /** The token stays in memory only, so a reload before the password is set asks for the link again. */
  function beginInvitation(token: string | null): void {
    if (token === null) return fail('broken-link')
    invitation = token
    state.phase = 'invitation'
  }

  /**
   * A browser with a live session would be past the first step, so the flow ignores the session. Once the password is set, the
   * flow moves into the URL like any other, and the steps the policy asks for next survive a reload.
   */
  async function acceptInvitation(password: string): Promise<void> {
    const token = invitation
    if (token === null) return
    await attempt(async () => {
      const id = invitationFlow ?? (await client.startFlow({ reauthenticate: true })).id
      invitationFlow = id
      const next = await client.acceptInvitation(id, token, password)
      invitation = null
      navigation.replace(flowUrl(next.id))
      await show(next)
    })
  }

  async function show(next: Flow): Promise<void> {
    if (!KNOWN_STEPS.has(next.step)) {
      if (!reload()) fail('unexpected')
      return
    }
    if (next.step === 'COMPLETE') return complete(next.id)
    const moved = state.phase !== 'step' || state.flow?.step !== next.step
    if (moved && release.newer && reload()) return
    state.flow = next
    state.phase = 'step'
    if (moved) clearProblem()
  }

  async function complete(id: string): Promise<void> {
    state.phase = 'leaving'
    const completed = await client.completeFlow(id)
    navigation.assign(completed.returnUrl ?? DIRECT_LOGIN_TARGET)
  }

  function enrolled(result: EnrolledFlow): void {
    afterCodes = { id: result.id, step: result.step, factors: result.factors }
    state.recoveryCodes = result.recoveryCodes
    state.phase = 'recovery-codes'
  }

  async function codesSaved(): Promise<void> {
    const next = afterCodes
    if (next === null) return
    afterCodes = null
    state.recoveryCodes = []
    await attempt(() => show(next))
  }

  async function submit(action: (id: string) => Promise<Flow>): Promise<void> {
    const id = state.flow?.id
    if (id === undefined) return
    await attempt(async () => show(await action(id)))
  }

  async function enroll(action: (id: string) => Promise<EnrolledFlow>): Promise<void> {
    const id = state.flow?.id
    if (id === undefined) return
    await attempt(async () => enrolled(await action(id)))
  }

  /** For a step that needs more from the server before the user can answer, such as a challenge. */
  async function fetchFor<T>(action: (id: string) => Promise<T>): Promise<T | null> {
    const id = state.flow?.id
    if (id === undefined) return null
    let result: T | null = null
    await attempt(async () => {
      result = await action(id)
    })
    return result
  }

  async function attempt(action: () => Promise<void>): Promise<void> {
    state.busy = true
    try {
      await action()
    } catch (error) {
      await recover(error)
    } finally {
      state.busy = false
    }
  }

  async function recover(error: unknown): Promise<void> {
    if (error instanceof ApiError && error.status === 409) {
      const id = state.flow?.id
      if (id === undefined) return fail('unexpected')
      try {
        return await show(await client.getFlow(id))
      } catch (again) {
        return again instanceof ApiError && again.status === 410 ? expire() : fail('unexpected')
      }
    }
    if (error instanceof ApiError && error.status === 410) {
      if (state.phase !== 'invitation') return expire()
      invitationFlow = null
      return problem('unexpected')
    }
    const trouble = troubleOf(error)
    problem(trouble.problem, trouble.retryAfter)
  }

  function problem(kind: Problem, retryAfter: number | null = null): void {
    if (state.phase === 'leaving' && state.flow !== null) state.phase = 'step'
    if (state.phase !== 'step' && state.phase !== 'recovery-codes' && state.phase !== 'invitation') state.phase = 'failed'
    state.problem = kind
    state.retryAfter = retryAfter
  }

  function clearProblem(): void {
    state.problem = null
    state.retryAfter = null
  }

  function expire(): void {
    state.phase = 'expired'
    state.flow = null
  }

  function fail(kind: Problem): void {
    state.phase = 'failed'
    state.problem = kind
  }

  function remember(login: string): void {
    state.login = login
  }

  return { state, client, begin, beginInvitation, acceptInvitation, submit, enroll, fetchFor, codesSaved, remember }
}

export type Login = ReturnType<typeof createLogin>

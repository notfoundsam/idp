import type { components } from './generated/auth'

type Schemas = components['schemas']

export type Flow = Schemas['Flow']
export type EnrolledFlow = Schemas['EnrolledFlow']
export type Step = Schemas['Step']
export type Factor = Schemas['Factor']
export type Completed = Schemas['Completed']
export type TotpEnrollment = Schemas['TotpEnrollment']
export type PasskeyLoginOptions = Schemas['PasskeyLoginOptions']
export type PasskeyLoginRequest = Schemas['PasskeyLoginRequest']
export type PasskeyEnrollmentOptions = Schemas['PasskeyEnrollmentOptions']
export type PasskeyEnrollmentRequest = Schemas['PasskeyEnrollmentRequest']
export type StartFlowRequest = Schemas['StartFlowRequest']

export const UPGRADE_REQUIRED = 426

const CSRF_COOKIE = '__Host-csrf'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly retryAfter: number | null,
  ) {
    super(`The API answered ${status}`)
    this.name = 'ApiError'
  }
}

export interface ClientOptions {
  version: string
  fetch?: typeof fetch
  cookies?: () => string
  onAppVersion?: (version: string) => void
  onUpgradeRequired?: () => void
}

function cookie(cookies: string, name: string): string | null {
  for (const pair of cookies.split(';')) {
    const separator = pair.indexOf('=')
    if (separator > 0 && pair.slice(0, separator).trim() === name) return pair.slice(separator + 1).trim()
  }
  return null
}

function retryAfterOf(response: Response): number | null {
  const header = response.headers.get('Retry-After')
  const seconds = header === null ? NaN : Number(header)
  return Number.isInteger(seconds) && seconds >= 0 ? seconds : null
}

export function createClient(options: ClientOptions) {
  const send = options.fetch ?? ((input, init) => globalThis.fetch(input, init))
  const cookies = options.cookies ?? (() => document.cookie)

  async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
    const headers = new Headers({ Accept: 'application/json', 'X-Client-Version': options.version })
    if (method !== 'GET') {
      const csrf = cookie(cookies(), CSRF_COOKIE)
      if (csrf !== null) headers.set('X-CSRF-Token', csrf)
    }
    if (body !== undefined) headers.set('Content-Type', 'application/json')
    const init: RequestInit = { method, headers, credentials: 'same-origin', cache: 'no-store' }
    if (body !== undefined) init.body = JSON.stringify(body)
    const response = await send(path, init)
    const appVersion = response.headers.get('X-App-Version')
    if (appVersion !== null) options.onAppVersion?.(appVersion)
    if (response.status === UPGRADE_REQUIRED) options.onUpgradeRequired?.()
    if (!response.ok) throw new ApiError(response.status, retryAfterOf(response))
    if (response.status === 202 || response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  const flow = (id: string, action = '') => `/api/flows/${encodeURIComponent(id)}${action}`

  return {
    startFlow: (request: StartFlowRequest = {}) => call<Flow>('POST', '/api/flows', request),
    getFlow: (id: string) => call<Flow>('GET', flow(id)),
    submitLogin: (id: string, login: string) => call<Flow>('POST', flow(id, '/login'), { login }),
    submitPassword: (id: string, password: string) => call<Flow>('POST', flow(id, '/password'), { password }),
    acceptInvitation: (id: string, token: string, password: string) =>
      call<Flow>('POST', flow(id, '/invitation'), { token, password }),
    submitTotp: (id: string, code: string) => call<Flow>('POST', flow(id, '/totp'), { code }),
    submitRecoveryCode: (id: string, code: string) => call<Flow>('POST', flow(id, '/recovery-code'), { code }),
    beginPasskeyLogin: (id: string) => call<PasskeyLoginOptions>('POST', flow(id, '/passkey/options')),
    submitPasskey: (id: string, answer: PasskeyLoginRequest) => call<Flow>('POST', flow(id, '/passkey'), answer),
    beginTotpEnrollment: (id: string) => call<TotpEnrollment>('POST', flow(id, '/enrollment/totp')),
    confirmTotpEnrollment: (id: string, code: string) =>
      call<EnrolledFlow>('POST', flow(id, '/enrollment/totp/confirmation'), { code }),
    beginPasskeyEnrollment: (id: string) => call<PasskeyEnrollmentOptions>('POST', flow(id, '/enrollment/passkey/options')),
    finishPasskeyEnrollment: (id: string, answer: PasskeyEnrollmentRequest) =>
      call<EnrolledFlow>('POST', flow(id, '/enrollment/passkey'), answer),
    completeFlow: (id: string) => call<Completed>('POST', flow(id, '/complete')),
    requestPasswordReset: (login: string) => call<undefined>('POST', '/api/password-reset', { login }),
    confirmPasswordReset: (token: string, password: string) =>
      call<undefined>('POST', '/api/password-reset/confirmation', { token, password }),
    confirmEmailChange: (token: string) => call<undefined>('POST', '/api/email-change/confirmation', { token }),
  }
}

export type Client = ReturnType<typeof createClient>

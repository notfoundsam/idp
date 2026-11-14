import { ApiError, createTransport, type ClientOptions } from './client'
import type { components } from './generated/account'

type Schemas = components['schemas']

export type Factors = Schemas['Factors']
export type Passkey = Schemas['Passkey']
export type Sessions = Schemas['Sessions']
export type Session = Schemas['Session']
export type TotpSetup = Schemas['TotpSetup']
export type RecoveryCodes = Schemas['RecoveryCodes']
export type PasskeySetupOptions = Schemas['PasskeySetupOptions']
export type PasskeyRegistrationRequest = Schemas['PasskeyRegistrationRequest']
export type PasswordChangeRequest = Schemas['PasswordChangeRequest']

export const REAUTHENTICATION_REQUIRED: Schemas['ReauthenticationRequired']['error'] = 'reauthentication_required'

/** The login is older than a factor change allows, so the user has to sign in again before it. */
export function needsReauthentication(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403 && error.error === REAUTHENTICATION_REQUIRED
}

export function createAccountClient(options: ClientOptions) {
  const call = createTransport(options)
  const passkey = (id: string) => `/api/account/passkeys/${encodeURIComponent(id)}`
  const session = (id: string) => `/api/account/sessions/${encodeURIComponent(id)}`

  return {
    getFactors: () => call<Factors>('GET', '/api/account/factors'),
    changePassword: (request: PasswordChangeRequest) => call<undefined>('POST', '/api/account/password', request),
    beginTotp: () => call<TotpSetup>('POST', '/api/account/totp'),
    confirmTotp: (code: string) => call<RecoveryCodes>('POST', '/api/account/totp/confirmation', { code }),
    removeTotp: () => call<undefined>('DELETE', '/api/account/totp'),
    beginPasskey: () => call<PasskeySetupOptions>('POST', '/api/account/passkeys/options'),
    addPasskey: (answer: PasskeyRegistrationRequest) => call<RecoveryCodes>('POST', '/api/account/passkeys', answer),
    removePasskey: (id: string) => call<undefined>('DELETE', passkey(id)),
    renewRecoveryCodes: () => call<RecoveryCodes>('POST', '/api/account/recovery-codes'),
    getSessions: () => call<Sessions>('GET', '/api/account/sessions'),
    endOtherSessions: () => call<undefined>('DELETE', '/api/account/sessions'),
    endSession: (id: string) => call<undefined>('DELETE', session(id)),
    logout: () => call<undefined>('POST', '/api/account/logout'),
  }
}

export type AccountClient = ReturnType<typeof createAccountClient>

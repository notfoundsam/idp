import { createTransport, type ClientOptions } from './client'
import type { components } from './generated/admin'

type Schemas = components['schemas']

export type Role = Schemas['Role']
export type UserStatus = Schemas['UserStatus']
export type FoundUser = Schemas['FoundUser']
export type UserCard = Schemas['UserCard']
export type UserEvent = Schemas['UserEvent']
export type AssignedServiceProvider = Schemas['AssignedServiceProvider']
export type InvitationRequest = Schemas['InvitationRequest']
export type InvitedUser = Schemas['InvitedUser']
export type Policy = Schemas['Policy']

export const PRECONDITION_FAILED = 412

/** The policy as read, with the version a save sends back in `If-Match`. */
export interface VersionedPolicy {
  policy: Policy
  version: string
}

export function createAdminClient(options: ClientOptions) {
  const call = createTransport(options)
  const user = (id: string, action = '') => `/api/admin/users/${encodeURIComponent(id)}${action}`

  return {
    searchUsers: async (email: string) => (await call<Schemas['UserSearch']>('POST', '/api/admin/users/search', { email })).users,
    inviteUser: (request: InvitationRequest) => call<InvitedUser>('POST', '/api/admin/users', request),
    getUser: (id: string) => call<UserCard>('GET', user(id)),
    resendInvitation: (id: string) => call<undefined>('POST', user(id, '/invitation')),
    changeRole: (id: string, role: Role) => call<undefined>('PUT', user(id, '/role'), { role }),
    blockUser: (id: string) => call<undefined>('POST', user(id, '/block')),
    unblockUser: (id: string) => call<undefined>('POST', user(id, '/unblock')),
    resetMfa: (id: string) => call<undefined>('POST', user(id, '/mfa-reset')),
    eraseUser: (id: string) => call<undefined>('DELETE', user(id)),
    endUserSessions: (id: string) => call<undefined>('DELETE', user(id, '/sessions')),
    endAllSessions: () => call<undefined>('DELETE', '/api/admin/sessions'),
    getPolicy: async (): Promise<VersionedPolicy> => {
      const { body, etag } = await call.exchange<Policy>('GET', '/api/admin/policy')
      if (etag === null) throw new Error('The policy came without its version')
      return { policy: body, version: etag }
    },
    /** Answers the new version, or `412` when someone saved the policy since [version] was read. */
    savePolicy: async (policy: Policy, version: string): Promise<string | null> =>
      (await call.exchange<undefined>('PUT', '/api/admin/policy', policy, { 'If-Match': version })).etag,
  }
}

export type AdminClient = ReturnType<typeof createAdminClient>

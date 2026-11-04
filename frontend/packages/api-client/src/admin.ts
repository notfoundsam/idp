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
export type ServiceProviderSummary = Schemas['ServiceProviderSummary']
export type ServiceProvider = Schemas['ServiceProvider']
export type SpSettings = Schemas['SpSettings']
export type SamlAttribute = SpSettings['attributes'][number]
export type AssignedUser = Schemas['AssignedUser']
export type SigningKey = Schemas['SigningKey']
export type AuditTargetType = Schemas['AuditTargetType']
export type AuditEntry = Schemas['AuditEntry']
export type AuditCursor = Schemas['AuditCursor']
export type AuditJournal = Schemas['AuditJournal']

export const PRECONDITION_FAILED = 412

/** The policy as read, with the version a save sends back in `If-Match`. */
export interface VersionedPolicy {
  policy: Policy
  version: string
}

/** A Service Provider as read, with the version of its settings a save sends back in `If-Match`. */
export interface VersionedServiceProvider {
  serviceProvider: ServiceProvider
  version: string
}

export interface AuditTarget {
  type: AuditTargetType
  id: string
}

/** A page of the journal: the first one without [after], the next one from the `next` of the page before. */
export interface JournalQuery {
  limit?: number
  after?: AuditCursor
  target?: AuditTarget
}

function journalPath({ limit, after, target }: JournalQuery): string {
  const query = new URLSearchParams()
  if (limit !== undefined) query.set('limit', String(limit))
  if (after !== undefined) {
    query.set('before', after.before)
    query.set('beforeId', after.beforeId)
  }
  if (target !== undefined) {
    query.set('targetType', target.type)
    query.set('targetId', target.id)
  }
  const search = query.toString()
  return search === '' ? '/api/admin/audit' : `/api/admin/audit?${search}`
}

export function createAdminClient(options: ClientOptions) {
  const call = createTransport(options)
  const user = (id: string, action = '') => `/api/admin/users/${encodeURIComponent(id)}${action}`
  const sp = (id: string, action = '') => `/api/admin/service-providers/${encodeURIComponent(id)}${action}`

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
    listServiceProviders: async () =>
      (await call<Schemas['ServiceProviderList']>('GET', '/api/admin/service-providers')).serviceProviders,
    /** Sends only the content of [metadata] under a fixed file name, since the server never reads the one the browser has. */
    registerServiceProvider: async (name: string, metadata: Blob) => {
      const form = new FormData()
      form.append('name', name)
      form.append('metadata', metadata, 'metadata.xml')
      return (await call<Schemas['Created']>('POST', '/api/admin/service-providers', form)).id
    },
    getServiceProvider: async (id: string): Promise<VersionedServiceProvider> => {
      const { body, etag } = await call.exchange<ServiceProvider>('GET', sp(id))
      if (etag === null) throw new Error('The Service Provider came without the version of its settings')
      return { serviceProvider: body, version: etag }
    },
    /** Answers the new version, or `412` when someone saved the settings since [version] was read. */
    saveServiceProviderSettings: async (id: string, settings: SpSettings, version: string): Promise<string | null> =>
      (await call.exchange<undefined>('PUT', sp(id, '/settings'), settings, { 'If-Match': version })).etag,
    removeServiceProvider: (id: string) => call<undefined>('DELETE', sp(id)),
    listAssignedUsers: async (id: string) => (await call<Schemas['AssignedUsers']>('GET', sp(id, '/users'))).users,
    assignUsers: (id: string, userIds: string[]) => call<undefined>('POST', sp(id, '/users'), { userIds }),
    unassignUsers: (id: string, userIds: string[]) => call<undefined>('POST', sp(id, '/users/removal'), { userIds }),
    listSigningKeys: async () => (await call<Schemas['SigningKeys']>('GET', '/api/admin/signing-keys')).keys,
    startKeyRotation: async () => (await call<Schemas['Created']>('POST', '/api/admin/signing-keys/rotation')).id,
    completeKeyRotation: () => call<undefined>('POST', '/api/admin/signing-keys/rotation/completion'),
    readAuditJournal: (query: JournalQuery = {}) => call<AuditJournal>('GET', journalPath(query)),
  }
}

export type AdminClient = ReturnType<typeof createAdminClient>

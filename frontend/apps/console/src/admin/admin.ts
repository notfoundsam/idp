import {
  ApiError,
  type AuditCursor,
  type AuditTarget,
  type AuditTargetType,
  type Role,
  type SamlAttribute,
  type SpSettings,
  type UserEvent,
  type UserStatus,
} from '@idp/api-client'
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query'
import type { Ref } from 'vue'
import { useConsole } from '../context'

export const ROLES: readonly Role[] = ['USER', 'TENANT_ADMIN']

export const USERS = ['admin', 'users'] as const
export const POLICY = ['admin', 'policy'] as const
export const SERVICE_PROVIDERS = ['admin', 'service-providers'] as const
export const SIGNING_KEYS = ['admin', 'signing-keys'] as const
export const AUDIT = ['admin', 'audit'] as const

/** The API takes at most this many users in one assignment or revocation. */
export const MAX_USERS_PER_CALL = 500

/** The importer refuses a larger file, so the page refuses it before sending it. */
export const MAX_METADATA_BYTES = 128 * 1024

export function roleOf(role: Role): string {
  return role === 'TENANT_ADMIN' ? 'Admin' : 'User'
}

export function statusOf(status: UserStatus): string {
  switch (status) {
    case 'INVITED':
      return 'Invited, has not accepted yet'
    case 'ACTIVE':
      return 'Active'
    case 'BLOCKED':
      return 'Blocked'
  }
}

/** The card names the user who acted only by ID, so the user themselves is told apart from someone else. */
export function actorOf(event: Pick<UserEvent, 'actorType' | 'actorId'>, user: string | null = null): string {
  switch (event.actorType) {
    case 'SYSTEM':
      return 'the system'
    case 'ANONYMOUS':
      return 'someone not signed in'
    case 'USER':
      return event.actorId === user ? 'the user' : `the user ${event.actorId ?? 'unknown'}`
  }
}

export const NAME_ID_FORMATS: readonly SpSettings['nameIdFormat'][] = ['PERSISTENT', 'EMAIL']
export const SIGNED_PARTS: readonly SpSettings['signedPart'][] = ['ASSERTION', 'RESPONSE', 'BOTH']
export const SIGNATURE_ALGORITHMS: readonly SpSettings['signatureAlgorithm'][] = ['RSA_PSS_SHA256', 'RSA_SHA256']
export const ATTRIBUTES: readonly SamlAttribute[] = ['EMAIL', 'DISPLAY_NAME', 'PHONE']
export const TARGET_TYPES: readonly AuditTargetType[] = ['USER', 'SERVICE_PROVIDER', 'SIGNING_KEY', 'TENANT']

export function nameIdFormatOf(format: SpSettings['nameIdFormat']): string {
  return format === 'PERSISTENT' ? 'Persistent, means nothing outside this service' : 'Email address'
}

export function signedPartOf(part: SpSettings['signedPart']): string {
  switch (part) {
    case 'ASSERTION':
      return 'The assertion'
    case 'RESPONSE':
      return 'The whole response'
    case 'BOTH':
      return 'Both'
  }
}

export function signatureAlgorithmOf(algorithm: SpSettings['signatureAlgorithm']): string {
  return algorithm === 'RSA_PSS_SHA256' ? 'RSA-PSS with SHA-256' : 'RSA with SHA-256, only for a service that verifies nothing else'
}

export function attributeOf(attribute: SamlAttribute): string {
  switch (attribute) {
    case 'EMAIL':
      return 'Email address'
    case 'DISPLAY_NAME':
      return 'Display name'
    case 'PHONE':
      return 'Phone number'
  }
}

export function targetTypeOf(type: AuditTargetType): string {
  switch (type) {
    case 'USER':
      return 'User'
    case 'SERVICE_PROVIDER':
      return 'Service'
    case 'SIGNING_KEY':
      return 'Signing key'
    case 'TENANT':
      return 'Organisation'
  }
}

/** A target in the query of the journal page, which a link from a card or a record sets. Half of one is no target. */
export function targetOf(type: unknown, id: unknown): AuditTarget | undefined {
  if (typeof id !== 'string' || id === '' || !TARGET_TYPES.includes(type as AuditTargetType)) return undefined
  return { type: type as AuditTargetType, id }
}

export const journalOf = (target: AuditTarget) => ({ name: 'audit', query: { targetType: target.type, targetId: target.id } })

export const hasStatus = (error: unknown, status: number) => error instanceof ApiError && error.status === status

export const LAST_ADMIN = 'This is the last admin who can act, and the organisation would be left without one.'
export const INVITATION_REFUSED = 'The address is not valid, or a user of your organisation already has it.'
export const ALREADY_ACCEPTED = 'The user has already accepted an invitation.'
export const BLOCK_REFUSED = 'The user is not active, or is the last admin who can act.'
export const UNBLOCK_REFUSED = 'The user is not blocked.'
export const TIMEOUTS_REFUSED =
  'The idle timeout is from 5 to 60 minutes, and the session lifetime from the idle timeout to 1440 minutes, that is 24 hours.'
export const METADATA_REFUSED =
  'The file is not valid SAML metadata of one service with https endpoints, or the name is empty or over 200 characters.'
export const METADATA_TOO_LARGE = 'The file is over 128 KiB, the largest metadata accepted.'
export const ENTITY_ID_TAKEN = 'A service with the EntityID of this file is connected already.'
export const USER_GONE = 'A user of the selection no longer exists, so nobody was assigned. Leave them out and try again.'
export const ROTATION_IN_PROGRESS = 'A rotation is in progress already. The page now shows it.'
export const NO_ROTATION = 'No rotation is in progress. The page now shows the keys as they are.'

export function useUserCard(id: Ref<string>) {
  const { admin } = useConsole()
  return useQuery({ queryKey: [...USERS, id] as const, queryFn: () => admin.getUser(id.value) })
}

/**
 * Read once per visit: a form filled in keeps the version it was read with, so a save over a change made meanwhile is refused with
 * `412` instead of undoing it.
 */
export function usePolicy() {
  const { admin } = useConsole()
  return useQuery({ queryKey: POLICY, queryFn: () => admin.getPolicy(), refetchOnWindowFocus: false })
}

export function useServiceProviders() {
  const { admin } = useConsole()
  return useQuery({ queryKey: SERVICE_PROVIDERS, queryFn: () => admin.listServiceProviders() })
}

/** Read once per visit, like the policy: the settings form keeps the version it was read with. */
export function useServiceProvider(id: Ref<string>) {
  const { admin } = useConsole()
  return useQuery({
    queryKey: [...SERVICE_PROVIDERS, id] as const,
    queryFn: () => admin.getServiceProvider(id.value),
    refetchOnWindowFocus: false,
  })
}

export function useAssignedUsers(id: Ref<string>) {
  const { admin } = useConsole()
  return useQuery({ queryKey: [...SERVICE_PROVIDERS, id, 'users'] as const, queryFn: () => admin.listAssignedUsers(id.value) })
}

export function useSigningKeys() {
  const { admin } = useConsole()
  return useQuery({ queryKey: SIGNING_KEYS, queryFn: () => admin.listSigningKeys() })
}

/** Pages follow the cursor of the page before, so a record written meanwhile neither repeats nor slips between two pages. */
export function useJournal(target: Ref<AuditTarget | undefined>) {
  const { admin } = useConsole()
  return useInfiniteQuery({
    queryKey: [...AUDIT, target] as const,
    queryFn: ({ pageParam }) => admin.readAuditJournal({ after: pageParam, target: target.value }),
    initialPageParam: undefined as AuditCursor | undefined,
    getNextPageParam: (page) => page.next ?? undefined,
    refetchOnWindowFocus: false,
  })
}

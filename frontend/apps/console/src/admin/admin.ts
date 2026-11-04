import { ApiError, PRECONDITION_FAILED, type Role, type UserEvent, type UserStatus } from '@idp/api-client'
import { useQuery } from '@tanstack/vue-query'
import type { Ref } from 'vue'
import { useConsole } from '../context'

export const ROLES: readonly Role[] = ['USER', 'TENANT_ADMIN']

export const USERS = ['admin', 'users'] as const
export const POLICY = ['admin', 'policy'] as const

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
export function actorOf(event: UserEvent, user: string): string {
  switch (event.actorType) {
    case 'SYSTEM':
      return 'the system'
    case 'ANONYMOUS':
      return 'someone not signed in'
    case 'USER':
      return event.actorId === user ? 'the user' : `the user ${event.actorId ?? 'unknown'}`
  }
}

export const isMissing = (error: unknown) => error instanceof ApiError && error.status === 404

export const isConflict = (error: unknown) => error instanceof ApiError && error.status === PRECONDITION_FAILED

export const LAST_ADMIN = 'This is the last admin who can act, and the organisation would be left without one.'
export const INVITATION_REFUSED = 'The address is not valid, or a user of your organisation already has it.'
export const ALREADY_ACCEPTED = 'The user has already accepted an invitation.'
export const BLOCK_REFUSED = 'The user is not active, or is the last admin who can act.'
export const UNBLOCK_REFUSED = 'The user is not blocked.'
export const TIMEOUTS_REFUSED =
  'The idle timeout is from 5 to 60 minutes, and the session lifetime from the idle timeout to 1440 minutes, that is 24 hours.'

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

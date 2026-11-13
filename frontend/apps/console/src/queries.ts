import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useConsole } from './context'

export const FACTORS = ['account', 'factors'] as const
export const SESSIONS = ['account', 'sessions'] as const

export function useFactors() {
  const { client } = useConsole()
  return useQuery({ queryKey: FACTORS, queryFn: () => client.getFactors() })
}

/** Also the source of the email, which the header and a new TOTP show, and of the role. */
export function useSessions() {
  const { client } = useConsole()
  return useQuery({ queryKey: SESSIONS, queryFn: () => client.getSessions() })
}

/** Only hides the admin pages from everyone else: the admin API checks the role on every call. */
export function useIsAdmin() {
  const sessions = useSessions()
  return computed(() => sessions.data.value?.role === 'TENANT_ADMIN')
}

type QueryKeys = readonly (readonly string[])[]

/** A change whose success makes the listed queries stale, so the page reads them anew. A getter names them as they are then. */
export function useChange<V, R>(change: (variables: V) => Promise<R>, stale: QueryKeys | (() => QueryKeys), onSuccess?: (result: R) => void) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: change,
    onSuccess: async (result: R) => {
      onSuccess?.(result)
      const keys = typeof stale === 'function' ? stale() : stale
      await Promise.all(keys.map((queryKey) => queries.invalidateQueries({ queryKey })))
    },
  })
}

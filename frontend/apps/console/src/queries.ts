import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useConsole } from './context'

export const FACTORS = ['account', 'factors'] as const
export const SESSIONS = ['account', 'sessions'] as const

export function useFactors() {
  const { client } = useConsole()
  return useQuery({ queryKey: FACTORS, queryFn: () => client.getFactors() })
}

/** Also the source of the email, which the header and a new TOTP show. */
export function useSessions() {
  const { client } = useConsole()
  return useQuery({ queryKey: SESSIONS, queryFn: () => client.getSessions() })
}

/** A change whose success makes the listed queries stale, so the page reads them anew. */
export function useChange<V, R>(change: (variables: V) => Promise<R>, stale: readonly (readonly string[])[], onSuccess?: (result: R) => void) {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: change,
    onSuccess: async (result: R) => {
      onSuccess?.(result)
      await Promise.all(stale.map((queryKey) => queries.invalidateQueries({ queryKey })))
    },
  })
}

import type { AccountClient, AdminClient, Release } from '@idp/api-client'
import { inject, type InjectionKey } from 'vue'

export const LOGIN_PATH = '/login/'
export const REAUTHENTICATION_PATH = '/login/?reauthenticate'

/** What the console needs from the window, so a test can stand in for it. */
export interface Browser {
  host: string
  credentials: CredentialsContainer | null
  assign(url: string): void
  reload(): void
}

export interface Console {
  client: AccountClient
  admin: AdminClient
  release: Release
  browser: Browser
  resetStores: () => void
}

export const CONSOLE: InjectionKey<Console> = Symbol('console')

export function useConsole(): Console {
  const context = inject(CONSOLE)
  if (context === undefined) throw new Error('The console is not provided')
  return context
}

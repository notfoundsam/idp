import type { Page } from '@playwright/test'

export interface Authenticator {
  /** The passkeys it holds, each with the host it belongs to. */
  passkeys(): Promise<{ rpId: string; discoverable: boolean }[]>
}

/**
 * A platform authenticator with user verification, as in a laptop or a phone. It answers without a prompt and lives as long as the
 * page, across navigations to other hosts.
 */
export async function virtualAuthenticator(page: Page): Promise<Authenticator> {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('WebAuthn.enable')
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  })
  return {
    passkeys: async () =>
      (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials.map((it) => ({
        rpId: it.rpId ?? '',
        discoverable: it.isResidentCredential,
      })),
  }
}

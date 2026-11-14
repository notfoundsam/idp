import { defineConfig, devices } from '@playwright/test'
import { createHash, X509Certificate } from 'node:crypto'
import { readFileSync } from 'node:fs'

const certificates = process.env.CERTS_DIR ?? '../../dev-tools/certs'

/**
 * Chromium on Linux keeps its trust in an NSS database, and the image has no tool to add the mkcert CA there. Trusting the key of
 * the certificate Traefik serves gives the same: no certificate error, so the pages are a secure context and passkeys work.
 */
const served = new X509Certificate(readFileSync(`${certificates}/idp.test.pem`))
const spki = createHash('sha256').update(served.publicKey.export({ type: 'spki', format: 'der' })).digest('base64')

/**
 * Traefik serves every tenant host, but the hosts file of the container names only the fixed ones. So Chromium resolves any host of
 * `idp.test` as `acme.idp.test`, and a tenant that a test creates under a random name is reachable too. Requests of Node still need
 * a host of the hosts file.
 */
const tenantHosts = 'MAP *.idp.test acme.idp.test'

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  reporter: [['list'], ['html', { open: 'never' }]],
  timeout: 90_000,
  expect: { timeout: 10_000 },
  use: {
    trace: 'retain-on-failure',
    launchOptions: { args: [`--ignore-certificate-errors-spki-list=${spki}`, `--host-resolver-rules=${tenantHosts}`] },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})

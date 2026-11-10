import { expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'

export interface Answer {
  status: number
  body: string
}

/** Through the API with the session of the page, for what a test sets up or leaves as it found it. */
export async function api(page: Page, method: string, path: string, body?: unknown): Promise<Answer> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const csrf = document.cookie
        .split('; ')
        .find((it) => it.startsWith('__Host-csrf='))
        ?.slice('__Host-csrf='.length)
      const headers = new Headers({ Accept: 'application/json', 'X-CSRF-Token': csrf ?? '' })
      if (body !== undefined) headers.set('Content-Type', 'application/json')
      const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
      return { status: response.status, body: await response.text() }
    },
    { method, path, body },
  )
}

/** A service of its own per test and run, so nothing an earlier run left can clash with its EntityID. */
export function metadataOf(entityId: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="${entityId}">
  <md:SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
    <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="${entityId}/acs" index="0" isDefault="true"/>
    <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="${entityId}/acs2" index="1"/>
  </md:SPSSODescriptor>
</md:EntityDescriptor>`
}

export const uniqueSp = () => {
  const suffix = randomBytes(6).toString('hex')
  return { name: `e2e ${suffix}`, entityId: `https://e2e-${suffix}.example/saml` }
}

export async function registerSp(page: Page, name: string, entityId: string): Promise<string> {
  const answer = await page.evaluate(
    async ({ name, metadata }) => {
      const csrf = document.cookie
        .split('; ')
        .find((it) => it.startsWith('__Host-csrf='))
        ?.slice('__Host-csrf='.length)
      const form = new FormData()
      form.append('name', name)
      form.append('metadata', new Blob([metadata], { type: 'text/xml' }), 'metadata.xml')
      const response = await fetch('/api/admin/service-providers', { method: 'POST', headers: { 'X-CSRF-Token': csrf ?? '' }, body: form })
      return { status: response.status, body: await response.text() }
    },
    { name, metadata: metadataOf(entityId) },
  )
  expect(answer.status).toBe(201)
  return (JSON.parse(answer.body) as { id: string }).id
}

/** The ID of the user with [email], which only the whole address finds. */
export async function userIdOf(page: Page, email: string): Promise<string | undefined> {
  const answer = await api(page, 'POST', '/api/admin/users/search', { email })
  expect(answer.status).toBe(200)
  return (JSON.parse(answer.body) as { users: { id: string }[] }).users[0]?.id
}

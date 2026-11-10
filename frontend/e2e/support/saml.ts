import type { Page, Request } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { deflateRawSync } from 'node:zlib'

/** An SP of the test alone: its ACS lives in a Playwright route, so it needs neither a host nor a key. */
export interface TestSp {
  entityId: string
  acs: string
}

/** Answers the post to the ACS, so the browser lands there and the test reads the Response from the request. */
export async function serveAcs(page: Page, sp: TestSp) {
  await page.route(sp.acs, (route) => route.fulfill({ status: 200, contentType: 'text/plain', body: 'received' }))
}

/** An unsigned AuthnRequest by HTTP-Redirect, which an SP registered without `AuthnRequestsSigned` may send. */
export function authnRequest(origin: string, sp: TestSp): { id: string; url: string } {
  const id = `_${randomBytes(16).toString('hex')}`
  const xml =
    `<samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ` +
    `ID="${id}" Version="2.0" IssueInstant="${new Date().toISOString()}" Destination="${origin}/saml/sso" ` +
    `AssertionConsumerServiceURL="${sp.acs}" ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST">` +
    `<saml:Issuer>${sp.entityId}</saml:Issuer>` +
    '</samlp:AuthnRequest>'
  const encoded = encodeURIComponent(deflateRawSync(Buffer.from(xml, 'utf8')).toString('base64'))
  return { id, url: `${origin}/saml/sso?SAMLRequest=${encoded}` }
}

/** The XML of the SAMLResponse that the browser posted to the ACS. */
export function samlResponseOf(posted: Request): string {
  const response = new URLSearchParams(posted.postData() ?? '').get('SAMLResponse')
  if (response === null) throw new Error(`The post to ${posted.url()} carries no SAMLResponse`)
  return Buffer.from(response, 'base64').toString('utf8')
}

// A fake of the IdP for the smoke test: it publishes metadata with a certificate made here, takes the AuthnRequest, walks the flow
// API and signs a Response with xml-crypto as OpenSAML lays it out. Every option breaks one thing that the smoke test has to notice.
import { generateKeyPairSync, randomUUID, sign, X509Certificate } from 'node:crypto'
import { inflateRawSync } from 'node:zlib'
import { SignedXml } from 'xml-crypto'
import { DS, ENVELOPED, EXC_C14N, MD, REDIRECT, REFEDS_MFA, SAML, SAMLP } from './saml.mjs'
import { totp } from './smoke-sso.mjs'

export const PSS = 'http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1'
export const PKCS1 = 'http://www.w3.org/2001/04/xmldsig-more#rsa-sha256'
const SHA256 = 'http://www.w3.org/2001/04/xmlenc#sha256'

const length = (size) => {
  if (size < 0x80) return Buffer.from([size])
  const bytes = []
  for (let rest = size; rest > 0; rest >>= 8) bytes.unshift(rest & 0xff)
  return Buffer.from([0x80 | bytes.length, ...bytes])
}
const der = (tag, ...parts) => {
  const body = Buffer.concat(parts)
  return Buffer.concat([Buffer.from([tag]), length(body.length), body])
}
const sequence = (...parts) => der(0x30, ...parts)
const utcTime = (text) => der(0x17, Buffer.from(text))

// A self-signed certificate in DER, since Node reads certificates but makes none.
export function signingKey(name = 'idp.test') {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const algorithm = sequence(Buffer.from('06092a864886f70d01010b0500', 'hex'))
  const subject = sequence(der(0x31, sequence(Buffer.from('0603550403', 'hex'), der(0x0c, Buffer.from(name)))))
  const tbs = sequence(
    der(0xa0, der(0x02, Buffer.from([2]))),
    der(0x02, Buffer.from([1])),
    algorithm,
    subject,
    sequence(utcTime('200101000000Z'), utcTime('491231235959Z')),
    subject,
    publicKey.export({ type: 'spki', format: 'der' }),
  )
  const certificate = new X509Certificate(sequence(tbs, algorithm, der(0x03, Buffer.from([0]), sign('sha256', tbs, privateKey))))
  return { privateKey, certificate }
}

export function metadataOf(entityId, sso, certificates) {
  const keys = certificates
    .map((it) => `<md:KeyDescriptor use="signing"><ds:KeyInfo xmlns:ds="${DS}"><ds:X509Data><ds:X509Certificate>${it.raw.toString('base64')}</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>`)
    .join('')
  return (
    `<?xml version="1.0" encoding="UTF-8"?><md:EntityDescriptor xmlns:md="${MD}" entityID="${entityId}">` +
    `<md:IDPSSODescriptor protocolSupportEnumeration="${SAMLP}">${keys}` +
    `<md:SingleSignOnService Binding="${REDIRECT}" Location="${sso}"/></md:IDPSSODescriptor></md:EntityDescriptor>`
  )
}

// The signature goes after the Issuer of the signed element, as SAML core 5.4.1 wants it. The inner one first, as in OpenSAML.
function signatureOver(xml, element, privateKey, algorithm) {
  const signer = new SignedXml({ privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }), signatureAlgorithm: algorithm, canonicalizationAlgorithm: EXC_C14N })
  signer.addReference({ xpath: element, transforms: [ENVELOPED, EXC_C14N], digestAlgorithm: SHA256 })
  signer.computeSignature(xml, { prefix: 'ds', location: { reference: `${element}/*[local-name(.)='Issuer']`, action: 'after' } })
  return signer.getSignedXml()
}

// A Response as ResponseBuilder writes it. [signed] names the parts that carry a signature, as SpSettings.signedPart does.
export function signedResponse({ key, issuer, sp, requestId, at, signed = ['assertion'], algorithm = PSS, ...fields }) {
  const iso = (offset) => new Date(at + offset).toISOString()
  const values = {
    destination: sp.acs,
    inResponseTo: requestId,
    recipient: sp.acs,
    audience: sp.entityId,
    contextClass: REFEDS_MFA,
    status: 'urn:oasis:names:tc:SAML:2.0:status:Success',
    assertionIssuer: issuer,
    notOnOrAfter: iso(300_000),
    ...fields,
  }
  const assertionId = `_${randomUUID().replace(/-/g, '')}`
  const responseId = `_${randomUUID().replace(/-/g, '')}`
  let xml =
    `<?xml version="1.0" encoding="UTF-8"?><saml2p:Response xmlns:saml2p="${SAMLP}" Destination="${values.destination}" ID="${responseId}" ` +
    `InResponseTo="${values.inResponseTo}" IssueInstant="${iso(0)}" Version="2.0"><saml2:Issuer xmlns:saml2="${SAML}">${issuer}</saml2:Issuer>` +
    `<saml2p:Status><saml2p:StatusCode Value="${values.status}"/></saml2p:Status>` +
    `<saml2:Assertion xmlns:saml2="${SAML}" ID="${assertionId}" IssueInstant="${iso(0)}" Version="2.0"><saml2:Issuer>${values.assertionIssuer}</saml2:Issuer>` +
    `<saml2:Subject><saml2:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:persistent">name-id-of-the-user</saml2:NameID>` +
    `<saml2:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer"><saml2:SubjectConfirmationData InResponseTo="${values.inResponseTo}" ` +
    `NotOnOrAfter="${values.notOnOrAfter}" Recipient="${values.recipient}"/></saml2:SubjectConfirmation></saml2:Subject>` +
    `<saml2:Conditions NotBefore="${iso(0)}" NotOnOrAfter="${values.notOnOrAfter}"><saml2:AudienceRestriction><saml2:Audience>${values.audience}</saml2:Audience>` +
    `</saml2:AudienceRestriction></saml2:Conditions><saml2:AuthnStatement AuthnInstant="${iso(0)}"><saml2:AuthnContext>` +
    `<saml2:AuthnContextClassRef>${values.contextClass}</saml2:AuthnContextClassRef></saml2:AuthnContext></saml2:AuthnStatement></saml2:Assertion></saml2p:Response>`
  if (signed.includes('assertion')) xml = signatureOver(xml, "/*/*[local-name(.)='Assertion']", key.privateKey, algorithm)
  if (signed.includes('response')) xml = signatureOver(xml, '/*', key.privateKey, algorithm)
  return xml
}

const escapeHtml = (value) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const reply = (status, body = '', headers = {}) => {
  const all = new Headers({ 'X-App-Version': headers.version ?? '2026.11.10.1' })
  for (const [name, value] of Object.entries(headers)) {
    if (name === 'version') continue
    for (const one of [value].flat()) all.append(name, one)
  }
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers: all })
}

// [response] overrides fields of the signed Response, [tamper] changes its XML after signing.
export function fakeIdp({ host, sp, user, now, key = signingKey(), metadataKeys, version, spentCode, response = {}, tamper = (it) => it, accept = true, locked = false }) {
  const origin = `https://${host}`
  const issuer = `${origin}/saml/metadata`
  const state = { requests: [], totps: [], calls: [] }
  const flow = randomUUID()
  const sso = randomUUID()
  const resume = randomUUID()
  const spent = new Set(spentCode === undefined ? [] : [spentCode])
  let step = 'IDENTIFY'
  const fetch = async (url, init = {}) => {
    const target = new URL(url)
    const path = target.pathname
    const method = init.method ?? 'GET'
    const cookie = init.headers?.Cookie ?? ''
    const body = init.body === undefined ? undefined : JSON.parse(init.body)
    state.calls.push(`${method} ${path}`)
    const json = (status, value, headers = {}) => reply(status, value, { 'Content-Type': 'application/json', version, ...headers })
    if (target.origin !== origin) throw new Error(`The fake IdP has no ${target.origin}`)
    if (method === 'GET' && path === '/saml/metadata') return reply(200, metadataOf(issuer, `${origin}/saml/sso`, (metadataKeys ?? [key]).map((it) => it.certificate)), { version })
    if (method === 'GET' && path === '/saml/sso') {
      const xml = inflateRawSync(Buffer.from(target.searchParams.get('SAMLRequest'), 'base64')).toString('utf8')
      const id = /<samlp:AuthnRequest [^>]*ID="([^"]+)"/.exec(xml)?.[1]
      const issuer = /<saml:Issuer>([^<]+)<\/saml:Issuer>/.exec(xml)?.[1]
      state.requests.push({ xml, id, relayState: target.searchParams.get('RelayState'), issuer })
      if (!accept) return reply(400, '<p>The sign-in request could not be accepted.</p>', { version })
      return reply(200, `<meta http-equiv="refresh" content="0;url=/saml/sso/${sso}">`, { version })
    }
    if (method === 'GET' && path === `/saml/sso/${sso}`) {
      return reply(303, '', { Location: `/login/?flow=${flow}`, 'Set-Cookie': '__Host-flow=browser; Path=/; Secure; HttpOnly', version })
    }
    if (!cookie.includes('__Host-flow=browser')) return json(410, {})
    if (method === 'POST' && path === `/api/flows/${flow}/login` && step === 'IDENTIFY') {
      step = 'PASSWORD'
      return json(200, { id: flow, step, factors: [] })
    }
    if (method === 'POST' && path === `/api/flows/${flow}/password` && step === 'PASSWORD') {
      if (body.password !== user.password) return json(422, {})
      step = 'SECOND_FACTOR'
      return json(200, { id: flow, step, factors: ['TOTP', 'RECOVERY_CODE'] })
    }
    if (method === 'POST' && path === `/api/flows/${flow}/totp` && step === 'SECOND_FACTOR') {
      state.totps.push(body.code)
      if (locked) return json(410, {})
      if (body.code !== totp(user.totpSecret, now()) || spent.has(body.code)) return json(422, {})
      spent.add(body.code)
      step = 'COMPLETE'
      return json(200, { id: flow, step, factors: [] })
    }
    if (method === 'POST' && path === `/api/flows/${flow}/complete` && step === 'COMPLETE') {
      return json(200, { returnUrl: `/saml/resume/${resume}` }, { 'Set-Cookie': ['__Host-session=session; Path=/; Secure', '__Host-csrf=csrf; Path=/; Secure'] })
    }
    if (method === 'GET' && path === `/saml/resume/${resume}` && cookie.includes('__Host-session=session')) {
      const [request] = state.requests
      const xml = tamper(signedResponse({ key, issuer, sp, requestId: request.id, at: now(), ...response }))
      const form =
        `<form method="post" action="${escapeHtml(sp.acs)}">\n<input type="hidden" name="SAMLResponse" value="${escapeHtml(Buffer.from(xml).toString('base64'))}">\n` +
        `<input type="hidden" name="RelayState" value="${escapeHtml(request.relayState)}">\n</form>`
      return reply(200, form, { version })
    }
    return json(409, {})
  }
  return { fetch, state, key, issuer }
}

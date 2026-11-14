import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { inflateRawSync } from 'node:zlib'
import { metadataOf, PKCS1, signedResponse, signingKey } from './fake-idp.mjs'
import { authnRequest, identityProviderOf, parseXml, redirectUrl, REFEDS_MFA, serviceProviderOf, SmokeFailure, verifyResponse } from './saml.mjs'

const fixture = (name) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8')
const SP = serviceProviderOf(readFileSync(new URL('./smoke-sp-metadata.xml', import.meta.url), 'utf8'))
const AT = Date.parse('2026-11-10T00:00:00Z')
const ISSUER = 'https://release-smoke.idp.test/saml/metadata'
const REQUEST = '_request'
const key = signingKey()
const idp = { entityId: ISSUER, sso: 'https://release-smoke.idp.test/saml/sso', certificates: [key.certificate] }
const respond = (fields = {}) => signedResponse({ key, issuer: ISSUER, sp: SP, requestId: REQUEST, at: AT, ...fields })
const check = (xml, overrides = {}) => verifyResponse(xml, { idp, sp: SP, requestId: REQUEST, now: AT + 1_000, ...overrides })

// Captured from the local environment: OpenSAML marshalled it and Santuario signed it with RSA-PSS, so it proves the
// canonicalization against theirs and not only against itself.
const REAL = {
  xml: fixture('smoke-response.xml'),
  idp: identityProviderOf(fixture('smoke-idp-metadata.xml')),
  requestId: '_25243cb9eadf0309cca1bd37d5230806',
  now: Date.parse('2026-11-09T22:47:02Z'),
}

test('the test SP of the repository asks for no signed requests and names its ACS', () => {
  assert.deepEqual(SP, { entityId: 'https://release-smoke.invalid/saml/metadata', acs: 'https://release-smoke.invalid/saml/acs' })
  const signed = '<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="x"><md:SPSSODescriptor AuthnRequestsSigned="true"/></md:EntityDescriptor>'
  assert.throws(() => serviceProviderOf(signed), /unsigned/)
})

test('the published metadata gives the signing certificates and the HTTP-Redirect endpoint', () => {
  assert.equal(REAL.idp.entityId, ISSUER)
  assert.equal(REAL.idp.sso, 'https://release-smoke.idp.test/saml/sso')
  assert.equal(REAL.idp.certificates.length, 1)
  const empty = metadataOf(ISSUER, idp.sso, [])
  assert.throws(() => identityProviderOf(empty), /no signing certificate/)
})

test('the AuthnRequest goes deflated in the query and asks for MFA at the ACS of the test SP', () => {
  const xml = authnRequest({ id: '_id', issueInstant: new Date(AT), destination: idp.sso, sp: SP })
  const url = new URL(redirectUrl(idp.sso, xml, 'state'))
  assert.equal(inflateRawSync(Buffer.from(url.searchParams.get('SAMLRequest'), 'base64')).toString('utf8'), xml)
  assert.equal(url.searchParams.get('RelayState'), 'state')
  const request = parseXml(xml, 'The request')
  assert.equal(request.getAttribute('AssertionConsumerServiceURL'), SP.acs)
  assert.equal(request.getAttribute('Destination'), idp.sso)
  assert.ok(xml.includes(`<saml:AuthnContextClassRef>${REFEDS_MFA}</saml:AuthnContextClassRef>`))
})

test('a Response that the IdP signed with Santuario verifies with the certificate of its metadata', () => {
  const verified = verifyResponse(REAL.xml, { idp: REAL.idp, sp: SP, requestId: REAL.requestId, now: REAL.now })
  assert.equal(verified.nameId, 'BKIVTWIn78qUjuxm-a1pe-IrasT2yc2eh1MPUx4Qog8')
  assert.deepEqual(
    verified.signatures.map((it) => [it.part, it.algorithm]),
    [['Assertion', 'http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1']],
  )
})

test('the same Response fails once a byte of the assertion changed or under another key', () => {
  const changed = REAL.xml.replace('BKIVTWIn78qUjuxm', 'AKIVTWIn78qUjuxm')
  assert.throws(() => verifyResponse(changed, { idp: REAL.idp, sp: SP, requestId: REAL.requestId, now: REAL.now }), /digest .* does not match/)
  assert.throws(() => verifyResponse(REAL.xml, { idp, sp: SP, requestId: REAL.requestId, now: REAL.now }), /does not verify/)
  assert.throws(() => verifyResponse(REAL.xml, { idp: REAL.idp, sp: SP, requestId: REAL.requestId, now: REAL.now + 600_000 }), /expired/)
})

test('a signed assertion, a signed Response or both verify, with RSA-PSS and with rsa-sha256', () => {
  assert.deepEqual(check(respond()).signatures.map((it) => it.part), ['Assertion'])
  assert.deepEqual(check(respond({ signed: ['response'] })).signatures.map((it) => it.part), ['Response'])
  assert.deepEqual(check(respond({ signed: ['assertion', 'response'], algorithm: PKCS1 })).signatures.map((it) => it.part).sort(), ['Assertion', 'Response'])
})

test('a Response for another SP, request or ACS is refused, even when the IdP signed it', () => {
  assert.throws(() => check(respond({ audience: 'https://other.example/sp' })), SmokeFailure)
  assert.throws(() => check(respond({ audience: 'https://other.example/sp' })), /meant for https:\/\/other.example\/sp/)
  assert.throws(() => check(respond({ inResponseTo: '_other' })), /another request/)
  assert.throws(() => check(respond({ destination: 'https://other.example/acs' })), /not to the ACS/)
  assert.throws(() => check(respond({ recipient: 'https://other.example/acs' })), /meant for https:\/\/other.example\/acs/)
  assert.throws(() => check(respond({ assertionIssuer: 'https://other.example/idp' })), /not from/)
  assert.throws(() => check(respond({ contextClass: 'https://refeds.org/profile/sfa' })), /not https:\/\/refeds.org\/profile\/mfa/)
  assert.throws(() => check(respond({ status: 'urn:oasis:names:tc:SAML:2.0:status:Requester' })), /answered/)
  assert.throws(() => check(respond(), { now: AT + 400_000 }), /expired/)
})

test('a Response that is unsigned, signed by another key, with SHA-1 or over another element is refused', () => {
  assert.throws(() => check(respond({ signed: [] })), /Neither/)
  assert.throws(() => check(respond({ key: signingKey() })), /does not verify/)
  assert.throws(() => check(respond({ algorithm: 'http://www.w3.org/2000/09/xmldsig#rsa-sha1' })), /uses http:\/\/www.w3.org\/2000\/09\/xmldsig#rsa-sha1/)
  assert.throws(() => check(respond().replace(/URI="#[^"]+"/, 'URI="#_elsewhere"')), /refers to another element/)
  assert.throws(() => check(respond().replace('name-id-of-the-user', 'name-id-of-another')), /does not match/)
})

test('a second assertion, a repeated ID or a document type is refused, so a signed copy cannot hide the one that is read', () => {
  const xml = respond()
  const assertion = /<saml2:Assertion[\s\S]*<\/saml2:Assertion>/.exec(xml)[0]
  assert.throws(() => check(xml.replace('</saml2p:Response>', `${assertion}</saml2p:Response>`)), /exactly one assertion/)
  const id = /<saml2:Assertion [^>]*ID="([^"]+)"/.exec(xml)[1]
  assert.throws(() => check(xml.replace('<saml2p:Status>', `<saml2p:Extensions ID="${id}"/><saml2p:Status>`)), /multiple elements with the same value/)
  assert.throws(() => check(xml.replace('<?xml version="1.0" encoding="UTF-8"?>', '<!DOCTYPE r>')), /document type/)
  assert.throws(() => check(xml.replace('</saml2p:Response>', '')), /no well-formed XML/)
})

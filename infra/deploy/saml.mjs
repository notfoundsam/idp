import { X509Certificate } from 'node:crypto'
import { deflateRawSync } from 'node:zlib'
import { DOMParser } from '@xmldom/xmldom'
import { SignedXml } from 'xml-crypto'

export const MD = 'urn:oasis:names:tc:SAML:2.0:metadata'
export const SAMLP = 'urn:oasis:names:tc:SAML:2.0:protocol'
export const SAML = 'urn:oasis:names:tc:SAML:2.0:assertion'
export const DS = 'http://www.w3.org/2000/09/xmldsig#'
export const EXC_C14N = 'http://www.w3.org/2001/10/xml-exc-c14n#'
export const ENVELOPED = 'http://www.w3.org/2000/09/xmldsig#enveloped-signature'
export const REDIRECT = 'urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect'
export const POST = 'urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST'
export const REFEDS_MFA = 'https://refeds.org/profile/mfa'
const SUCCESS = 'urn:oasis:names:tc:SAML:2.0:status:Success'
const BEARER = 'urn:oasis:names:tc:SAML:2.0:cm:bearer'
const PERSISTENT = 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent'

// What the IdP signs with, see SignatureAlgorithm, among what xml-crypto verifies. SHA-1 stays out.
export const SIGNATURE_METHODS = [
  'http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1',
  'http://www.w3.org/2001/04/xmldsig-more#rsa-sha256',
  'http://www.w3.org/2001/04/xmldsig-more#rsa-sha512',
]
export const DIGEST_METHODS = ['http://www.w3.org/2001/04/xmlenc#sha256', 'http://www.w3.org/2001/04/xmlenc#sha512']

export class SmokeFailure extends Error {}

const refuse = (why) => {
  throw new SmokeFailure(why)
}

const required = (value, why) => (value === undefined || value === null || value === '' ? refuse(why) : value)

const listOf = (nodes) => Array.from({ length: nodes.length }, (_, index) => nodes.item(index))

// A document type could declare entities, and nothing the IdP sends has one.
export function parseXml(xml, what) {
  if (/<!DOCTYPE/i.test(xml)) refuse(`${what} carries a document type`)
  let failure
  const record = (message) => {
    failure ??= message
  }
  const document = new DOMParser({ errorHandler: { warning: () => {}, error: record, fatalError: record } }).parseFromString(xml, 'text/xml')
  if (failure !== undefined || document?.documentElement == null) refuse(`${what} is no well-formed XML`)
  return document.documentElement
}

const elementsOf = (node) => listOf(node.childNodes).filter((it) => it.nodeType === 1)
const childrenOf = (node, uri, local) => elementsOf(node).filter((it) => it.namespaceURI === uri && it.localName === local)
const descendants = (node, uri, local) => listOf(node.getElementsByTagNameNS(uri, local))
const attributeOf = (element, name) => (element.hasAttribute(name) ? element.getAttribute(name) : undefined)
const textOf = (element) => element.textContent ?? ''

function childOf(node, uri, local) {
  const found = childrenOf(node, uri, local)
  if (found.length > 1) refuse(`${local} repeats in ${node.localName}`)
  return found[0]
}

const is = (element, uri, local) => element.namespaceURI === uri && element.localName === local

// The certificates an IdP signs with, from its published metadata, and where it takes a request by HTTP-Redirect.
export function identityProviderOf(metadata) {
  const entity = parseXml(metadata, 'The IdP metadata')
  if (!is(entity, MD, 'EntityDescriptor')) refuse('The IdP metadata is no EntityDescriptor')
  const idp = required(childOf(entity, MD, 'IDPSSODescriptor'), 'The IdP metadata has no IDPSSODescriptor')
  const certificates = childrenOf(idp, MD, 'KeyDescriptor')
    .filter((it) => (attributeOf(it, 'use') ?? 'signing') === 'signing')
    .flatMap((it) => descendants(it, DS, 'X509Certificate'))
    .map((it) => new X509Certificate(Buffer.from(textOf(it).replace(/\s/g, ''), 'base64')))
  if (certificates.length === 0) refuse('The IdP metadata names no signing certificate')
  const sso = childrenOf(idp, MD, 'SingleSignOnService').find((it) => attributeOf(it, 'Binding') === REDIRECT)
  return {
    entityId: required(attributeOf(entity, 'entityID'), 'The IdP metadata has no entityID'),
    sso: required(sso && attributeOf(sso, 'Location'), 'The IdP metadata has no HTTP-Redirect SingleSignOnService'),
    certificates,
  }
}

// The test SP of infra/deploy: its EntityID and the ACS the IdP picks without a URL in the request, as MetadataImporter does.
export function serviceProviderOf(metadata) {
  const entity = parseXml(metadata, 'The SP metadata')
  if (!is(entity, MD, 'EntityDescriptor')) refuse('The SP metadata is no EntityDescriptor')
  const sp = required(childOf(entity, MD, 'SPSSODescriptor'), 'The SP metadata has no SPSSODescriptor')
  if (attributeOf(sp, 'AuthnRequestsSigned') === 'true') refuse('The smoke test sends unsigned requests, so its SP may not ask for signed ones')
  const post = childrenOf(sp, MD, 'AssertionConsumerService').filter((it) => attributeOf(it, 'Binding') === POST)
  const acs =
    post.find((it) => attributeOf(it, 'isDefault') === 'true') ?? post.find((it) => attributeOf(it, 'isDefault') === undefined) ?? post[0]
  return {
    entityId: required(attributeOf(entity, 'entityID'), 'The SP metadata has no entityID'),
    acs: required(acs && attributeOf(acs, 'Location'), 'The SP metadata has no HTTP-POST AssertionConsumerService'),
  }
}

const escape = (value) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;')

// Unsigned, so the SP holds no key. It asks for a fresh login with MFA, so the password and the TOTP code both have to work.
export function authnRequest({ id, issueInstant, destination, sp }) {
  return (
    `<samlp:AuthnRequest xmlns:samlp="${SAMLP}" xmlns:saml="${SAML}" ID="${escape(id)}" Version="2.0" ` +
    `IssueInstant="${issueInstant.toISOString()}" Destination="${escape(destination)}" ForceAuthn="true" ` +
    `AssertionConsumerServiceURL="${escape(sp.acs)}" ProtocolBinding="${POST}">` +
    `<saml:Issuer>${escape(sp.entityId)}</saml:Issuer>` +
    `<samlp:NameIDPolicy Format="${PERSISTENT}" AllowCreate="true"/>` +
    `<samlp:RequestedAuthnContext Comparison="exact"><saml:AuthnContextClassRef>${REFEDS_MFA}</saml:AuthnContextClassRef></samlp:RequestedAuthnContext>` +
    '</samlp:AuthnRequest>'
  )
}

// SAML bindings 3.4.4.1: DEFLATE without a header, then base64, then URL encoding.
export function redirectUrl(sso, request, relayState) {
  const encoded = encodeURIComponent(deflateRawSync(Buffer.from(request, 'utf8')).toString('base64'))
  return `${sso}${sso.includes('?') ? '&' : '?'}SAMLRequest=${encoded}&RelayState=${encodeURIComponent(relayState)}`
}

const only = (algorithms, allowed) => Object.fromEntries(Object.entries(algorithms).filter(([uri]) => allowed.includes(uri)))

// The profile of SAML core 5.4 is checked first: one reference to the element that holds the signature, by its ID, with only the
// enveloped and the exclusive transforms. xml-crypto then canonicalizes, digests and verifies, and hands back the canonical form
// of what it verified, which is what the caller reads.
export function verifySignature(signature, xml, certificates) {
  const target = signature.parentNode
  const id = required(attributeOf(target, 'ID'), `The signed ${target.localName} has no ID`)
  const info = required(childOf(signature, DS, 'SignedInfo'), 'A signature without SignedInfo')
  if (elementsOf(signature)[0] !== info) refuse('SignedInfo is not the first element of the signature')
  const c14n = required(childOf(info, DS, 'CanonicalizationMethod'), 'A signature without CanonicalizationMethod')
  if (attributeOf(c14n, 'Algorithm') !== EXC_C14N) refuse('The signature is not canonicalized with exclusive C14N')
  const algorithm = attributeOf(required(childOf(info, DS, 'SignatureMethod'), 'A signature without SignatureMethod'), 'Algorithm')
  if (!SIGNATURE_METHODS.includes(algorithm)) refuse(`The signature uses ${algorithm}`)
  const references = childrenOf(info, DS, 'Reference')
  if (references.length !== 1) refuse('A signature does not hold exactly one reference')
  if (attributeOf(references[0], 'URI') !== `#${id}`) refuse(`The signature of ${target.localName} refers to another element`)
  const transforms = childrenOf(required(childOf(references[0], DS, 'Transforms'), 'A reference without transforms'), DS, 'Transform')
  const kinds = transforms.map((it) => attributeOf(it, 'Algorithm'))
  if (kinds.length !== 2 || kinds[0] !== ENVELOPED || kinds[1] !== EXC_C14N) refuse('The reference has other transforms than SAML allows')
  const digest = attributeOf(required(childOf(references[0], DS, 'DigestMethod'), 'A reference without DigestMethod'), 'Algorithm')
  if (!DIGEST_METHODS.includes(digest)) refuse(`The reference is digested with ${digest}`)

  for (const certificate of certificates) {
    const verifier = new SignedXml({ publicCert: certificate.toString(), getCertFromKeyInfo: () => null })
    verifier.SignatureAlgorithms = only(verifier.SignatureAlgorithms, SIGNATURE_METHODS)
    verifier.HashAlgorithms = only(verifier.HashAlgorithms, DIGEST_METHODS)
    verifier.loadSignature(signature)
    let valid
    try {
      valid = verifier.checkSignature(xml)
    } catch (failure) {
      if (failure.message.startsWith('invalid signature: the signature value')) continue
      refuse(`The signature of ${target.localName} does not verify: ${failure.message}`)
    }
    if (valid !== true) refuse(`The digest of the signed ${target.localName} does not match: it changed after signing`)
    const [signed] = verifier.getSignedReferences()
    return { algorithm, fingerprint: certificate.fingerprint256, element: parseXml(signed, `The signed ${target.localName}`) }
  }
  return refuse(`The signature of ${target.localName} does not verify with a certificate of the IdP metadata`)
}

const instant = (element, name) => {
  const raw = attributeOf(element, name)
  const value = raw === undefined ? NaN : Date.parse(raw)
  return Number.isNaN(value) ? refuse(`${element.localName} has no readable ${name}`) : value
}

function soleAssertion(response) {
  if (descendants(response, SAML, 'EncryptedAssertion').length > 0) refuse('The Response holds an encrypted assertion, which this SP never asked for')
  const assertions = descendants(response, SAML, 'Assertion')
  if (assertions.length !== 1 || assertions[0].parentNode !== response) refuse('The Response does not hold exactly one assertion')
  return assertions[0]
}

// Checks a Response the way an SP has to, against the request it answers and the metadata of the IdP, and reads the assertion
// only from what a signature covers. [skew] is in milliseconds.
export function verifyResponse(xml, { idp, sp, requestId, now, contextClass = REFEDS_MFA, skew = 60_000 }) {
  const posted = parseXml(xml, 'The Response')
  if (!is(posted, SAMLP, 'Response')) refuse('The IdP posted no Response')
  const postedAssertion = soleAssertion(posted)
  const signatures = descendants(posted, DS, 'Signature')
  const own = (element) => signatures.filter((it) => it.parentNode === element)
  if (signatures.some((it) => it.parentNode !== posted && it.parentNode !== postedAssertion) || own(posted).length > 1 || own(postedAssertion).length > 1) {
    refuse('A signature sits elsewhere than on the Response or the assertion')
  }
  if (signatures.length === 0) refuse('Neither the Response nor the assertion is signed')
  const verified = signatures.map((it) => ({ part: it.parentNode.localName, ...verifySignature(it, xml, idp.certificates) }))
  const signedResponse = verified.find((it) => it.part === 'Response')?.element
  const response = signedResponse ?? posted
  const assertion = verified.find((it) => it.part === 'Assertion')?.element ?? soleAssertion(signedResponse)

  if (attributeOf(response, 'Version') !== '2.0') refuse('The Response is not SAML 2.0')
  if (attributeOf(response, 'Destination') !== sp.acs) refuse(`The Response goes to ${attributeOf(response, 'Destination')}, not to the ACS ${sp.acs}`)
  if (attributeOf(response, 'InResponseTo') !== requestId) refuse('The Response answers another request')
  const issuer = childOf(response, SAML, 'Issuer')
  if (issuer !== undefined && textOf(issuer) !== idp.entityId) refuse(`The Response comes from ${textOf(issuer)}, not from ${idp.entityId}`)
  const status = childOf(required(childOf(response, SAMLP, 'Status'), 'The Response has no Status'), SAMLP, 'StatusCode')
  if (status === undefined || attributeOf(status, 'Value') !== SUCCESS) refuse(`The IdP answered ${status && attributeOf(status, 'Value')}`)

  const assertionIssuer = required(childOf(assertion, SAML, 'Issuer'), 'The assertion has no Issuer')
  if (textOf(assertionIssuer) !== idp.entityId) refuse(`The assertion comes from ${textOf(assertionIssuer)}, not from ${idp.entityId}`)
  const subject = required(childOf(assertion, SAML, 'Subject'), 'The assertion has no Subject')
  const nameId = textOf(required(childOf(subject, SAML, 'NameID'), 'The assertion has no NameID'))
  if (nameId.trim() === '') refuse('The NameID is empty')
  const confirmation = childrenOf(subject, SAML, 'SubjectConfirmation').find((it) => attributeOf(it, 'Method') === BEARER)
  const data = required(confirmation && childOf(confirmation, SAML, 'SubjectConfirmationData'), 'The assertion has no bearer confirmation')
  if (attributeOf(data, 'Recipient') !== sp.acs) refuse(`The assertion is meant for ${attributeOf(data, 'Recipient')}, not for the ACS ${sp.acs}`)
  if (attributeOf(data, 'InResponseTo') !== requestId) refuse('The assertion answers another request')
  if (now >= instant(data, 'NotOnOrAfter') + skew) refuse('The bearer confirmation has expired')

  const conditions = required(childOf(assertion, SAML, 'Conditions'), 'The assertion has no Conditions')
  if (now + skew < instant(conditions, 'NotBefore')) refuse('The assertion is not valid yet')
  if (now >= instant(conditions, 'NotOnOrAfter') + skew) refuse('The assertion has expired')
  const audiences = childrenOf(conditions, SAML, 'AudienceRestriction').map((it) => childrenOf(it, SAML, 'Audience').map(textOf))
  if (audiences.length === 0 || audiences.some((it) => it.length !== 1 || it[0] !== sp.entityId)) {
    refuse(`The assertion is meant for ${audiences.flat().join(', ') || 'anybody'}, not for ${sp.entityId} alone`)
  }

  const statement = required(childOf(assertion, SAML, 'AuthnStatement'), 'The assertion has no AuthnStatement')
  const context = childOf(required(childOf(statement, SAML, 'AuthnContext'), 'The assertion has no AuthnContext'), SAML, 'AuthnContextClassRef')
  if (context === undefined || textOf(context) !== contextClass) refuse(`The login proved ${context && textOf(context)}, not ${contextClass}`)
  return { nameId, signatures: verified.map(({ part, algorithm, fingerprint }) => ({ part, algorithm, fingerprint })) }
}

import type {
  PasskeyEnrollmentOptions,
  PasskeyEnrollmentRequest,
  PasskeyLoginOptions,
  PasskeyLoginRequest,
} from '@idp/api-client'

export function toBase64Url(bytes: ArrayBuffer | ArrayBufferView): string {
  const view = bytes instanceof ArrayBuffer ? new Uint8Array(bytes) : new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let binary = ''
  for (const byte of view) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

export function fromBase64Url(value: string): Uint8Array<ArrayBuffer> {
  const base64 = value.replaceAll('-', '+').replaceAll('_', '/')
  const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export function passkeysSupported(scope: typeof globalThis): boolean {
  return typeof scope.PublicKeyCredential === 'function' && scope.navigator.credentials !== undefined
}

function credentialOf(value: Credential | null): PublicKeyCredential {
  if (value === null || value.type !== 'public-key') throw new DOMException('No passkey was used', 'NotAllowedError')
  return value as PublicKeyCredential
}

/** Lists no credentials: passkeys are discoverable, so the browser offers those it has for this host. */
export async function signInWithPasskey(
  credentials: CredentialsContainer,
  options: PasskeyLoginOptions,
): Promise<PasskeyLoginRequest> {
  const credential = credentialOf(
    await credentials.get({
      publicKey: {
        challenge: fromBase64Url(options.challenge),
        rpId: options.rpId,
        userVerification: options.userVerification as UserVerificationRequirement,
      },
    }),
  )
  const response = credential.response as AuthenticatorAssertionResponse
  return {
    credentialId: toBase64Url(credential.rawId),
    userHandle: response.userHandle === null ? null : toBase64Url(response.userHandle),
    authenticatorData: toBase64Url(response.authenticatorData),
    clientDataJson: toBase64Url(response.clientDataJSON),
    signature: toBase64Url(response.signature),
  }
}

/** The options name no user, so the passkey is labelled with the login typed on this page, or with the host. */
export async function registerPasskey(
  credentials: CredentialsContainer,
  options: PasskeyEnrollmentOptions,
  label: string,
): Promise<PasskeyEnrollmentRequest> {
  const credential = credentialOf(
    await credentials.create({
      publicKey: {
        challenge: fromBase64Url(options.challenge),
        rp: { id: options.rpId, name: options.rpId },
        user: { id: fromBase64Url(options.userHandle), name: label, displayName: label },
        pubKeyCredParams: options.algorithms.map((alg) => ({ type: 'public-key', alg })),
        excludeCredentials: options.excludeCredentials.map((id) => ({ type: 'public-key', id: fromBase64Url(id) })),
        authenticatorSelection: {
          residentKey: options.residentKey as ResidentKeyRequirement,
          requireResidentKey: options.residentKey === 'required',
          userVerification: options.userVerification as UserVerificationRequirement,
        },
        attestation: options.attestation as AttestationConveyancePreference,
      },
    }),
  )
  const response = credential.response as AuthenticatorAttestationResponse
  return { attestationObject: toBase64Url(response.attestationObject), clientDataJson: toBase64Url(response.clientDataJSON) }
}

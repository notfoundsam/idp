import { describe, expect, test } from 'vitest'
import { grouped, otpauthUri } from './otpauth'
import { fromBase64Url, registerPasskey, signInWithPasskey, toBase64Url } from './webauthn'

const bytes = (...values: number[]) => new Uint8Array(values)

describe('base64url', () => {
  test('round trips every length without padding', () => {
    for (let length = 0; length < 8; length++) {
      const value = Uint8Array.from({ length }, (_, i) => 250 - i)
      const encoded = toBase64Url(value)
      expect(encoded).not.toMatch(/[+/=]/)
      expect([...fromBase64Url(encoded)]).toEqual([...value])
    }
  })

  test('uses the URL alphabet', () => {
    expect(toBase64Url(bytes(0xfb, 0xff))).toBe('-_8')
  })

  test('reads a view at its offset', () => {
    expect(toBase64Url(new Uint8Array(bytes(1, 2, 3).buffer, 1, 1))).toBe('Ag')
  })

  test('refuses what is not base64url', () => {
    expect(() => fromBase64Url('***')).toThrow()
  })
})

describe('passkeys', () => {
  test('a sign-in answers the challenge in the shape of the contract', async () => {
    let asked: CredentialRequestOptions | undefined
    const credentials = {
      get: async (options: CredentialRequestOptions) => {
        asked = options
        return {
          type: 'public-key',
          rawId: bytes(1, 2).buffer,
          response: {
            userHandle: bytes(3).buffer,
            authenticatorData: bytes(4).buffer,
            clientDataJSON: bytes(5).buffer,
            signature: bytes(6).buffer,
          },
        }
      },
    } as unknown as CredentialsContainer
    const answer = await signInWithPasskey(credentials, { challenge: 'AQID', rpId: 'acme.idp.test', userVerification: 'required' })
    expect([...new Uint8Array(asked?.publicKey?.challenge as ArrayBuffer)]).toEqual([1, 2, 3])
    expect(asked?.publicKey?.allowCredentials).toBeUndefined()
    expect(answer).toEqual({ credentialId: 'AQI', userHandle: 'Aw', authenticatorData: 'BA', clientDataJson: 'BQ', signature: 'Bg' })
  })

  test('a sign-in the user does not finish is refused', async () => {
    const credentials = { get: async () => null } as unknown as CredentialsContainer
    await expect(signInWithPasskey(credentials, { challenge: 'AQID', rpId: 'acme.idp.test', userVerification: 'required' })).rejects.toThrow(
      DOMException,
    )
  })

  test('a registration asks for a discoverable passkey with what the server offers', async () => {
    let asked: CredentialCreationOptions | undefined
    const credentials = {
      create: async (options: CredentialCreationOptions) => {
        asked = options
        return { type: 'public-key', rawId: bytes(1).buffer, response: { attestationObject: bytes(7).buffer, clientDataJSON: bytes(8).buffer } }
      },
    } as unknown as CredentialsContainer
    const answer = await registerPasskey(
      credentials,
      {
        challenge: 'AQID',
        rpId: 'acme.idp.test',
        userHandle: 'CQ',
        algorithms: [-7, -257],
        excludeCredentials: ['Cg'],
        residentKey: 'required',
        userVerification: 'required',
        attestation: 'none',
      },
      'alice@acme.test',
    )
    const options = asked?.publicKey
    expect(options?.rp).toEqual({ id: 'acme.idp.test', name: 'acme.idp.test' })
    expect(options?.user.name).toBe('alice@acme.test')
    expect(options?.pubKeyCredParams).toEqual([
      { type: 'public-key', alg: -7 },
      { type: 'public-key', alg: -257 },
    ])
    expect(options?.authenticatorSelection).toEqual({ residentKey: 'required', requireResidentKey: true, userVerification: 'required' })
    expect(answer).toEqual({ attestationObject: 'Bw', clientDataJson: 'CA' })
  })
})

describe('authenticator apps', () => {
  test('the link names the host and the account', () => {
    expect(otpauthUri('JBSWY3DPEHPK3PXP', 'acme.idp.test', 'alice@acme.test')).toBe(
      'otpauth://totp/acme.idp.test%3Aalice%40acme.test?secret=JBSWY3DPEHPK3PXP&issuer=acme.idp.test&algorithm=SHA1&digits=6&period=30',
    )
  })

  test('without a known account the link names the host only', () => {
    expect(otpauthUri('JBSW', 'acme.idp.test', null)).toMatch(/^otpauth:\/\/totp\/acme\.idp\.test\?/)
  })

  test('the key is shown in groups of four', () => {
    expect(grouped('JBSWY3DPEHPK3PXPJB')).toBe('JBSW Y3DP EHPK 3PXP JB')
    expect(grouped('JBSW')).toBe('JBSW')
  })
})

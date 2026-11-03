import { createHmac } from 'node:crypto'

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

function base32(secret: string): Buffer {
  let bits = ''
  for (const char of secret.replace(/[\s=]/g, '').toUpperCase()) {
    const value = ALPHABET.indexOf(char)
    if (value < 0) throw new Error(`Not base32: ${char}`)
    bits += value.toString(2).padStart(5, '0')
  }
  const bytes = bits.match(/.{8}/g) ?? []
  return Buffer.from(bytes.map((byte) => parseInt(byte, 2)))
}

/** RFC 6238 with the parameters of `TotpCredential`: SHA-1, six digits, 30 seconds. */
export function totp(secret: string, at = Date.now()): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30_000)))
  const mac = createHmac('sha1', base32(secret)).update(counter).digest()
  const offset = (mac.at(-1) ?? 0) & 0x0f
  const value = mac.readUInt32BE(offset) & 0x7fffffff
  return (value % 1_000_000).toString().padStart(6, '0')
}

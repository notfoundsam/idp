/** The parameters are those of `TotpCredential` on the server. */
export function otpauthUri(secret: string, issuer: string, account: string | null): string {
  const label = account === null ? issuer : `${issuer}:${account}`
  const query = new URLSearchParams({ secret, issuer, algorithm: 'SHA1', digits: '6', period: '30' })
  return `otpauth://totp/${encodeURIComponent(label)}?${query.toString()}`
}

/** Groups of four are easier to type into an app by hand. */
export function grouped(secret: string): string {
  return secret.replace(/(.{4})(?=.)/g, '$1 ')
}

/** The domain of the environment under test, IDP_DOMAIN of compose, see dev-tools/README.md. */
export const DOMAIN = process.env.IDP_DOMAIN ?? 'idp.test'

export const host = (name: string) => `${name}.${DOMAIN}`

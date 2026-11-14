import { describe, expect, test } from 'vitest'
import { pageAt, takeToken } from './pages'

function address(hash: string, pathname = '/invite', search = '') {
  const replaced: string[] = []
  return {
    replaced,
    location: { hash, pathname, search },
    history: { state: { kept: true }, replaceState: (_: unknown, __: string, url: string) => replaced.push(url) },
  }
}

describe('pages', () => {
  test('each link of a mail opens its own page', () => {
    expect(pageAt('/invite')).toBe('invite')
    expect(pageAt('/reset-password')).toBe('reset-password')
    expect(pageAt('/confirm-email')).toBe('confirm-email')
    expect(pageAt('/login/forgot-password')).toBe('forgot-password')
  })

  test('any other path of the app is the login', () => {
    expect(pageAt('/login/')).toBe('login')
    expect(pageAt('/login/any/path')).toBe('login')
    expect(pageAt('/invite/')).toBe('login')
  })
})

describe('tokens from mails', () => {
  test('the token is read from the fragment and the fragment leaves the address', () => {
    const at = address('#token=abc-DEF_123', '/invite', '?lang=en')
    expect(takeToken(at)).toBe('abc-DEF_123')
    expect(at.replaced).toEqual(['/invite?lang=en'])
  })

  test('a fragment without a token is dropped all the same', () => {
    const at = address('#other=1')
    expect(takeToken(at)).toBeNull()
    expect(at.replaced).toEqual(['/invite'])
  })

  test('an empty token counts as none', () => {
    expect(takeToken(address('#token='))).toBeNull()
  })

  test('an address without a fragment is left as it is', () => {
    const at = address('')
    expect(takeToken(at)).toBeNull()
    expect(at.replaced).toEqual([])
  })
})

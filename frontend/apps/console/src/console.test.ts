import { qrModules } from '@idp/ui'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import { PASSWORD_REFUSED } from './messages'
import { button, click, consoleAt, EMAIL, factorsWith, hasButton, refused, sessionsOf, settle } from './testing'

let mounted: VueWrapper | null = null

afterEach(() => {
  mounted?.unmount()
  mounted = null
})

async function start(page: Awaited<ReturnType<typeof consoleAt>>) {
  mounted = await page.start()
  return mounted
}

const PASSKEY = {
  id: '0b8f6c52-41d7-4f0e-9a1a-1f3f1f8e2c10',
  createdAt: '2026-10-01T10:00:00Z',
  lastUsedAt: null,
  backedUp: true,
}

async function signedIn(path = '/', factors = factorsWith(), options: Parameters<typeof consoleAt>[1] = {}) {
  const page = await consoleAt(path, options)
  page.answer('getFactors', factors)
  page.answer('getSessions', sessionsOf('other'))
  return page
}

describe('every page', () => {
  test.each(['/', '/sessions'])('%s shows the account and a way to sign out', async (path) => {
    const wrapper = await start(await signedIn(path))
    expect(wrapper.get('[data-testid="account-email"]').text()).toBe(EMAIL)
    expect(hasButton(wrapper, 'Sign out')).toBe(true)
  })

  test('without a session the browser goes to the login', async () => {
    const page = await consoleAt('/')
    page.answer('getFactors', refused(401))
    page.answer('getSessions', refused(401))
    await start(page)
    expect(page.browser.assigned).toContain('/login/')
  })

  test('a server that cannot be reached is named, and no account is shown', async () => {
    const page = await consoleAt('/sessions')
    page.answer('getSessions', new TypeError('Failed to fetch'))
    const wrapper = await start(page)
    expect(wrapper.get('[role="alert"]').text()).toContain('could not be reached')
    expect(wrapper.find('[data-testid="account-email"]').exists()).toBe(false)
  })

  test('an unknown path leads to the sign-in methods', async () => {
    const page = await signedIn('/nowhere')
    const wrapper = await start(page)
    expect(page.router.currentRoute.value.name).toBe('security')
    expect(wrapper.get('h1').text()).toBe('Sign-in methods')
  })
})

describe('the password', () => {
  test('recommends 15 characters and changes with the current one, ending the other sessions when asked', async () => {
    const page = await signedIn()
    page.answer('changePassword', undefined)
    const wrapper = await start(page)
    expect(wrapper.get('#password-rules').text()).toContain('15 or more is recommended')
    expect(wrapper.get('#current-password').attributes('type')).toBe('password')
    expect(wrapper.get('#new-password').attributes('type')).toBe('password')

    await wrapper.get('#current-password').setValue('old password')
    await wrapper.get('#new-password').setValue('a new long password')
    await wrapper.get('input[name="end-other-sessions"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await settle()

    expect(page.called('changePassword')[0]?.args).toEqual([
      { currentPassword: 'old password', newPassword: 'a new long password', endOtherSessions: true },
    ])
    expect(wrapper.find('[data-testid="password-changed"]').exists()).toBe(true)
    expect((wrapper.get('#current-password').element as HTMLInputElement).value).toBe('')
    expect(page.called('getSessions').length).toBeGreaterThan(1)
  })

  test('keeps the other sessions unless asked', async () => {
    const page = await signedIn()
    page.answer('changePassword', undefined)
    const wrapper = await start(page)
    await wrapper.get('#current-password').setValue('old')
    await wrapper.get('#new-password').setValue('new')
    await wrapper.get('form').trigger('submit')
    await settle()
    expect(page.called('changePassword')[0]?.args).toEqual([{ currentPassword: 'old', newPassword: 'new', endOtherSessions: false }])
  })

  test('a refusal names both passwords and keeps what was typed', async () => {
    const page = await signedIn()
    page.answer('changePassword', refused(422))
    const wrapper = await start(page)
    await wrapper.get('#current-password').setValue('wrong')
    await wrapper.get('#new-password').setValue('new password')
    await wrapper.get('form').trigger('submit')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toBe(PASSWORD_REFUSED)
    expect((wrapper.get('#new-password').element as HTMLInputElement).value).toBe('new password')
    expect(wrapper.find('[data-testid="password-changed"]').exists()).toBe(false)
  })
})

describe('TOTP', () => {
  test('a new secret is shown in groups with a link for this account, and its code brings recovery codes once', async () => {
    const page = await signedIn()
    page.answer('getFactors', factorsWith(), factorsWith({ totp: true, recoveryCodesLeft: 2 }))
    page.answer('beginTotp', { secret: 'JBSWY3DPEHPK3PXP' })
    page.answer('confirmTotp', { recoveryCodes: ['code-1', 'code-2'] })
    const wrapper = await start(page)

    await click(wrapper, 'Set up an authenticator app')
    expect(wrapper.get('[data-testid="totp-secret"]').text()).toBe('JBSW Y3DP EHPK 3PXP')
    const link = wrapper.get('a[href^="otpauth:"]').attributes('href') ?? ''
    expect(link).toContain(`otpauth://totp/${encodeURIComponent(`acme.idp.test:${EMAIL}`)}?secret=JBSWY3DPEHPK3PXP`)
    expect(wrapper.get('[data-testid="qr-code"] path').attributes('d')).toBe(qrModules(link).path)

    await wrapper.get('#totp').setValue(' 123456 ')
    await wrapper.get('[data-testid="totp-form"]').trigger('submit')
    await settle()
    expect(page.called('confirmTotp')[0]?.args).toEqual(['123456'])
    expect(wrapper.findAll('[data-testid="recovery-codes"] li').map((it) => it.text())).toEqual(['code-1', 'code-2'])
    expect(wrapper.get('[data-testid="totp-status"]').text()).toContain('is set up')
    expect(wrapper.find('[data-testid="end-sessions-offer"]').exists()).toBe(true)

    await click(wrapper, 'I have saved them')
    expect(wrapper.find('[data-testid="recovery-codes"]').exists()).toBe(false)
  })

  test('a wrong code is refused and the secret stays on screen', async () => {
    const page = await signedIn()
    page.answer('beginTotp', { secret: 'JBSWY3DPEHPK3PXP' })
    page.answer('confirmTotp', refused(422))
    const wrapper = await start(page)
    await click(wrapper, 'Set up an authenticator app')
    await wrapper.get('#totp').setValue('000000')
    await wrapper.get('[data-testid="totp-form"]').trigger('submit')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('That code did not work')
    expect(wrapper.find('[data-testid="totp-secret"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="end-sessions-offer"]').exists()).toBe(false)
  })

  test('is removed, after which the page offers to end the other sessions', async () => {
    const page = await signedIn('/', factorsWith({ totp: true, recoveryCodesLeft: 10 }))
    page.answer('getFactors', factorsWith())
    page.answer('removeTotp', undefined)
    page.answer('endOtherSessions', undefined)
    const wrapper = await start(page)
    await click(wrapper, 'Remove authenticator app')
    expect(page.called('removeTotp')).toHaveLength(1)
    expect(wrapper.get('[data-testid="totp-status"]').text()).toContain('No authenticator app')

    await click(wrapper, 'Sign out everywhere else')
    expect(page.called('endOtherSessions')).toHaveLength(1)
    expect(wrapper.find('[data-testid="end-sessions-offer"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="notice"]').text()).toContain('other sessions are ended')
  })

  test('a login that is too old leads to a fresh one', async () => {
    const page = await signedIn('/', factorsWith({ totp: true }))
    page.answer('removeTotp', refused(403, 'reauthentication_required'))
    const wrapper = await start(page)
    await click(wrapper, 'Remove authenticator app')
    expect(wrapper.get('[role="alert"]').text()).toContain('sign in again')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')
    expect(wrapper.find('[data-testid="end-sessions-offer"]').exists()).toBe(false)
  })

  test('the last factor the policy asks for stays, and the page says to add another first', async () => {
    const page = await signedIn('/', factorsWith({ totp: true }))
    page.answer('removeTotp', refused(422))
    const wrapper = await start(page)
    await click(wrapper, 'Remove authenticator app')
    expect(wrapper.get('[role="alert"]').text()).toContain('Add another one first')
    expect(wrapper.get('[data-testid="totp-status"]').text()).toContain('An authenticator app is set up')
    expect(wrapper.find('[data-testid="end-sessions-offer"]').exists()).toBe(false)
  })
})

describe('passkeys', () => {
  const answered = {
    type: 'public-key',
    rawId: new Uint8Array([1]).buffer,
    response: { attestationObject: new Uint8Array([2]).buffer, clientDataJSON: new Uint8Array([3]).buffer },
  }

  test('are listed with their dates, and one is added with the email as its name', async () => {
    let asked: CredentialCreationOptions | undefined
    const credentials = {
      create: async (options: CredentialCreationOptions) => {
        asked = options
        return answered
      },
    } as unknown as CredentialsContainer
    const page = await signedIn('/', factorsWith({ passkeys: [PASSKEY] }), { credentials })
    page.answer('beginPasskey', {
      challenge: 'AQ',
      rpId: 'acme.idp.test',
      userHandle: 'Ag',
      algorithms: [-7],
      excludeCredentials: [],
      residentKey: 'required',
      userVerification: 'required',
      attestation: 'none',
    })
    page.answer('addPasskey', { recoveryCodes: [] })
    const wrapper = await start(page)
    expect(wrapper.findAll('[data-testid="passkey"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="passkey"]').text()).toContain('never used')
    expect(wrapper.get('[data-testid="passkey"]').text()).toContain('synced')

    await click(wrapper, 'Add a passkey')
    expect(asked?.publicKey?.user.name).toBe(EMAIL)
    expect(page.called('addPasskey')[0]?.args).toEqual([{ attestationObject: 'Ag', clientDataJson: 'Aw' }])
    expect(wrapper.find('[data-testid="recovery-codes"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="end-sessions-offer"]').exists()).toBe(true)
  })

  test('a passkey the browser does not create adds nothing', async () => {
    const credentials = {
      create: async () => {
        throw new DOMException('cancelled', 'NotAllowedError')
      },
    } as unknown as CredentialsContainer
    const page = await signedIn('/', factorsWith(), { credentials })
    page.answer('beginPasskey', {
      challenge: 'AQ',
      rpId: 'acme.idp.test',
      userHandle: 'Ag',
      algorithms: [-7],
      excludeCredentials: [],
      residentKey: 'required',
      userVerification: 'required',
      attestation: 'none',
    })
    const wrapper = await start(page)
    await click(wrapper, 'Add a passkey')
    expect(page.called('addPasskey')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('did not create a passkey')
  })

  test('one is removed by its id', async () => {
    const page = await signedIn('/', factorsWith({ passkeys: [PASSKEY] }))
    page.answer('getFactors', factorsWith())
    page.answer('removePasskey', undefined)
    const wrapper = await start(page)
    await click(wrapper, 'Remove')
    expect(page.called('removePasskey')[0]?.args).toEqual([PASSKEY.id])
    expect(wrapper.find('[data-testid="no-passkeys"]').exists()).toBe(true)
  })

  test('the last one the policy asks for stays, and the page says to add another first', async () => {
    const page = await signedIn('/', factorsWith({ passkeys: [PASSKEY] }))
    page.answer('removePasskey', refused(422))
    const wrapper = await start(page)
    await click(wrapper, 'Remove')
    expect(wrapper.get('[role="alert"]').text()).toContain('Add another one first')
    expect(wrapper.findAll('[data-testid="passkey"]')).toHaveLength(1)
  })

  test('a browser without passkeys is told so', async () => {
    const wrapper = await start(await signedIn())
    expect(hasButton(wrapper, 'Add a passkey')).toBe(false)
    expect(wrapper.text()).toContain('cannot create passkeys')
  })
})

describe('recovery codes', () => {
  test('are renewed and shown once', async () => {
    const page = await signedIn('/', factorsWith({ totp: true, recoveryCodesLeft: 3 }))
    page.answer('getFactors', factorsWith({ totp: true, recoveryCodesLeft: 10 }))
    page.answer('renewRecoveryCodes', { recoveryCodes: ['new-1', 'new-2'] })
    const wrapper = await start(page)
    expect(wrapper.get('[data-testid="recovery-codes-left"]').text()).toContain('3')
    await click(wrapper, 'Get new recovery codes')
    expect(wrapper.findAll('[data-testid="recovery-codes"] li').map((it) => it.text())).toEqual(['new-1', 'new-2'])
    expect(wrapper.get('[data-testid="recovery-codes-left"]').text()).toContain('10')
  })

  test('are not offered without a second factor', async () => {
    const wrapper = await start(await signedIn())
    expect(hasButton(wrapper, 'Get new recovery codes')).toBe(false)
  })
})

describe('sessions', () => {
  test('mark the current one, which ends only by signing out, and end one other by its id', async () => {
    const page = await consoleAt('/sessions')
    page.answer('getSessions', sessionsOf('other'), sessionsOf())
    page.answer('endSession', undefined)
    const wrapper = await start(page)
    const sessions = wrapper.findAll('[data-testid="session"]')
    expect(sessions.map((it) => it.attributes('data-current'))).toEqual(['true', 'false'])
    expect(sessions[0]?.text()).toContain('This browser')
    expect(sessions[0]?.find('button').exists()).toBe(false)
    expect(sessions[1]?.text()).toContain('Browser other')

    await click(wrapper, 'End session')
    expect(page.called('endSession')[0]?.args).toEqual(['other'])
    expect(wrapper.findAll('[data-testid="session"]')).toHaveLength(1)
    expect(hasButton(wrapper, 'Sign out everywhere else')).toBe(false)
  })

  test('the other ones end at once', async () => {
    const page = await consoleAt('/sessions')
    page.answer('getSessions', sessionsOf('one', 'two'), sessionsOf())
    page.answer('endOtherSessions', undefined)
    const wrapper = await start(page)
    expect(wrapper.findAll('[data-testid="session"]')).toHaveLength(3)
    await click(wrapper, 'Sign out everywhere else')
    expect(page.called('endOtherSessions')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="session"]')).toHaveLength(1)
  })

  test('ending them after an old login asks for a fresh one', async () => {
    const page = await signedIn('/sessions')
    page.answer('endSession', refused(403, 'reauthentication_required'))
    const wrapper = await start(page)
    await click(wrapper, 'End session')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')
    expect(wrapper.findAll('[data-testid="session"]')).toHaveLength(2)
  })
})

describe('logout', () => {
  test('ends the session and forgets the account: the cache, the codes shown once and every offer', async () => {
    const page = await signedIn('/', factorsWith({ totp: true, recoveryCodesLeft: 1 }))
    page.answer('renewRecoveryCodes', { recoveryCodes: ['secret-code'] })
    page.answer('logout', undefined)
    const wrapper = await start(page)
    await click(wrapper, 'Get new recovery codes')
    expect(wrapper.text()).toContain('secret-code')

    await click(wrapper, 'Sign out')
    expect(page.called('logout')).toHaveLength(1)
    expect(page.router.currentRoute.value.name).toBe('signed-out')
    expect(wrapper.get('[data-testid="signed-out"]').text()).toContain('You are signed out')
    expect(page.queries.getQueryCache().getAll()).toEqual([])
    const store = page.pinia.state.value['account']
    expect(store).toMatchObject({ recoveryCodes: [], offerToEndSessions: false, signedOut: 'done' })
    expect(wrapper.text()).not.toContain(EMAIL)
    expect(wrapper.text()).not.toContain('secret-code')
  })

  test('forgets the account also when the server cannot be reached, and says that the session may live on', async () => {
    const page = await signedIn('/sessions')
    page.answer('logout', new TypeError('Failed to fetch'), undefined)
    const wrapper = await start(page)
    await click(wrapper, 'Sign out')
    expect(page.queries.getQueryCache().getAll()).toEqual([])
    expect(wrapper.get('[data-testid="signed-out-unconfirmed"]').text()).toContain('did not confirm')
    expect(wrapper.text()).not.toContain(EMAIL)

    await click(wrapper, 'Sign out again')
    expect(page.called('logout')).toHaveLength(2)
    expect(wrapper.find('[data-testid="signed-out"]').exists()).toBe(true)
  })

  test('a refusal of the server counts as unconfirmed too', async () => {
    const page = await signedIn('/')
    page.answer('logout', refused(403))
    const wrapper = await start(page)
    await click(wrapper, 'Sign out')
    expect(wrapper.find('[data-testid="signed-out-unconfirmed"]').exists()).toBe(true)
    expect(page.queries.getQueryCache().getAll()).toEqual([])
  })
})

describe('a new release', () => {
  test('shows a banner and loads with the next page, not in the middle of a form', async () => {
    const page = await signedIn('/')
    const wrapper = await start(page)
    await wrapper.get('#current-password').setValue('typed')
    page.release.observe('1.1.0')
    await settle()
    expect(wrapper.get('[data-testid="update-banner"]').text()).toContain('next page')
    expect(page.browser.assigned).toEqual([])
    expect((wrapper.get('#current-password').element as HTMLInputElement).value).toBe('typed')

    await wrapper.findAll('a').find((it) => it.text() === 'Sessions')?.trigger('click')
    await settle()
    expect(page.browser.assigned).toEqual(['/console/sessions'])
    expect(page.router.currentRoute.value.name).toBe('security')
  })

  test('that the server requires asks for a reload and keeps the form', async () => {
    const page = await signedIn('/')
    const wrapper = await start(page)
    await wrapper.get('#current-password').setValue('typed')
    page.release.upgradeRequired()
    await settle()
    await button(wrapper, 'Reload').trigger('click')
    expect(page.browser.reloads).toBe(1)
    expect((wrapper.get('#current-password').element as HTMLInputElement).value).toBe('typed')
  })
})

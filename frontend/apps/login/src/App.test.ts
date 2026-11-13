import { qrModules } from '@idp/ui'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import App from './App.vue'
import { LINK_REFUSED, PASSWORD_LINK_REFUSED } from './messages'
import type { Page } from './pages'
import { FLOW_ID, flowAt, loginWith, refused } from './testing'

async function mounted(
  setup: ReturnType<typeof loginWith>,
  credentials: CredentialsContainer | null = null,
  page: Page = 'login',
  token: string | null = null,
) {
  const reloadPage = vi.fn()
  const wrapper = mount(App, {
    props: { login: setup.login, release: setup.release, credentials, host: 'acme.idp.test', reloadPage, page, token },
  })
  await settled()
  return { wrapper, reloadPage }
}

const onPage = (setup: ReturnType<typeof loginWith>, page: Page, token: string | null = null) => mounted(setup, null, page, token)

async function submitted(wrapper: Awaited<ReturnType<typeof mounted>>['wrapper']) {
  await wrapper.get('form').trigger('submit')
  await settled()
}

async function settled() {
  await flushPromises()
  await vi.dynamicImportSettled()
  await flushPromises()
}

describe('steps on screen', () => {
  test('the first step asks for the login and sends it', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('IDENTIFY'))
    setup.answer('submitLogin', flowAt('PASSWORD'))
    const { wrapper } = await mounted(setup)
    await wrapper.get('#login').setValue('  alice@acme.test ')
    await wrapper.get('form').trigger('submit')
    await settled()
    expect(setup.calls.at(-1)).toEqual({ operation: 'submitLogin', args: [setup.login.state.flow?.id, 'alice@acme.test'] })
    expect(wrapper.find('[data-step="PASSWORD"]').exists()).toBe(true)
  })

  test('the password field hides what is typed and suits a password manager', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('PASSWORD'))
    const { wrapper } = await mounted(setup)
    const field = wrapper.get('#password')
    expect(field.attributes('type')).toBe('password')
    expect(field.attributes('autocomplete')).toBe('current-password')
  })

  test('a passkey is offered on the first step only where the browser has passkeys', async () => {
    const without = loginWith()
    without.answer('startFlow', flowAt('IDENTIFY'))
    expect((await mounted(without)).wrapper.text()).not.toContain('Sign in with a passkey')
    const withPasskeys = loginWith()
    withPasskeys.answer('startFlow', flowAt('IDENTIFY'))
    expect((await mounted(withPasskeys, {} as CredentialsContainer)).wrapper.text()).toContain('Sign in with a passkey')
  })

  test('the second factor offers the factors of the step and switches between them', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('SECOND_FACTOR', ['TOTP', 'RECOVERY_CODE']))
    setup.answer('submitRecoveryCode', refused(422))
    const { wrapper } = await mounted(setup)
    expect(wrapper.get('#totp').attributes('autocomplete')).toBe('one-time-code')
    await wrapper.get('button[type="button"]').trigger('click')
    await wrapper.get('#recovery-code').setValue('abcd-efgh')
    await wrapper.get('form').trigger('submit')
    await settled()
    expect(setup.calls.at(-1)?.operation).toBe('submitRecoveryCode')
    expect(wrapper.get('[role="alert"]').text()).toContain('did not work')
  })

  test('a passkey that the browser cannot use is not offered as a second factor', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('SECOND_FACTOR', ['PASSKEY', 'TOTP']))
    const { wrapper } = await mounted(setup)
    expect(wrapper.find('#totp').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('passkey')
  })

  test('setting up an app shows its key as a QR code, as text and as a link to open it with', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('ENROLL_MFA', ['TOTP']))
    setup.answer('beginTotpEnrollment', { secret: 'JBSWY3DPEHPK3PXP' })
    const { wrapper } = await mounted(setup)
    await wrapper.get('button').trigger('click')
    await settled()
    expect(wrapper.get('[data-testid="totp-secret"]').text()).toBe('JBSW Y3DP EHPK 3PXP')
    const link = wrapper.get('a').attributes('href') ?? ''
    expect(link).toMatch(/^otpauth:\/\/totp\/acme\.idp\.test\?secret=JBSWY3DPEHPK3PXP&/)
    expect(wrapper.get('[data-testid="qr-code"] path').attributes('d')).toBe(qrModules(link).path)
  })

  test('recovery codes are shown once and the page goes on when they are saved', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('ENROLL_MFA', ['TOTP']))
    setup.answer('beginTotpEnrollment', { secret: 'JBSWY3DPEHPK3PXP' })
    setup.answer('confirmTotpEnrollment', { ...flowAt('COMPLETE'), recoveryCodes: ['aaaa-bbbb'] })
    setup.answer('completeFlow', { returnUrl: null })
    const { wrapper } = await mounted(setup)
    await wrapper.get('button').trigger('click')
    await settled()
    await wrapper.get('#totp').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await settled()
    expect(wrapper.get('[data-testid="recovery-codes"]').text()).toBe('aaaa-bbbb')
    await wrapper.get('button').trigger('click')
    await settled()
    expect(setup.navigation.assigned).toEqual(['/console/'])
  })

  test('an expired flow offers to start again', async () => {
    const setup = loginWith({ search: '?flow=3f0c2b4e-8a51-4c8e-b8a4-5d0f9e7b1a20' })
    setup.answer('getFlow', refused(410))
    const { wrapper } = await mounted(setup)
    expect(wrapper.get('a').attributes('href')).toBe('/login/')
  })
})

describe('pages of mail links', () => {
  test('an invitation asks for a password under the rules of the policy and recommends 15 characters', async () => {
    const { wrapper } = await onPage(loginWith(), 'invite', 'mail-token')
    expect(wrapper.get('h1').text()).toBe('Accept your invitation')
    const field = wrapper.get('#new-password')
    expect(field.attributes('type')).toBe('password')
    expect(field.attributes('autocomplete')).toBe('new-password')
    expect(field.attributes('minlength')).toBe('8')
    expect(wrapper.get('#password-rules').text()).toContain('15 or more is recommended')
  })

  test('an accepted invitation goes on to the step the policy asks for', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('IDENTIFY'))
    setup.answer('acceptInvitation', flowAt('ENROLL_MFA', ['TOTP']))
    const { wrapper } = await onPage(setup, 'invite', 'mail-token')
    await wrapper.get('#new-password').setValue('first password of mine')
    await submitted(wrapper)
    expect(setup.calls.at(-1)).toEqual({ operation: 'acceptInvitation', args: [FLOW_ID, 'mail-token', 'first password of mine'] })
    expect(wrapper.find('[data-step="ENROLL_MFA"]').exists()).toBe(true)
  })

  test('a refused invitation names both causes, because the answer does not tell them apart', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('IDENTIFY'))
    setup.answer('acceptInvitation', refused(422))
    const { wrapper } = await onPage(setup, 'invite', 'mail-token')
    await wrapper.get('#new-password').setValue('password')
    await submitted(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toBe(PASSWORD_LINK_REFUSED)
    expect(wrapper.find('#new-password').exists()).toBe(true)
  })

  test('an invitation link without its token says so and calls nothing', async () => {
    const setup = loginWith()
    const { wrapper } = await onPage(setup, 'invite')
    expect(wrapper.get('[role="alert"]').text()).toContain('link is incomplete')
    expect(setup.calls).toEqual([])
  })

  test('a reset link sets the new password with its token and sends the user to sign in', async () => {
    const setup = loginWith()
    setup.answer('confirmPasswordReset', null)
    const { wrapper } = await onPage(setup, 'reset-password', 'mail-token')
    expect(wrapper.get('#password-rules').text()).toContain('15 or more')
    await wrapper.get('#new-password').setValue('a new password of mine')
    await submitted(wrapper)
    expect(setup.calls).toEqual([{ operation: 'confirmPasswordReset', args: ['mail-token', 'a new password of mine'] }])
    expect(wrapper.get('[data-testid="password-changed"]').text()).toContain('signed out')
    expect(wrapper.get('a').attributes('href')).toBe('/login/')
  })

  test('a refused reset keeps the form and offers a new link', async () => {
    const setup = loginWith()
    setup.answer('confirmPasswordReset', refused(422))
    const { wrapper } = await onPage(setup, 'reset-password', 'mail-token')
    await wrapper.get('#new-password').setValue('password')
    await submitted(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toBe(PASSWORD_LINK_REFUSED)
    expect((wrapper.get('#new-password').element as HTMLInputElement).value).toBe('password')
    expect(wrapper.get('a').attributes('href')).toBe('/login/forgot-password')
  })

  test('a reset link without its token shows no form', async () => {
    const { wrapper } = await onPage(loginWith(), 'reset-password')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('link is incomplete')
  })

  test('a new address is confirmed only when the user asks, not when the link opens', async () => {
    const setup = loginWith()
    setup.answer('confirmEmailChange', null)
    const { wrapper } = await onPage(setup, 'confirm-email', 'mail-token')
    expect(setup.calls).toEqual([])
    await submitted(wrapper)
    expect(setup.calls).toEqual([{ operation: 'confirmEmailChange', args: ['mail-token'] }])
    expect(wrapper.find('[data-testid="email-changed"]').exists()).toBe(true)
  })

  test('a spent confirmation link says so', async () => {
    const setup = loginWith()
    setup.answer('confirmEmailChange', refused(422))
    const { wrapper } = await onPage(setup, 'confirm-email', 'mail-token')
    await submitted(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toBe(LINK_REFUSED)
  })
})

describe('forgotten passwords', () => {
  test('the password step leads to the reset form', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('PASSWORD'))
    const { wrapper } = await mounted(setup)
    expect(wrapper.get('a').attributes('href')).toBe('/login/forgot-password')
  })

  test('the form asks for a mail and answers the same whoever the login belongs to', async () => {
    const setup = loginWith()
    setup.answer('requestPasswordReset', null)
    const { wrapper } = await onPage(setup, 'forgot-password')
    await wrapper.get('#login').setValue(' alice@acme.test ')
    await submitted(wrapper)
    expect(setup.calls).toEqual([{ operation: 'requestPasswordReset', args: ['alice@acme.test'] }])
    expect(wrapper.get('[data-testid="reset-requested"]').text()).toContain('If an account uses this address')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  test('a limit on mails says how long to wait and keeps the form', async () => {
    const setup = loginWith()
    setup.answer('requestPasswordReset', refused(429, 600))
    const { wrapper } = await onPage(setup, 'forgot-password')
    await wrapper.get('#login').setValue('alice@acme.test')
    await submitted(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toContain('600 s')
    expect(wrapper.find('form').exists()).toBe(true)
  })

  test('a server that cannot be reached is told apart from a refusal', async () => {
    const setup = loginWith()
    setup.answer('requestPasswordReset', new TypeError('Failed to fetch'))
    const { wrapper } = await onPage(setup, 'forgot-password')
    await wrapper.get('#login').setValue('alice@acme.test')
    await submitted(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toContain('could not be reached')
  })
})

describe('releases on screen', () => {
  test('a new release shows a banner and keeps the form', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('PASSWORD'))
    const { wrapper } = await mounted(setup)
    await wrapper.get('#password').setValue('typed so far')
    setup.release.observe('2.0.0')
    await flushPromises()
    expect(wrapper.get('[data-testid="update-banner"]').text()).toContain('next step')
    expect((wrapper.get('#password').element as HTMLInputElement).value).toBe('typed so far')
  })

  test('426 keeps what was typed and offers a reload', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('PASSWORD'))
    setup.answer('submitPassword', refused(426))
    const { wrapper, reloadPage } = await mounted(setup)
    await wrapper.get('#password').setValue('typed before the release')
    await wrapper.get('form').trigger('submit')
    setup.release.upgradeRequired()
    await settled()
    expect((wrapper.get('#password').element as HTMLInputElement).value).toBe('typed before the release')
    await wrapper.get('[data-testid="update-banner"] button').trigger('click')
    expect(reloadPage).toHaveBeenCalledOnce()
  })

  test('without a new release there is no banner', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('PASSWORD'))
    const { wrapper } = await mounted(setup)
    expect(wrapper.find('[data-testid="update-banner"]').exists()).toBe(false)
  })
})

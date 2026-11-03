import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import App from './App.vue'
import { flowAt, loginWith, refused } from './testing'

async function mounted(setup: ReturnType<typeof loginWith>, credentials: CredentialsContainer | null = null) {
  const reloadPage = vi.fn()
  const wrapper = mount(App, {
    props: { login: setup.login, release: setup.release, credentials, host: 'acme.idp.test', reloadPage },
  })
  await settled()
  return { wrapper, reloadPage }
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

  test('setting up an app shows its key and a link to open it with', async () => {
    const setup = loginWith()
    setup.answer('startFlow', flowAt('ENROLL_MFA', ['TOTP']))
    setup.answer('beginTotpEnrollment', { secret: 'JBSWY3DPEHPK3PXP' })
    const { wrapper } = await mounted(setup)
    await wrapper.get('button').trigger('click')
    await settled()
    expect(wrapper.get('[data-testid="totp-secret"]').text()).toBe('JBSW Y3DP EHPK 3PXP')
    expect(wrapper.get('a').attributes('href')).toMatch(/^otpauth:\/\/totp\/acme\.idp\.test\?secret=JBSWY3DPEHPK3PXP&/)
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

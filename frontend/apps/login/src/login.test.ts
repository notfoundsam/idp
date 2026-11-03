import { describe, expect, test } from 'vitest'
import { DIRECT_LOGIN_TARGET } from './login'
import { FLOW_ID, flowAt, loginWith, refused } from './testing'

describe('starting', () => {
  test('a page opened by an SP reads the flow named in its URL', async () => {
    const { login, answer, calls } = loginWith({ search: `?flow=${FLOW_ID}` })
    answer('getFlow', flowAt('IDENTIFY'))
    await login.begin()
    expect(calls).toEqual([{ operation: 'getFlow', args: [FLOW_ID] }])
    expect(login.state.phase).toBe('step')
    expect(login.state.flow?.step).toBe('IDENTIFY')
  })

  test('a page opened directly starts a flow and keeps it in the URL, so a reload goes on with it', async () => {
    const { login, answer, navigation } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    await login.begin()
    expect(navigation.replaced).toBe(`/login/?flow=${FLOW_ID}`)
    expect(login.state.flow?.step).toBe('IDENTIFY')
  })

  test('a flow that a live session already completes goes on to the SP without a form', async () => {
    const { login, answer, navigation } = loginWith({ search: `?flow=${FLOW_ID}` })
    answer('getFlow', flowAt('COMPLETE'))
    answer('completeFlow', { returnUrl: `/saml/resume/${FLOW_ID}` })
    await login.begin()
    expect(navigation.assigned).toEqual([`/saml/resume/${FLOW_ID}`])
  })

  test('a flow that is gone reads as expired', async () => {
    const { login, answer } = loginWith({ search: `?flow=${FLOW_ID}` })
    answer('getFlow', refused(410))
    await login.begin()
    expect(login.state.phase).toBe('expired')
  })

  test('a server that cannot be reached fails the page instead of showing a form', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', new TypeError('Failed to fetch'))
    await login.begin()
    expect(login.state.phase).toBe('failed')
    expect(login.state.problem).toBe('unreachable')
  })
})

describe('steps', () => {
  test('each answer shows the step the server names next', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    answer('submitLogin', flowAt('PASSWORD'))
    answer('submitPassword', flowAt('SECOND_FACTOR', ['TOTP', 'RECOVERY_CODE']))
    await login.begin()
    await login.submit((id) => login.client.submitLogin(id, 'alice@acme.test'))
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(login.state.flow).toEqual(flowAt('SECOND_FACTOR', ['TOTP', 'RECOVERY_CODE']))
  })

  test('the last step completes the flow and leaves for where the SP waits', async () => {
    const { login, answer, navigation } = loginWith()
    answer('startFlow', flowAt('SECOND_FACTOR', ['TOTP']))
    answer('submitTotp', flowAt('COMPLETE'))
    answer('completeFlow', { returnUrl: `/saml/resume/${FLOW_ID}` })
    await login.begin()
    await login.submit((id) => login.client.submitTotp(id, '123456'))
    expect(navigation.assigned).toEqual([`/saml/resume/${FLOW_ID}`])
  })

  test('a login that came from no SP leaves for the console', async () => {
    const { login, answer, navigation } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', flowAt('COMPLETE'))
    answer('completeFlow', { returnUrl: null })
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(navigation.assigned).toEqual([DIRECT_LOGIN_TARGET])
  })

  test('a refused answer stays on the step and says so', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(422))
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'wrong'))
    expect(login.state.flow?.step).toBe('PASSWORD')
    expect(login.state.problem).toBe('rejected')
  })

  test('the problem is cleared on the next step, not on a retry', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(422), flowAt('SECOND_FACTOR', ['TOTP']))
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'wrong'))
    expect(login.state.problem).toBe('rejected')
    await login.submit((id) => login.client.submitPassword(id, 'right'))
    expect(login.state.problem).toBeNull()
  })

  test('a limit says how long to wait', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(429, 42))
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(login.state.problem).toBe('throttled')
    expect(login.state.retryAfter).toBe(42)
  })

  test('a conflict reads the flow again and goes on from where it stands', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(409))
    answer('getFlow', flowAt('SECOND_FACTOR', ['TOTP']))
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(login.state.flow?.step).toBe('SECOND_FACTOR')
    expect(login.state.problem).toBeNull()
  })

  test('a flow that expires on a step reads as expired', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(410))
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(login.state.phase).toBe('expired')
    expect(login.state.flow).toBeNull()
  })

  test('a passkey the user cancels is a refusal on the step', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    await login.begin()
    await login.submit(async () => {
      throw new DOMException('cancelled', 'NotAllowedError')
    })
    expect(login.state.phase).toBe('step')
    expect(login.state.problem).toBe('rejected')
  })

  test('an enrollment shows the recovery codes before the next step', async () => {
    const { login, answer, navigation } = loginWith()
    answer('startFlow', flowAt('ENROLL_MFA', ['PASSKEY', 'TOTP']))
    answer('confirmTotpEnrollment', { ...flowAt('COMPLETE'), recoveryCodes: ['aaaa-bbbb', 'cccc-dddd'] })
    answer('completeFlow', { returnUrl: null })
    await login.begin()
    await login.enroll((id) => login.client.confirmTotpEnrollment(id, '123456'))
    expect(login.state.phase).toBe('recovery-codes')
    expect(login.state.recoveryCodes).toEqual(['aaaa-bbbb', 'cccc-dddd'])
    expect(navigation.assigned).toEqual([])
    await login.codesSaved()
    expect(login.state.recoveryCodes).toEqual([])
    expect(navigation.assigned).toEqual([DIRECT_LOGIN_TARGET])
  })
})

describe('invitations', () => {
  test('the first password goes with the token on a flow that ignores a live session', async () => {
    const { login, answer, calls } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    answer('acceptInvitation', flowAt('ENROLL_MFA', ['TOTP']))
    login.beginInvitation('mail-token')
    expect(login.state.phase).toBe('invitation')
    await login.acceptInvitation('first password')
    expect(calls).toEqual([
      { operation: 'startFlow', args: [{ reauthenticate: true }] },
      { operation: 'acceptInvitation', args: [FLOW_ID, 'mail-token', 'first password'] },
    ])
    expect(login.state.phase).toBe('step')
    expect(login.state.flow?.step).toBe('ENROLL_MFA')
  })

  test('once the password is set the flow is in the URL, so a reload goes on with the next step', async () => {
    const { login, answer, navigation } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    answer('acceptInvitation', flowAt('ENROLL_MFA', ['TOTP']))
    login.beginInvitation('mail-token')
    expect(navigation.replaced).toBeNull()
    await login.acceptInvitation('first password')
    expect(navigation.replaced).toBe(`/login/?flow=${FLOW_ID}`)
  })

  test('a refused password keeps the form and tries again on the same flow', async () => {
    const { login, answer, calls } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    answer('acceptInvitation', refused(422), flowAt('COMPLETE'))
    answer('completeFlow', { returnUrl: null })
    login.beginInvitation('mail-token')
    await login.acceptInvitation('weak')
    expect(login.state.phase).toBe('invitation')
    expect(login.state.problem).toBe('rejected')
    await login.acceptInvitation('a much better password')
    expect(calls.filter((it) => it.operation === 'startFlow')).toHaveLength(1)
  })

  test('a flow that expired while the form was open is replaced on the next try', async () => {
    const { login, answer, calls } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'), flowAt('IDENTIFY'))
    answer('acceptInvitation', refused(410), flowAt('ENROLL_MFA', ['TOTP']))
    login.beginInvitation('mail-token')
    await login.acceptInvitation('first password')
    expect(login.state.phase).toBe('invitation')
    expect(login.state.problem).toBe('unexpected')
    await login.acceptInvitation('first password')
    expect(calls.filter((it) => it.operation === 'startFlow')).toHaveLength(2)
    expect(login.state.flow?.step).toBe('ENROLL_MFA')
  })

  test('a link without a token fails the page before any call', () => {
    const { login, calls } = loginWith()
    login.beginInvitation(null)
    expect(login.state.phase).toBe('failed')
    expect(login.state.problem).toBe('broken-link')
    expect(calls).toEqual([])
  })
})

describe('releases', () => {
  test('a step the page does not know reloads it, so the bundle of the new release draws it', async () => {
    const { login, answer, reloads } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    answer('submitLogin', flowAt('CONFIRM_DEVICE'))
    await login.begin()
    await login.submit((id) => login.client.submitLogin(id, 'alice@acme.test'))
    expect(reloads.count).toBe(1)
  })

  test('an unknown step after a refused reload fails the page', async () => {
    const { login, answer } = loginWith({ reloads: false })
    answer('startFlow', flowAt('CONFIRM_DEVICE'))
    await login.begin()
    expect(login.state.phase).toBe('failed')
  })

  test('a new release reloads on the next step, not on the form being filled in', async () => {
    const { login, answer, release, reloads } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(422), flowAt('SECOND_FACTOR', ['TOTP']))
    await login.begin()
    release.observe('1.1.0')
    await login.submit((id) => login.client.submitPassword(id, 'wrong'))
    expect(reloads.count).toBe(0)
    await login.submit((id) => login.client.submitPassword(id, 'right'))
    expect(reloads.count).toBe(1)
  })

  test('the same release never reloads', async () => {
    const { login, answer, release, reloads } = loginWith()
    answer('startFlow', flowAt('IDENTIFY'))
    answer('submitLogin', flowAt('PASSWORD'))
    await login.begin()
    release.observe('1.0.0')
    await login.submit((id) => login.client.submitLogin(id, 'alice@acme.test'))
    expect(reloads.count).toBe(0)
  })

  test('a new release does not hold up leaving for the SP', async () => {
    const { login, answer, release, reloads, navigation } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', flowAt('COMPLETE'))
    answer('completeFlow', { returnUrl: `/saml/resume/${FLOW_ID}` })
    await login.begin()
    release.observe('1.1.0')
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(reloads.count).toBe(0)
    expect(navigation.assigned).toHaveLength(1)
  })

  test('426 keeps the step on screen and asks for a reload', async () => {
    const { login, answer } = loginWith()
    answer('startFlow', flowAt('PASSWORD'))
    answer('submitPassword', refused(426))
    await login.begin()
    await login.submit((id) => login.client.submitPassword(id, 'secret'))
    expect(login.state.phase).toBe('step')
    expect(login.state.problem).toBe('outdated')
  })
})

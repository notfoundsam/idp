import { Release } from '@idp/api-client'
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import { reactive } from 'vue'
import { throttled } from './messages'
import NewPassword from './NewPassword.vue'
import { qrModules } from './qr'
import QrCode from './QrCode.vue'
import RecoveryCodeList from './RecoveryCodeList.vue'
import UpdateBanner from './UpdateBanner.vue'

describe('the recovery codes', () => {
  test('are listed in their order, each as code, with the advice to save them', () => {
    const wrapper = mount(RecoveryCodeList, { props: { codes: ['code-2', 'code-1'] } })
    expect(wrapper.text()).toContain('They are shown only now.')
    expect(wrapper.get('[data-testid="recovery-codes"]').findAll('li code').map((it) => it.text())).toEqual(['code-2', 'code-1'])
  })
})

describe('a throttled call', () => {
  test('names the wait when the server tells it', () => {
    expect(throttled(12)).toBe('Too many attempts. Try again in 12 s.')
    expect(throttled(null)).toBe('Too many attempts. Wait a moment and try again.')
  })
})

describe('the QR code', () => {
  test('is an SVG image named by its label that draws the modules of the value on white', () => {
    const value = 'otpauth://totp/acme.idp.test?secret=JBSWY3DPEHPK3PXP'
    const wrapper = mount(QrCode, { props: { value, label: 'QR code of the key' } })
    const { size, path } = qrModules(value)
    expect(wrapper.get('svg').attributes()).toMatchObject({ role: 'img', 'aria-label': 'QR code of the key', viewBox: `0 0 ${size} ${size}` })
    expect(wrapper.get('rect').attributes()).toMatchObject({ width: `${size}`, height: `${size}`, fill: '#fff' })
    expect(wrapper.get('path').attributes()).toMatchObject({ d: path, fill: '#000' })
    expect(wrapper.find('[style]').exists()).toBe(false)
  })
})

describe('the update banner', () => {
  test('is hidden while the release matches', () => {
    const wrapper = mount(UpdateBanner, { props: { release: reactive(new Release('1.0.0')), reloadPage: () => {} } })
    expect(wrapper.find('[data-testid="update-banner"]').exists()).toBe(false)
  })

  test('names the moment a newer release loads, a step by default or a page', async () => {
    const release = reactive(new Release('1.0.0'))
    release.observe('1.1.0')
    const step = mount(UpdateBanner, { props: { release, reloadPage: () => {} } })
    const page = mount(UpdateBanner, { props: { release, reloadPage: () => {}, next: 'page' } })
    expect(step.text()).toContain('the next step')
    expect(page.text()).toContain('the next page')
    expect(page.find('button').exists()).toBe(false)
  })

  test('offers a reload when the server requires one', async () => {
    let reloads = 0
    const release = reactive(new Release('1.0.0'))
    release.upgradeRequired()
    const wrapper = mount(UpdateBanner, { props: { release, reloadPage: () => reloads++ } })
    await wrapper.get('button').trigger('click')
    expect(reloads).toBe(1)
  })
})

describe('the new password', () => {
  test('is a password field that recommends 15 characters', async () => {
    const wrapper = mount(NewPassword, { props: { modelValue: '' } })
    expect(wrapper.get('input').attributes()).toMatchObject({ type: 'password', autocomplete: 'new-password', minlength: '8' })
    expect(wrapper.get('#password-rules').text()).toContain('15 or more is recommended')
    await wrapper.get('input').setValue('typed')
    expect(wrapper.emitted('update:modelValue')).toEqual([['typed']])
  })
})

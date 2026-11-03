import { Release } from '@idp/api-client'
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import { reactive } from 'vue'
import NewPassword from './NewPassword.vue'
import UpdateBanner from './UpdateBanner.vue'

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

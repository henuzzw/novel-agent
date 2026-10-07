import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import GenerationModeControl from '@/components/GenerationModeControl.vue'

describe('GenerationModeControl', () => {
  it('selects a generation mode with one radio change', async () => {
    const wrapper = mount(GenerationModeControl, { props: { modelValue: 'REVISE' } })
    expect(wrapper.find('input[value="REVISE"]').element).toHaveProperty('checked', true)
    await wrapper.find('input[value="REGENERATE"]').setValue(true)
    expect(wrapper.emitted('update:modelValue')).toEqual([['REGENERATE']])
    expect(wrapper.find('select').exists()).toBe(false)
  })
  it('has an accessible disabled group and customizable labels', () => {
    const wrapper = mount(GenerationModeControl, { props: { modelValue: 'REGENERATE', disabled: true, label: '打回方式', reviseLabel: '按原稿修订', regenerateLabel: '重写整章' } })
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.find('legend').text()).toBe('打回方式')
    expect(wrapper.text()).toContain('按原稿修订')
    expect(wrapper.text()).toContain('重写整章')
  })
})

import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { navigateWorkspaceButtons } from '@/lib/workspace-keyboard'

describe('workspace keyboard navigation', () => {
  it('cycles enabled tabs with arrows and reaches the ends with Home/End', async () => {
    const select = vi.fn()
    const wrapper = mount({ setup: () => ({ navigateWorkspaceButtons, select }),
      template: '<div @keydown="navigateWorkspaceButtons"><button @click="select(1)">一</button><button disabled>二</button><button @click="select(3)">三</button></div>' })
    const buttons = wrapper.findAll('button')
    await buttons[0]!.trigger('keydown', { key: 'ArrowRight' })
    expect(select).toHaveBeenLastCalledWith(3)
    await buttons[2]!.trigger('keydown', { key: 'ArrowRight' })
    expect(select).toHaveBeenLastCalledWith(1)
    await buttons[2]!.trigger('keydown', { key: 'Home' })
    expect(select).toHaveBeenLastCalledWith(1)
    await buttons[0]!.trigger('keydown', { key: 'End' })
    expect(select).toHaveBeenLastCalledWith(3)
    const calls = select.mock.calls.length
    await buttons[0]!.trigger('keydown', { key: 'ArrowLeft', altKey: true })
    expect(select).toHaveBeenCalledTimes(calls)
    wrapper.unmount()
  })
})

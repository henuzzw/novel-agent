import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import CreativeBoundaryPanel from '@/components/CreativeBoundaryPanel.vue'
import { getCreativeBoundaries, updateCreativeBoundaries, type CreativeBoundarySettings } from '@/api/projects'

vi.mock('@/api/projects', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/projects')>(),
  getCreativeBoundaries: vi.fn(), updateCreativeBoundaries: vi.fn(),
}))
const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.resetAllMocks() })
const state: CreativeBoundarySettings = { worldMode: 'AUTO', adaptationScope: 'AUTO', effectiveWorld: 'REALISTIC',
  effectiveScope: 'PRESERVE_CORE', assessed: true, reason: '原文为高中生活。', sourceImportId: 'source', version: 3 }
async function render() {
  vi.mocked(getCreativeBoundaries).mockResolvedValue({ ...state })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const wrapper = mount(CreativeBoundaryPanel, { props: { projectId: 'project' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('input[value=ALTERNATE]').exists()).toBe(true))
  return wrapper
}
it('shows saved inference and submits independent explicit choices with its version', async () => {
  const wrapper = await render()
  expect(wrapper.text()).toContain('原文为高中生活。')
  expect(wrapper.findAll('input[value=AUTO]').every(input => (input.element as HTMLInputElement).checked)).toBe(true)
  vi.mocked(updateCreativeBoundaries).mockResolvedValue({ ...state, worldMode: 'ALTERNATE', effectiveWorld: 'ALTERNATE', version: 4 })
  await wrapper.get('input[value=ALTERNATE]').setValue()
  await wrapper.get('button.button.primary').trigger('click')
  await flushPromises()
  expect(updateCreativeBoundaries).toHaveBeenCalledWith('project', { worldMode: 'ALTERNATE', adaptationScope: 'AUTO', version: 3, resetAssessment: false })
})
it('clears only the saved assessment and surfaces save failures', async () => {
  const wrapper = await render()
  vi.mocked(updateCreativeBoundaries).mockRejectedValue(new Error('保存失败'))
  await wrapper.get('button[aria-label="清除自动判断"]').trigger('click')
  await flushPromises()
  expect(updateCreativeBoundaries).toHaveBeenCalledWith('project', { worldMode: 'AUTO', adaptationScope: 'AUTO', version: 3, resetAssessment: true })
  expect(wrapper.get('[role=alert]').text()).toContain('保存失败')
})

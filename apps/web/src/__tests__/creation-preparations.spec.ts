import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import CreationPreparationPanel from '@/components/CreationPreparationPanel.vue'
import PreparationListEditor from '@/components/PreparationListEditor.vue'
import * as api from '@/api/creationPreparations'
import type { PreparationView } from '@/api/creationPreparations'
vi.mock('@/api/creationPreparations', () => ({ listPreparations: vi.fn(), listPreparationCheckpoints: vi.fn(), createPreparation: vi.fn(), preparationAction: vi.fn(), editPreparation: vi.fn(), getPreparation: vi.fn(), confirmPreparation: vi.fn() }))
vi.mock('@/composables/useGlobalModelSettings', () => ({ useGlobalModelSettings: () => ({ provider: ref('DEEPSEEK') }) }))
const view = (status: PreparationView['task']['status'], step: number, version: number): PreparationView => ({ stale: false, ruleWarnings: [], task: {
  id: 'task', projectId: 'p', mode: 'PREPARE', provider: 'DEEPSEEK', instruction: '', sourceBibleId: 'b', sourceOutlineId: 'o', sourceHash: '', sourceSnapshot: {},
  startChapter: 1, endChapter: 20, status, nextStep: step, worldDesign: null, plotDesign: null, reviewReport: step === 3 ? { summary: '检查规划，没有检查正文', issues: [], adjustments: [], planLinks: [] } : null, resultOutlineId: null, errorMessage: null, version, updatedAt: '',
} })
function render() {
  vi.mocked(api.listPreparationCheckpoints).mockResolvedValue([])
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(CreationPreparationPanel, { props: { projectId: 'p' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return { wrapper, client }
}
afterEach(() => vi.clearAllMocks())
describe('creation preparation workflow', () => {
  it('runs exactly three serial stages but never confirms automatically', async () => {
    vi.mocked(api.listPreparations).mockResolvedValue([])
    vi.mocked(api.createPreparation).mockResolvedValue(view('READY', 0, 0))
    vi.mocked(api.preparationAction).mockResolvedValueOnce(view('READY', 1, 2)).mockResolvedValueOnce(view('READY', 2, 4)).mockResolvedValueOnce(view('AWAITING_CONFIRMATION', 3, 6))
    const { wrapper, client } = render()
    try {
      await flushPromises(); await wrapper.findAll('button').find(button => button.text() === '准备并检查')!.trigger('click'); await flushPromises()
      expect(api.createPreparation).toHaveBeenCalledWith('p', expect.objectContaining({ mode: 'PREPARE', provider: 'DEEPSEEK' }))
      expect(api.preparationAction).toHaveBeenCalledTimes(3)
      expect(api.preparationAction).toHaveBeenNthCalledWith(2, 'p', expect.objectContaining({ version: 2 }), 'run-next')
      expect(api.confirmPreparation).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('检查规划，没有检查正文')
      const confirm = wrapper.findAll('button').find(button => button.text() === '应用规划资料')!
      expect(confirm.attributes('disabled')).toBeDefined()
    } finally { wrapper.unmount(); client.clear() }
  })
  it('stops after a failure without hidden retries or the next model call', async () => {
    vi.mocked(api.listPreparations).mockResolvedValue([]); vi.mocked(api.createPreparation).mockResolvedValue(view('READY', 0, 0))
    vi.mocked(api.preparationAction).mockResolvedValue(view('FAILED', 0, 2))
    const { wrapper, client } = render()
    try { await flushPromises(); await wrapper.findAll('button').find(button => button.text() === '准备并检查')!.trigger('click'); await flushPromises(); expect(api.preparationAction).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('明确重试') }
    finally { wrapper.unmount(); client.clear() }
  })
  it('blocks stale report confirmation even after a checkbox is checked', async () => {
    vi.mocked(api.listPreparations).mockResolvedValue([{ ...view('AWAITING_CONFIRMATION', 3, 6), stale: true }])
    const { wrapper, client } = render()
    try { await flushPromises(); const checkboxes = wrapper.findAll('input[type=checkbox]'); const checkbox = checkboxes[checkboxes.length - 1]!; expect(checkbox.attributes('disabled')).toBeDefined(); expect(wrapper.text()).toContain('来源资料已变化'); expect(api.confirmPreparation).not.toHaveBeenCalled() }
    finally { wrapper.unmount(); client.clear() }
  })
  it('edits structured fields without mutating cached records', async () => {
    const unit = { key: 'first', title: '误会升级', startChapter: 1, characters: ['林安'] }
    const wrapper = mount(PreparationListEditor, { props: { modelValue: [unit], title: '剧情单元', disabled: false, maxItems: 40, fields: [ { key: 'key', label: '标识' }, { key: 'title', label: '标题' }, { key: 'startChapter', label: '起始章', type: 'number' }, { key: 'characters', label: '人物', type: 'list' } ] } })
    try { await wrapper.findAll('textarea')[1]!.setValue('秘密暴露'); expect(unit.title).toBe('误会升级'); expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual([expect.objectContaining({ key: 'first', title: '秘密暴露' })]) }
    finally { wrapper.unmount() }
  })
})

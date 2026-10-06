import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import ImportAnalysisPanel from '@/components/ImportAnalysisPanel.vue'
import * as api from '@/api/importAnalyses'
import type { AnalysisView } from '@/api/importAnalyses'
vi.mock('@/api/importAnalyses', () => ({ listImportAnalyses: vi.fn(), createImportAnalysis: vi.fn(), importAnalysisAction: vi.fn(), confirmImportAnalysis: vi.fn(), getImportAnalysis: vi.fn() }))
vi.mock('@/composables/useGlobalModelSettings', () => ({ useGlobalModelSettings: () => ({ provider: ref('DEEPSEEK') }) }))
const view = (status: AnalysisView['report']['status'], version = 2): AnalysisView => ({ stale: false, report: { id: 'a', projectId: 'p', importId: 'i', provider: 'DEEPSEEK', sourceHash: 'h', slices: [{ chapterId: 'c', ordinal: 1, title: '纸条', start: 0, end: 4 }], nextSlice: status === 'READY' ? 0 : 1, status, content: { summaries: ['仅核对当前原文'], items: status === 'READY' ? [] : [{ key: 'b0_note', category: 'CLUE', certainty: 'INFERENCE', title: '纸条主人', description: '归属未确认，不能推测成事实', subjects: [], progress: 'UNRESOLVED', evidence: [{ chapterId: 'c', quote: '纸条', occurrence: 0 }] }] }, decisions: [], confirmedMode: null, errorMessage: null, version, updatedAt: '2026-10-05T00:00:00Z' } })
function render(mode: 'ADAPT_SOURCE' | 'CONTINUE_MANUSCRIPT' = 'ADAPT_SOURCE') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(ImportAnalysisPanel, { props: { projectId: 'p', importId: 'i', mode, chapters: [{ id: 'c', ordinal: 1, title: '纸条', content: '纸条在书里。', characterCount: 6, contentType: 'MANUSCRIPT', selected: true }] }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return { wrapper, client }
}
afterEach(() => vi.clearAllMocks())
it('requires complete decisions, rework instructions and explicit author confirmation', async () => {
  vi.mocked(api.listImportAnalyses).mockResolvedValue([view('REVIEW')]); const { wrapper, client } = render()
  try {
    await flushPromises(); const confirm = wrapper.findAll('button').find(v => v.text() === '确认解析报告')!
    expect(confirm.attributes('disabled')).toBeDefined()
    await wrapper.find('select[aria-label="纸条主人处理"]').setValue('REWORK')
    await wrapper.find('input[type=checkbox]').setValue(true); expect(confirm.attributes('disabled')).toBeDefined()
    await wrapper.find('textarea').setValue('改为未寄出的信'); await wrapper.find('input[type=checkbox]').setValue(true)
    vi.mocked(api.confirmImportAnalysis).mockResolvedValue({ ...view('CONFIRMED', 3), report: { ...view('CONFIRMED', 3).report, confirmedMode: 'ADAPT_SOURCE', decisions: [{ key: 'b0_note', action: 'REWORK', note: '改为未寄出的信' }] } })
    await confirm.trigger('click'); await flushPromises()
    expect(api.confirmImportAnalysis).toHaveBeenCalledWith('p', 'i', expect.anything(), 'ADAPT_SOURCE', [{ key: 'b0_note', action: 'REWORK', note: '改为未寄出的信' }], true)
    expect(wrapper.emitted('ready')!.slice(-1)[0]).toEqual([{ id: 'a', version: 3, mode: 'ADAPT_SOURCE' }])
    expect(wrapper.text()).toContain('分析推测')
  } finally { wrapper.unmount(); client.clear() }
})
it('continuation never offers reworking existing material and switching mode invalidates readiness', async () => {
  const confirmed = view('CONFIRMED', 3); confirmed.report.confirmedMode = 'CONTINUE_MANUSCRIPT'; confirmed.report.decisions = [{ key: 'b0_note', action: 'KEEP', note: '' }]
  vi.mocked(api.listImportAnalyses).mockResolvedValue([confirmed]); const { wrapper, client } = render('CONTINUE_MANUSCRIPT')
  try { await flushPromises(); expect(wrapper.find('option[value=REWORK]').exists()).toBe(false); expect(wrapper.emitted('ready')!.slice(-1)[0]).toEqual([{ id: 'a', version: 3, mode: 'CONTINUE_MANUSCRIPT' }]); await wrapper.setProps({ mode: 'ADAPT_SOURCE' }); expect(wrapper.emitted('ready')!.slice(-1)[0]).toEqual([null]) }
  finally { wrapper.unmount(); client.clear() }
})
it('does not start the next paid segment after leaving the page', async () => {
  vi.mocked(api.listImportAnalyses).mockResolvedValue([]); vi.mocked(api.createImportAnalysis).mockResolvedValue(view('READY', 0))
  let resolve!: (value: AnalysisView) => void; vi.mocked(api.importAnalysisAction).mockImplementation(() => new Promise(done => { resolve = done }))
  const { wrapper, client } = render()
  await flushPromises(); await wrapper.findAll('button').find(v => v.text() === '解析原文')!.trigger('click'); await flushPromises()
  wrapper.unmount(); resolve(view('READY', 2)); await flushPromises(); expect(api.importAnalysisAction).toHaveBeenCalledTimes(1); expect(api.confirmImportAnalysis).not.toHaveBeenCalled(); client.clear()
})
it('disables confirmation for a stale report', async () => {
  vi.mocked(api.listImportAnalyses).mockResolvedValue([{ ...view('REVIEW'), stale: true }]); const { wrapper, client } = render()
  try { await flushPromises(); expect(wrapper.text()).toContain('原文或章节选择已变化'); expect(wrapper.find('input[type=checkbox]').attributes('disabled')).toBeDefined(); expect(api.confirmImportAnalysis).not.toHaveBeenCalled() }
  finally { wrapper.unmount(); client.clear() }
})

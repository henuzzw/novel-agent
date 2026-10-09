import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import DraftLoopPanel from '@/components/DraftLoopPanel.vue'
import { draftChange, draftStopLabels, listDraftLoops, startDraftLoop, stopDraftLoop, type DraftLoop } from '@/api/draftLoops'
import type { ManuscriptVersion } from '@/api/writing'

vi.mock('@/api/draftLoops', async importOriginal => ({ ...await importOriginal<typeof import('@/api/draftLoops')>(),
  listDraftLoops: vi.fn(), startDraftLoop: vi.fn(), stopDraftLoop: vi.fn() }))
const cleanup: (() => void)[] = []
const record = (overrides: Partial<DraftLoop> = {}): DraftLoop => ({ id: 'loop', projectId: 'p', chapterNumber: 1,
  provider: 'DEEPSEEK', writeFirst: true, maxRounds: 10, status: 'RUNNING', phase: 'B', stopReason: null,
  manuscriptId: 'm1', rounds: [], errorMessage: null, createdAt: '2026-10-08T00:00:00Z', updatedAt: '2026-10-08T00:00:00Z', ...overrides })
beforeEach(() => { vi.mocked(listDraftLoops).mockResolvedValue([]); vi.mocked(startDraftLoop).mockResolvedValue(record()) })
afterEach(() => { cleanup.splice(0).forEach(f => f()); vi.resetAllMocks() })
function render(unsaved = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(DraftLoopPanel, { props: { projectId: 'p', chapter: 1, provider: 'DEEPSEEK', manuscript: null,
    externalBusy: false, unsaved }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() }); return wrapper
}
it('starts without author instruction or issue selection and restores active status', async () => {
  const wrapper = render(); await flushPromises()
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(startDraftLoop).toHaveBeenCalledWith('p', 1, 'DEEPSEEK', true, 10, expect.any(String))
  expect(wrapper.get('[role=status]').text()).toContain('B · 正在检查')
  expect(wrapper.text()).toContain('第 1 / 10 轮')
  expect(wrapper.emitted('busy-change')?.slice(-1)[0]).toEqual([true])
  expect(wrapper.find('textarea').exists()).toBe(false)
})
it('restores a running task and stops it using the persisted id', async () => {
  vi.mocked(listDraftLoops).mockResolvedValue([record({ phase: 'C' })])
  vi.mocked(stopDraftLoop).mockResolvedValue(record({ status: 'CANCELLED', stopReason: 'CANCELLED' }))
  const wrapper = render(); await flushPromises()
  expect(wrapper.text()).toContain('C · 正在裁决与修订')
  await wrapper.findAll('button').find(b => b.text() === '停止')!.trigger('click'); await flushPromises()
  expect(stopDraftLoop).toHaveBeenCalledWith('p', 1, 'loop')
  expect(wrapper.text()).toContain('已停止'); expect(wrapper.emitted('refresh-requested')).toBeDefined()
})
it('shows cap termination as unverified rather than passed', async () => {
  vi.mocked(listDraftLoops).mockResolvedValue([record({ status: 'STOPPED', stopReason: 'ROUND_LIMIT' })])
  const wrapper = render(); await flushPromises()
  expect(wrapper.get('[role=status]').text()).toContain('最后修订稿未复检')
  expect(draftStopLabels.C_NO_CHANGE).toContain('不代表 B 检查通过')
})
it('does not overwrite unsaved manuscript and disables start', async () => {
  const wrapper = render(true); await flushPromises()
  expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  expect(startDraftLoop).not.toHaveBeenCalled()
})
it('displays independent basis, candidate patches, C verdict and actual change comparison', async () => {
  const before = { title: '标题', body: '她递出纸条。', summary: '摘要', continuityNotes: [] }
  vi.mocked(listDraftLoops).mockResolvedValue([record({ status: 'STOPPED', stopReason: 'ROUND_LIMIT', rounds: [{
    number: 1, beforeManuscriptId: 'm1', before, afterManuscriptId: 'm2',
    check: { summary: '语句检查', issues: [{ id: 'F1', category: 'FLUENCY', description: '指代不明', evidence: '她递出纸条。',
      existingBasis: '正文人物声口', gap: '', candidateDesign: '动作过渡', impact: '不改变结果', suggestion: '明确动作主体' }] },
    judgment: { action: 'REVISED', decisions: [{ issueId: 'F1', verdict: 'ACCEPT', reason: '计划内细节' }],
      content: { ...before, body: '沈秋递出纸条。' }, changeSummary: ['F1 明确主体'] },
  }] })])
  const wrapper = render(); await flushPromises()
  expect(wrapper.text()).toContain('候选设计（非既定事实）')
  expect(wrapper.text()).toContain('C · 接受并修订')
  expect(wrapper.findAll('pre').map(p => p.text())).toEqual(['她递出纸条。', '沈秋递出纸条。'])
})
it('change view handles insertion deletion and unchanged text', () => {
  expect(draftChange('abc', 'abd')).toEqual({ before: 'abc', after: 'abd' })
  expect(draftChange('', 'new')).toEqual({ before: '', after: 'new' })
  expect(draftChange('old', '')).toEqual({ before: 'old', after: '' })
  expect(draftChange('same', 'same')).toEqual({ before: 'same', after: 'same' })
})
it('refreshes a completed artifact once when the manuscript arrives late, but preserves unsaved edits', async () => {
  vi.mocked(listDraftLoops).mockResolvedValue([record({ status: 'STOPPED', stopReason: 'B_CLEAR' })])
  const wrapper = render(true); await flushPromises()
  expect(wrapper.emitted('refresh-requested')).toBeUndefined()
  const source: ManuscriptVersion = { id: 'old', projectId: 'p', chapterNumber: 1, version: 0, versionNumber: 1,
    status: 'DRAFT', schemaVersion: 'manuscript/1', generatorType: 'DEEPSEEK', authorInstruction: null,
    sourceContractVersionId: null, baseManuscriptVersionId: null, sourceReviewVersionId: null,
    content: { title: '标题', body: '原稿', summary: '摘要', continuityNotes: [] }, changeSummary: [], createdAt: '', updatedAt: '' }
  await wrapper.setProps({ manuscript: source })
  expect(wrapper.emitted('refresh-requested')).toBeUndefined()
  await wrapper.setProps({ unsaved: false })
  expect(wrapper.emitted('refresh-requested')).toHaveLength(1)
  await wrapper.setProps({ manuscript: { ...source, id: 'another' } })
  expect(wrapper.emitted('refresh-requested')).toHaveLength(1)
})

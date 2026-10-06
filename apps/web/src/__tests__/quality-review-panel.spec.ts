import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QualityReviewPanel from '@/components/QualityReviewPanel.vue'
import { generateQualityReview, getQualityReview, reviseFromQuality, type QualityReview } from '@/api/writingQuality'
import { getManuscriptVersion, type ManuscriptVersion } from '@/api/writing'

vi.mock('@/api/writingQuality', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/writingQuality')>(),
  generateQualityReview: vi.fn(), getQualityReview: vi.fn(), reviseFromQuality: vi.fn(),
}))
vi.mock('@/api/writing', () => ({ getManuscriptVersion: vi.fn() }))
const manuscript: ManuscriptVersion = {
  id: 'm1', projectId: 'p', sourceContractVersionId: 'c', baseManuscriptVersionId: null, sourceReviewVersionId: null,
  chapterNumber: 1, versionNumber: 1, schemaVersion: 'manuscript/1', status: 'DRAFT', generatorType: 'DEEPSEEK',
  authorInstruction: null, changeSummary: [], version: 0, createdAt: '', updatedAt: '',
  content: { title: '纸条', body: '重复。。\n\n走到门口。\n\n重复。。', summary: '纸条', continuityNotes: [] },
}
const report: QualityReview = {
  id: 'q1', projectId: 'p', chapterNumber: 1, versionNumber: 1, sourceManuscriptId: 'm1', sourceManuscriptRowVersion: 0,
  generatorType: 'DEEPSEEK', current: true, createdAt: '', content: { summary: '质量建议', scores: [], issues: [
    { id: 'Q1', severity: 'INFO', category: 'FLUENCY', description: '标点', evidence: '重复。。', suggestion: '修正', resolved: false },
    { id: 'Q2', severity: 'WARNING', category: 'SCENE', description: '场景组织', evidence: '走到门口', suggestion: '组织', resolved: false },
  ] },
}
const clients: QueryClient[] = []
const wrappers: ReturnType<typeof mount>[] = []
function render(client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }), value = manuscript) {
  clients.push(client)
  const wrapper = mount(QualityReviewPanel, { props: { projectId: 'p', chapterNumber: 1, manuscript: value,
    provider: 'DEEPSEEK', instruction: '', hasUnsavedChanges: false, externalBusy: false },
  global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  wrappers.push(wrapper)
  return { wrapper, client }
}
describe('quality review authorization and freshness', () => {
  beforeEach(() => {
    vi.mocked(getQualityReview).mockResolvedValue(report)
    vi.mocked(getManuscriptVersion).mockResolvedValue(manuscript)
    vi.stubGlobal('HTMLElement', globalThis.HTMLElement)
    HTMLElement.prototype.scrollIntoView = vi.fn()
  })
  afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); clients.splice(0).forEach(c => c.clear()); vi.resetAllMocks(); vi.unstubAllGlobals() })

  it('keeps an unrun check unrun until explicitly checked, and skipping stays incomplete for the same saved version', async () => {
    vi.mocked(getQualityReview).mockResolvedValue(null)
    const { wrapper, client } = render()
    await vi.waitFor(() => expect(wrapper.text()).toContain('尚未检查'))
    expect(wrapper.attributes('data-state')).toBe('unrun')
    expect(generateQualityReview).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === '本次跳过')!.trigger('click')
    expect(wrapper.attributes('data-state')).toBe('skipped')
    expect(wrapper.text()).toContain('未完成检查')
    wrapper.unmount()
    const reopened = render(client).wrapper
    await flushPromises()
    expect(reopened.attributes('data-state')).toBe('skipped')
    await reopened.setProps({ manuscript: { ...manuscript, version: 1 } })
    await flushPromises()
    expect(reopened.attributes('data-state')).toBe('unrun')
  })

  it('requires explicit scene authorization and emits a new candidate followed by a required recheck', async () => {
    const candidate = { ...manuscript, id: 'm2', versionNumber: 2, baseManuscriptVersionId: 'm1', changeSummary: ['Q2：调整组织'] }
    vi.mocked(reviseFromQuality).mockResolvedValue(candidate)
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('valid'))
    const scene = wrapper.find('input[type="checkbox"][value="Q2"]')
    expect(scene.attributes('disabled')).toBeDefined()
    await wrapper.find('input[value="SCENE_STRUCTURE"]').setValue(true)
    expect(scene.attributes('disabled')).toBeUndefined()
    await scene.setValue(true)
    await wrapper.findAll('button').find(b => b.text() === '按建议生成润色稿')!.trigger('click')
    await vi.waitFor(() => expect(reviseFromQuality).toHaveBeenCalledWith('p', 1, 'q1', 'DEEPSEEK', ['Q2'], '', 'SCENE_STRUCTURE'))
    await vi.waitFor(() => expect(wrapper.emitted('revised')?.[0]?.[0]).toEqual(candidate))
    vi.mocked(getQualityReview).mockResolvedValue({ ...report, current: false })
    await wrapper.setProps({ manuscript: candidate })
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('stale'))
    expect(wrapper.text()).toContain('修订后需复检')
    expect(wrapper.findAll('button').find(b => b.text() === '按建议生成润色稿')!.attributes('disabled')).toBeDefined()
    expect(generateQualityReview).not.toHaveBeenCalled()
  })

  it('locates every exact occurrence in the bound source rather than highlighting a later manuscript', async () => {
    const { wrapper } = render(undefined, { ...manuscript, id: 'm2', versionNumber: 2, content: { ...manuscript.content, body: '另一个新稿' } })
    await vi.waitFor(() => expect(wrapper.findAll('button').find(b => b.text() === '定位原文')!.attributes('disabled')).toBeUndefined())
    expect(wrapper.attributes('data-state')).toBe('stale')
    await wrapper.findAll('button').find(b => b.text() === '定位原文')!.trigger('click')
    expect(wrapper.find('mark').text()).toBe('重复。。')
    expect(wrapper.find('.quality-original').text()).toContain('来源正文第 1 版 · 第 1 段')
    await wrapper.find('[aria-label="下一处原文"]').trigger('click')
    expect(wrapper.find('.quality-original').text()).toContain('第 3 段')
    expect(wrapper.find('.quality-original').text()).not.toContain('另一个新稿')
  })

  it('does not locate evidence in an edited row even when its text still happens to match', async () => {
    const { wrapper } = render(undefined, { ...manuscript, version: 1 })
    await vi.waitFor(() => expect(wrapper.text()).toContain('绑定版本原文不可定位'))
    expect(wrapper.attributes('data-state')).toBe('stale')
    expect(wrapper.findAll('button').find(b => b.text() === '定位原文')!.attributes('disabled')).toBeDefined()
  })

  it('blocks a cached current report when project invalidation cannot revalidate its new strategy', async () => {
    const { wrapper, client } = render()
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('valid'))
    vi.mocked(getQualityReview).mockRejectedValue(new Error('无法核对策略'))
    client.setQueryData(['project', 'p'], { creativeStrategy: 'FANQIE_GRIPPING' })
    await client.invalidateQueries({ queryKey: ['project', 'p'] })
    await vi.waitFor(() => expect(wrapper.text()).toContain('报告来源未核对'))
    expect(wrapper.attributes('data-state')).toBe('stale')
    expect(wrapper.find('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    expect(reviseFromQuality).not.toHaveBeenCalled()
  })

  it('blocks unsaved edits and shows rechecking only while a requested check is in progress', async () => {
    vi.mocked(getQualityReview).mockResolvedValue(null)
    const { wrapper } = render()
    await flushPromises()
    await wrapper.setProps({ hasUnsavedChanges: true })
    expect(wrapper.findAll('button').find(b => b.text() === '检查正文')!.attributes('disabled')).toBeDefined()
    await wrapper.setProps({ hasUnsavedChanges: false })
    let complete!: (value: QualityReview) => void
    vi.mocked(generateQualityReview).mockImplementation(() => new Promise(resolve => { complete = resolve }))
    await wrapper.findAll('button').find(b => b.text() === '检查正文')!.trigger('click')
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('rechecking'))
    complete(report)
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('valid'))
  })
})

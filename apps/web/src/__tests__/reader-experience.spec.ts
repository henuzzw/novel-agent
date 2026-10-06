import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReaderExperiencePanel from '@/components/ReaderExperiencePanel.vue'
import { ApiError } from '@/api/http'
import * as api from '@/api/readerExperience'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { listPlanOrigins } from '@/api/planningMaterials'
import { listForeshadows } from '@/api/writing'

vi.mock('@/api/planningMaterials', () => ({ listPlanOrigins: vi.fn(), syncPlanningMaterials: vi.fn() }))
vi.mock('@/api/writing', () => ({ listForeshadows: vi.fn() }))

vi.mock('@/api/readerExperience', async original => ({
  ...await original<typeof import('@/api/readerExperience')>(),
  listReaderExperiences: vi.fn(), listReaderExperienceSources: vi.fn(), getReaderExperienceSource: vi.fn(),
  getReaderExperienceMemory: vi.fn(), createReaderExperience: vi.fn(), updateReaderExperience: vi.fn(),
  deleteReaderExperience: vi.fn(), submitReaderExperience: vi.fn(),
}))

const emptyMemory: api.ReaderExperienceMemory = {
  schemaVersion: 'reader-experience-memory/1', mode: 'EXISTING_CANON_SUMMARIES', outlineId: null,
  outlineRowVersion: null, arcs: [], unassignedChapters: [],
}
const source: api.ReaderExperienceSource = {
  id: 'manuscript-1', projectId: 'project-a', rowVersion: 2, chapterNumber: 1, status: 'AUTHOR_ACCEPTED',
  body: '林安把纸条交给我。', fingerprint: 'a'.repeat(64), chapterCanonCommitId: null, canonManuscriptId: null,
  canonVersion: null, superseded: false,
}
const entry: api.ReaderExperienceEntry = {
  plan: { id: 'plan-1', projectId: 'project-a', kind: 'FORESHADOW', title: '纸条', promise: '找到纸条主人', setup: '签名',
    payoff: '认出主人', aftermath: '新的选择', plannedChapter: 3, version: 0, schemaVersion: 'reader-experience/1',
    deleted: false, createdAt: '2026-10-05', updatedAt: '2026-10-05' },
  state: 'PLANNED', stale: false, history: [],
}
const wrappers: ReturnType<typeof mount>[] = []
function render() {
  const wrapper = mount(ReaderExperiencePanel, { props: { projectId: 'project-a' },
    global: { plugins: [[VueQueryPlugin, { queryClient: new QueryClient() }]] } })
  wrappers.push(wrapper)
  return wrapper
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
beforeEach(() => {
  vi.mocked(listPlanOrigins).mockResolvedValue([])
  vi.mocked(listForeshadows).mockResolvedValue([])
  vi.mocked(api.listReaderExperiences).mockResolvedValue([structuredClone(entry)])
  vi.mocked(api.listReaderExperienceSources).mockResolvedValue([{ id: source.id, rowVersion: 2, chapterNumber: 1,
    title: '纸条', canon: false, superseded: false }])
  vi.mocked(api.getReaderExperienceSource).mockResolvedValue(source)
  vi.mocked(api.getReaderExperienceMemory).mockResolvedValue(emptyMemory)
  vi.mocked(api.createReaderExperience).mockResolvedValue(entry)
  vi.mocked(api.updateReaderExperience).mockResolvedValue({ ...entry, plan: { ...entry.plan, version: 1 } })
  vi.mocked(api.submitReaderExperience).mockResolvedValue({ ...entry, state: 'SET_UP', plan: { ...entry.plan, version: 1 } })
  vi.mocked(api.deleteReaderExperience).mockResolvedValue(undefined)
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.resetAllMocks() })

describe('reader experience panel', () => {
  it('distinguishes replaced planning sources from actual canon evidence', async () => {
    vi.mocked(listPlanOrigins).mockResolvedValue([{ planId: entry.plan.id, sourceKind: 'BIBLE', sourceId: 'bible-1', current: false }])
    vi.mocked(listForeshadows).mockResolvedValue([{ id: 'canon-1', title: '正文纸条', targetEffect: '追查签名', status: 'PLANTED',
      plannedResolveChapter: 3, canonVersionFrom: 1, evidence: '林安把纸条交给我。' }])
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('故事圣经规划 · 来源已替换')
    expect(wrapper.get('.entry').text()).toContain('计划中')
    expect(wrapper.get('.canon-foreshadows').text()).toContain('已埋设 · 正史 V1')
    expect(api.submitReaderExperience).not.toHaveBeenCalled()
  })
  it('shows an empty legacy ledger without claiming setup or completion', async () => {
    vi.mocked(api.listReaderExperiences).mockResolvedValue([])
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无承诺或伏笔记录')
    expect(wrapper.find('.entry').exists()).toBe(false)
    expect(wrapper.find('.history').exists()).toBe(false)
    await wrapper.findAll('nav button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('暂无有效正史章节摘要')
  })

  it('creates and edits a plan with idempotency and optimistic versions', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[aria-label="新增计划"]').trigger('click')
    const form = wrapper.get('[aria-label="计划编辑"]')
    await form.findAll('input')[1]!.setValue('纸条')
    await form.findAll('textarea')[0]!.setValue('找到纸条主人')
    await form.trigger('submit')
    await flushPromises()
    expect(api.createReaderExperience).toHaveBeenCalledWith('project-a', expect.objectContaining({
      expectedVersion: null, title: '纸条', promise: '找到纸条主人', requestId: expect.any(String),
    }))
    await wrapper.get('[aria-label="计划编辑"]').findAll('textarea')[0]!.setValue('确认签名')
    await wrapper.get('[aria-label="计划编辑"]').trigger('submit')
    await flushPromises()
    expect(api.updateReaderExperience).toHaveBeenCalledWith('project-a', 'plan-1', expect.objectContaining({ expectedVersion: 0, promise: '确认签名' }))
  })

  it('requires explicit author confirmation and exact displayed body evidence', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('.entry').trigger('click')
    const form = wrapper.get('[aria-label="实际进展提交"]')
    await form.findAll('select')[1]!.setValue(source.id)
    await flushPromises()
    expect(form.text()).toContain('林安把纸条交给我。')
    expect(form.text()).toContain('未正史')
    await form.findAll('textarea')[0]!.setValue('林安把纸条')
    await form.trigger('submit')
    expect(api.submitReaderExperience).not.toHaveBeenCalled()
    await form.get('input[type=checkbox]').setValue(true)
    await form.trigger('submit')
    await flushPromises()
    expect(api.submitReaderExperience).toHaveBeenCalledWith('project-a', 'plan-1', expect.objectContaining({
      expectedVersion: 0, state: 'SET_UP', manuscriptId: source.id, manuscriptRowVersion: 2,
      sourceFingerprint: source.fingerprint, evidence: '林安把纸条', authorConfirmed: true,
    }))
  })

  it('rejects invented evidence before submitting and deletes with a version', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('.entry').trigger('click')
    const form = wrapper.get('[aria-label="实际进展提交"]')
    await form.findAll('select')[1]!.setValue(source.id)
    await flushPromises()
    await form.findAll('textarea')[0]!.setValue('编造原文')
    await form.get('input[type=checkbox]').setValue(true)
    await form.trigger('submit')
    expect(wrapper.text()).toContain('证据须逐字存在')
    expect(api.submitReaderExperience).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="删除计划"]').trigger('click')
    await flushPromises()
    expect(api.deleteReaderExperience).toHaveBeenCalledWith('project-a', 'plan-1', 0, expect.any(String))
    expect(wrapper.text()).toContain('暂无承诺或伏笔记录')
  })

  it('blocks a version conflict until reload and preserves the unsaved draft', async () => {
    vi.mocked(api.updateReaderExperience).mockRejectedValue(new ApiError('版本冲突', 409))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('.entry').trigger('click')
    await wrapper.get('[aria-label="计划编辑"]').findAll('textarea')[0]!.setValue('新的承诺')
    await wrapper.get('[aria-label="计划编辑"]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('重新读取后再提交')
    expect((wrapper.get('[aria-label="计划编辑"] textarea').element as HTMLTextAreaElement).value).toBe('新的承诺')
    expect(wrapper.get('[aria-label="计划编辑"] fieldset').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="重新读取台账"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="计划编辑"] fieldset').attributes('disabled')).toBeUndefined()
  })

  it('ignores late reads after switching projects', async () => {
    const pending = deferred<api.ReaderExperienceEntry[]>()
    vi.mocked(api.listReaderExperiences).mockImplementation(project => project === 'project-a' ? pending.promise : Promise.resolve([]))
    const wrapper = render()
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    pending.resolve([entry])
    await flushPromises()
    expect(wrapper.text()).toContain('暂无承诺或伏笔记录')
    expect(wrapper.find('.entry').exists()).toBe(false)
  })

  it('keeps terminal, open and stale state actions distinct', () => {
    expect(api.readerExperienceTransitions(entry)).toEqual(['SET_UP', 'OPEN', 'ABANDONED'])
    expect(api.readerExperienceTransitions({ ...entry, state: 'PAYOFF' })).toEqual([])
    expect(api.readerExperienceTransitions({ ...entry, state: 'PAYOFF', stale: true })).toEqual(['PAYOFF'])
    expect(api.readerExperienceTransitions({ ...entry, state: 'OPEN' })).toContain('PAYOFF')
    expect(() => api.validateReaderExperienceEvidence({ requestId: 'key', expectedVersion: 0, state: 'SET_UP',
      manuscriptId: source.id, manuscriptRowVersion: 2, sourceFingerprint: 'b'.repeat(64), evidence: '纸条', authorNote: '', authorConfirmed: true }, source, 'project-a'))
      .toThrow('人物姓名已变化')
  })
})

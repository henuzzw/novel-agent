import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PlanningCheckpointPanel from '@/components/PlanningCheckpointPanel.vue'
import type { StoryBibleVersion, OutlineVersion } from '@/api/planning'
import * as api from '@/api/planningCheckpoints'

vi.mock('@/api/planningCheckpoints', async original => ({
  ...await original<typeof import('@/api/planningCheckpoints')>(),
  getCurrentPlanningBible: vi.fn(), listPlanningBatches: vi.fn(), getPlanningBatch: vi.fn(),
  createPlanningBatch: vi.fn(), actOnPlanningBatch: vi.fn(), assemblePlanningBatch: vi.fn(),
}))

const bible = { id: 'b1', projectId: 'project-a', status: 'PUBLISHED', version: 1, generationNumber: 1 } as StoryBibleVersion
const base: api.PlanningBatch = {
  id: 'batch-1', projectId: 'project-a', bibleId: 'b1', bibleRowVersion: 1, chapterTo: 4, chunkSize: 2,
  provider: 'DEEPSEEK', instruction: '承接线索', version: 0, status: 'READY', outlineVersionId: null,
  checkpoints: [], createdAt: '2026-10-05T08:00:00Z',
}
const chunk: api.PlanningCheckpoint = {
  id: 'c1', projectId: 'project-a', chunkKey: 'batch:batch-1:1', chapterFrom: 1, chapterTo: 2,
  source: { bibleId: 'b1', bibleRowVersion: 1, creativeStrategy: { strategy: 'STANDARD', policyVersion: 1 },
    provider: 'DEEPSEEK', instruction: '', dependencyHash: 'hash' },
  status: 'SUCCEEDED', attempt: 1, version: 2, result: { arcs: [] }, failure: null,
  createdAt: base.createdAt, updatedAt: base.createdAt,
}
const wrappers: ReturnType<typeof mount>[] = []
function render() {
  const wrapper = mount(PlanningCheckpointPanel, { props: { projectId: 'project-a', provider: 'DEEPSEEK' },
    global: { stubs: { GlobalModelBadge: true } } })
  wrappers.push(wrapper)
  return wrapper
}
function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find(value => value.text() === text)!
}
function state(value: api.PlanningBatch) {
  vi.mocked(api.listPlanningBatches).mockResolvedValue([value])
  vi.mocked(api.getPlanningBatch).mockResolvedValue(value)
}
beforeEach(() => {
  vi.mocked(api.getCurrentPlanningBible).mockResolvedValue(bible)
  state(structuredClone(base))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.resetAllMocks() })

describe('planning batch panel', () => {
  it('loads persisted current sources and advances exactly one explicit chunk', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('已完成 0 / 2 块')
    expect(button(wrapper, '生成下一块').attributes('disabled')).toBeUndefined()
    expect(button(wrapper, '拼装为大纲草稿').attributes('disabled')).toBeDefined()
    vi.mocked(api.actOnPlanningBatch).mockImplementation(async () => {
      const next = { ...base, version: 2, checkpoints: [chunk] }
      state(next)
      return next
    })
    await button(wrapper, '生成下一块').trigger('click')
    await flushPromises()
    expect(api.actOnPlanningBatch).toHaveBeenCalledTimes(1)
    expect(api.actOnPlanningBatch).toHaveBeenCalledWith('project-a', expect.objectContaining({ version: 0 }), 'run-next')
    expect(wrapper.text()).toContain('已完成 1 / 2 块')
    expect(button(wrapper, '拼装为大纲草稿').attributes('disabled')).toBeDefined()
  })

  it('does not retry a failed generation automatically and resumes explicitly', async () => {
    const failed = { ...base, status: 'FAILED' as const, version: 2,
      checkpoints: [{ ...chunk, status: 'FAILED' as const, result: null, failure: 'provider failed' }] }
    state(failed)
    const wrapper = render()
    await flushPromises()
    expect(api.actOnPlanningBatch).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('provider failed')
    vi.mocked(api.actOnPlanningBatch).mockImplementation(async () => {
      const next = { ...failed, status: 'READY' as const, version: 3,
        checkpoints: [{ ...chunk, status: 'PENDING' as const, result: null }] }
      state(next)
      return next
    })
    await button(wrapper, '恢复批次').trigger('click')
    await flushPromises()
    expect(api.actOnPlanningBatch).toHaveBeenCalledTimes(1)
    expect(api.actOnPlanningBatch).toHaveBeenLastCalledWith('project-a', failed, 'resume')
    expect(button(wrapper, '生成下一块').attributes('disabled')).toBeUndefined()
  })

  it('blocks a changed bible even when its row version is equal', async () => {
    vi.mocked(api.getCurrentPlanningBible).mockResolvedValue({ ...bible, id: 'b2' })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('当前已发布圣经已变化')
    expect(button(wrapper, '生成下一块').attributes('disabled')).toBeDefined()
    expect(api.actOnPlanningBatch).not.toHaveBeenCalled()
  })

  it('keeps an idempotency key after an ambiguous creation failure', async () => {
    vi.mocked(api.createPlanningBatch).mockRejectedValue(new Error('network'))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const calls = vi.mocked(api.createPlanningBatch).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[0]![1].requestId).toBe(calls[1]![1].requestId)
    expect(calls[0]![1]).toMatchObject({ expectedBibleId: 'b1', expectedBibleVersion: 1 })
  })

  it('assembles only after all chunks and emits a draft without publishing', async () => {
    const complete = { ...base, version: 4, checkpoints: [chunk, { ...chunk, id: 'c2', chapterFrom: 3, chapterTo: 4 }] }
    state(complete)
    const draft = { id: 'o2', projectId: 'project-a', status: 'DRAFT' } as OutlineVersion
    vi.mocked(api.assemblePlanningBatch).mockResolvedValue(draft)
    const wrapper = render()
    await flushPromises()
    expect(button(wrapper, '生成下一块').attributes('disabled')).toBeDefined()
    await button(wrapper, '拼装为大纲草稿').trigger('click')
    await flushPromises()
    expect(api.assemblePlanningBatch).toHaveBeenCalledWith('project-a', complete)
    expect(wrapper.emitted('assembled')).toEqual([[draft]])
  })

  it('ignores a late read after switching projects', async () => {
    let resolve!: (value: api.PlanningBatch[]) => void
    const pending = new Promise<api.PlanningBatch[]>(yes => { resolve = yes })
    vi.mocked(api.listPlanningBatches).mockImplementation(id => id === 'project-a' ? pending : Promise.resolve([]))
    vi.mocked(api.getCurrentPlanningBible).mockImplementation(async id => id === 'project-a' ? bible : null)
    const wrapper = render()
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    resolve([base])
    await flushPromises()
    expect(wrapper.text()).toContain('尚无规划批次')
    expect(wrapper.find('.planning-progress').exists()).toBe(false)
  })

  it('blocks editing while the parent is saving and never submits template generation', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.setProps({ externalBusy: true })
    expect(button(wrapper, '生成下一块').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(api.createPlanningBatch).not.toHaveBeenCalled()
    await wrapper.setProps({ externalBusy: false, provider: 'LOCAL_TEMPLATE' })
    await wrapper.get('form').trigger('submit')
    expect(api.createPlanningBatch).not.toHaveBeenCalled()
  })
})

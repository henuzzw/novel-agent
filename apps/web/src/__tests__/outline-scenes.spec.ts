import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OutlinePanel from '@/components/OutlinePanel.vue'
import * as api from '@/api/planning'
import type { OutlineVersion } from '@/api/planning'

vi.mock('@/api/planning', () => ({ generateOutline: vi.fn(), getCurrentOutline: vi.fn(), getLatestOutline: vi.fn(),
  getOutlineVersion: vi.fn(), listOutlineVersions: vi.fn(), publishOutline: vi.fn(), updateOutline: vi.fn() }))
vi.mock('@/composables/useGlobalModelSettings', async () => {
  const { ref } = await import('vue')
  return { useGlobalModelSettings: () => ({ provider: ref('LOCAL_CODEX') }) }
})
let wrapper: VueWrapper | undefined
let client: QueryClient
function outline(): OutlineVersion {
  return { id: 'o1', projectId: 'p1', generationNumber: 1, status: 'DRAFT', version: 0, generatorType: 'LOCAL_CODEX',
    sourceBibleVersionId: 'b1', changeSummary: [], baseOutlineVersionId: null, createdAt: '', updatedAt: '',
    schemaVersion: 'v1', authorInstruction: null,
    wordBudget: { targetWords: 50000, acceptableMinWords: 40000, acceptableMaxWords: 60000, recommendedVolumeCount: 1,
      recommendedChapterCount: 20, averageChapterWords: 2500, recommendedChapterMinWords: 2000, recommendedChapterMaxWords: 3000 },
    content: { title: '返工', premise: '选择和责任', structureSummary: '误会暴露', pacingStrategy: '行动与后果',
      suggestedMinWords: 40000, suggestedMaxWords: 60000, arcs: [{ ordinal: 1, title: '第一幕', objective: '承担责任',
        mainConflict: '条件缺失', turningPoint: '署名被撤回', outcome: '完成返工', suggestedMinWords: 40000, suggestedMaxWords: 60000,
        chapters: [{ number: 1, title: '停止传阅', pov: '江澈', objective: '收回问题版本', coreEvent: '发现遗漏',
          reveal: '条件缺失', endingHook: '仍有版本未收回', suggestedMinWords: 2000, suggestedMaxWords: 3000, status: 'PLANNED',
          sceneOutline: '教室：停止传阅。\n核对原稿，发现漏项，决定逐页返工。', sceneOutlineNeedsUpdate: false }] }] } }
}
async function render(value = outline()) {
  vi.mocked(api.getLatestOutline).mockResolvedValue(value)
  vi.mocked(api.getCurrentOutline).mockResolvedValue(null)
  vi.mocked(api.listOutlineVersions).mockResolvedValue([])
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  wrapper = mount(OutlinePanel, { props: { projectId: 'p1' }, global: {
    plugins: [[VueQueryPlugin, { queryClient: client }]],
    stubs: { PlanningCheckpointPanel: true, GlobalModelBadge: true, ReaderExperienceSeedEditor: true },
  } })
  await flushPromises()
  return wrapper
}
const scenes = (view: VueWrapper) => view.find('.chapter-scene-field textarea')

describe('outline scenes', () => {
  beforeEach(() => vi.resetAllMocks())
  afterEach(() => { wrapper?.unmount(); client?.clear() })

  it('loads saved scenes without generating and saves edited prose with the outline', async () => {
    const view = await render()
    expect((scenes(view).element as HTMLTextAreaElement).value).toContain('决定逐页返工')
    expect(api.generateOutline).not.toHaveBeenCalled()
    await scenes(view).setValue('先收回所有版本，再共同核对。')
    vi.mocked(api.updateOutline).mockResolvedValue(outline())
    await view.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()
    expect(api.updateOutline).toHaveBeenCalledWith('p1', expect.anything(), expect.objectContaining({
      arcs: [expect.objectContaining({ chapters: [expect.objectContaining({ sceneOutline: '先收回所有版本，再共同核对。' })] })],
    }))
  })

  it('marks unchanged scenes when chapter or upstream basis changes without overwriting them', async () => {
    const view = await render()
    await view.find('.chapter-fields textarea').setValue('先通知所有持有者')
    expect(view.text()).toContain('场景底稿待核对')
    expect((scenes(view).element as HTMLTextAreaElement).value).toContain('决定逐页返工')
    expect(api.generateOutline).not.toHaveBeenCalled()
    await scenes(view).setValue('通知所有持有者后再安排返工。')
    expect(view.text()).not.toContain('场景底稿待核对')
  })

  it('shows persisted stale status on published, read-only scenes', async () => {
    const value = outline()
    value.status = 'PUBLISHED'
    value.content.arcs[0]!.chapters[0]!.sceneOutlineNeedsUpdate = true
    const view = await render(value)
    expect(scenes(view).attributes('disabled')).toBeDefined()
    expect(view.text()).toContain('场景底稿待核对')
  })

  it('shows missing scenes without manufacturing text or unsaved changes', async () => {
    const value = outline()
    delete value.content.arcs[0]!.chapters[0]!.sceneOutline
    delete value.content.arcs[0]!.chapters[0]!.sceneOutlineNeedsUpdate
    const view = await render(value)
    expect(view.text()).toContain('待补充')
    expect(view.text()).not.toContain('大纲有未保存修改')
    expect((scenes(view).element as HTMLTextAreaElement).value).toBe('')
    expect(api.generateOutline).not.toHaveBeenCalled()
  })
})

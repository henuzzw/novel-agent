import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StylePreviewEditor from '@/components/StylePreviewEditor.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { checkStylePreview, reviseStylePreview, stylePreviewKey, type StylePreview, type StylePreviewReview } from '@/api/writingQuality'

vi.mock('@/api/writingQuality', async (original) => ({ ...await original<typeof import('@/api/writingQuality')>(),
  checkStylePreview: vi.fn(), reviseStylePreview: vi.fn() }))
const value: StylePreview = { sourceOutlineVersionId: 'outline-1', sourceOutlineRowVersion: 2, outlineGenerationNumber: 1,
  sourceBibleVersionId: 'bible-1', profile: { name: '轻快口语', narrativeVoice: '自然', sentenceRhythm: '变化',
    descriptionFocus: '细节', dialogueStyle: '口语', emotionalExpression: '反应', pacing: '轻快', avoidPatterns: [] },
  provider: 'DEEPSEEK', targetWords: 800, previewMode: 'MODEL', content: { title: '选座', body: '她手里攥着座位名单的边角。' } }
const report: StylePreviewReview = { id: 'report-1', preview: value, provider: 'DEEPSEEK', reviewMode: 'MODEL', revisionAttempted: false,
  content: { summary: '名单来源需核对', scores: [], issues: [{ id: 'E1', severity: 'WARNING', category: 'LOGIC',
    description: '持有名单的依据不明', evidence: '攥着座位名单', suggestion: '删除道具，不编造来源', resolved: false }] } }

describe('preview editor', () => {
  const cleanup: Array<() => void> = []
  beforeEach(() => {
    useGlobalModelSettings().apply({ provider: 'DEEPSEEK', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 1 })
    vi.mocked(checkStylePreview).mockResolvedValue(report); vi.mocked(reviseStylePreview).mockResolvedValue({ ...value, content: { title: '选座', body: '她走到座位旁。' } })
  })
  afterEach(() => { cleanup.splice(0).forEach((dispose) => dispose()); vi.resetAllMocks() })
  function render(auto = false, current = true) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    client.setQueryData(['style-preview-auto-check', 'project-1', stylePreviewKey(value)], auto)
    const wrapper = mount(StylePreviewEditor, { props: { projectId: 'project-1', value, current, externalBusy: false, instruction: '保留事件' },
      global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
    cleanup.push(() => { wrapper.unmount(); client.clear() })
    return { wrapper, client }
  }

  it('checks once automatically using the displayed candidate, not an applied style', async () => {
    const { wrapper } = render(true)
    await vi.waitFor(() => expect(wrapper.text()).toContain('名单来源需核对'))
    expect(checkStylePreview).toHaveBeenCalledExactlyOnceWith('project-1', { source: {
      outlineVersionId: 'outline-1', expectedOutlineVersion: 2, profile: value.profile, provider: 'DEEPSEEK', targetWords: 800,
      instruction: '保留事件',
    }, content: value.content })
    expect(reviseStylePreview).not.toHaveBeenCalled()
  })

  it('requires selected evidence and returns a new candidate without changing the original', async () => {
    const { wrapper } = render(true)
    await vi.waitFor(() => expect(wrapper.find('.editor-issue').exists()).toBe(true))
    expect(wrapper.findAll('button')[1]!.attributes('disabled')).toBeDefined()
    await wrapper.find('input[type=checkbox]').setValue(true)
    await wrapper.findAll('button')[1]!.trigger('click')
    await vi.waitFor(() => expect(wrapper.emitted('revised')).toHaveLength(1))
    expect(reviseStylePreview).toHaveBeenCalledExactlyOnceWith('project-1', 'report-1', 'DEEPSEEK', ['E1'], '保留事件')
    expect(value.content.body).toContain('名单')
    expect(wrapper.findAll('button')[1]!.attributes('disabled')).toBeDefined()
  })

  it('shows check errors and allows explicit retry without automatically looping', async () => {
    vi.mocked(checkStylePreview).mockRejectedValueOnce(new Error('检查模型不可用'))
    const { wrapper } = render(true)
    await vi.waitFor(() => expect(wrapper.text()).toContain('检查模型不可用'))
    expect(checkStylePreview).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('revised')).toBeUndefined()
    await wrapper.find('button').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('名单来源需核对'))
    expect(checkStylePreview).toHaveBeenCalledTimes(2)
  })

  it('does not check or revise stale outlines', () => {
    const { wrapper } = render(true, false)
    expect(wrapper.text()).toContain('旧样例不能检查或修订')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    expect(checkStylePreview).not.toHaveBeenCalled()
  })

  it('waits for a source version refresh before starting the requested automatic check', async () => {
    const { wrapper } = render(true, false)
    expect(checkStylePreview).not.toHaveBeenCalled()
    await wrapper.setProps({ current: true })
    await vi.waitFor(() => expect(wrapper.text()).toContain('名单来源需核对'))
    await wrapper.setProps({ current: false })
    await wrapper.setProps({ current: true })
    expect(checkStylePreview).toHaveBeenCalledTimes(1)
  })

  it('prevents semantic template revision and does not restart a cached automatic check', async () => {
    vi.mocked(checkStylePreview).mockResolvedValue({ ...report, reviewMode: 'RULES' })
    const { wrapper } = render(true)
    await vi.waitFor(() => expect(wrapper.text()).toContain('未判断语义逻辑'))
    await wrapper.find('input[type=checkbox]').setValue(true)
    useGlobalModelSettings().apply({ provider: 'LOCAL_TEMPLATE', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 2 })
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button')[1]!.attributes('disabled')).toBeDefined()
    expect(reviseStylePreview).not.toHaveBeenCalled()
  })

  it('isolates late revision results from another project', async () => {
    let finish!: (v: StylePreview) => void
    vi.mocked(reviseStylePreview).mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const { wrapper } = render(true)
    await vi.waitFor(() => expect(wrapper.find('input').exists()).toBe(true))
    await wrapper.find('input[type=checkbox]').setValue(true)
    await wrapper.findAll('button')[1]!.trigger('click')
    await vi.waitFor(() => expect(reviseStylePreview).toHaveBeenCalled())
    await wrapper.setProps({ projectId: 'project-2' })
    finish(value)
    await vi.waitFor(() => expect(wrapper.find('button').attributes('disabled')).toBeUndefined())
    expect(wrapper.emitted('revised')).toBeUndefined()
  })
})

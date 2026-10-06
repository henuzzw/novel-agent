import { mount } from '@vue/test-utils'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getLatestOutline, getLatestStoryBible, type OutlineVersion, type StoryBibleVersion } from '@/api/planning'
import { analyzeWritingStyle, applyWritingStyle, generateStylePreview, getStylePresets, getWritingStyle, recommendWritingStyle, checkStylePreview, type StylePreview, type StyleRecommendation, type WritingStyleProfile } from '@/api/writingQuality'
import WritingStylePanel from '@/components/WritingStylePanel.vue'
import StylePreviewEditor from '@/components/StylePreviewEditor.vue'

vi.mock('@/api/planning', () => ({ getLatestOutline: vi.fn(), getLatestStoryBible: vi.fn() }))
vi.mock('@/api/writingQuality', () => ({
  getWritingStyle: vi.fn(), getStylePresets: vi.fn(), generateStylePreview: vi.fn(),
  applyWritingStyle: vi.fn(), analyzeWritingStyle: vi.fn(), uploadWritingStyle: vi.fn(),
  recommendWritingStyle: vi.fn(),
  checkStylePreview: vi.fn(), reviseStylePreview: vi.fn(),
  stylePreviewKey: (value: StylePreview) => JSON.stringify([value.sourceOutlineVersionId, value.sourceOutlineRowVersion, value.profile, value.content]),
}))

const profile: WritingStyleProfile = { name: '悬疑克制', narrativeVoice: '冷静', sentenceRhythm: '长短交错',
  descriptionFocus: '线索', dialogueStyle: '自然口语', emotionalExpression: '行动', pacing: '逐步揭示', avoidPatterns: ['空泛议论'] }
const outline = { id: 'outline-1', version: 3, generationNumber: 2, status: 'DRAFT',
  content: { arcs: [{ chapters: [{ number: 1, title: '旧信' }] }] } } as OutlineVersion
const result: StylePreview = { sourceOutlineVersionId: 'outline-1', sourceOutlineRowVersion: 3,
  outlineGenerationNumber: 2, sourceBibleVersionId: 'bible-1', profile, provider: 'LOCAL_CODEX', targetWords: 800,
  previewMode: 'MODEL', content: { title: '旧信', body: '她停在门口，认出了信封上的字迹。' } }
const bible = { id: 'bible-1', version: 2, generationNumber: 1, status: 'DRAFT' } as StoryBibleVersion
const recommendation: StyleRecommendation = { sourceBibleVersionId: 'bible-1', sourceBibleRowVersion: 2,
  bibleGenerationNumber: 1, provider: 'LOCAL_CODEX', recommendationMode: 'MODEL', summary: '适合冷静观察',
  recommendations: [{ profile, reason: '逐步揭示人物误会', tradeoff: '不牺牲人物温度', evidence: [{ field: 'theme', quote: '信任' }] }] }

describe('style preview workflow', () => {
  const mounted: Array<() => void> = []
  beforeEach(() => {
    useGlobalModelSettings().apply({ provider: 'LOCAL_CODEX', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 0 })
    vi.mocked(getWritingStyle).mockResolvedValue({ profile: null, version: 4 })
    vi.mocked(getStylePresets).mockResolvedValue([profile])
    vi.mocked(getLatestOutline).mockResolvedValue(outline)
    vi.mocked(getLatestStoryBible).mockResolvedValue(bible)
    vi.mocked(recommendWritingStyle).mockResolvedValue(recommendation)
    vi.mocked(generateStylePreview).mockResolvedValue(result)
    vi.mocked(checkStylePreview).mockResolvedValue({ id: 'report-1', preview: result, provider: 'LOCAL_CODEX', reviewMode: 'MODEL',
      content: { summary: '编辑检查完成', scores: [], issues: [] }, revisionAttempted: false })
    vi.mocked(applyWritingStyle).mockResolvedValue({ profile, version: 5 })
  })
  afterEach(() => { mounted.splice(0).forEach((dispose) => dispose()); vi.resetAllMocks() })
  function render() {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingStylePanel, { props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] } })
    mounted.push(() => { wrapper.unmount(); queryClient.clear() })
    return { wrapper, queryClient }
  }

  it('previews an unapplied edited preset using saved first-chapter outline, then adopts explicitly', async () => {
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.text()).toContain('旧信'))
    await wrapper.find('select').setValue(profile.name)
    await wrapper.find('form textarea').setValue('贴近人物，冷静观察')
    await wrapper.find('.style-preview button').trigger('click')
    await vi.waitFor(() => expect(generateStylePreview).toHaveBeenCalledWith('project-1', expect.objectContaining({
      outlineVersionId: 'outline-1', expectedOutlineVersion: 3, provider: 'LOCAL_CODEX', targetWords: 800,
      profile: expect.objectContaining({ name: profile.name, narrativeVoice: '贴近人物，冷静观察' }),
    })))
    await vi.waitFor(() => expect(wrapper.find('.preview-body').text()).toBe(result.content.body))
    expect(applyWritingStyle).not.toHaveBeenCalled()
    await wrapper.find('.preview-result button').trigger('click')
    await vi.waitFor(() => expect(applyWritingStyle).toHaveBeenCalledWith('project-1', profile, 4))
  })

  it('does not offer generation without first-chapter planning', async () => {
    vi.mocked(getLatestOutline).mockResolvedValue(null)
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.text()).toContain('尚无第一章大纲'))
    await wrapper.find('select').setValue(profile.name)
    expect(wrapper.find('.style-preview button').attributes('disabled')).toBeDefined()
    expect(generateStylePreview).not.toHaveBeenCalled()
  })

  it('edits deep techniques without losing provenance or mutating cached preset and saves the whole snapshot', async () => {
    const rich: WritingStyleProfile = { ...profile, basePresetId: 'street-humor', basePresetVersion: 1, craft: {
      narratorPosition: '观察人物如何维持体面', paragraphMoves: '动作引出窘境，回应改变处境', sentenceMoves: '完整复句与短收束',
      wordChoice: '具体日常词', dialogueMoves: '不同人物保留自己的顾忌', rhetoricMoves: '笑意来自行动落差',
      sceneVariants: '冲突处减弱幽默', revisionChecks: '核对幽默的实际依据', examples: [{ scene: '让位', facts: '拿包让出座位',
        positive: '她拿下书包，让他坐下。', nearMiss: '她幽默地让座。', explanation: '动作而非标签' }], evidence: [],
    } }
    vi.mocked(getStylePresets).mockResolvedValue([rich])
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.findAll('select option')).toHaveLength(2))
    await wrapper.find('select').setValue(rich.name)
    await wrapper.find('form input').setValue('校园人情')
    await wrapper.findAll('.style-craft-details textarea')[1]!.setValue('先办事，再展示维护体面的窘境')
    expect(rich.craft?.paragraphMoves).toBe('动作引出窘境，回应改变处境')
    expect(wrapper.text()).toContain('原创技法对照')
    await wrapper.find('.style-preview button').trigger('click')
    await vi.waitFor(() => expect(generateStylePreview).toHaveBeenCalledWith('project-1', expect.objectContaining({
      profile: expect.objectContaining({ name: '校园人情', basePresetId: 'street-humor', basePresetVersion: 1,
        craft: expect.objectContaining({ paragraphMoves: '先办事，再展示维护体面的窘境', examples: rich.craft!.examples }) }),
    })))
    await wrapper.find('form').trigger('submit')
    await vi.waitFor(() => expect(applyWritingStyle).toHaveBeenCalledWith('project-1', expect.objectContaining({
      name: '校园人情', basePresetId: 'street-humor', basePresetVersion: 1,
      craft: expect.objectContaining({ paragraphMoves: '先办事，再展示维护体面的窘境' }),
    }), 4))
  })

  it('keeps the source beside its revision even when revising the oldest of three samples', async () => {
    const { wrapper, queryClient } = render()
    const others = [1, 2].map((n) => ({ ...result, content: { title: '旧信', body: `其他样例${n}` } }))
    queryClient.setQueryData(['style-previews', 'project-1'], [...others, result])
    await vi.waitFor(() => expect(wrapper.findAllComponents(StylePreviewEditor)).toHaveLength(3))
    const revision = { ...result, content: { title: '旧信', body: '她认出了字迹，停在门口。' } }
    wrapper.findAllComponents(StylePreviewEditor)[2]!.vm.$emit('revised', revision)
    await vi.waitFor(() => expect(queryClient.getQueryData(['style-previews', 'project-1'])).toEqual([revision, result, others[0]]))
    expect(applyWritingStyle).not.toHaveBeenCalled()
  })

  it('uses analyzed style without applying it and preserves previous previews on failure', async () => {
    vi.mocked(analyzeWritingStyle).mockResolvedValue({ profile, analysisMode: 'MODEL', sampleCharacters: 100 })
    const { wrapper, queryClient } = render()
    await wrapper.find('.style-sample textarea').setValue('示例内容。'.repeat(30))
    await wrapper.findAll('button').find((button) => button.text().includes('分析风格'))!.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('样本风格已提取'))
    await wrapper.find('.style-preview button').trigger('click')
    await vi.waitFor(() => expect(wrapper.findAll('.preview-result')).toHaveLength(1))
    vi.mocked(generateStylePreview).mockRejectedValueOnce(new Error('大纲已更新，请重试'))
    await wrapper.find('.style-preview button').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('大纲已更新，请重试'))
    expect(wrapper.findAll('.preview-result')).toHaveLength(1)
    expect(applyWritingStyle).not.toHaveBeenCalled()
    expect(queryClient.getQueryData(['style-previews', 'project-1'])).toEqual([result])
  })

  it('recommends from the saved bible and loads a candidate without applying, then previews it', async () => {
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.find('.style-recommendation button').attributes('disabled')).toBeUndefined())
    await wrapper.find('.style-recommendation input').setValue('  保留温度  ')
    await wrapper.find('.style-recommendation button').trigger('click')
    await vi.waitFor(() => expect(recommendWritingStyle).toHaveBeenCalledWith('project-1', {
      bibleVersionId: 'bible-1', expectedBibleVersion: 2, provider: 'LOCAL_CODEX', instruction: '保留温度',
    }))
    await vi.waitFor(() => expect(wrapper.find('.recommendation-list').text()).toContain('不牺牲人物温度'))
    expect(wrapper.find('.recommendation-evidence').text()).toContain('主题：信任')
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.find('.recommendation-list button').trigger('click')
    expect(wrapper.find('form input').element).toHaveProperty('value', profile.name)
    expect(applyWritingStyle).not.toHaveBeenCalled()
    await wrapper.find('.style-preview button').trigger('click')
    await vi.waitFor(() => expect(generateStylePreview).toHaveBeenCalledWith('project-1', expect.objectContaining({ profile })))
  })

  it('requires a saved bible but not an outline for recommendations', async () => {
    vi.mocked(getLatestStoryBible).mockResolvedValue(null)
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.text()).toContain('尚无已保存的故事圣经'))
    expect(wrapper.find('.style-recommendation button').attributes('disabled')).toBeDefined()
    expect(recommendWritingStyle).not.toHaveBeenCalled()
  })

  it('disables old suggestions after the saved bible changes and preserves them on a failed retry', async () => {
    const { wrapper, queryClient } = render()
    await vi.waitFor(() => expect(wrapper.find('.style-recommendation button').attributes('disabled')).toBeUndefined())
    await wrapper.find('.style-recommendation button').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.recommendation-list button').exists()).toBe(true))
    queryClient.setQueryData(['story-bible', 'project-1'], { ...bible, version: 3 })
    await vi.waitFor(() => expect(wrapper.text()).toContain('故事圣经已更新'))
    expect(wrapper.find('.recommendation-list button').attributes('disabled')).toBeDefined()
    vi.mocked(recommendWritingStyle).mockRejectedValueOnce(new Error('推荐模型暂时不可用'))
    vi.mocked(getLatestStoryBible).mockResolvedValue({ ...bible, version: 3 })
    await wrapper.find('.style-recommendation button').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('推荐模型暂时不可用'))
    expect(wrapper.findAll('.recommendation-list > li')).toHaveLength(1)
    expect(applyWritingStyle).not.toHaveBeenCalled()
  })

  it('keeps a late response isolated from a different project', async () => {
    let finish!: (value: StyleRecommendation) => void
    vi.mocked(recommendWritingStyle).mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const { wrapper, queryClient } = render()
    await vi.waitFor(() => expect(wrapper.find('.style-recommendation button').attributes('disabled')).toBeUndefined())
    await wrapper.find('.style-recommendation button').trigger('click')
    await vi.waitFor(() => expect(recommendWritingStyle).toHaveBeenCalled())
    await wrapper.setProps({ projectId: 'project-2' })
    finish(recommendation)
    await vi.waitFor(() => expect(queryClient.getQueryData(['style-recommendation', 'project-1'])).toEqual(recommendation))
    expect(queryClient.getQueryData(['style-recommendation', 'project-2'])).toBeNull()
    expect(wrapper.findAll('.recommendation-list > li')).toHaveLength(0)
  })

  it('does not present template output as a semantic recommendation', async () => {
    vi.mocked(recommendWritingStyle).mockResolvedValue({ ...recommendation, recommendationMode: 'TEMPLATE',
      recommendations: [], summary: '本地模板不判断语义适配' })
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.find('.style-recommendation button').attributes('disabled')).toBeUndefined())
    useGlobalModelSettings().apply({ provider: 'LOCAL_TEMPLATE', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 1 })
    await wrapper.vm.$nextTick()
    await wrapper.find('.style-recommendation button').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('本地模板未作风格适配判断'))
    expect(wrapper.findAll('.recommendation-list > li')).toHaveLength(0)
  })
})

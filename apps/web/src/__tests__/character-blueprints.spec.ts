import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { completeStoryBibleCharacters, createStoryBibleRevision, generateStoryBible, getLatestStoryBible,
  listStoryBibleVersions, publishStoryBible, updateStoryBible } from '@/api/planning'
import StoryBiblePanel from '@/components/StoryBiblePanel.vue'
import CharacterBlueprintEditor from '@/components/CharacterBlueprintEditor.vue'
import { bible, character } from './character-blueprint-fixtures'

vi.mock('@/api/planning', () => ({
  getLatestStoryBible: vi.fn(), listStoryBibleVersions: vi.fn(), getStoryBibleVersion: vi.fn(),
  createStoryBibleRevision: vi.fn(), publishStoryBible: vi.fn(), generateStoryBible: vi.fn(),
  updateStoryBible: vi.fn(), completeStoryBibleCharacters: vi.fn(),
}))

function mountPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const wrapper = mount(StoryBiblePanel, { props: { projectId: 'project-1' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return { wrapper, clear: () => { wrapper.unmount(); client.clear() } }
}

describe('character blueprint editing and publication', () => {
  afterEach(() => vi.clearAllMocks())

  it('keeps cached fields unchanged while editing and requires save before publication or completion', async () => {
    const original = bible()
    vi.mocked(getLatestStoryBible).mockResolvedValue(original)
    vi.mocked(listStoryBibleVersions).mockResolvedValue([])
    vi.mocked(updateStoryBible).mockImplementation(async (_id, saved, content) =>
      ({ ...saved, content, version: saved.version + 1 }))
    const { wrapper, clear } = mountPanel()
    try {
      await vi.waitFor(() => expect(wrapper.find('.blueprint-character').exists()).toBe(true))
      const label = wrapper.findAll('.blueprint-fields label').find(node => node.text() === '背景与行为成因')!
      await label.find('textarea').setValue('作者修改后的成长经历')
      expect(original.content.characterBlueprints?.[0]?.background).toBe('家里一直要求他懂事')
      const publish = () => wrapper.findAll('button').find(node => node.text() === '确认并发布')!
      const complete = () => wrapper.findAll('button').find(node => node.text() === '补全人物底稿')!
      expect(publish().attributes('disabled')).toBeDefined()
      expect(complete().attributes('disabled')).toBeDefined()
      await wrapper.findAll('button').find(node => node.text() === '保存修改')!.trigger('click')
      await vi.waitFor(() => expect(updateStoryBible).toHaveBeenCalledOnce())
      expect(vi.mocked(updateStoryBible).mock.calls[0]?.[2].characterBlueprints?.[0]?.background).toBe('作者修改后的成长经历')
      await vi.waitFor(() => expect(publish().attributes('disabled')).toBeUndefined())
      expect(publishStoryBible).not.toHaveBeenCalled()
      expect(completeStoryBibleCharacters).not.toHaveBeenCalled()
    } finally { clear() }
  })

  it('creates a completion draft only on explicit click without publishing or regenerating the bible', async () => {
    const original = { ...bible(), status: 'PUBLISHED' as const }
    delete original.content.characterBlueprints
    const result = { ...original, id: 'bible-2', status: 'DRAFT' as const, generationNumber: 2,
      content: { ...original.content, characterBlueprints: [character()] }, baseBibleVersionId: original.id }
    vi.mocked(getLatestStoryBible).mockResolvedValue(original)
    vi.mocked(listStoryBibleVersions).mockResolvedValue([])
    vi.mocked(completeStoryBibleCharacters).mockResolvedValue(result)
    const { wrapper, clear } = mountPanel()
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('尚无人物底稿'))
      expect(completeStoryBibleCharacters).not.toHaveBeenCalled()
      await wrapper.findAll('button').find(node => node.text() === '补全人物底稿')!.trigger('click')
      await vi.waitFor(() => expect(completeStoryBibleCharacters).toHaveBeenCalledWith('project-1', original, 'LOCAL_CODEX', ''))
      await vi.waitFor(() => expect(wrapper.text()).toContain('江澈 · 主角'))
      expect(wrapper.text()).toContain('第 2 版 · 草稿')
      expect(publishStoryBible).not.toHaveBeenCalled()
      expect(generateStoryBible).not.toHaveBeenCalled()
      expect(createStoryBibleRevision).not.toHaveBeenCalled()
    } finally { clear() }
  })

  it('disables save for incomplete manually added characters without calling a model', async () => {
    const original = bible()
    original.content.characterBlueprints = []
    vi.mocked(getLatestStoryBible).mockResolvedValue(original)
    vi.mocked(listStoryBibleVersions).mockResolvedValue([])
    const { wrapper, clear } = mountPanel()
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('尚无人物底稿'))
      await wrapper.findAll('button').find(node => node.text() === '添加人物')!.trigger('click')
      expect(wrapper.text()).toContain('需填写姓名或称谓')
      expect(wrapper.findAll('button').find(node => node.text() === '保存修改')!.attributes('disabled')).toBeDefined()
      expect(updateStoryBible).not.toHaveBeenCalled()
      expect(generateStoryBible).not.toHaveBeenCalled()
    } finally { clear() }
  })

  it('does not replace a different project with a late completion response', async () => {
    let resolve: (value: ReturnType<typeof bible>) => void = () => {}
    const original = bible()
    const other = { ...bible(), id: 'other', projectId: 'project-2', content: { ...bible().content, logline: '另一项目' } }
    vi.mocked(getLatestStoryBible).mockImplementation(async id => id === 'project-1' ? original : other)
    vi.mocked(listStoryBibleVersions).mockResolvedValue([])
    vi.mocked(completeStoryBibleCharacters).mockImplementation(() => new Promise(done => { resolve = done }))
    const { wrapper, clear } = mountPanel()
    try {
      await vi.waitFor(() => expect(wrapper.find('.blueprint-character').exists()).toBe(true))
      await wrapper.findAll('button').find(node => node.text() === '补全人物底稿')!.trigger('click')
      await wrapper.setProps({ projectId: 'project-2' })
      await vi.waitFor(() => expect(wrapper.find('.bible-field.full textarea').element).toHaveProperty('value', '另一项目'))
      resolve({ ...original, generationNumber: 99 })
      await flushPromises()
      expect(wrapper.text()).not.toContain('第 99 版')
      expect(wrapper.find('.bible-field.full textarea').element).toHaveProperty('value', '另一项目')
    } finally { clear() }
  })
})

describe('character blueprint editor', () => {
  it('edits lists without mutating input and disables historical editing', async () => {
    const original = character()
    const wrapper = mount(CharacterBlueprintEditor, { props: { modelValue: [original] } })
    const label = wrapper.findAll('label').find(node => node.text() === '开篇物品、持有人与来源')!
    await label.find('textarea').setValue('家长买的眼镜\n自己购买的笔')
    const changed = wrapper.emitted('update:modelValue')?.[0]?.[0] as typeof original[]
    expect(changed[0]?.initialPossessions).toEqual(['家长买的眼镜', '自己购买的笔'])
    expect(original.initialPossessions).toEqual(['眼镜由家长购买，目前本人持有'])
    await wrapper.setProps({ disabled: true })
    expect(wrapper.findAll('button').every(node => node.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.findAll('textarea').every(node => node.attributes('disabled') !== undefined)).toBe(true)
    wrapper.unmount()
  })
})

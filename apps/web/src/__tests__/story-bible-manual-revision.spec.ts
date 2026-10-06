import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { createStoryBibleRevision, generateStoryBible, getLatestStoryBible, getStoryBibleVersion,
  listStoryBibleVersions, publishStoryBible,
  type StoryBibleContent, type StoryBibleVersion } from '@/api/planning'
import StoryBiblePanel from '@/components/StoryBiblePanel.vue'

vi.mock('@/api/planning', () => ({
  getLatestStoryBible: vi.fn(),
  listStoryBibleVersions: vi.fn(),
  getStoryBibleVersion: vi.fn(),
  createStoryBibleRevision: vi.fn(),
  publishStoryBible: vi.fn(),
  generateStoryBible: vi.fn(),
  updateStoryBible: vi.fn(),
}))

const content: StoryBibleContent = {
  logline: '原版故事', theme: '主题', worldSetting: '世界', worldRules: [], protagonist: '主角',
  protagonistArc: '弧光', supportingCharacters: [], relationshipDynamics: [], centralConflict: '冲突',
  stakes: '代价', narrativeStyle: '风格', endingDirection: '结局', hardConstraints: [], openQuestions: [],
}

function bible(status: StoryBibleVersion['status'], logline: string): StoryBibleVersion {
  return {
    id: status === 'PUBLISHED' ? 'published' : 'revision', projectId: 'project-1',
    generationNumber: status === 'PUBLISHED' ? 1 : 2, schemaVersion: 'story-bible/1', status,
    generatorType: status === 'PUBLISHED' ? 'LOCAL_CODEX' : 'AUTHOR_EDIT', authorInstruction: null,
    sourceDirectionSetId: 'direction-1', sourceCandidateId: 'candidate-1', sourceImportId: null,
    baseBibleVersionId: null,
    content: { ...content, logline }, changeSummary: [], version: 0, createdAt: '', updatedAt: '',
  }
}

describe('manual revision of a published story bible', () => {
  afterEach(() => vi.clearAllMocks())

  it('previews and adjusts from a selected older bible', async () => {
    const latest = bible('DRAFT', '较新的草稿')
    const older = bible('PUBLISHED', '喜欢的旧版')
    vi.mocked(getLatestStoryBible).mockResolvedValue(latest)
    vi.mocked(listStoryBibleVersions).mockResolvedValue([
      { id: latest.id, generationNumber: 2, status: 'DRAFT', logline: latest.content.logline,
        baseBibleVersionId: null, createdAt: '' },
      { id: older.id, generationNumber: 1, status: 'PUBLISHED', logline: older.content.logline,
        baseBibleVersionId: null, createdAt: '' },
    ])
    vi.mocked(getStoryBibleVersion).mockResolvedValue(older)
    vi.mocked(generateStoryBible).mockResolvedValue({ ...latest, id: 'new', generationNumber: 3,
      baseBibleVersionId: older.id })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(StoryBiblePanel, {
      props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.find('.outline-base-field select').exists()).toBe(true))
      await vi.waitFor(() => expect(wrapper.find('.outline-base-field').text()).toContain('喜欢的旧版'))
      await wrapper.find('.outline-base-field select').setValue('published')
      await flushPromises()
      await vi.waitFor(() => expect(wrapper.text()).toContain('查看第 1 版基准故事圣经'))
      await wrapper.findAll('button').find((button) => button.text().includes('按要求调整'))!.trigger('click')
      await vi.waitFor(() => expect(generateStoryBible).toHaveBeenCalledWith(
        'project-1', '', 'LOCAL_CODEX', 'REVISE', 'published'))
    }
    finally { wrapper.unmount(); queryClient.clear() }
  })

  it('saves the edited on-screen text as a new draft without calling a model or publishing', async () => {
    const published = bible('PUBLISHED', '原版故事')
    vi.mocked(getLatestStoryBible).mockResolvedValue(published)
    vi.mocked(createStoryBibleRevision).mockImplementation(async (_projectId, _bible, edited) =>
      bible('DRAFT', edited.logline))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(StoryBiblePanel, {
      props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('保存为修订草稿'))
      await wrapper.find('.bible-field.full textarea').setValue('作者刚改的故事')
      await wrapper.findAll('button').find((button) => button.text().includes('保存为修订草稿'))!.trigger('click')

      await vi.waitFor(() => expect(createStoryBibleRevision).toHaveBeenCalled())
      expect(vi.mocked(createStoryBibleRevision).mock.calls[0]?.[0]).toBe('project-1')
      expect(vi.mocked(createStoryBibleRevision).mock.calls[0]?.[1]).toEqual(published)
      expect(vi.mocked(createStoryBibleRevision).mock.calls[0]?.[2].logline).toBe('作者刚改的故事')
      await vi.waitFor(() => expect(wrapper.text()).toContain('确认并发布'))
      expect(wrapper.text()).toContain('第 2 版 · 草稿')
      expect(publishStoryBible).not.toHaveBeenCalled()
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })
})

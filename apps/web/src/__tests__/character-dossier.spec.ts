import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import StoryMaterialsPanel from '@/components/StoryMaterialsPanel.vue'
import CharacterRelationsPanel from '@/components/CharacterRelationsPanel.vue'
import * as writing from '@/api/writing'
import * as planning from '@/api/planning'
import * as materials from '@/api/planningMaterials'
import { bible, character } from './character-blueprint-fixtures'
import type { CharacterProfile } from '@/api/writing'

vi.mock('@/composables/useGlobalModelSettings', () => ({ useGlobalModelSettings: () => ({ provider: ref('DEEPSEEK') }) }))
vi.mock('@/api/writing', () => ({ listCanonEntities: vi.fn(), listCharacterNames: vi.fn(), listCharacterProfiles: vi.fn(),
  listCanonTimeline: vi.fn(), initializeCharacterNames: vi.fn(), updateCharacterName: vi.fn(), updateCharacterProfile: vi.fn(),
  getEntityState: vi.fn(), listEntityAliases: vi.fn(), listEntityMentions: vi.fn(), addEntityAlias: vi.fn(),
  listRelationships: vi.fn(), listCharacterKnowledge: vi.fn() }))
vi.mock('@/api/planning', () => ({ getLatestStoryBible: vi.fn(), completeStoryBibleCharacters: vi.fn() }))
vi.mock('@/api/planningMaterials', () => ({ listPlanningCharacters: vi.fn(), listPlanningRelationships: vi.fn(), syncPlanningMaterials: vi.fn() }))
const profile = (): CharacterProfile => ({ ...character(), characterId: 'c1', roleKey: 'PROTAGONIST', canonicalName: '江澈',
  gender: '', ageDescription: '', notes: '', version: 1 })
beforeEach(() => {
  vi.mocked(writing.listCanonEntities).mockResolvedValue([{ id: 'c1', type: 'CHARACTER', name: '江澈', status: 'PLANNED', canonVersionFrom: 0 },
    { id: 'item1', type: 'ITEM', name: '眼镜', status: 'PLANNED', canonVersionFrom: 0 }])
  vi.mocked(writing.listCharacterNames).mockResolvedValue([{ id: 'c1', canonicalName: '江澈', sourceName: '江澈', roleKey: 'PROTAGONIST', nickname: null, title: null, version: 0 }])
  vi.mocked(writing.listCharacterProfiles).mockResolvedValue([profile()])
  vi.mocked(planning.getLatestStoryBible).mockResolvedValue(bible())
  vi.mocked(materials.listPlanningCharacters).mockResolvedValue([{ sourceBibleId: 'bible-1', characterId: 'c1', blueprint: character() }])
  vi.mocked(materials.listPlanningRelationships).mockResolvedValue([{ id: 'r1', sourceBibleId: 'bible-1', characterId: 'c1', description: '与同学相识一年，互相讲题' }])
  vi.mocked(writing.getEntityState).mockResolvedValue([])
  vi.mocked(writing.listEntityAliases).mockResolvedValue([])
  vi.mocked(writing.listEntityMentions).mockResolvedValue([])
  vi.mocked(writing.listRelationships).mockResolvedValue([])
  vi.mocked(writing.listCharacterKnowledge).mockResolvedValue([])
})
afterEach(() => vi.clearAllMocks())
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(StoryMaterialsPanel, { props: { projectId: 'project-1' }, global: {
    plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { WritingStylePanel: true, ReaderExperiencePanel: true },
  } })
  return { wrapper, client, close: () => { wrapper.unmount(); client.clear() } }
}
describe('unified character dossier', () => {
  it('combines naming, blueprint, relations, knowledge and entity facts without duplicating characters in entities', async () => {
    const { wrapper, close } = setup()
    try {
      await flushPromises()
      expect(wrapper.text()).toContain('待补充设定：性别、年龄')
      expect(wrapper.text()).toContain('与同学相识一年，互相讲题')
      expect(wrapper.text()).toContain('正文正史知识边界')
      expect(wrapper.text()).toContain('正文当前状态')
      expect(wrapper.findAll('form form')).toHaveLength(0)
      expect(wrapper.findAll('button').some(button => button.text() === '人物命名')).toBe(false)
      expect(materials.listPlanningRelationships).toHaveBeenCalledWith('project-1', 'c1')
      await wrapper.findAll('button').find(button => button.text() === '非人物实体')!.trigger('click')
      await flushPromises()
      expect(wrapper.get('.entity-list').text()).toContain('眼镜')
      expect(wrapper.get('.entity-list').text()).not.toContain('江澈')
      expect(writing.getEntityState).toHaveBeenCalledWith('project-1', 'item1')
    } finally { close() }
  })
  it('preserves unsaved profile edits and their original optimistic version when synchronization refetches', async () => {
    const { wrapper, client, close } = setup()
    try {
      await flushPromises()
      await wrapper.get('textarea[placeholder="别人通常怎样看待此人"]').setValue('作者未保存的性格')
      client.setQueryData(['character-profiles', 'project-1'], [{ ...profile(), externalPersonality: '后台更新', version: 9 }])
      await flushPromises()
      expect((wrapper.get('textarea[placeholder="别人通常怎样看待此人"]').element as HTMLTextAreaElement).value).toBe('作者未保存的性格')
      vi.mocked(writing.updateCharacterProfile).mockRejectedValue(new Error('版本冲突'))
      await wrapper.findAll('button').find(button => button.text() === '保存档案')!.trigger('submit')
      await flushPromises()
      expect(writing.updateCharacterProfile).toHaveBeenCalledWith('project-1', expect.objectContaining({ version: 1 }), expect.objectContaining({ externalPersonality: '作者未保存的性格' }))
      expect(wrapper.text()).toContain('版本冲突')
    } finally { close() }
  })
  it('makes model completion an explicit draft, not profile publication or canonical facts', async () => {
    const { wrapper, client, close } = setup()
    try {
      await flushPromises()
      expect(planning.completeStoryBibleCharacters).not.toHaveBeenCalled()
      vi.mocked(planning.completeStoryBibleCharacters).mockResolvedValue({ ...bible(), id: 'new-draft' })
      await wrapper.findAll('button').find(button => button.text() === '补全人物设定')!.trigger('click')
      await flushPromises()
      expect(planning.completeStoryBibleCharacters).toHaveBeenCalledWith('project-1', bible(), 'DEEPSEEK', expect.stringContaining('未知留空'))
      expect(wrapper.text()).toContain('等待确认发布')
      expect(client.getQueryData(['character-profiles', 'project-1'])).toEqual([profile()])
      expect(writing.updateCharacterProfile).not.toHaveBeenCalled()
      await wrapper.findAll('button').find(button => button.text() === '查看待确认圣经')!.trigger('click')
      expect(wrapper.emitted('openBible')).toHaveLength(1)
    } finally { close() }
  })
  it('keeps an unsaved name and sends its original version after a background rename', async () => {
    const { wrapper, client, close } = setup()
    try {
      await flushPromises()
      await wrapper.get('.profile-name-form input').setValue('作者新名')
      client.setQueryData(['character-names', 'project-1'], [{ id: 'c1', canonicalName: '别人新名', sourceName: '江澈', roleKey: 'PROTAGONIST', nickname: null, title: null, version: 4 }])
      await flushPromises()
      expect((wrapper.get('.profile-name-form input').element as HTMLInputElement).value).toBe('作者新名')
      vi.mocked(writing.updateCharacterName).mockRejectedValue(new Error('姓名版本冲突'))
      await wrapper.get('.profile-name-form').trigger('submit')
      await flushPromises()
      expect(writing.updateCharacterName).toHaveBeenCalledWith('project-1', expect.objectContaining({ version: 0 }), expect.objectContaining({ canonicalName: '作者新名' }))
      expect(wrapper.text()).toContain('姓名版本冲突')
    } finally { close() }
  })
  it('reuses the same filtered relation view and surfaces errors rather than empty facts', async () => {
    vi.mocked(writing.listRelationships).mockRejectedValue(new Error('关系查询失败'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(CharacterRelationsPanel, { props: { projectId: 'p2', characterId: 'c2', view: 'relationships' },
      global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
    try {
      await flushPromises()
      expect(materials.listPlanningRelationships).toHaveBeenCalledWith('p2', 'c2')
      expect(wrapper.text()).toContain('正文关系读取失败：关系查询失败')
      expect(writing.listCharacterKnowledge).not.toHaveBeenCalled()
    } finally { wrapper.unmount(); client.clear() }
  })
})

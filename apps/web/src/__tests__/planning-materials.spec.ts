import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PlanningMaterialSyncButton from '@/components/PlanningMaterialSyncButton.vue'
import ReaderExperienceSeedEditor from '@/components/ReaderExperienceSeedEditor.vue'
import { syncPlanningMaterials } from '@/api/planningMaterials'
import type { ReaderExperienceSeed } from '@/api/planning'

vi.mock('@/api/planningMaterials', () => ({ syncPlanningMaterials: vi.fn() }))
afterEach(() => vi.clearAllMocks())
describe('planning material controls', () => {
  it('synchronizes explicitly and invalidates only the selected project', async () => {
    const client = new QueryClient()
    client.setQueryData(['character-profiles', 'p1'], [])
    client.setQueryData(['character-profiles', 'p2'], [])
    vi.mocked(syncPlanningMaterials).mockResolvedValue(undefined)
    const wrapper = mount(PlanningMaterialSyncButton, { props: { projectId: 'p1' },
      global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
    try {
      expect(syncPlanningMaterials).not.toHaveBeenCalled()
      await wrapper.get('button').trigger('click'); await flushPromises()
      expect(syncPlanningMaterials).toHaveBeenCalledExactlyOnceWith('p1')
      expect(wrapper.emitted('synced')).toHaveLength(1)
      expect(client.getQueryState(['character-profiles', 'p1'])?.isInvalidated).toBe(true)
      expect(client.getQueryState(['character-profiles', 'p2'])?.isInvalidated).toBe(false)
    } finally { wrapper.unmount(); client.clear() }
  })
  it('keeps keys stable and does not mutate cached seed values', async () => {
    const seed: ReaderExperienceSeed = { key: 'note', kind: 'FORESHADOW', title: '纸条', promise: '找到主人',
      setup: '', payoff: '', aftermath: '', plannedChapter: null }
    const wrapper = mount(ReaderExperienceSeedEditor, { props: { modelValue: [seed] } })
    try {
      await wrapper.findAll('textarea')[0]!.setValue('找出签名')
      expect(seed.promise).toBe('找到主人')
      const updated = wrapper.emitted('update:modelValue')?.[0]?.[0] as ReaderExperienceSeed[]
      expect(updated[0]).toMatchObject({ key: 'note', promise: '找出签名' })
      await wrapper.get('[aria-label="新增承诺与伏笔规划"]').trigger('click')
      const added = wrapper.emitted('update:modelValue')?.[1]?.[0] as ReaderExperienceSeed[]
      expect(added[1]?.key).toMatch(/^plan_[0-9a-f]{32}$/)
    } finally { wrapper.unmount() }
  })
})

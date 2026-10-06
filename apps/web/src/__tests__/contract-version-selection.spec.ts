import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { generateContract, getContractVersion, getLatestContract, listContractVersions,
  type ChapterContractVersion } from '@/api/writing'
import WritingWorkbench from '@/components/WritingWorkbench.vue'

vi.mock('@/api/planning', () => ({
  getCurrentOutline: vi.fn().mockResolvedValue({
    id: 'outline-1', status: 'PUBLISHED', content: {
      arcs: [{ ordinal: 1, title: '第一卷', chapters: [{ number: 1, title: '第一章', objective: '目标', coreEvent: '事件' }] }],
    },
  }),
}))
vi.mock('@/api/projects', () => ({ getProject: vi.fn().mockResolvedValue({ currentCanonVersion: 0 }) }))
vi.mock('@/api/writing', () => ({
  getLatestContract: vi.fn(), listContractVersions: vi.fn(), getContractVersion: vi.fn(), generateContract: vi.fn(),
  getLatestManuscript: vi.fn().mockResolvedValue(null), getLatestReview: vi.fn().mockResolvedValue(null),
  manuscriptExportUrl: vi.fn().mockReturnValue('/export'),
}))

function contract(id: string, versionNumber: number, beat: string): ChapterContractVersion {
  return {
    id, projectId: 'project-1', chapterNumber: 1, versionNumber, sourceOutlineVersionId: 'outline-1',
    baseContractVersionId: id === 'new' ? 'older' : null,
    schemaVersion: 'chapter-contract/1', status: 'DRAFT', generatorType: 'LOCAL_CODEX',
    authorInstruction: null, content: { chapterTitle: '第一章', pov: '主角', objective: '目标', storyTime: '当天',
      locations: ['教室'], requiredBeats: [beat], requiredReveals: [], forbiddenFacts: [],
      expectedExitState: '新状态', foreshadowActions: [], hook: '钩子',
      suggestedMinWords: 2000, suggestedMaxWords: 3000 },
    version: 0, createdAt: '', updatedAt: '',
  }
}

describe('contract version selection', () => {
  afterEach(() => vi.clearAllMocks())

  it('previews an older contract and sends it as the revision base', async () => {
    vi.mocked(getLatestContract).mockResolvedValue(contract('latest', 3, '最新节拍'))
    vi.mocked(listContractVersions).mockResolvedValue([
      { id: 'latest', versionNumber: 3, status: 'DRAFT', chapterTitle: '最新合同' },
      { id: 'older', versionNumber: 2, status: 'APPROVED', chapterTitle: '喜欢的旧合同' },
    ] as Awaited<ReturnType<typeof listContractVersions>>)
    vi.mocked(getContractVersion).mockResolvedValue(contract('older', 2, '喜欢的旧节拍'))
    vi.mocked(generateContract).mockResolvedValue(contract('new', 4, '微调结果'))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('基准合同'))
      const baseSelect = wrapper.findAll('label').find((label) => label.text().includes('基准合同'))!.find('select')
      await vi.waitFor(() => expect(baseSelect.findAll('option')).toHaveLength(2))
      await baseSelect.setValue('older')
      await vi.waitFor(() => expect(getContractVersion).toHaveBeenCalledWith('project-1', 1, 'older'))
      await vi.waitFor(() => expect(wrapper.find('.contract-base-preview-body').text()).toContain('喜欢的旧节拍'))
      expect(wrapper.text()).toContain('查看第 2 版基准合同')
      await wrapper.findAll('button').find((button) => button.text().includes('按要求调整'))!.trigger('click')
      await vi.waitFor(() => expect(generateContract).toHaveBeenCalledWith(
        'project-1', 1, 'LOCAL_CODEX', '', 'REVISE', 'older'))
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })
})

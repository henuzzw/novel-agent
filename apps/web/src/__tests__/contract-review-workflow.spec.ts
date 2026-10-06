import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { approveContract, approveContractReview, generateContractReview, getLatestContract,
  getLatestContractReview, type ChapterContractReviewVersion, type ChapterContractVersion } from '@/api/writing'
import WritingWorkbench from '@/components/WritingWorkbench.vue'

vi.mock('@/api/planning', () => ({ getCurrentOutline: vi.fn().mockResolvedValue({ id: 'outline-1',
  status: 'PUBLISHED', content: { arcs: [{ ordinal: 1, title: '第一卷', chapters: [
    { number: 1, title: '第一章', objective: '目标', coreEvent: '事件' },
  ] }] } }) }))
vi.mock('@/api/projects', () => ({ getProject: vi.fn().mockResolvedValue({ currentCanonVersion: 0 }) }))
vi.mock('@/api/writing', () => ({
  getLatestContract: vi.fn(), getLatestContractReview: vi.fn(),
  generateContractReview: vi.fn(), approveContractReview: vi.fn(), approveContract: vi.fn(),
  getLatestManuscript: vi.fn().mockResolvedValue(null), getLatestReview: vi.fn().mockResolvedValue(null),
  getCanonCommitStatus: vi.fn().mockResolvedValue({ committed: false }),
  listContractVersions: vi.fn().mockResolvedValue([]),
  manuscriptExportUrl: vi.fn().mockReturnValue('/export'),
}))

const contract: ChapterContractVersion = {
  id: 'contract-1', projectId: 'project-1', sourceOutlineVersionId: 'outline-1',
  baseContractVersionId: null, chapterNumber: 1, versionNumber: 1, schemaVersion: 'chapter-contract/1',
  status: 'DRAFT', generatorType: 'LOCAL_TEMPLATE', authorInstruction: null,
  content: { chapterTitle: '第一章', pov: '主角', objective: '目标', storyTime: '当天', locations: [],
    requiredBeats: [], requiredReveals: [], forbiddenFacts: [], expectedExitState: '结束状态',
    foreshadowActions: [], hook: '钩子', suggestedMinWords: 2000, suggestedMaxWords: 3000 },
  version: 0, createdAt: '', updatedAt: '',
}
const review: ChapterContractReviewVersion = {
  id: 'review-1', projectId: 'project-1', chapterNumber: 1, sourceContractVersionId: 'contract-1',
  sourceContractRowVersion: 0, versionNumber: 1, status: 'DRAFT', generatorType: 'LOCAL_TEMPLATE',
  authorInstruction: null, content: { summary: '合同可执行', issues: [] }, version: 0,
  createdAt: '', updatedAt: '',
}

describe('contract review workflow', () => {
  afterEach(() => vi.clearAllMocks())

  it('keeps progress and instruction above content and gates contract approval', async () => {
    vi.mocked(getLatestContract).mockResolvedValue(contract)
    vi.mocked(getLatestContractReview).mockResolvedValue(null)
    vi.mocked(generateContractReview).mockResolvedValue(review)
    vi.mocked(approveContractReview).mockResolvedValue({ ...review, status: 'APPROVED', version: 1 })
    vi.mocked(approveContract).mockResolvedValue({ ...contract, status: 'APPROVED', version: 1 })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.find('.chapter-flow').text()).toContain('合同审阅'))
      const confirm = wrapper.findAll('button').find((button) => button.text() === '确认合同')!
      expect(confirm.attributes('disabled')).toBeDefined()
      await wrapper.find('.chapter-workflow-top textarea').setValue('检查人物位置')
      await wrapper.findAll('.chapter-flow button')[1]!.trigger('click')
      expect((wrapper.find('.chapter-workflow-top textarea').element as HTMLTextAreaElement).value)
        .toBe('检查人物位置')
      await wrapper.findAll('button').find((button) => button.text().includes('开始合同审阅'))!.trigger('click')
      await vi.waitFor(() => expect(generateContractReview).toHaveBeenCalledWith(
        'project-1', 1, 'LOCAL_CODEX', '检查人物位置'))
      await vi.waitFor(() => expect(wrapper.text()).toContain('合同可执行'))
      await wrapper.findAll('button').find((button) => button.text() === '确认审阅')!.trigger('click')
      await vi.waitFor(() => expect(approveContractReview).toHaveBeenCalled())
      await wrapper.findAll('.chapter-flow button')[0]!.trigger('click')
      await vi.waitFor(() => expect(wrapper.findAll('button').find((button) => button.text() === '确认合同')!
        .attributes('disabled')).toBeUndefined())
      await wrapper.findAll('button').find((button) => button.text() === '确认合同')!.trigger('click')
      await vi.waitFor(() => expect(approveContract).toHaveBeenCalled())
      await vi.waitFor(() => expect(wrapper.find('.chapter-flow').text()).toContain('合同确认已完成'))
    }
    finally { wrapper.unmount(); queryClient.clear() }
  })
})

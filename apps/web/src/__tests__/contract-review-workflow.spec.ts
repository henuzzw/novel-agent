import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { expect, it, vi } from 'vitest'
import { generateManuscript, getLatestContract, getLatestContractReview } from '@/api/writing'
import WritingWorkbench from '@/components/WritingWorkbench.vue'

vi.mock('@/api/planning', () => ({ getCurrentOutline: vi.fn().mockResolvedValue({ id: 'outline-1',
  status: 'PUBLISHED', content: { arcs: [{ ordinal: 1, title: '第一幕', chapters: [
    { number: 1, title: '第一章', objective: '目标', coreEvent: '事件' },
  ] }] } }) }))
vi.mock('@/api/projects', () => ({ getProject: vi.fn().mockResolvedValue({ currentCanonVersion: 0 }) }))
vi.mock('@/api/writing', () => ({
  getLatestContract: vi.fn(), getLatestContractReview: vi.fn(),
  generateManuscript: vi.fn().mockRejectedValue(new Error('受控模型失败')),
  getLatestManuscript: vi.fn().mockResolvedValue(null), getLatestReview: vi.fn().mockResolvedValue(null),
  getCanonCommitStatus: vi.fn().mockResolvedValue({ committed: false }),
  manuscriptExportUrl: vi.fn().mockReturnValue('/export'),
}))

it('generates directly from a published outline without fetching or approving contracts', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(WritingWorkbench, {
    props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
  })
  try {
    await vi.waitFor(() => expect(wrapper.find('.chapter-flow').text()).toContain('正文草稿'))
    expect(wrapper.find('.chapter-flow').text()).not.toContain('合同')
    const button = wrapper.findAll('button').find(button => button.text() === '生成正文')!
    await vi.waitFor(() => expect(button.attributes('disabled')).toBeUndefined())
    await wrapper.find('.chapter-workflow-top textarea').setValue('增强选择与代价')
    await button.trigger('click')
    await vi.waitFor(() => expect(generateManuscript).toHaveBeenCalledWith(
      'project-1', 1, 'LOCAL_CODEX', '增强选择与代价', 'REGENERATE', null))
    expect(getLatestContract).not.toHaveBeenCalled()
    expect(getLatestContractReview).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(wrapper.text()).toContain('受控模型失败'))
  } finally { wrapper.unmount(); queryClient.clear() }
})

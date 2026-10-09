import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { generateManuscript, getLatestManuscript, getManuscriptVersion, listManuscriptVersions,
  type ManuscriptVersion } from '@/api/writing'
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
  getLatestManuscript: vi.fn(),
  listManuscriptVersions: vi.fn(),
  getManuscriptVersion: vi.fn(),
  manuscriptExportUrl: vi.fn().mockReturnValue('/export'),
  generateManuscript: vi.fn(),
}))

function manuscript(id: string, versionNumber: number, body: string): ManuscriptVersion {
  return {
    id, projectId: 'project-1', chapterNumber: 1, versionNumber,
    sourceContractVersionId: null, baseManuscriptVersionId: id === 'new' ? 'older' : null,
    sourceReviewVersionId: null,
    schemaVersion: 'manuscript/1', status: 'DRAFT', generatorType: 'LOCAL_CODEX',
    authorInstruction: null, content: { title: '第一章', body, summary: '摘要', continuityNotes: [] },
    changeSummary: [], version: 0, createdAt: '', updatedAt: '',
  }
}

describe('manuscript version selection', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('sends a selected older version for revision and previews its full text', async () => {
    vi.mocked(getLatestManuscript).mockResolvedValue(manuscript('latest', 3, '最新稿'))
    vi.mocked(listManuscriptVersions).mockResolvedValue([
      { id: 'latest', versionNumber: 3, status: 'DRAFT', title: '最新稿' },
      { id: 'older', versionNumber: 2, status: 'AUTHOR_ACCEPTED', title: '喜欢的旧稿' },
    ] as Awaited<ReturnType<typeof listManuscriptVersions>>)
    vi.mocked(getManuscriptVersion).mockResolvedValue(manuscript('older', 2, '喜欢的旧稿'))
    vi.mocked(generateManuscript).mockResolvedValue(manuscript('new', 4, '微调结果'))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('第一章'))
      await wrapper.findAll('button').find((button) => button.text().includes('正文草稿'))!.trigger('click')
      await vi.waitFor(() => expect((wrapper.find('.manuscript-body').element as HTMLTextAreaElement).value)
        .toBe('最新稿'))
      await vi.waitFor(() => expect(wrapper.text()).toContain('基准正文'))
      const baseSelect = wrapper.findAll('label').find((label) => label.text().includes('基准正文'))!.find('select')
      await vi.waitFor(() => expect(baseSelect.findAll('option')).toHaveLength(2))
      await baseSelect.setValue('older')
      await vi.waitFor(() => expect(getManuscriptVersion).toHaveBeenCalledWith('project-1', 1, 'older'))
      await vi.waitFor(() => expect(wrapper.find('.manuscript-base-text').text()).toBe('喜欢的旧稿'))
      expect(wrapper.text()).toContain('查看第 2 版基准正文')
      await wrapper.findAll('button').find((button) => button.text().includes('按要求调整'))!.trigger('click')
      await vi.waitFor(() => expect(generateManuscript).toHaveBeenCalledWith(
        'project-1', 1, 'LOCAL_CODEX', '', 'REVISE', 'older'))
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })

})

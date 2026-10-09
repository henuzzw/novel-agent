import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { createManuscriptRevision, getCanonCommitStatus, getLatestManuscript, listManuscriptVersions,
  updateManuscript, type ManuscriptVersion } from '@/api/writing'
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
  getLatestManuscript: vi.fn(), listManuscriptVersions: vi.fn(),
  getCanonCommitStatus: vi.fn(),
  createManuscriptRevision: vi.fn(), updateManuscript: vi.fn(),
  manuscriptExportUrl: vi.fn().mockReturnValue('/export'),
}))

function manuscript(id: string, status: ManuscriptVersion['status'], body: string): ManuscriptVersion {
  return {
    id, projectId: 'project-1', chapterNumber: 1, versionNumber: id === 'accepted' ? 1 : 2,
    sourceContractVersionId: null, baseManuscriptVersionId: id === 'accepted' ? null : 'accepted',
    sourceReviewVersionId: null,
    schemaVersion: 'manuscript/1', status, generatorType: id === 'accepted' ? 'LOCAL_CODEX' : 'AUTHOR_EDIT',
    authorInstruction: null, content: { title: '第一章', body, summary: '摘要', continuityNotes: [] },
    changeSummary: [], version: 0, createdAt: '', updatedAt: '',
  }
}

describe('manual revision of confirmed manuscript', () => {
  afterEach(() => vi.clearAllMocks())

  it('copies confirmed text into an editable draft and saves the author changes', async () => {
    const accepted = manuscript('accepted', 'AUTHOR_ACCEPTED', '已确认正文')
    vi.mocked(getLatestManuscript).mockResolvedValue(accepted)
    vi.mocked(listManuscriptVersions).mockResolvedValue([])
    vi.mocked(getCanonCommitStatus).mockResolvedValue({ committed: false, activeCommitId: null,
      activeManuscriptVersionId: null, canonVersion: 0 })
    vi.mocked(createManuscriptRevision).mockResolvedValue(manuscript('revision', 'DRAFT', '已确认正文'))
    vi.mocked(updateManuscript).mockImplementation(async (_projectId, value, content) =>
      ({ ...value, content }))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('正文草稿'))
      await wrapper.findAll('button').find((button) => button.text().includes('正文草稿'))!.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('复制为修订草稿'))
      expect((wrapper.find('.manuscript-body').element as HTMLTextAreaElement).disabled).toBe(true)
      await wrapper.findAll('button').find((button) => button.text().includes('复制为修订草稿'))!.trigger('click')
      await vi.waitFor(() => expect(createManuscriptRevision).toHaveBeenCalledWith('project-1', accepted))
      await vi.waitFor(() => expect(wrapper.text()).toContain('第 2 版 · 草稿'))
      const body = wrapper.find('.manuscript-body')
      expect((body.element as HTMLTextAreaElement).disabled).toBe(false)
      await body.setValue('作者人工修改后的正文')
      await wrapper.findAll('button').find((button) => button.text() === '保存')!.trigger('click')
      await vi.waitFor(() => expect(updateManuscript).toHaveBeenCalled())
      expect(vi.mocked(updateManuscript).mock.calls[0]?.[2].body).toBe('作者人工修改后的正文')
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })

})

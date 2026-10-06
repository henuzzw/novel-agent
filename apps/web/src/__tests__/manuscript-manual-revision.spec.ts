import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { createManuscriptRevision, getCanonCommitStatus, getLatestManuscript, getLatestReview, listManuscriptVersions,
  returnReviewToWriting,
  updateManuscript, type ChapterReviewVersion, type ManuscriptVersion } from '@/api/writing'
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
  getLatestContract: vi.fn().mockResolvedValue({ id: 'contract-1', status: 'APPROVED',
    content: { chapterTitle: '第一章', pov: '主角', objective: '目标', storyTime: '当天',
      locations: [], requiredBeats: [], requiredReveals: [], forbiddenFacts: [],
      expectedExitState: '新状态', foreshadowActions: [], hook: '钩子',
      suggestedMinWords: 2000, suggestedMaxWords: 3000 } }),
  getLatestManuscript: vi.fn(), listManuscriptVersions: vi.fn(), getLatestReview: vi.fn(),
  getCanonCommitStatus: vi.fn(),
  createManuscriptRevision: vi.fn(), updateManuscript: vi.fn(),
  returnReviewToWriting: vi.fn(),
  manuscriptExportUrl: vi.fn().mockReturnValue('/export'),
}))

function manuscript(id: string, status: ManuscriptVersion['status'], body: string): ManuscriptVersion {
  return {
    id, projectId: 'project-1', chapterNumber: 1, versionNumber: id === 'accepted' ? 1 : 2,
    sourceContractVersionId: 'contract-1', baseManuscriptVersionId: id === 'accepted' ? null : 'accepted',
    sourceReviewVersionId: null,
    schemaVersion: 'manuscript/1', status, generatorType: id === 'accepted' ? 'LOCAL_CODEX' : 'AUTHOR_EDIT',
    authorInstruction: null, content: { title: '第一章', body, summary: '摘要', continuityNotes: [] },
    changeSummary: [], version: 0, createdAt: '', updatedAt: '',
  }
}

describe('manual revision of confirmed manuscript', () => {
  afterEach(() => vi.clearAllMocks())

  it('copies confirmed text into an editable draft while marking the old review as stale', async () => {
    const accepted = manuscript('accepted', 'AUTHOR_ACCEPTED', '已确认正文')
    vi.mocked(getLatestManuscript).mockResolvedValue(accepted)
    vi.mocked(listManuscriptVersions).mockResolvedValue([])
    vi.mocked(getCanonCommitStatus).mockResolvedValue({ committed: false, activeCommitId: null,
      activeManuscriptVersionId: null, canonVersion: 0 })
    const oldReview: ChapterReviewVersion = { id: 'review-1', projectId: 'project-1', chapterNumber: 1,
      versionNumber: 1, schemaVersion: 'chapter-review/2', status: 'APPROVED', generatorType: 'LOCAL_CODEX',
      authorInstruction: null, sourceManuscriptVersionId: 'accepted',
      content: { summary: '旧审稿', issues: [], factProposals: [] },
      version: 0, createdAt: '', updatedAt: '' }
    vi.mocked(getLatestReview).mockResolvedValue(oldReview)
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
      await wrapper.findAll('button').find((button) => button.text().includes('审稿与记忆'))!.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('此审稿对应旧版正文'))
      expect(wrapper.findAll('button').find((button) => button.text().includes('提交正史'))!.attributes('disabled'))
        .toBeDefined()
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })

  it('blocks a second canon commit for an already committed chapter', async () => {
    const accepted = manuscript('accepted', 'AUTHOR_ACCEPTED', '已确认正文')
    vi.mocked(getLatestManuscript).mockResolvedValue(accepted)
    vi.mocked(getCanonCommitStatus).mockResolvedValue({ committed: true, activeCommitId: 'old-commit',
      activeManuscriptVersionId: 'accepted', canonVersion: 1 })
    vi.mocked(getLatestReview).mockResolvedValue({
      id: 'review-1', projectId: 'project-1', chapterNumber: 1, sourceManuscriptVersionId: 'accepted',
      versionNumber: 1, schemaVersion: 'chapter-review/2', status: 'APPROVED', generatorType: 'LOCAL_CODEX',
      authorInstruction: null, content: { summary: '旧审稿', issues: [], factProposals: [] },
      version: 0, createdAt: '', updatedAt: '',
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('审稿与记忆'))
      await wrapper.findAll('button').find((button) => button.text().includes('审稿与记忆'))!.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('本章已有正史 v1'))
      expect(wrapper.findAll('button').some((button) => button.text() === '提交正史')).toBe(false)
      expect(wrapper.findAll('button').some((button) => button.text() === '替换正史')).toBe(true)
      expect(wrapper.findAll('button').find((button) => button.text() === '替换正史')!.attributes('disabled'))
        .toBeDefined()
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })

  it('returns selected review issues and opens the generated draft', async () => {
    const accepted = manuscript('accepted', 'AUTHOR_ACCEPTED', '成绩不错的旧稿')
    const revised = { ...manuscript('revision', 'DRAFT', '成绩一直偏下游的新稿'),
      sourceReviewVersionId: 'review-2' }
    vi.mocked(getLatestManuscript).mockResolvedValue(accepted)
    vi.mocked(getCanonCommitStatus).mockResolvedValue({ committed: true, activeCommitId: 'commit-1',
      activeManuscriptVersionId: 'accepted', canonVersion: 1 })
    vi.mocked(getLatestReview).mockResolvedValue({
      id: 'review-2', projectId: 'project-1', chapterNumber: 1, sourceManuscriptVersionId: 'accepted',
      versionNumber: 2, schemaVersion: 'chapter-review/2', status: 'DRAFT', generatorType: 'LOCAL_CODEX',
      authorInstruction: null, content: { summary: '需修改', issues: [
        { id: 'I1', severity: 'WARNING', category: '人物连续性', description: '成绩不符',
          evidence: '成绩不错', suggestion: '改为成绩偏下游', resolved: false },
      ], factProposals: [] }, version: 0, createdAt: '', updatedAt: '',
    })
    vi.mocked(returnReviewToWriting).mockResolvedValue(revised)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' }, global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('审稿与记忆'))
      await wrapper.findAll('button').find((button) => button.text().includes('审稿与记忆'))!.trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('成绩不符'))
      await wrapper.findAll('label').find((label) => label.text().includes('打回重写'))!
        .find('input').setValue(true)
      await wrapper.findAll('button').find((button) => button.text() === '打回并生成新稿')!.trigger('click')
      await vi.waitFor(() => expect(returnReviewToWriting).toHaveBeenCalledWith('project-1',
        expect.objectContaining({ id: 'review-2' }), 'LOCAL_CODEX', 'REVISE', ['I1'], ''))
      await vi.waitFor(() => expect(wrapper.text()).toContain('第 2 版 · 草稿'))
      expect((wrapper.find('.manuscript-body').element as HTMLTextAreaElement).value)
        .toBe('成绩一直偏下游的新稿')
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })
})

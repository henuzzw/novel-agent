import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import * as planning from '@/api/planning'
import OutlinePanel from '@/components/OutlinePanel.vue'

vi.mock('@/api/planning', () => ({
  getLatestOutline: vi.fn(),
  getCurrentOutline: vi.fn(),
  listOutlineVersions: vi.fn(),
  getOutlineVersion: vi.fn(),
  generateOutline: vi.fn(),
  updateOutline: vi.fn(),
  publishOutline: vi.fn(),
}))

const content = {
  title: '喜欢的旧版',
  premise: '故事前提',
  structureSummary: '结构路线',
  pacingStrategy: '节奏策略',
  suggestedMinWords: 110000,
  suggestedMaxWords: 130000,
  arcs: [{
    ordinal: 1,
    title: '第一卷',
    objective: '阶段目标',
    mainConflict: '主要对抗',
    turningPoint: '关键转折',
    outcome: '退出状态',
    suggestedMinWords: 50000,
    suggestedMaxWords: 70000,
    chapters: [{
      number: 1,
      title: '开端',
      pov: '主角',
      objective: '目标',
      coreEvent: '核心事件',
      reveal: '必要揭示',
      endingHook: '结尾钩子',
      suggestedMinWords: 2400,
      suggestedMaxWords: 3600,
      status: 'PLANNED' as const,
    }],
  }],
}

const latest = {
  id: 'latest',
  projectId: 'project-1',
  generationNumber: 3,
  schemaVersion: 'outline/1',
  status: 'DRAFT' as const,
  generatorType: 'LOCAL_CODEX',
  authorInstruction: null,
  sourceBibleVersionId: 'bible-1',
  baseOutlineVersionId: null,
  wordBudget: {
    targetWords: 120000,
    acceptableMinWords: 110000,
    acceptableMaxWords: 130000,
    recommendedVolumeCount: 2,
    recommendedChapterCount: 40,
    averageChapterWords: 3000,
    recommendedChapterMinWords: 2400,
    recommendedChapterMaxWords: 3600,
  },
  content: { ...content, title: '不满意的新草稿' },
  changeSummary: [],
  version: 0,
  createdAt: '2026-09-29T00:00:00Z',
  updatedAt: '2026-09-29T00:00:00Z',
} as planning.OutlineVersion

const older = {
  ...latest,
  id: 'older',
  generationNumber: 2,
  status: 'PUBLISHED' as const,
  content,
}

describe('OutlinePanel version selection', () => {
  afterEach(() => vi.clearAllMocks())

  function mockVersions() {
    vi.mocked(planning.getLatestOutline).mockResolvedValue(latest)
    vi.mocked(planning.getCurrentOutline).mockResolvedValue(null)
    vi.mocked(planning.listOutlineVersions).mockResolvedValue([
      { id: latest.id, generationNumber: 3, status: 'DRAFT', title: latest.content.title,
        chapterCount: 1, sourceBibleVersionId: 'bible-1', baseOutlineVersionId: null,
        createdAt: latest.createdAt },
      { id: older.id, generationNumber: 2, status: 'PUBLISHED', title: older.content.title,
        chapterCount: 1, sourceBibleVersionId: 'bible-1', baseOutlineVersionId: null,
        createdAt: older.createdAt },
    ])
    vi.mocked(planning.getOutlineVersion).mockResolvedValue(older)
  }

  it('uses the selected older version when adjusting', async () => {
    mockVersions()
    vi.mocked(planning.generateOutline).mockResolvedValue({ ...latest, id: 'new', generationNumber: 4 })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(OutlinePanel, {
      props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })

    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('选择历史版本'))
      await vi.waitFor(() => expect(wrapper.text()).toContain('喜欢的旧版'))
      const selector = wrapper.findAll('label').find((label) => label.text().includes('选择历史版本'))
      expect(selector).toBeDefined()
      await selector!.find('select').setValue('older')
      await flushPromises()
      await vi.waitFor(() => expect(planning.getOutlineVersion).toHaveBeenCalledWith('project-1', 'older'))
      const generateButton = wrapper.findAll('button').find((button) => button.text().includes('按要求调整'))
      await generateButton!.trigger('click')

      await vi.waitFor(() => expect(planning.generateOutline).toHaveBeenCalledWith(
        'project-1', '', 'LOCAL_CODEX', 'REVISE', 'older',
      ))
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })

  it('restores the selected older version as the current writing outline', async () => {
    mockVersions()
    vi.mocked(planning.publishOutline).mockResolvedValue(older)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(OutlinePanel, {
      props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('选择历史版本'))
      const selector = wrapper.findAll('label').find((label) => label.text().includes('选择历史版本'))
      await selector!.find('select').setValue('older')
      await flushPromises()
      await vi.waitFor(() => expect(wrapper.text()).toContain('恢复此版为当前大纲'))
      const restore = wrapper.findAll('button').find((button) => button.text().includes('恢复此版'))
      await restore!.trigger('click')
      await vi.waitFor(() => expect(planning.publishOutline).toHaveBeenCalledWith('project-1', older))
      expect(confirm).toHaveBeenCalledOnce()
      await vi.waitFor(() => expect(wrapper.text()).toContain('当前用于写作：第 2 版'))
      expect(wrapper.text()).toContain('最新生成：第 3 版')
      expect(queryClient.getQueryData(['current-outline', 'project-1'])).toEqual(older)
      await selector!.find('select').setValue('')
      await wrapper.findAll('button').find((button) => button.text().includes('按要求调整'))!.trigger('click')
      await vi.waitFor(() => expect(planning.generateOutline).toHaveBeenCalledWith(
        'project-1', '', 'LOCAL_CODEX', 'REVISE', 'older',
      ))
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
      confirm.mockRestore()
    }
  })

  it('does not publish when restoration is cancelled', async () => {
    mockVersions()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(OutlinePanel, {
      props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('选择历史版本'))
      const selector = wrapper.findAll('label').find((label) => label.text().includes('选择历史版本'))
      await selector!.find('select').setValue('older')
      await flushPromises()
      await vi.waitFor(() => expect(wrapper.text()).toContain('恢复此版为当前大纲'))
      await wrapper.findAll('button').find((button) => button.text().includes('恢复此版'))!.trigger('click')
      await flushPromises()
      expect(planning.publishOutline).not.toHaveBeenCalled()
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
      confirm.mockRestore()
    }
  })
})

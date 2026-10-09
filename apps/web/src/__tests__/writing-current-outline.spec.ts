import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import * as planning from '@/api/planning'
import WritingWorkbench from '@/components/WritingWorkbench.vue'

vi.mock('@/api/planning', () => ({
  getCurrentOutline: vi.fn(),
  getLatestOutline: vi.fn(),
}))
vi.mock('@/api/projects', () => ({ getProject: vi.fn().mockResolvedValue({ currentCanonVersion: 0 }) }))
vi.mock('@/api/writing', () => ({
  getLatestManuscript: vi.fn().mockResolvedValue(null),
}))

describe('WritingWorkbench current outline', () => {
  afterEach(() => vi.clearAllMocks())

  it('shows chapters from the restored current version even when a newer draft exists', async () => {
    vi.mocked(planning.getCurrentOutline).mockResolvedValue({
      id: 'restored', status: 'PUBLISHED', generationNumber: 1,
      content: { arcs: [{ ordinal: 1, title: '恢复的旧卷', chapters: [{ number: 1, title: '恢复的旧章节', objective: '目标', coreEvent: '事件' }] }] },
    } as planning.OutlineVersion)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(WritingWorkbench, {
      props: { projectId: 'project-1' },
      global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    })
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('恢复的旧章节'))
      expect(wrapper.text()).not.toContain('先发布分层大纲')
      expect(planning.getCurrentOutline).toHaveBeenCalledWith('project-1')
      expect(planning.getLatestOutline).not.toHaveBeenCalled()
    }
    finally {
      wrapper.unmount()
      queryClient.clear()
    }
  })
})

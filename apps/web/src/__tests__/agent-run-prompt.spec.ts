import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'

import * as runs from '@/api/agentRuns'
import AgentRunPrompt from '@/components/AgentRunPrompt.vue'

vi.mock('@/api/agentRuns', () => ({ getAgentRunPrompt: vi.fn() }))

function mountPrompt() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return mount(AgentRunPrompt, {
    props: { projectId: 'project-1', runId: 'run-1' },
    global: { plugins: [[VueQueryPlugin, { queryClient }]] },
  })
}

describe('AgentRunPrompt', () => {
  afterEach(() => vi.clearAllMocks())

  it('loads and shows complete system and user prompts on demand', async () => {
    const fullPrompt = '末章设定'.repeat(150)
    vi.mocked(runs.getAgentRunPrompt).mockResolvedValue({
      id: 'run-1', systemPrompt: '系统指令全文', userPrompt: fullPrompt, promptPreview: '末章设定',
    })
    const wrapper = mountPrompt()
    try {
      expect(runs.getAgentRunPrompt).not.toHaveBeenCalled()
      const details = wrapper.find('details')
      ;(details.element as HTMLDetailsElement).open = true
      await details.trigger('toggle')
      await flushPromises()
      await vi.waitFor(() => expect(wrapper.text()).toContain(fullPrompt))
      expect(wrapper.text()).toContain('系统指令全文')
      expect(runs.getAgentRunPrompt).toHaveBeenCalledWith('project-1', 'run-1')
    }
    finally { wrapper.unmount() }
  })

  it('explains that older previews cannot be restored', async () => {
    vi.mocked(runs.getAgentRunPrompt).mockResolvedValue({
      id: 'run-1', systemPrompt: null, userPrompt: null, promptPreview: '历史摘要',
    })
    const wrapper = mountPrompt()
    try {
      const details = wrapper.find('details')
      ;(details.element as HTMLDetailsElement).open = true
      await details.trigger('toggle')
      await flushPromises()
      await vi.waitFor(() => expect(wrapper.text()).toContain('历史摘要'))
      expect(wrapper.text()).toContain('无法还原完整 Prompt')
    }
    finally { wrapper.unmount() }
  })
})

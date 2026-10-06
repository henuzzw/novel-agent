import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAgentRunOutput, subscribeAgentRunOutput, type AgentRunOutput } from '@/api/agentRuns'
import AgentRunOutputPanel from '@/components/AgentRunOutput.vue'

vi.mock('@/api/agentRuns', () => ({ getAgentRunOutput: vi.fn(), subscribeAgentRunOutput: vi.fn() }))
const output: AgentRunOutput = { id: 'run-1', status: 'RUNNING', responseText: '', truncated: false,
  errorType: null, errorCategory: null, errorDetail: null, durationMs: null }
function setup(status: 'RUNNING' | 'FAILED' = 'RUNNING') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(AgentRunOutputPanel, { props: { projectId: 'project-1', runId: 'run-1', status },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  return { wrapper, clear: () => { wrapper.unmount(); client.clear() } }
}

describe('private streamed agent output', () => {
  afterEach(() => vi.clearAllMocks())
  it('shows matching live output, records failure detail and closes the stream without retrying generation', async () => {
    vi.mocked(getAgentRunOutput).mockResolvedValue(output)
    const stop = vi.fn()
    vi.mocked(subscribeAgentRunOutput).mockReturnValue(stop)
    const { wrapper, clear } = setup()
    try {
      await vi.waitFor(() => expect(subscribeAgentRunOutput).toHaveBeenCalledOnce())
      expect(wrapper.text()).toContain('尚未收到正文输出')
      const receive = vi.mocked(subscribeAgentRunOutput).mock.calls[0]![2]
      receive({ ...output, responseText: '{"logline":"未完成' })
      await vi.waitFor(() => expect(wrapper.find('.model-response').text()).toContain('未完成'))
      receive({ ...output, status: 'FAILED', responseText: '{"logline":"未完成', errorType: 'CodexAppServerException',
        errorCategory: 'TIMEOUT', errorDetail: '等待上限 600 秒', durationMs: 600_000 })
      await vi.waitFor(() => expect(wrapper.text()).toContain('等待上限 600 秒'))
      expect(wrapper.text()).toContain('等待超时')
      expect(wrapper.text()).toContain('未完成响应，不作为有效规划或正文')
      expect(stop).toHaveBeenCalled()
      expect(subscribeAgentRunOutput).toHaveBeenCalledOnce()
    } finally { clear() }
  })
  it('does not expose a late event after changing projects and closes on unmount', async () => {
    vi.mocked(getAgentRunOutput).mockResolvedValue(output)
    const stop = vi.fn()
    vi.mocked(subscribeAgentRunOutput).mockReturnValue(stop)
    const { wrapper, clear } = setup()
    try {
      await vi.waitFor(() => expect(subscribeAgentRunOutput).toHaveBeenCalledOnce())
      const receive = vi.mocked(subscribeAgentRunOutput).mock.calls[0]![2]
      vi.mocked(getAgentRunOutput).mockResolvedValue({ ...output, status: 'FAILED', responseText: null })
      await wrapper.setProps({ projectId: 'project-2', status: 'FAILED' })
      receive({ ...output, responseText: 'other project private content' })
      expect(wrapper.text()).not.toContain('other project private content')
      expect(stop).toHaveBeenCalled()
    } finally { clear() }
  })
  it('labels legacy missing details and opens completed responses only on request', async () => {
    vi.mocked(getAgentRunOutput).mockResolvedValue({ ...output, status: 'FAILED', responseText: null })
    const { wrapper, clear } = setup('FAILED')
    try {
      expect(getAgentRunOutput).not.toHaveBeenCalled()
      const details = wrapper.find('details').element
      details.open = true
      await wrapper.find('details').trigger('toggle')
      await vi.waitFor(() => expect(wrapper.text()).toContain('历史任务未保存模型响应'))
      expect(wrapper.text()).toContain('历史任务未保存具体错误原因')
      expect(subscribeAgentRunOutput).not.toHaveBeenCalled()
    } finally { clear() }
  })
  it('renders model HTML as plain text and marks truncated previews', async () => {
    vi.mocked(getAgentRunOutput).mockResolvedValue({ ...output, status: 'SUCCEEDED', responseText: '<script>alert(1)</script>', truncated: true })
    const { wrapper, clear } = setup()
    try {
      await vi.waitFor(() => expect(wrapper.find('.model-response').exists()).toBe(true))
      expect(wrapper.find('.model-response').text()).toContain('<script>alert(1)</script>')
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.text()).toContain('200,000 字符')
    } finally { clear() }
  })
  it('retains stopped partial responses and ends the stream without claiming successful output', async () => {
    vi.mocked(getAgentRunOutput).mockResolvedValue(output)
    const stop = vi.fn()
    vi.mocked(subscribeAgentRunOutput).mockReturnValue(stop)
    const { wrapper, clear } = setup()
    try {
      await vi.waitFor(() => expect(subscribeAgentRunOutput).toHaveBeenCalledOnce())
      vi.mocked(subscribeAgentRunOutput).mock.calls[0]![2]({ ...output, status: 'CANCELLED', responseText: '部分响应',
        errorType: 'GenerationStoppedException', errorCategory: 'CANCELLED', errorDetail: '生成已停止' })
      await vi.waitFor(() => expect(wrapper.text()).toContain('已停止'))
      expect(wrapper.text()).toContain('部分响应')
      expect(wrapper.text()).toContain('未完成响应，不作为有效规划或正文')
      expect(stop).toHaveBeenCalled()
    } finally { clear() }
  })
})

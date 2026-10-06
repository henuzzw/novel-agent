import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import GenerationStatusPanel from '@/components/GenerationStatusPanel.vue'
import { listAgentRuns, stopGeneration, type AgentRun } from '@/api/agentRuns'
import { beginGenerationRequest, endGenerationRequest, generationRequests } from '@/lib/generation-activity'

vi.mock('@/api/agentRuns', () => ({ listAgentRuns: vi.fn(), stopGeneration: vi.fn() }))
const cleanup: (() => void)[] = []
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); generationRequests.value = []; vi.resetAllMocks() })
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(GenerationStatusPanel, { props: { projectId: 'p' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  return wrapper
}
function run(stage: string, status: AgentRun['status']) {
  return { id: stage, stage, status, startedAt: new Date(Date.now() - 120_000).toISOString(), completedAt: null, errorMessage: status === 'FAILED' ? '模型额度不足' : null } as AgentRun
}
describe('project generation status', () => {
  it('recovers server states after page reload and distinguishes model success from saved requests', async () => {
    vi.mocked(listAgentRuns).mockResolvedValue([run('IMPORT_REVERSE_BIBLE', 'SUCCEEDED'), run('IMPORT_REVERSE_OUTLINE', 'RUNNING'), run('CHAPTER_REVIEW', 'FAILED')])
    const wrapper = render()
    await vi.waitFor(() => expect(wrapper.get('[data-stage="OUTLINE"]').text()).toContain('请求中'))
    expect(wrapper.get('[data-stage="STORY_BIBLE"]').text()).toContain('成功')
    expect(wrapper.get('[data-stage="STORY_BIBLE"]').text()).toContain('模型任务')
    expect(wrapper.get('[data-stage="CHAPTER_REVIEW"]').text()).toContain('模型额度不足')
    expect(wrapper.emitted('busy')?.slice(-1)[0]).toEqual([true])
    await wrapper.get('.generation-tasks').trigger('click')
    expect(wrapper.emitted('openTasks')).toHaveLength(1)
  })
  it('shows local pending then failure without waiting for polling and isolates projects', async () => {
    vi.mocked(listAgentRuns).mockResolvedValue([])
    const wrapper = render()
    const id = beginGenerationRequest('/api/v1/projects/p/chapters/3/contracts/actions/generate', 'POST')
    await vi.waitFor(() => expect(wrapper.get('[data-stage="CHAPTER_CONTRACT"]').text()).toContain('请求中'))
    expect(wrapper.text()).toContain('第 3 章')
    endGenerationRequest(id, new Error('HTTP 403'))
    await vi.waitFor(() => expect(wrapper.get('[data-stage="CHAPTER_CONTRACT"]').text()).toContain('失败'))
    await wrapper.setProps({ projectId: 'other' })
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('HTTP 403'))
  })
  it('shows status lookup failure instead of pretending no tasks exist', async () => {
    vi.mocked(listAgentRuns).mockRejectedValue(new Error('查询不可用'))
    const wrapper = render()
    await vi.waitFor(() => expect(wrapper.text()).toContain('任务状态读取失败'))
    expect(wrapper.get('[data-stage="STORY_BIBLE"]').text()).toContain('未知')
  })
  it('stops the exact local request, waits for cancellation, and does not count it as a failure', async () => {
    vi.mocked(listAgentRuns).mockResolvedValue([])
    vi.mocked(stopGeneration).mockResolvedValue({ status: 'STOP_REQUESTED' })
    const wrapper = render()
    const id = beginGenerationRequest('/api/v1/projects/p/story-bibles/actions/generate', 'POST')!
    await vi.waitFor(() => expect(wrapper.find('[aria-label="停止故事圣经"]').exists()).toBe(true))
    await wrapper.get('[aria-label="停止故事圣经"]').trigger('click')
    await vi.waitFor(() => expect(stopGeneration).toHaveBeenCalledWith('p', id, 'request'))
    expect(wrapper.get('[data-stage="STORY_BIBLE"]').text()).toContain('正在停止')
    expect(wrapper.get('[aria-label="停止故事圣经"]').attributes('disabled')).toBeDefined()
    endGenerationRequest(id, Object.assign(new Error('停止'), { code: 'GENERATION_CANCELLED' }))
    await vi.waitFor(() => expect(wrapper.get('[data-stage="STORY_BIBLE"]').text()).toContain('已停止'))
    expect(wrapper.find('[aria-label="停止故事圣经"]').exists()).toBe(false)
    expect(wrapper.emitted('busy')?.slice(-1)[0]).toEqual([false])
  })
  it('stops recovered server calls by run ID and offers retry after stop failures', async () => {
    vi.mocked(listAgentRuns).mockResolvedValue([run('OUTLINE', 'RUNNING')])
    vi.mocked(stopGeneration).mockRejectedValueOnce(new Error('保存结果，不能停止'))
      .mockResolvedValueOnce({ status: 'STOP_REQUESTED' })
    const wrapper = render()
    await vi.waitFor(() => expect(wrapper.find('[aria-label="停止分层大纲"]').exists()).toBe(true))
    await wrapper.get('[aria-label="停止分层大纲"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('保存结果，不能停止'))
    expect(wrapper.get('[aria-label="停止分层大纲"]').attributes('disabled')).toBeUndefined()
    vi.mocked(listAgentRuns).mockResolvedValue([run('OUTLINE', 'CANCELLED')])
    await wrapper.get('[aria-label="停止分层大纲"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.get('[data-stage="OUTLINE"]').text()).toContain('已停止'))
    expect(stopGeneration).toHaveBeenLastCalledWith('p', 'OUTLINE', 'model')
  })
})

import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import AgentTokenUsagePanel from '@/components/AgentTokenUsagePanel.vue'
import AgentRunPanel from '@/components/AgentRunPanel.vue'
import { getAgentTokenUsage, listAgentRuns, type AgentRunSummary, type AgentTokenUsage } from '@/api/agentRuns'

vi.mock('@/api/agentRuns', async original => ({ ...await original<typeof import('@/api/agentRuns')>(),
  getAgentTokenUsage: vi.fn(), listAgentRuns: vi.fn() }))
const cleanup: (() => void)[] = []
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.resetAllMocks() })
const summary = (overrides: Partial<AgentRunSummary> = {}): AgentRunSummary => ({ calls: 3, failures: 1,
  inputTokens: 100, outputTokens: 20, estimatedInputTokens: 100, estimatedOutputTokens: 20,
  actualInputTokens: 1000, actualOutputTokens: 200, actualCalls: 1, estimatedCalls: 2, unknownCalls: 0,
  estimatedCost: 0, currency: 'CNY', tokenSource: 'MIXED', ...overrides })
const usage = (): AgentTokenUsage => ({ asOf: '2026-10-13T09:00:00Z', recentFrom: '2026-10-13T04:00:00Z',
  projectTotal: summary(), lastFiveHours: summary({ calls: 1, actualCalls: 0, actualInputTokens: 0, actualOutputTokens: 0 }) })

it('separates actual and estimated totals and does not turn unknown vendor usage into zero', () => {
  const wrapper = mount(AgentTokenUsagePanel, { props: { usage: usage() } })
  cleanup.push(() => wrapper.unmount())
  expect(wrapper.get('section[aria-label="项目累计 Token"]').text()).toContain('1,200')
  expect(wrapper.get('section[aria-label="项目累计 Token"]').text()).toContain('120')
  expect(wrapper.get('section[aria-label="项目累计 Token"]').text()).toContain('2 次实际用量未知')
  expect(wrapper.get('section[aria-label="最近 5 小时 Token"]').text()).toContain('未知 / 未知')
  expect(wrapper.text()).toContain('按调用开始时间统计本项目')
})

it('renders zero for a project with no calls rather than a missing-usage warning', () => {
  const empty = summary({ calls: 0, actualCalls: 0, actualInputTokens: 0, actualOutputTokens: 0,
    estimatedInputTokens: 0, estimatedOutputTokens: 0 })
  const wrapper = mount(AgentTokenUsagePanel, { props: { usage: { ...usage(), projectTotal: empty, lastFiveHours: empty } } })
  cleanup.push(() => wrapper.unmount())
  expect(wrapper.text()).not.toContain('未知 / 未知')
  expect(wrapper.text()).toContain('0 次调用')
})

it('loads the current project and refreshes usage together with the task list', async () => {
  vi.mocked(getAgentTokenUsage).mockResolvedValue(usage())
  vi.mocked(listAgentRuns).mockResolvedValue([])
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(AgentRunPanel, { props: { projectId: 'project-1' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { AutomationPanel: true } } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  await flushPromises()
  expect(getAgentTokenUsage).toHaveBeenCalledWith('project-1')
  expect(wrapper.text()).toContain('最近 5 小时 Token')
  await wrapper.get('[aria-label="刷新任务"]').trigger('click'); await flushPromises()
  expect(getAgentTokenUsage).toHaveBeenCalledTimes(2)
})

it('shows usage-loading failures even if the task list loaded successfully', async () => {
  vi.mocked(getAgentTokenUsage).mockRejectedValue(new Error('统计不可用'))
  vi.mocked(listAgentRuns).mockResolvedValue([])
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(AgentRunPanel, { props: { projectId: 'project-2' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { AutomationPanel: true } } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('统计不可用')
})

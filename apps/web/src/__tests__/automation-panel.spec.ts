import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AutomationPanel from '@/components/AutomationPanel.vue'
import { createAutomationRun, listAutomationRuns, type AutomationRun } from '@/api/automation'

vi.mock('@/api/automation', async importOriginal => ({ ...await importOriginal<typeof import('@/api/automation')>(),
  createAutomationRun: vi.fn(), listAutomationRuns: vi.fn() }))
vi.mock('@/composables/useGlobalModelSettings', () => ({ useGlobalModelSettings: () => ({ provider: ref('LOCAL_CODEX') }) }))
const cleanup: (() => void)[] = []
const record = (overrides: Partial<AutomationRun> = {}): AutomationRun => ({
  id: 'run', projectId: 'p', outlineId: 'o', firstChapter: 1, lastChapter: 3, currentChapter: 1, provider: 'LOCAL_CODEX',
  qualityReviewEnabled: false, maxAutoRevisionRounds: 0, maxGenerationSteps: 100, usedGenerationSteps: 2, usedAutoRevisionRounds: 0,
  maxLoopRounds: 3, currentLoopId: 'child', waitingForPublication: true, autoPublish: false, autoMemory: true,
  status: 'WAITING_FOR_USER', cancelRequested: false, attempt: 1, waitingReason: '发布成功后自动继续下一章', errorCode: null,
  steps: [{ chapterNumber: 1, stage: 'DRAFT_B', status: 'SUCCEEDED', artifactId: 'draft', startedAt: '', completedAt: '', errorCode: null }],
  createdAt: '', updatedAt: '', ...overrides,
})
beforeEach(() => { vi.mocked(listAutomationRuns).mockResolvedValue([]); vi.mocked(createAutomationRun).mockResolvedValue(record()) })
afterEach(() => { cleanup.splice(0).forEach(f => f()); vi.resetAllMocks() })
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(AutomationPanel, { props: { projectId: 'p' },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { GlobalModelBadge: true } } })
  cleanup.push(() => { wrapper.unmount(); client.clear() }); return wrapper
}
it('defaults to author publication and automatic memory and sends independent explicit settings', async () => {
  const wrapper = render(); await flushPromises()
  const switches = wrapper.findAll('input[type=checkbox]')
  expect((switches[0]!.element as HTMLInputElement).checked).toBe(false)
  expect((switches[1]!.element as HTMLInputElement).checked).toBe(true)
  await switches[0]!.setValue(true); await switches[1]!.setValue(false)
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(createAutomationRun).toHaveBeenCalledWith('p', expect.objectContaining({ autoPublish: true, autoMemory: false, maxLoopRounds: 3 }), expect.any(String))
  expect(createAutomationRun).toHaveBeenCalledWith('p', expect.not.objectContaining({ maxAutoRevisionRounds: expect.anything() }), expect.any(String))
})
it('restores waiting publication and opens the current chapter without an unnecessary resume action', async () => {
  vi.mocked(listAutomationRuns).mockResolvedValue([record()])
  const wrapper = render(); await flushPromises()
  expect(wrapper.text()).toContain('等待章节推进')
  expect(wrapper.text()).toContain('B 独立检查')
  expect(wrapper.find('[aria-label="继续任务"]').exists()).toBe(false)
  await wrapper.get('[aria-label="处理当前章节"]').trigger('click')
  expect(wrapper.emitted('open-chapter')?.[0]).toEqual([{ chapter: 1, mode: 'manuscript', provider: 'LOCAL_CODEX' }])
})
it('retains explicit automatic switches on refresh and allows processing paused failures', async () => {
  vi.mocked(listAutomationRuns).mockResolvedValue([record({ autoPublish: true, autoMemory: false, waitingForPublication: false, status: 'FAILED', errorCode: 'DRAFT_LOOP_FAILED' })])
  const wrapper = render(); await flushPromises()
  expect(wrapper.text()).toContain('自动发布 · 记忆手动整理')
  expect(wrapper.find('[aria-label="重试任务"]').exists()).toBe(true)
  expect(wrapper.get('input[type=checkbox]').attributes('disabled')).toBeDefined()
})
it('shows the actual failed phase and validation field instead of the generic loop code', async () => {
  vi.mocked(listAutomationRuns).mockResolvedValue([record({ waitingForPublication: false, status: 'FAILED',
    errorCode: 'DRAFT_LOOP_FAILED', failureDetail: '第 6 章 · B 阶段失败：$.issues[0].evidence 字段类型错误' })])
  const wrapper = render(); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('第 6 章 · B 阶段失败')
  expect(wrapper.get('[role="alert"]').text()).toContain('evidence')
  expect(wrapper.get('[role="alert"]').text()).not.toContain('DRAFT_LOOP_FAILED')
})

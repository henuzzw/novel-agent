import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ProjectChatPanel from '@/components/ProjectChatPanel.vue'
import { decideChatChange, getChatChanges, getChatSources, getChatTurns, sendChat, stopChat, type ChatChange, type ChatTurn } from '@/api/projectChat'

vi.mock('@/api/projectChat', () => ({ decideChatChange: vi.fn(), getChatChanges: vi.fn(), getChatSources: vi.fn(), getChatTurns: vi.fn(), readChatSource: vi.fn(), sendChat: vi.fn(), stopChat: vi.fn() }))
const cleanup: (() => void)[] = []
const turn: ChatTurn = { id: 'turn', question: '调整前两章', reply: '已提出候选，等待确认。', status: 'SUCCEEDED', error: null, sources: [], createdAt: '2026-10-12T01:00:00Z' }
const change: ChatChange = { id: 'change1', turnId: 'turn', key: 'OUTLINE:version:1', title: '第1章', before: '旧一', after: '新一', reason: '增强行动', impact: '后续核对', status: 'PENDING', resultKey: null, createdAt: turn.createdAt }
beforeEach(() => {
  vi.mocked(getChatTurns).mockResolvedValue([turn]); vi.mocked(getChatChanges).mockResolvedValue([change, { ...change, id: 'change2', key: 'OUTLINE:version:2', title: '第2章', before: '旧二', after: '新二' }])
  vi.mocked(getChatSources).mockResolvedValue([])
})
afterEach(() => { cleanup.splice(0).forEach(run => run()); vi.resetAllMocks() })
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(ProjectChatPanel, { props: { projectId: 'project' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() }); return wrapper
}
it('shows grouped before/after and applies only on author confirmation', async () => {
  const wrapper = render(); await flushPromises()
  expect(wrapper.text()).toContain('旧一'); expect(wrapper.text()).toContain('新二')
  expect(decideChatChange).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button => button.text().includes('确认应用本组 2 项'))!.trigger('click'); await flushPromises()
  expect(decideChatChange).toHaveBeenCalledWith('project', 'change1', 'apply')
})
it('restores running state on mount and supports stopping', async () => {
  vi.mocked(getChatTurns).mockResolvedValue([{ ...turn, status: 'RUNNING', reply: '' }])
  const wrapper = render(); await flushPromises()
  expect(wrapper.text()).toContain('请求中')
  await wrapper.findAll('button').find(button => button.text() === '停止')!.trigger('click'); await flushPromises()
  expect(stopChat).toHaveBeenCalledWith('project', 'turn')
})
it('sends a natural question without automatically applying candidates', async () => {
  const wrapper = render(); await flushPromises()
  await wrapper.get('textarea').setValue('这个人物为什么要离开？')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(sendChat).toHaveBeenCalledWith('project', expect.any(String), '这个人物为什么要离开？')
  expect(decideChatChange).not.toHaveBeenCalled()
})

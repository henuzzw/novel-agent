import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import PublishedMemoryPanel from '@/components/PublishedMemoryPanel.vue'
import { savePublishedMemory, getPublishedMemory, getPublishedMemoryHistory, listCanonEntities, type FactProposal, type PublishedMemory } from '@/api/writing'

vi.mock('@/api/writing', () => ({ savePublishedMemory: vi.fn(), getPublishedMemoryHistory: vi.fn(), getPublishedMemory: vi.fn(), listCanonEntities: vi.fn(), retryPublishedMemory: vi.fn() }))
const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.resetAllMocks() })
const fact = (id: string, decision: FactProposal['decision']): FactProposal => ({
  id, factType: 'STATE_CHANGE', subject: id, predicate: '位置', object: '教室', evidence: `${id}在教室。`, confidence: null, payload: null, decision,
})
function render(state: PublishedMemory['status'], candidates: FactProposal[]) {
  const value: PublishedMemory = { commitId: 'commit', manuscriptVersionId: 'm1', status: state, candidates, error: null, version: 3 }
  vi.mocked(getPublishedMemory).mockResolvedValue(value)
  vi.mocked(listCanonEntities).mockResolvedValue([])
  vi.mocked(savePublishedMemory).mockImplementation(async (_project, _chapter, _base, facts) => ({ ...value, status: 'SUCCEEDED', version: 4, candidates: facts }))
  vi.mocked(getPublishedMemoryHistory).mockResolvedValue([])
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(PublishedMemoryPanel, { props: { projectId: 'project', chapter: 1, published: true, compact: false }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  return wrapper
}

it('shows automatically accepted memory without per-item confirmation controls or writes', async () => {
  const wrapper = render('SUCCEEDED', [fact('江澈', 'ACCEPTED'), fact('沈秋', 'ACCEPTED')])
  await vi.waitFor(() => expect(wrapper.text()).toContain('已保存 2 项'))
  expect(wrapper.findAll('select')).toHaveLength(0)
  expect(wrapper.text()).not.toContain('保存消歧结果')
  expect(savePublishedMemory).not.toHaveBeenCalled()
})

it('edits saved memories and retains the source version for conflict checking', async () => {
  const candidates = [fact('江澈', 'ACCEPTED'), fact('她', 'PENDING'), fact('他', 'PENDING')]
  const wrapper = render('NEEDS_CONFIRMATION', candidates)
  await vi.waitFor(() => expect(wrapper.text()).toContain('历史待处理'))
  await wrapper.get('[aria-label="编辑记忆"]').trigger('click')
  await wrapper.findAll('button').find(button => button.text() === '保存记忆')!.trigger('click')
  await vi.waitFor(() => expect(savePublishedMemory).toHaveBeenCalledWith('project', 1, expect.objectContaining({ commitId: 'commit', version: 3 }),
    candidates.map(f => ({ ...f, decision: 'ACCEPTED' }))))
  expect(candidates[1]!.decision).toBe('PENDING')
})

it('removes accepted memories through the save API and allows cancellation without writing', async () => {
  const wrapper = render('NEEDS_CONFIRMATION', [fact('她', 'PENDING'), fact('他', 'PENDING')])
  await vi.waitFor(() => expect(wrapper.text()).toContain('历史待处理'))
  await wrapper.get('[aria-label="编辑记忆"]').trigger('click')
  await wrapper.findAll('[aria-label="移除记忆"]')[0]!.trigger('click')
  await wrapper.findAll('button').find(button => button.text() === '保存记忆')!.trigger('click')
  await vi.waitFor(() => expect(savePublishedMemory).toHaveBeenCalledWith('project', 1, expect.anything(), [
    expect.objectContaining({ id: '她', decision: 'REJECTED' }), expect.objectContaining({ id: '他', decision: 'ACCEPTED' }),
  ]))
})

it('adds a new memory and cancels without mutating the stored source', async () => {
  const wrapper = render('SUCCEEDED', [fact('江澈', 'ACCEPTED')])
  await vi.waitFor(() => expect(wrapper.text()).toContain('已保存 1 项'))
  await wrapper.get('[aria-label="新增记忆"]').trigger('click')
  expect(wrapper.findAll('article')).toHaveLength(2)
  await wrapper.get('[aria-label="取消编辑"]').trigger('click')
  expect(wrapper.findAll('article')).toHaveLength(1)
  expect(savePublishedMemory).not.toHaveBeenCalled()
})

it('does not claim writing can continue while memory is failed', async () => {
  const wrapper = render('FAILED', [])
  await vi.waitFor(() => expect(wrapper.text()).toContain('下一章生成须等待'))
  expect(wrapper.text()).not.toContain('可继续创作下一章')
})

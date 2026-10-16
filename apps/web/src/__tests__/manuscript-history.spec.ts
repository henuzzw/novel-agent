import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import ManuscriptHistoryPanel from '@/components/ManuscriptHistoryPanel.vue'
import { getManuscriptVersion, listManuscriptVersions, type ManuscriptVersion } from '@/api/writing'
vi.mock('@/api/writing', () => ({ getManuscriptVersion: vi.fn(), listManuscriptVersions: vi.fn() }))
const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.resetAllMocks() })
function version(n: number): ManuscriptVersion {
  return { id: `v${n}`, projectId: 'project', chapterNumber: 1, versionNumber: n, version: 0, status: n === 1 ? 'AUTHOR_ACCEPTED' : 'DRAFT',
    sourceContractVersionId: null, baseManuscriptVersionId: n === 3 ? 'v1' : null, sourceReviewVersionId: null,
    schemaVersion: 'manuscript/1', generatorType: 'LOCAL_EDIT_LOCAL_CODEX', authorInstruction: '增强主动选择', changeSummary: ['只改选区'],
    createdAt: '2026-10-12T00:00:00Z', updatedAt: '2026-10-12T00:00:00Z',
    content: { title: `第${n}版标题`, body: n === 1 ? '她收下纸条。' : '她退回纸条。', summary: `摘要${n}`, continuityNotes: [] } }
}
function render() {
  const versions = [3, 2, 1].map(version)
  vi.mocked(listManuscriptVersions).mockResolvedValue(versions.map(v => ({ ...v, title: v.content.title, bodyLength: v.content.body.length })))
  vi.mocked(getManuscriptVersion).mockImplementation(async (_p, _chapter, id) => versions.find(v => v.id === id)!)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(ManuscriptHistoryPanel, { props: { projectId: 'project', chapterNumber: 1, publishedId: 'v1' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  return wrapper
}

it('defaults to the actual base version, compares all fields and swaps without writing', async () => {
  const wrapper = render()
  await vi.waitFor(() => expect(wrapper.find('[aria-label="文本差异"]').exists()).toBe(true))
  expect((wrapper.get('[aria-label="基准版本"]').element as HTMLSelectElement).value).toBe('v1')
  expect((wrapper.get('[aria-label="对比版本"]').element as HTMLSelectElement).value).toBe('v3')
  expect(wrapper.text()).toContain('当前已发布')
  expect(wrapper.findAll('.changed').map(element => element.text())).toEqual(['收下', '退回'])
  await wrapper.get('[aria-label="对比内容"]').findAll('button')[2]!.trigger('click')
  expect(wrapper.text()).toContain('摘要1')
  expect(wrapper.text()).toContain('摘要3')
  await wrapper.get('[aria-label="交换两个版本"]').trigger('click')
  await vi.waitFor(() => expect((wrapper.get('[aria-label="对比版本"]').element as HTMLSelectElement).value).toBe('v1'))
  expect(wrapper.emitted()).not.toHaveProperty('drafted')
  expect(getManuscriptVersion).toHaveBeenCalledWith('project', 1, 'v1')
})

it('allows selecting any two versions and displays identical content truthfully', async () => {
  const wrapper = render()
  await vi.waitFor(() => expect(wrapper.find('[aria-label="文本差异"]').exists()).toBe(true))
  await wrapper.get('[aria-label="基准版本"]').setValue('v2')
  await vi.waitFor(() => expect(wrapper.text()).toContain('两版内容一致'))
  await wrapper.get('button[title="合并对比"]').trigger('click')
  expect(wrapper.find('.diff-surface.unified').exists()).toBe(true)
})

it('rejects a mismatched project response instead of displaying stale text', async () => {
  const wrapper = render()
  vi.mocked(getManuscriptVersion).mockResolvedValue({ ...version(3), projectId: 'other' })
  await vi.waitFor(() => expect(wrapper.text()).toContain('所选版本与当前项目或章节不匹配'))
  expect(wrapper.find('[aria-label="文本差异"]').exists()).toBe(false)
})

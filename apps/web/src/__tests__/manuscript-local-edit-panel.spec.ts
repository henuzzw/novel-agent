import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ManuscriptLocalEditPanel from '@/components/ManuscriptLocalEditPanel.vue'
import { editManuscriptSelection, type ManuscriptLocalEditResult } from '@/api/manuscriptLocalEdit'
import { ApiError } from '@/api/http'
import type { ManuscriptVersion } from '@/api/writing'
vi.mock('@/api/manuscriptLocalEdit', () => ({ editManuscriptSelection: vi.fn() }))
const source: ManuscriptVersion = { id: 'source', projectId: 'project', chapterNumber: 1, version: 3, versionNumber: 4,
  sourceContractVersionId: 'contract', baseManuscriptVersionId: null, sourceReviewVersionId: null,
  schemaVersion: 'manuscript/1', status: 'DRAFT', generatorType: 'DEEPSEEK', authorInstruction: null,
  changeSummary: [], createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z',
  content: { title: 'title', body: 'before old middle old after', summary: 'summary', continuityNotes: [] } }
const result: ManuscriptLocalEditResult = { assessment: 'DRAFT_CREATED', message: '局部编辑草稿已保存', sourceManuscriptId: 'source', sourceRowVersion: 3,
  selection: 'old', occurrence: 2, offset: 18, replacement: 'new', manuscript: { ...source, id: 'new', status: 'DRAFT', baseManuscriptVersionId: 'source' } }
const cleanup: Array<() => void> = []
beforeEach(() => { vi.mocked(editManuscriptSelection).mockResolvedValue(result) })
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.resetAllMocks() })
function render() {
  const client = new QueryClient()
  const wrapper = mount(ManuscriptLocalEditPanel, { props: { projectId: 'project', source },
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: { GlobalModelBadge: true } } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  return wrapper
}
async function fill(wrapper: ReturnType<typeof render>) {
  await wrapper.get('textarea[maxlength="12000"]').setValue('old')
  await wrapper.get('input[type=number]').setValue(2)
  await wrapper.get('textarea[maxlength="2000"]').setValue('clarify')
  await wrapper.get('input[type=checkbox]').setValue(true)
}
it('requires explicit authorization and saves the second exact occurrence with source row version', async () => {
  const wrapper = render()
  await fill(wrapper)
  await wrapper.get('input[type=checkbox]').setValue(false)
  expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  await wrapper.get('input[type=checkbox]').setValue(true)
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.find('ins').exists()).toBe(true))
  expect(editManuscriptSelection).toHaveBeenCalledWith('project', 1, { sourceManuscriptId: 'source', sourceRowVersion: 3,
    selection: 'old', occurrence: 2, offset: 18, provider: 'LOCAL_CODEX', instruction: 'clarify', authorized: true })
  expect(wrapper.get('del').text()).toBe('old')
  expect(wrapper.get('ins').text()).toBe('new')
  expect(wrapper.emitted('drafted')?.[0]).toEqual([result.manuscript])
  expect(source.content.body).toBe('before old middle old after')
})
it('captures a highlighted source selection and exact offset', async () => {
  const wrapper = render()
  const textarea = wrapper.get('[aria-label="原稿正文"]')
  ;(textarea.element as HTMLTextAreaElement).setSelectionRange(18, 21)
  await textarea.trigger('select')
  expect((wrapper.get('textarea[maxlength="12000"]').element as HTMLTextAreaElement).value).toBe('old')
  expect((wrapper.get('input[type=number]').element as HTMLInputElement).value).toBe('2')
})
it.each([403, 409, 502])('shows %i and offers refresh only for conflict', async status => {
  vi.mocked(editManuscriptSelection).mockRejectedValue(new ApiError('模型调用失败', status))
  const wrapper = render()
  await fill(wrapper)
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.find('[role=alert]').exists()).toBe(true))
  expect(wrapper.emitted('drafted')).toBeUndefined()
  if (status === 409) {
    expect(wrapper.text()).toContain('重新读取原稿')
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
    await wrapper.get('.button.secondary').trigger('click')
    expect(wrapper.emitted('refreshRequested')).toHaveLength(1)
  } else expect(wrapper.text()).toContain(status === 403 ? '无权修改' : '模型调用失败')
})
it('returns unsupported template status without a diff or draft event', async () => {
  vi.mocked(editManuscriptSelection).mockResolvedValue({ ...result, assessment: 'NOT_ASSESSED', message: '本地模板不支持局部改写', replacement: null, manuscript: null })
  const wrapper = render()
  await fill(wrapper)
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.text()).toContain('本地模板不支持'))
  expect(wrapper.find('ins').exists()).toBe(false)
  expect(wrapper.emitted('drafted')).toBeUndefined()
})
it('ignores a late result after switching source or project', async () => {
  let resolve!: (value: ManuscriptLocalEditResult) => void
  vi.mocked(editManuscriptSelection).mockReturnValue(new Promise(yes => { resolve = yes }))
  const wrapper = render()
  await fill(wrapper)
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(editManuscriptSelection).toHaveBeenCalledTimes(1))
  await wrapper.setProps({ projectId: 'other', source: { ...source, id: 'other-source', projectId: 'other' } })
  resolve(result)
  await flushPromises()
  expect(wrapper.find('ins').exists()).toBe(false)
  expect(wrapper.emitted('drafted')).toBeUndefined()
  expect((wrapper.get('textarea[maxlength="12000"]').element as HTMLTextAreaElement).value).toBe('')
})
it('preserves the diff when the parent adopts this draft, but clears it for another project', async () => {
  const wrapper = render()
  await fill(wrapper)
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.find('ins').exists()).toBe(true))
  await wrapper.setProps({ source: result.manuscript })
  expect(wrapper.get('del').text()).toBe('old')
  expect(wrapper.get('ins').text()).toBe('new')
  expect((wrapper.get('textarea[maxlength="12000"]').element as HTMLTextAreaElement).value).toBe('')
  expect((wrapper.get('input[type=checkbox]').element as HTMLInputElement).checked).toBe(false)
  await wrapper.setProps({ projectId: 'other', source: { ...result.manuscript!, projectId: 'other' } })
  expect(wrapper.find('ins').exists()).toBe(false)
})
it('blocks external work and reports only its own pending state, including unmount cleanup', async () => {
  let resolve!: (value: ManuscriptLocalEditResult) => void
  vi.mocked(editManuscriptSelection).mockReturnValue(new Promise(yes => { resolve = yes }))
  const wrapper = render()
  await fill(wrapper)
  await wrapper.setProps({ externalBusy: true })
  await wrapper.get('form').trigger('submit')
  expect(editManuscriptSelection).not.toHaveBeenCalled()
  expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  expect(wrapper.emitted('busy-change')).toEqual([[false]])
  await wrapper.setProps({ externalBusy: false })
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.emitted('busy-change')).toEqual([[false], [true]]))
  resolve(result)
  await vi.waitFor(() => expect(wrapper.emitted('busy-change')?.slice(-1)[0]).toEqual([false]))
  wrapper.unmount()
  expect(wrapper.emitted('busy-change')?.slice(-1)[0]).toEqual([false])
})

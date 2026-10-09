import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CreateProjectView from '@/views/CreateProjectView.vue'
import { createProjectFromStory } from '@/api/projects'

vi.mock('@/api/projects', () => ({ createProjectFromStory: vi.fn() }))
const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.resetAllMocks() })

async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/projects/new', component: CreateProjectView },
    { path: '/projects/:id', component: { template: '<div />' } },
    { path: '/projects', component: { template: '<div />' } },
  ] })
  await router.push('/projects/new')
  await router.isReady()
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  const wrapper = mount(CreateProjectView, { global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  vi.mocked(createProjectFromStory).mockResolvedValue({ id: 'created' } as Awaited<ReturnType<typeof createProjectFromStory>>)
  return { wrapper, router }
}

describe('source-only project creation', () => {
  it('offers exactly file and text and requires a source, not a name', async () => {
    const { wrapper } = await render()
    expect(wrapper.findAll('input[type=radio]').slice(0, 2).map(input => input.attributes('value'))).toEqual(['file', 'text'])
    expect((wrapper.get('input[value=FANQIE_GRIPPING]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请选择故事文件')
    expect(createProjectFromStory).not.toHaveBeenCalled()
    await wrapper.get('input[value=text]').setValue()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请粘贴故事文字')
  })
  it('accepts blank name and navigates to import for automatic analysis', async () => {
    const { wrapper, router } = await render()
    await wrapper.get('input[value=text]').setValue()
    await wrapper.get('textarea').setValue('她把生日礼物放回桌上，第一次问他究竟在等谁。')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(createProjectFromStory).toHaveBeenCalledWith(expect.objectContaining({ name: '', file: null,
      text: '她把生日礼物放回桌上，第一次问他究竟在等谁。', creativeStrategy: 'FANQIE_GRIPPING' }))
    await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({ section: 'imports', analyze: '1' }))
  })
  it('preserves an explicit name and standard strategy and sends only selected source', async () => {
    const { wrapper } = await render()
    await wrapper.get('input[value=text]').setValue()
    await wrapper.get('textarea').setValue('故事素材')
    await wrapper.get('input[maxlength="200"]').setValue('  未寄出的信  ')
    await wrapper.get('input[value=STANDARD]').setValue()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(createProjectFromStory).toHaveBeenCalledWith({ name: '未寄出的信', file: null, text: '故事素材', creativeStrategy: 'STANDARD' })
  })
  it('surfaces import failure without leaving the form', async () => {
    const { wrapper, router } = await render()
    vi.mocked(createProjectFromStory).mockRejectedValue(new Error('文件解析失败'))
    await wrapper.get('input[value=text]').setValue()
    await wrapper.get('textarea').setValue('故事素材')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toContain('文件解析失败')
    expect(router.currentRoute.value.path).toBe('/projects/new')
  })
})

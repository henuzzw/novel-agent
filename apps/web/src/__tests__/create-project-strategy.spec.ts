import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CreateProjectView from '@/views/CreateProjectView.vue'
import { createProject, type CreateProjectInput } from '@/api/projects'

vi.mock('@/api/projects', () => ({ createProject: vi.fn() }))
const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.resetAllMocks() })

async function render(mode: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/projects/new', component: CreateProjectView },
    { path: '/projects/:id', component: { template: '<div />' } },
    { path: '/projects', component: { template: '<div />' } },
  ] })
  await router.push(`/projects/new?mode=${mode}`)
  await router.isReady()
  const client = new QueryClient()
  const wrapper = mount(CreateProjectView, { global: { plugins: [router, [VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  vi.mocked(createProject).mockResolvedValue({ id: 'created' } as Awaited<ReturnType<typeof createProject>>)
  await wrapper.get('input[maxlength="200"]').setValue('创作策略测试')
  if (mode === 'IDEA') {
    for (const textarea of wrapper.findAll('textarea')) await textarea.setValue('校园故事')
  }
  return wrapper
}

describe('project creation strategy', () => {
  it.each(['IDEA', 'MANUSCRIPT', 'MATERIALS'])('defaults %s projects to explicit STANDARD', async mode => {
    const wrapper = await render(mode)
    expect((wrapper.get('input[value=STANDARD]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(vi.mocked(createProject).mock.calls[0]?.[0]).toMatchObject({ entryMode: mode, creativeStrategy: 'STANDARD' }))
  })

  it.each(['IDEA', 'MANUSCRIPT', 'MATERIALS'])('submits an explicit FANQIE_GRIPPING selection for %s', async mode => {
    const wrapper = await render(mode)
    await wrapper.get('input[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(vi.mocked(createProject).mock.calls[0]?.[0]).toMatchObject({ entryMode: mode, creativeStrategy: 'FANQIE_GRIPPING' }))
    const input = vi.mocked(createProject).mock.calls[0]![0] as CreateProjectInput
    if (mode === 'IDEA') expect(input.creativeIntent?.stylePreferences).toEqual([])
    else expect(input.creativeIntent).toBeUndefined()
  })
})

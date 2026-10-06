import { mount, flushPromises } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CreativeStrategyPanel from '@/components/CreativeStrategyPanel.vue'
import ProjectSettingsPanel from '@/components/ProjectSettingsPanel.vue'
import { ApiError, getCreativeStrategy, updateCreativeStrategy, type CreativeStrategySettings } from '@/api/projects'

vi.mock('@/api/projects', async (original) => ({
  ...await original<typeof import('@/api/projects')>(),
  getCreativeStrategy: vi.fn(), updateCreativeStrategy: vi.fn(),
}))
const standard: CreativeStrategySettings = { strategy: 'STANDARD', policyVersion: 1, version: 7 }
const cleanup: Array<() => void> = []
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(CreativeStrategyPanel, { props: { projectId: 'project-a' }, global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  return { wrapper, client }
}
beforeEach(() => {
  vi.mocked(getCreativeStrategy).mockResolvedValue({ ...standard })
  vi.mocked(updateCreativeStrategy).mockResolvedValue({ strategy: 'FANQIE_GRIPPING', policyVersion: 1, version: 8 })
})
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.resetAllMocks() })

describe('creative strategy settings', () => {
  it('waits for settings instead of making a failed read look like STANDARD', async () => {
    const request = deferred<CreativeStrategySettings>()
    vi.mocked(getCreativeStrategy).mockReturnValue(request.promise)
    const { wrapper } = render()
    expect(wrapper.text()).toContain('正在读取创作策略')
    expect(wrapper.find('form').exists()).toBe(false)
    request.resolve(standard)
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    expect((wrapper.get('[value=STANDARD]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  })

  it('allows retry after a failed read and uses the recovered version', async () => {
    vi.mocked(getCreativeStrategy).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce({ ...standard, version: 12 })
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.get('[role=alert]').text()).toBe('读取失败'))
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.get('.strategy-error button').trigger('click')
    await vi.waitFor(() => expect(wrapper.get('[aria-label="重新读取创作策略"]').attributes('disabled')).toBeUndefined())
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(updateCreativeStrategy).toHaveBeenCalledWith('project-a', { strategy: 'FANQIE_GRIPPING', version: 12 }))
  })

  it('saves a changed selection, updates project caches and prevents duplicate saves', async () => {
    const request = deferred<CreativeStrategySettings>()
    vi.mocked(updateCreativeStrategy).mockReturnValue(request.promise)
    const { wrapper, client } = render()
    client.setQueryData(['project', 'project-a'], { id: 'project-a', creativeStrategy: 'STANDARD', version: 7 })
    client.setQueryData(['project', 'project-b'], { id: 'project-b', creativeStrategy: 'STANDARD', version: 20 })
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    expect(wrapper.text()).not.toContain('已保存')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('正在保存'))
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(updateCreativeStrategy).toHaveBeenCalledTimes(1)
    request.resolve({ strategy: 'FANQIE_GRIPPING', policyVersion: 1, version: 8 })
    await vi.waitFor(() => expect(wrapper.text()).toContain('已保存'))
    expect(client.getQueryData(['project', 'project-a'])).toMatchObject({ creativeStrategy: 'FANQIE_GRIPPING', version: 8 })
    expect(client.getQueryData(['project', 'project-b'])).toMatchObject({ creativeStrategy: 'STANDARD', version: 20 })
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  })

  it('blocks a 409 until explicit reload, then saves with the latest row version', async () => {
    vi.mocked(updateCreativeStrategy).mockRejectedValueOnce(new ApiError('版本冲突', 409))
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.get('[role=alert]').text()).toContain('重新读取'))
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(updateCreativeStrategy).toHaveBeenCalledTimes(1)
    vi.mocked(getCreativeStrategy).mockResolvedValue({ ...standard, version: 15 })
    await wrapper.get('[aria-label="重新读取创作策略"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[role=alert]').exists()).toBe(false))
    expect((wrapper.get('[value=STANDARD]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(updateCreativeStrategy).toHaveBeenLastCalledWith('project-a', { strategy: 'FANQIE_GRIPPING', version: 15 }))
  })

  it('keeps a conflict blocked if the reload fails', async () => {
    vi.mocked(updateCreativeStrategy).mockRejectedValue(new ApiError('版本冲突', 409))
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('重新读取后再保存'))
    vi.mocked(getCreativeStrategy).mockRejectedValue(new Error('暂时无法读取'))
    await wrapper.get('[aria-label="重新读取创作策略"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('暂时无法读取'))
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  })

  it('preserves the draft on permission failure and allows a subsequent retry', async () => {
    vi.mocked(updateCreativeStrategy).mockRejectedValueOnce(new ApiError('无权修改项目', 403))
    const { wrapper } = render()
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.get('[role=alert]').text()).toBe('无权修改项目'))
    expect((wrapper.get('[value=FANQIE_GRIPPING]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('已保存'))
  })

  it('ignores a late read for the previous project', async () => {
    const request = deferred<CreativeStrategySettings>()
    vi.mocked(getCreativeStrategy).mockImplementation(id => id === 'project-a' ? request.promise : Promise.resolve({ ...standard, version: 30 }))
    const { wrapper } = render()
    await wrapper.setProps({ projectId: 'project-b' })
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    request.resolve({ strategy: 'FANQIE_GRIPPING', policyVersion: 1, version: 8 })
    await flushPromises()
    expect((wrapper.get('[value=STANDARD]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(updateCreativeStrategy).toHaveBeenCalledWith('project-b', { strategy: 'FANQIE_GRIPPING', version: 30 }))
  })

  it.each(['success', 'error'] as const)('isolates a late save %s after switching projects', async (outcome) => {
    const request = deferred<CreativeStrategySettings>()
    vi.mocked(updateCreativeStrategy).mockReturnValue(request.promise)
    vi.mocked(getCreativeStrategy).mockImplementation(async id => ({ ...standard, version: id === 'project-a' ? 7 : 30 }))
    const { wrapper, client } = render()
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(updateCreativeStrategy).toHaveBeenCalledTimes(1))
    await wrapper.setProps({ projectId: 'project-b' })
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    if (outcome === 'success') request.resolve({ strategy: 'FANQIE_GRIPPING', policyVersion: 1, version: 8 })
    else request.reject(new ApiError('旧项目冲突', 409))
    await flushPromises()
    expect(wrapper.text()).not.toContain('已保存')
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeUndefined()
    expect(client.getQueryData(['creative-strategy', 'project-b'])).toEqual({ ...standard, version: 30 })
  })

  it('resets unsaved selection and notices when switching to a cached project', async () => {
    const { wrapper, client } = render()
    client.setQueryData(['creative-strategy', 'project-b'], { ...standard, strategy: 'FANQIE_GRIPPING', version: 30 })
    vi.mocked(getCreativeStrategy).mockImplementation(async id => ({ ...standard, strategy: id === 'project-b' ? 'FANQIE_GRIPPING' : 'STANDARD', version: id === 'project-b' ? 30 : 7 }))
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.setProps({ projectId: 'project-b' })
    await vi.waitFor(() => expect((wrapper.get('[value=FANQIE_GRIPPING]').element as HTMLInputElement).checked).toBe(true))
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ projectId: 'project-a' })
    await vi.waitFor(() => expect((wrapper.get('[value=STANDARD]').element as HTMLInputElement).checked).toBe(true))
  })

  it('loads a cached project even when both project caches share the same settings object', async () => {
    const { wrapper, client } = render()
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    client.setQueryData(['creative-strategy', 'project-b'], client.getQueryData(['creative-strategy', 'project-a']))
    await wrapper.get('[value=FANQIE_GRIPPING]').setValue()
    await wrapper.setProps({ projectId: 'project-b' })
    await vi.waitFor(() => expect(wrapper.find('form').exists()).toBe(true))
    expect((wrapper.get('[value=STANDARD]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  })

  it('keeps global model settings separate from project strategy', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(ProjectSettingsPanel, { props: { projectId: 'project-a' }, global: {
      plugins: [[VueQueryPlugin, { queryClient: client }]],
      stubs: { GlobalModelSettingsPanel: { template: '<section aria-label="全局模型设置">全局模型设置</section>' } },
    } })
    cleanup.push(() => { wrapper.unmount(); client.clear() })
    await vi.waitFor(() => expect(wrapper.find('[aria-label="项目创作策略"] form').exists()).toBe(true))
    expect(wrapper.find('[aria-label="全局模型设置"]').exists()).toBe(true)
    expect(wrapper.findAll('form')).toHaveLength(1)
    await wrapper.setProps({ projectId: 'project-b' })
    await vi.waitFor(() => expect(getCreativeStrategy).toHaveBeenCalledWith('project-b'))
  })
})

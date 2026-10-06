import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import GlobalModelSettingsPanel from '@/components/GlobalModelSettingsPanel.vue'
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import { getChatGptModels, updateGlobalModelSettings, type GlobalModelSettings } from '@/api/modelSettings'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
vi.mock('@/api/modelSettings', () => ({ getChatGptModels: vi.fn(), updateGlobalModelSettings: vi.fn(), getGlobalModelSettings: vi.fn() }))
const initial: GlobalModelSettings = { provider: 'LOCAL_CODEX', codexModel: 'gpt-6.1-sol', codexEffort: 'high', deepSeekModel: 'deepseek-flash', version: 0 }
const cleanup: Array<() => void> = []
beforeEach(() => {
  useGlobalModelSettings().apply({ ...initial })
  vi.mocked(getChatGptModels).mockResolvedValue([
    { model: 'gpt-6.1-sol', label: 'GPT-6.1 Sol', efforts: ['high', 'max'], defaultEffort: 'high' },
    { model: 'gpt-6-luna', label: 'GPT-6 Luna', efforts: ['low', 'medium'], defaultEffort: 'medium' },
  ])
  vi.mocked(updateGlobalModelSettings).mockImplementation(async value => ({ ...value, version: value.version + 1 }))
})
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.resetAllMocks() })
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(GlobalModelSettingsPanel, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } })
  cleanup.push(() => { wrapper.unmount(); client.clear() })
  return wrapper
}
describe('global model settings', () => {
  it('changes only after saving and updates every consumer', async () => {
    const wrapper = render()
    const badge = mount(GlobalModelBadge)
    cleanup.push(() => badge.unmount())
    await wrapper.get('[aria-label="全局模型供应商"]').setValue('DEEPSEEK')
    await wrapper.get('[aria-label="DeepSeek 模型"]').setValue('deepseek-v4-pro')
    expect(useGlobalModelSettings().provider.value).toBe('LOCAL_CODEX')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(badge.text()).toContain('DeepSeek V4 Pro'))
    expect(wrapper.text()).toContain('已保存')
    expect(useGlobalModelSettings().provider.value).toBe('DEEPSEEK')
    expect(updateGlobalModelSettings).toHaveBeenCalledWith({ ...initial, provider: 'DEEPSEEK', deepSeekModel: 'deepseek-v4-pro' })
  })
  it('uses runtime effort options and resets an unsupported effort on model change', async () => {
    const wrapper = render()
    await vi.waitFor(() => expect(wrapper.findAll('[aria-label="ChatGPT 模型"] option')).toHaveLength(2))
    await wrapper.get('[aria-label="ChatGPT 模型"]').setValue('gpt-6-luna')
    expect(wrapper.findAll('[aria-label="ChatGPT 推理强度"] option').map(option => option.attributes('value'))).toEqual(['low', 'medium'])
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(updateGlobalModelSettings).toHaveBeenCalledWith({ ...initial, codexModel: 'gpt-6-luna', codexEffort: 'medium' }))
  })
  it('preserves saved settings after an error and still allows choosing DeepSeek when ChatGPT is unavailable', async () => {
    vi.mocked(getChatGptModels).mockRejectedValue(new Error('ChatGPT 未连接'))
    vi.mocked(updateGlobalModelSettings).mockRejectedValue(new Error('设置版本冲突'))
    const wrapper = render()
    await vi.waitFor(() => expect(wrapper.text()).toContain('ChatGPT 未连接'))
    await wrapper.get('[aria-label="全局模型供应商"]').setValue('DEEPSEEK')
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('设置版本冲突'))
    expect(useGlobalModelSettings().settings.value).toEqual(initial)
  })
})

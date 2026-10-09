import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import PromptSettingsView from '@/views/PromptSettingsView.vue'
import { listPrompts, savePrompt, resetPrompt, promptHistory, type AgentPrompt } from '@/api/prompts'
import { ApiError } from '@/api/http'

vi.mock('@/api/prompts', () => ({ listPrompts: vi.fn(), savePrompt: vi.fn(), resetPrompt: vi.fn(), promptHistory: vi.fn() }))
const cleanup: (() => void)[] = []
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.resetAllMocks(); vi.restoreAllMocks() })
function template(key = 'OUTLINE'): AgentPrompt {
  return { key, workflow: key, name: key === 'OUTLINE' ? '分层大纲' : '正文创作', group: '规划', systemPrompt: `${key} 默认`, sessionSystemPrompt: '系统默认', guidance: '', defaultSystemPrompt: `${key} 默认`, defaultSessionSystemPrompt: '系统默认', protectedRules: '作者确认与正史边界', customized: false, version: 0, updatedAt: null }
}
async function render(query = '') {
  vi.mocked(listPrompts).mockResolvedValue([template(), template('MANUSCRIPT')])
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/settings/prompts', component: PromptSettingsView }, { path: '/projects', component: { template: '<p>项目</p>' } },
  ] })
  await router.push(`/settings/prompts${query}`)
  await router.isReady()
  const wrapper = mount(RouterView, { global: { plugins: [router] } })
  cleanup.push(() => wrapper.unmount())
  await flushPromises()
  return { wrapper, router }
}

describe('prompt settings', () => {
  it('shows an explicit deployment message when the running backend has no prompt API yet', async () => {
    vi.mocked(listPrompts).mockRejectedValueOnce(new ApiError('请求失败', 404))
    const { wrapper } = await render()
    expect(wrapper.get('[role="alert"]').text()).toContain('请重启后端服务')
    expect(savePrompt).not.toHaveBeenCalled()
  })

  it('opens the stage from the URL and shows real defaults without creating a model request', async () => {
    const { wrapper } = await render('?template=MANUSCRIPT')
    expect((wrapper.get('#prompt-system').element as HTMLTextAreaElement).value).toBe('MANUSCRIPT 默认')
    expect(wrapper.text()).toContain('作者确认与正史边界')
    expect(wrapper.find('.primary-button').attributes('disabled')).toBeDefined()
    expect(savePrompt).not.toHaveBeenCalled()
  })

  it('saves both editable fields with the current version and clears the unsaved state', async () => {
    const { wrapper } = await render()
    vi.mocked(savePrompt).mockResolvedValue({ ...template(), systemPrompt: '新的系统指令', sessionSystemPrompt: '新的系统角色', guidance: '先写眼前矛盾', version: 1, customized: true, updatedAt: '2026-10-07T01:00:00Z' })
    await wrapper.get('#prompt-session-system').setValue('新的系统角色')
    await wrapper.get('#prompt-system').setValue('新的系统指令')
    await wrapper.get('#prompt-guidance').setValue('先写眼前矛盾')
    expect(wrapper.text()).toContain('未保存')
    await wrapper.get('.primary-button').trigger('click')
    await flushPromises()
    expect(savePrompt).toHaveBeenCalledWith('OUTLINE', { systemPrompt: '新的系统指令', sessionSystemPrompt: '新的系统角色', guidance: '先写眼前矛盾', version: 0 })
    expect(wrapper.text()).toContain('已保存 · 版本 1')
    expect(wrapper.text()).not.toContain('未保存')
    expect(wrapper.get('.primary-button').attributes('disabled')).toBeDefined()
  })

  it('keeps edits on a version conflict instead of overwriting or clearing them', async () => {
    const { wrapper } = await render()
    vi.mocked(savePrompt).mockRejectedValue(new ApiError('版本冲突', 409))
    await wrapper.get('#prompt-system').setValue('保留我的编辑')
    await wrapper.get('#prompt-session-system').setValue('保留系统编辑')
    await wrapper.get('.primary-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('本次编辑仍保留')
    expect((wrapper.get('#prompt-system').element as HTMLTextAreaElement).value).toBe('保留我的编辑')
    expect(wrapper.text()).toContain('未保存')
    vi.mocked(listPrompts).mockResolvedValue([{ ...template(), systemPrompt: '其他页面修改', version: 4 }, template('MANUSCRIPT')])
    await wrapper.findAll('button').find(button => button.text() === '保留编辑，读取最新配置')!.trigger('click')
    await flushPromises()
    expect((wrapper.get('#prompt-system').element as HTMLTextAreaElement).value).toBe('保留我的编辑')
    expect(wrapper.text()).toContain('当前已保存配置 · 版本 4')
    expect(wrapper.text()).toContain('其他页面修改')
    expect((wrapper.get('#prompt-session-system').element as HTMLTextAreaElement).value).toBe('保留系统编辑')
    vi.mocked(savePrompt).mockResolvedValue({ ...template(), systemPrompt: '保留我的编辑', sessionSystemPrompt: '保留系统编辑', version: 5, customized: true })
    await wrapper.get('.primary-button').trigger('click')
    await flushPromises()
    expect(savePrompt).toHaveBeenLastCalledWith('OUTLINE', { systemPrompt: '保留我的编辑', sessionSystemPrompt: '保留系统编辑', guidance: '', version: 4 })
  })

  it('guards both stage navigation and leaving the editor', async () => {
    const { wrapper, router } = await render()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.get('#prompt-system').setValue('未保存修改')
    await router.push('/settings/prompts?template=MANUSCRIPT')
    expect(router.currentRoute.value.query.template).toBeUndefined()
    await router.push('/projects')
    expect(router.currentRoute.value.path).toBe('/settings/prompts')
    expect(confirm).toHaveBeenCalledTimes(2)
    confirm.mockReturnValue(true)
    await router.push('/settings/prompts?template=MANUSCRIPT')
    await flushPromises()
    expect((wrapper.get('#prompt-system').element as HTMLTextAreaElement).value).toBe('MANUSCRIPT 默认')
  })

  it('restores default only after confirmation and persists the reset as a version', async () => {
    const { wrapper } = await render()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.get('#prompt-system').setValue('临时编辑')
    await wrapper.get('#prompt-session-system').setValue('临时系统')
    const reset = wrapper.findAll('button').find(button => button.text() === '恢复默认')!
    await reset.trigger('click')
    expect(resetPrompt).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    vi.mocked(resetPrompt).mockResolvedValue({ ...template(), version: 1 })
    await reset.trigger('click')
    await flushPromises()
    expect(resetPrompt).toHaveBeenCalledWith('OUTLINE', 0)
    expect((wrapper.get('#prompt-system').element as HTMLTextAreaElement).value).toBe('OUTLINE 默认')
    expect((wrapper.get('#prompt-session-system').element as HTMLTextAreaElement).value).toBe('系统默认')
    expect(wrapper.text()).toContain('已恢复默认 · 版本 1')
  })

  it('loads a historical version into the editor without immediately saving it', async () => {
    const { wrapper } = await render()
    vi.mocked(promptHistory).mockResolvedValue([{ version: 3, systemPrompt: '历史指令', sessionSystemPrompt: '历史系统', guidance: '历史规则', operation: 'SAVE', createdAt: '2026-10-07T01:00:00Z' }])
    await wrapper.get('[aria-label="版本历史"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '载入此版本')!.trigger('click')
    expect((wrapper.get('#prompt-system').element as HTMLTextAreaElement).value).toBe('历史指令')
    expect((wrapper.get('#prompt-session-system').element as HTMLTextAreaElement).value).toBe('历史系统')
    expect(wrapper.text()).toContain('尚未保存')
    expect(savePrompt).not.toHaveBeenCalled()
  })

  it('does not allow saving a blank system prompt and supports stage search', async () => {
    const { wrapper } = await render()
    await wrapper.get('#prompt-system').setValue('  ')
    expect(wrapper.get('.primary-button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('用户提示词不能为空')
    await wrapper.get('#prompt-system').setValue('有效用户指令')
    await wrapper.get('#prompt-session-system').setValue('  ')
    expect(wrapper.get('.primary-button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('系统提示词不能为空')
    await wrapper.get('[aria-label="搜索提示词"]').setValue('MANUSCRIPT')
    expect(wrapper.findAll('.prompt-group button')).toHaveLength(1)
    await wrapper.get('[aria-label="搜索提示词"]').setValue('不存在的阶段')
    expect(wrapper.text()).toContain('无匹配阶段')
  })
})

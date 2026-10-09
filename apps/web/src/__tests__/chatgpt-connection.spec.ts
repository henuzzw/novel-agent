import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatGptConnectionPanel from '@/components/ChatGptConnectionPanel.vue'
import { beginChatGptLogin, chooseChatGptTransport, completeChatGptLogin, getChatGptConnection, logoutChatGpt, type ChatGptConnection } from '@/api/chatGptConnection'
vi.mock('@/api/chatGptConnection', () => ({ beginChatGptLogin: vi.fn(), cancelChatGptLogin: vi.fn(), chooseChatGptTransport: vi.fn(), completeChatGptLogin: vi.fn(), getChatGptConnection: vi.fn(), logoutChatGpt: vi.fn() }))
const cleanups: Array<() => void> = []
let current: ChatGptConnection
beforeEach(() => {
  current = { choice: { transport: 'APP_SERVER', version: 0 }, auth: { connected: false, canGenerate: false, email: null, expiresAt: null, loginStatus: 'IDLE', error: null, attemptId: null, loginExpiresAt: null } }
  vi.mocked(getChatGptConnection).mockImplementation(async () => structuredClone(current))
})
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); vi.resetAllMocks() })
function render() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(ChatGptConnectionPanel, { global: { plugins: [[VueQueryPlugin, { queryClient }]] } })
  cleanups.push(() => { wrapper.unmount(); queryClient.clear() })
  return wrapper
}
async function unlock(wrapper: ReturnType<typeof render>) {
  await wrapper.get('#chatgpt-admin-key').setValue('test-management')
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.findAll('input[type=radio]')).toHaveLength(2))
}
describe('ChatGPT connection', () => {
  it('requires an explicit management key and never enables direct mode without usage permission', async () => {
    const wrapper = render()
    expect(getChatGptConnection).not.toHaveBeenCalled()
    await unlock(wrapper)
    expect(getChatGptConnection).toHaveBeenCalledWith('test-management')
    expect(wrapper.findAll('input[type=radio]')[1]!.attributes('disabled')).toBeDefined()
    expect(chooseChatGptTransport).not.toHaveBeenCalled()
  })
  it('does not show a failed transport switch as applied and reports unconfirmed revocation', async () => {
    current.auth.connected = true; current.auth.canGenerate = true
    vi.mocked(chooseChatGptTransport).mockRejectedValue(new Error('正在生成，不能切换'))
    vi.mocked(logoutChatGpt).mockResolvedValue({ revocationConfirmed: false, message: '本地已清除，远端撤销未确认' })
    const wrapper = render(); await unlock(wrapper)
    await wrapper.findAll('input[type=radio]')[1]!.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('正在生成，不能切换'))
    expect((wrapper.findAll('input[type=radio]')[0]!.element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.findAll('input[type=radio]')[1]!.element as HTMLInputElement).checked).toBe(false)
    await wrapper.get('[aria-label="断开 ChatGPT 直连账号"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('远端撤销未确认'))
  })
  it('redirects the user-opened popup and clears pasted callback after validation', async () => {
    const popup = { opener: {}, location: { href: 'about:blank' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window)
    vi.mocked(beginChatGptLogin).mockImplementation(async () => {
      current.auth.loginStatus = 'PENDING'; current.auth.attemptId = 'attempt-test'
      return { attemptId: 'attempt-test', authorizationUrl: 'https://auth.openai.com/authorize-test', expiresAt: 'future' }
    })
    vi.mocked(completeChatGptLogin).mockImplementation(async () => { current.auth.loginStatus = 'SUCCEEDED'; return current })
    const wrapper = render(); await unlock(wrapper)
    await wrapper.get('.oauth-login').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('#chatgpt-callback').exists()).toBe(true))
    expect(popup.opener).toBeNull(); expect(popup.location.href).toContain('https://auth.openai.com')
    await wrapper.get('#chatgpt-callback').setValue('http://127.0.0.1:10000/auth/callback?code=test')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.find('#chatgpt-callback').exists()).toBe(false))
    expect(completeChatGptLogin).toHaveBeenCalledWith('test-management', 'attempt-test', 'http://127.0.0.1:10000/auth/callback?code=test')
    expect(localStorage.length).toBe(0)
  })
})

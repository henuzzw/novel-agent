import { apiRequest } from '@/api/http'

export type ChatGptTransport = 'APP_SERVER' | 'SIWC_HTTP'
export interface ChatGptConnection {
  choice: { transport: ChatGptTransport; version: number }
  auth: { connected: boolean; canGenerate: boolean; email: string | null; expiresAt: string | null;
    loginStatus: string; error: string | null; attemptId: string | null; loginExpiresAt: string | null }
}
const root = '/api/v1/settings/model/chatgpt'
// A management key stays in component memory, never in the URL, query cache or browser storage.
const headers = (key: string) => ({ 'X-ChatGPT-Admin-Key': key })
export const getChatGptConnection = (key: string) => apiRequest<ChatGptConnection>(root, { headers: headers(key) })
export const beginChatGptLogin = (key: string) => apiRequest<{ attemptId: string; authorizationUrl: string; expiresAt: string }>(`${root}/login`, { method: 'POST', headers: headers(key) })
export const completeChatGptLogin = (key: string, attemptId: string, callbackUrl: string) =>
  apiRequest<ChatGptConnection>(`${root}/callback`, { method: 'POST', headers: headers(key), body: JSON.stringify({ attemptId, callbackUrl }) })
export const cancelChatGptLogin = (key: string) => apiRequest<ChatGptConnection>(`${root}/login/cancel`, { method: 'POST', headers: headers(key) })
export const logoutChatGpt = (key: string) => apiRequest<{ revocationConfirmed: boolean; message: string }>(`${root}/logout`, { method: 'POST', headers: headers(key) })
export const chooseChatGptTransport = (key: string, transport: ChatGptTransport, version: number) =>
  apiRequest<ChatGptConnection['choice']>(`${root}/transport`, { method: 'PUT', headers: headers(key), body: JSON.stringify({ transport, version }) })

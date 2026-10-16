import { apiRequest } from './http'

export interface ChatReference { key: string; title: string; offset: number; end: number; characters: number; fingerprint: string }
export interface ChatTurn {
  id: string; question: string; reply: string; status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  error: string | null; sources: ChatReference[]; createdAt: string
}
export interface ChatChange {
  id: string; turnId: string; key: string; title: string; before: string; after: string; reason: string; impact: string
  status: 'PENDING' | 'APPLIED' | 'REJECTED' | 'UNDONE'; resultKey: string | null; createdAt: string
}
export interface ChatSource { key: string; title: string; writable: boolean; characters: number }
export interface SourcePage { key: string; title: string; offset: number; end: number; characters: number; text: string }
const path = (project: string) => `/api/v1/projects/${project}/chat`
export const getChatTurns = (project: string) => apiRequest<ChatTurn[]>(`${path(project)}/turns`)
export const getChatChanges = (project: string) => apiRequest<ChatChange[]>(`${path(project)}/changes`)
export const getChatSources = (project: string) => apiRequest<ChatSource[]>(`${path(project)}/sources`)
export const readChatSource = (project: string, key: string, offset = 0) =>
  apiRequest<SourcePage>(`${path(project)}/source?${new URLSearchParams({ key, offset: String(offset) })}`)
export const sendChat = (project: string, id: string, question: string) =>
  apiRequest<ChatTurn>(`${path(project)}/turns`, { method: 'POST', body: JSON.stringify({ id, question }) })
export const stopChat = (project: string, id: string) => apiRequest<void>(`${path(project)}/turns/${id}/stop`, { method: 'POST' })
export const decideChatChange = (project: string, id: string, action: 'apply' | 'reject' | 'undo') =>
  apiRequest<void>(`${path(project)}/changes/${id}/${action}`, { method: 'POST' })

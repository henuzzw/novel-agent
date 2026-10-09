import { apiRequest } from './http'

export interface AgentPrompt {
  key: string
  workflow: string
  name: string
  group: string
  systemPrompt: string
  sessionSystemPrompt: string
  guidance: string
  defaultSystemPrompt: string
  defaultSessionSystemPrompt: string
  protectedRules: string
  customized: boolean
  version: number
  updatedAt: string | null
}

export interface PromptRevision {
  version: number
  systemPrompt: string | null
  sessionSystemPrompt: string | null
  guidance: string
  operation: 'SAVE' | 'RESET'
  createdAt: string
}

const base = '/api/v1/settings/prompts'
export const listPrompts = () => apiRequest<AgentPrompt[]>(base)
export const savePrompt = (key: string, value: Pick<AgentPrompt, 'systemPrompt' | 'sessionSystemPrompt' | 'guidance' | 'version'>) =>
  apiRequest<AgentPrompt>(`${base}/${encodeURIComponent(key)}`, { method: 'PUT', body: JSON.stringify(value) })
export const resetPrompt = (key: string, version: number) =>
  apiRequest<AgentPrompt>(`${base}/${encodeURIComponent(key)}/reset`, { method: 'POST', body: JSON.stringify({ version }) })
export const promptHistory = (key: string) => apiRequest<PromptRevision[]>(`${base}/${encodeURIComponent(key)}/history`)

import { apiRequest } from '@/api/http'
import type { ModelProvider } from '@/api/planning'
export interface GlobalModelSettings {
  provider: ModelProvider
  codexModel: string
  codexEffort: string
  deepSeekModel: 'deepseek-flash' | 'deepseek-v4-pro'
  version: number
}
export interface ModelOption { model: string; label: string; efforts: string[]; defaultEffort: string }
export const getGlobalModelSettings = () => apiRequest<GlobalModelSettings>('/api/v1/settings/model')
export const updateGlobalModelSettings = (value: GlobalModelSettings) =>
  apiRequest<GlobalModelSettings>('/api/v1/settings/model', { method: 'PUT', body: JSON.stringify(value) })
export const getChatGptModels = () => apiRequest<ModelOption[]>('/api/v1/settings/model/chatgpt-models')

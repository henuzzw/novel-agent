import { apiRequest } from '@/api/http'
import type { ModelProvider, StoryBibleVersion } from '@/api/planning'

export interface SnowflakePlan {
  id: string
  projectId: string
  mode: string
  provider: ModelProvider
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  activeStage: string
  steps: Record<string, string>
  invalidSteps?: Record<string, string>
  core: string | null
  characters: string | null
  world: string | null
  plot: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  sourceImportId?: string | null
}

export function getLatestSnowflakePlan(projectId: string): Promise<SnowflakePlan | null> {
  return apiRequest(`/api/v1/projects/${projectId}/snowflake-plans/latest`)
}

export function confirmSnowflakePlan(projectId: string, planId: string): Promise<StoryBibleVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/snowflake-plans/${planId}/actions/confirm`, { method: 'POST' })
}

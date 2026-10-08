import { apiRequest } from '@/api/http'
import type { ModelProvider } from '@/api/planning'

export interface SnowflakePlan {
  id: string
  projectId: string
  mode: string
  provider: ModelProvider
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  activeStage: 'CORE' | 'CHARACTERS' | 'WORLD' | 'PLOT'
  core: string | null
  characters: string | null
  world: string | null
  plot: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export function getLatestSnowflakePlan(projectId: string): Promise<SnowflakePlan | null> {
  return apiRequest(`/api/v1/projects/${projectId}/snowflake-plans/latest`)
}

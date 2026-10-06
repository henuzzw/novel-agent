import { apiRequest } from '@/api/http'

export { ApiError, apiRequest } from '@/api/http'

export type EntryMode = 'IDEA' | 'MANUSCRIPT' | 'MATERIALS'
export type CreativeStrategy = 'STANDARD' | 'FANQIE_GRIPPING'

export interface CreativeStrategySettings {
  strategy: CreativeStrategy
  policyVersion: 1
  version: number
}

export interface UpdateCreativeStrategyInput {
  strategy: CreativeStrategy
  version: number
}

export function getCreativeStrategy(projectId: string): Promise<CreativeStrategySettings> {
  return apiRequest(`/api/v1/projects/${projectId}/settings/creative-strategy`)
}

export function updateCreativeStrategy(
  projectId: string,
  input: UpdateCreativeStrategyInput,
): Promise<CreativeStrategySettings> {
  return apiRequest(`/api/v1/projects/${projectId}/settings/creative-strategy`, {
    method: 'PUT', body: JSON.stringify(input),
  })
}

export interface CreativeIntentInput {
  premise?: string
  genres: string[]
  targetAudience?: string
  protagonistBrief?: string
  centralConflict?: string
  tones: string[]
  targetWords?: number
  endingPreference?: string
  mustHave: string[]
  avoid: string[]
  stylePreferences: string[]
}

export interface ProjectSummary {
  id: string
  name: string
  entryMode: EntryMode
  creativeStrategy?: CreativeStrategy
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETING'
  currentCanonVersion: number
  version: number
  createdAt: string
  updatedAt: string
  creativeIntent: (CreativeIntentInput & { version: number }) | null
}

export interface CodexModelChoice {
  model: string
  effort: string
}

export function getCodexModelChoice(projectId: string): Promise<CodexModelChoice> {
  return apiRequest(`/api/v1/projects/${projectId}/settings/codex-model`)
}

export function updateCodexModelChoice(projectId: string, choice: CodexModelChoice): Promise<CodexModelChoice> {
  return apiRequest(`/api/v1/projects/${projectId}/settings/codex-model`, {
    method: 'PUT', body: JSON.stringify(choice),
  })
}

export interface CreateProjectInput {
  name: string
  entryMode: EntryMode
  creativeStrategy?: CreativeStrategy
  creativeIntent?: CreativeIntentInput
}

export function listProjects(): Promise<ProjectSummary[]> {
  return apiRequest('/api/v1/projects')
}

export function getProject(projectId: string): Promise<ProjectSummary> {
  return apiRequest(`/api/v1/projects/${projectId}`)
}

export function updateCreativeIntent(
  project: ProjectSummary,
  creativeIntent: CreativeIntentInput,
): Promise<ProjectSummary> {
  return apiRequest(`/api/v1/projects/${project.id}/creative-intent`, {
    method: 'PUT',
    headers: {
      'If-Match': `"${project.creativeIntent?.version ?? 0}"`,
    },
    body: JSON.stringify(creativeIntent),
  })
}

export function createProject(input: CreateProjectInput): Promise<ProjectSummary> {
  return apiRequest('/api/v1/projects', {
    method: 'POST',
    headers: {
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(input),
  })
}

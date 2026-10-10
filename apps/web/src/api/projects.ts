import { apiRequest } from '@/api/http'
import { createUuid } from '@/lib/uuid'

export { ApiError, apiRequest } from '@/api/http'

export type EntryMode = 'IDEA' | 'MANUSCRIPT' | 'MATERIALS'
export type CreativeStrategy = 'STANDARD' | 'FANQIE_GRIPPING'
export type WorldMode = 'AUTO' | 'REALISTIC' | 'REALISTIC_FICTION' | 'ALTERNATE'
export type AdaptationScope = 'AUTO' | 'PRESERVE_CORE' | 'RESTRUCTURE'
export interface CreativeBoundarySettings {
  worldMode: WorldMode
  adaptationScope: AdaptationScope
  effectiveWorld: Exclude<WorldMode, 'AUTO'>
  effectiveScope: Exclude<AdaptationScope, 'AUTO'>
  assessed: boolean
  reason: string
  sourceImportId: string
  version: number
}
export function getCreativeBoundaries(projectId: string): Promise<CreativeBoundarySettings> {
  return apiRequest(`/api/v1/projects/${projectId}/settings/creative-boundaries`)
}
export function updateCreativeBoundaries(projectId: string, input: {
  worldMode: WorldMode; adaptationScope: AdaptationScope; version: number; resetAssessment?: boolean
}): Promise<CreativeBoundarySettings> {
  return apiRequest(`/api/v1/projects/${projectId}/settings/creative-boundaries`, { method: 'PUT', body: JSON.stringify(input) })
}

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
  worldMode?: WorldMode
  adaptationScope?: AdaptationScope
  creativeIntent?: CreativeIntentInput
}

export function createProjectFromStory(input: { name?: string; creativeStrategy: CreativeStrategy; worldMode?: WorldMode; adaptationScope?: AdaptationScope; file?: File | null; text?: string }): Promise<ProjectSummary> {
  const body = new FormData()
  if (input.name) body.append('name', input.name)
  body.append('creativeStrategy', input.creativeStrategy)
  body.append('worldMode', input.worldMode ?? 'AUTO')
  body.append('adaptationScope', input.adaptationScope ?? 'AUTO')
  if (input.file) body.append('file', input.file)
  if (input.text) body.append('text', input.text)
  return apiRequest('/api/v1/projects/from-story', { method: 'POST', body, headers: { 'Idempotency-Key': createUuid() } })
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
      'Idempotency-Key': createUuid(),
    },
    body: JSON.stringify(input),
  })
}

import { apiRequest } from '@/api/http'
import type { ModelProvider, OutlineVersion, StoryBibleVersion } from '@/api/planning'

export interface ImportedChapter {
  id: string
  ordinal: number
  title: string
  content: string
  characterCount: number
  contentType: 'MANUSCRIPT' | 'OUTLINE' | 'MATERIALS'
  selected: boolean
}

export interface WorkImport {
  id: string
  projectId: string
  originalFilename: string
  mediaType: string
  sizeBytes: number
  sha256: string
  parserVersion: string
  detectedContentType: 'MANUSCRIPT' | 'OUTLINE' | 'MATERIALS'
  status: 'PARSED' | 'CONFIRMED'
  planningStatus: 'NOT_STARTED' | 'GENERATING' | 'GENERATED' | 'FAILED'
  planningMode: ImportPlanningMode | null
  generatedBibleVersionId: string | null
  generatedOutlineVersionId: string | null
  planningError: string | null
  warnings: string[]
  chapters: ImportedChapter[]
  createdAt: string
  confirmedAt: string | null
}

export type ImportPlanningMode = 'ADAPT_SOURCE' | 'CONTINUE_MANUSCRIPT'

export async function uploadWork(projectId: string, file: File): Promise<WorkImport> {
  const body = new FormData()
  body.append('file', file)
  return apiRequest(`/api/v1/projects/${projectId}/imports`, { method: 'POST', body })
}

export async function listWorkImports(projectId: string): Promise<WorkImport[]> {
  return apiRequest(`/api/v1/projects/${projectId}/imports`)
}

export async function confirmWorkImport(projectId: string, importId: string): Promise<WorkImport> {
  return apiRequest(`/api/v1/projects/${projectId}/imports/${importId}/actions/confirm`, { method: 'POST' })
}

export interface ReversePlanResult {
  storyBible: StoryBibleVersion
  outline: OutlineVersion
}

export async function reversePlanFromImport(projectId: string, importId: string,
  provider: Exclude<ModelProvider, 'LOCAL_TEMPLATE'>, mode: ImportPlanningMode,
  instruction: string, analysisId: string, analysisVersion: number): Promise<ReversePlanResult> {
  return apiRequest(`/api/v1/projects/${projectId}/imports/${importId}/actions/reverse-plan`, {
    method: 'POST',
    body: JSON.stringify({ provider, mode, instruction: instruction.trim() || null, analysisId, analysisVersion }),
  })
}

export function workImportSourceUrl(projectId: string, importId: string) {
  return `/api/v1/projects/${projectId}/imports/${importId}/source`
}

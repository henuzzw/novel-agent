import { apiRequest } from '@/api/http'
import type { ModelProvider, StoryDirectionSet } from '@/api/planning'

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
  planningStatus: 'NOT_STARTED' | 'GENERATING' | 'GENERATED' | 'DIRECTIONS_READY' | 'FAILED' | 'CANCELLED'
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

export function pasteWork(projectId: string, text: string): Promise<WorkImport> {
  return apiRequest(`/api/v1/projects/${projectId}/imports/text`, { method: 'POST', body: JSON.stringify({ text }) })
}

export function prepareImportedDirections(projectId: string, importId: string,
  provider: Exclude<ModelProvider, 'LOCAL_TEMPLATE'>, mode: ImportPlanningMode, instruction: string,
  analysisId: string, analysisVersion: number, targetWords: number, expandScenes: boolean): Promise<StoryDirectionSet> {
  return apiRequest(`/api/v1/projects/${projectId}/imports/${importId}/actions/prepare-directions`, {
    method: 'POST', body: JSON.stringify({ provider, mode, instruction, analysisId, analysisVersion, targetWords, expandScenes }),
  })
}

export async function listWorkImports(projectId: string): Promise<WorkImport[]> {
  return apiRequest(`/api/v1/projects/${projectId}/imports`)
}

export async function confirmWorkImport(projectId: string, importId: string): Promise<WorkImport> {
  return apiRequest(`/api/v1/projects/${projectId}/imports/${importId}/actions/confirm`, { method: 'POST' })
}

export function workImportSourceUrl(projectId: string, importId: string) {
  return `/api/v1/projects/${projectId}/imports/${importId}/source`
}

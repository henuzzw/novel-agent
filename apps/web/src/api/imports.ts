import { ApiError } from '@/api/projects'
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

async function responseBody<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new ApiError(problem?.detail ?? problem?.message ?? `请求失败（HTTP ${response.status}）`, response.status, problem?.code)
  }
  return response.json() as Promise<T>
}

export async function uploadWork(projectId: string, file: File): Promise<WorkImport> {
  const body = new FormData()
  body.append('file', file)
  return responseBody(await fetch(`/api/v1/projects/${projectId}/imports`, { method: 'POST', body }))
}

export async function listWorkImports(projectId: string): Promise<WorkImport[]> {
  return responseBody(await fetch(`/api/v1/projects/${projectId}/imports`, { cache: 'no-store' }))
}

export async function confirmWorkImport(projectId: string, importId: string): Promise<WorkImport> {
  return responseBody(await fetch(`/api/v1/projects/${projectId}/imports/${importId}/actions/confirm`, { method: 'POST' }))
}

export interface ReversePlanResult {
  storyBible: StoryBibleVersion
  outline: OutlineVersion
}

export async function reversePlanFromImport(projectId: string, importId: string,
  provider: Exclude<ModelProvider, 'LOCAL_TEMPLATE'>, mode: ImportPlanningMode,
  instruction: string): Promise<ReversePlanResult> {
  return responseBody(await fetch(`/api/v1/projects/${projectId}/imports/${importId}/actions/reverse-plan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, mode, instruction: instruction.trim() || null }),
  }))
}

export function workImportSourceUrl(projectId: string, importId: string) {
  return `/api/v1/projects/${projectId}/imports/${importId}/source`
}

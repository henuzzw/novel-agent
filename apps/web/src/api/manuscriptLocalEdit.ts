import { apiRequest } from '@/api/http'
import type { ModelProvider } from '@/api/planning'
import type { ManuscriptVersion } from '@/api/writing'

export interface ManuscriptLocalEditInput {
  sourceManuscriptId: string
  sourceRowVersion: number
  selection: string
  occurrence?: number
  offset?: number
  provider: ModelProvider
  instruction: string
  authorized: boolean
}

export interface ManuscriptLocalEditResult {
  assessment: 'DRAFT_CREATED' | 'NOT_ASSESSED'
  message: string
  sourceManuscriptId: string
  sourceRowVersion: number
  selection: string
  occurrence: number
  offset: number
  replacement: string | null
  manuscript: ManuscriptVersion | null
}

export function editManuscriptSelection(projectId: string, chapter: number, input: ManuscriptLocalEditInput): Promise<ManuscriptLocalEditResult> {
  return apiRequest(`/api/v1/projects/${projectId}/chapters/${chapter}/manuscripts/actions/local-edit`, {
    method: 'POST', body: JSON.stringify(input),
  })
}

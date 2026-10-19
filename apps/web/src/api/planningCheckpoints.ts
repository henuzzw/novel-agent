import { apiRequest } from '@/api/http'
import type { ModelProvider, OutlineArc, OutlineVersion, StoryBibleVersion } from '@/api/planning'
import type { CreativeStrategy } from '@/api/projects'

export interface PlanningCheckpoint {
  id: string
  projectId: string
  chunkKey: string
  chapterFrom: number
  chapterTo: number
  source: {
    bibleId: string
    bibleRowVersion: number
    creativeStrategy: { strategy: CreativeStrategy; policyVersion: number }
    provider: ModelProvider
    instruction: string
    dependencyHash: string
  }
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  attempt: number
  version: number
  result: { arcs: OutlineArc[] } | null
  failure: string | null
  createdAt: string
  updatedAt: string
}

export interface PlanningBatch {
  id: string
  projectId: string
  bibleId: string
  bibleRowVersion: number
  chapterTo: number
  chunkSize: number
  provider: ModelProvider
  instruction: string
  version: number
  status: 'READY' | 'RUNNING' | 'FAILED' | 'CANCELLED' | 'SUCCEEDED'
  outlineVersionId: string | null
  checkpoints: PlanningCheckpoint[]
  createdAt: string
}

export interface CreatePlanningBatch {
  chapterTo: number
  chunkSize: number
  provider: ModelProvider
  instruction: string
  requestId: string
  expectedBibleVersion: number
  expectedBibleId: string
}

function batchPath(projectId: string, id?: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/planning-batches${id ? `/${encodeURIComponent(id)}` : ''}`
}

export function getCurrentPlanningBible(projectId: string): Promise<StoryBibleVersion | null> {
  return apiRequest(`/api/v1/projects/${encodeURIComponent(projectId)}/planning-snapshot`)
}

export function listPlanningBatches(projectId: string): Promise<PlanningBatch[]> {
  return apiRequest(batchPath(projectId))
}

export function getPlanningBatch(projectId: string, id: string): Promise<PlanningBatch> {
  return apiRequest(batchPath(projectId, id))
}

export function createPlanningBatch(projectId: string, input: CreatePlanningBatch): Promise<PlanningBatch> {
  return apiRequest(batchPath(projectId), { method: 'POST', body: JSON.stringify(input) })
}

export function actOnPlanningBatch(
  projectId: string, batch: Pick<PlanningBatch, 'id' | 'version'>, action: 'run-next' | 'cancel' | 'resume',
): Promise<PlanningBatch> {
  return apiRequest(`${batchPath(projectId, batch.id)}/actions/${action}`, {
    method: 'POST', body: JSON.stringify({ version: batch.version }),
  })
}

export function assemblePlanningBatch(
  projectId: string, batch: Pick<PlanningBatch, 'id' | 'version'>,
): Promise<OutlineVersion> {
  return apiRequest(`${batchPath(projectId, batch.id)}/actions/assemble`, {
    method: 'POST', body: JSON.stringify({ version: batch.version }),
  })
}

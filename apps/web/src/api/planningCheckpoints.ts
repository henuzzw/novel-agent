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

export interface CreatePlanningCheckpoint {
  chunkKey: string
  chapterFrom: number
  chapterTo: number
  provider: ModelProvider
  instruction: string
}

export interface ReusedPlanningResult {
  checkpointId: string
  attempt: number
  dependencyHash: string
  result: { arcs: OutlineArc[] }
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

function path(projectId: string, resource: 'planning-checkpoints' | 'planning-batches', id?: string) {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/${resource}${id ? `/${encodeURIComponent(id)}` : ''}`
}

export function getCurrentPlanningBible(projectId: string): Promise<StoryBibleVersion | null> {
  return apiRequest(`/api/v1/projects/${encodeURIComponent(projectId)}/story-bibles/current`)
}

export function listPlanningCheckpoints(projectId: string): Promise<PlanningCheckpoint[]> {
  return apiRequest(path(projectId, 'planning-checkpoints'))
}

export function getPlanningCheckpoint(projectId: string, id: string): Promise<PlanningCheckpoint> {
  return apiRequest(path(projectId, 'planning-checkpoints', id))
}

export function createPlanningCheckpoint(projectId: string, input: CreatePlanningCheckpoint): Promise<PlanningCheckpoint> {
  return apiRequest(path(projectId, 'planning-checkpoints'), { method: 'POST', body: JSON.stringify(input) })
}

export function actOnPlanningCheckpoint(
  projectId: string, checkpoint: Pick<PlanningCheckpoint, 'id' | 'version'>, action: 'run' | 'cancel' | 'retry',
): Promise<PlanningCheckpoint> {
  return apiRequest(`${path(projectId, 'planning-checkpoints', checkpoint.id)}/actions/${action}`, {
    method: 'POST', body: JSON.stringify({ version: checkpoint.version }),
  })
}

export function reusePlanningCheckpoint(projectId: string, id: string): Promise<ReusedPlanningResult> {
  return apiRequest(`${path(projectId, 'planning-checkpoints', id)}/actions/reuse`, { method: 'POST' })
}

export function listPlanningBatches(projectId: string): Promise<PlanningBatch[]> {
  return apiRequest(path(projectId, 'planning-batches'))
}

export function getPlanningBatch(projectId: string, id: string): Promise<PlanningBatch> {
  return apiRequest(path(projectId, 'planning-batches', id))
}

export function createPlanningBatch(projectId: string, input: CreatePlanningBatch): Promise<PlanningBatch> {
  return apiRequest(path(projectId, 'planning-batches'), { method: 'POST', body: JSON.stringify(input) })
}

export function actOnPlanningBatch(
  projectId: string, batch: Pick<PlanningBatch, 'id' | 'version'>, action: 'run-next' | 'cancel' | 'resume',
): Promise<PlanningBatch> {
  return apiRequest(`${path(projectId, 'planning-batches', batch.id)}/actions/${action}`, {
    method: 'POST', body: JSON.stringify({ version: batch.version }),
  })
}

export function assemblePlanningBatch(
  projectId: string, batch: Pick<PlanningBatch, 'id' | 'version'>,
): Promise<OutlineVersion> {
  return apiRequest(`${path(projectId, 'planning-batches', batch.id)}/actions/assemble`, {
    method: 'POST', body: JSON.stringify({ version: batch.version }),
  })
}

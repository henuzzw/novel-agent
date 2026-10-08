import type { ModelProvider } from '@/api/planning'
import { apiRequest } from '@/api/http'

export interface AutomationStep {
  chapterNumber: number
  stage: 'CONTRACT' | 'CONTRACT_REVIEW' | 'MANUSCRIPT' | 'QUALITY_REVIEW' | 'QUALITY_REVISION' | 'REVIEW'
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  artifactId: string | null
  startedAt: string
  completedAt: string | null
  errorCode: string | null
}

export interface AutomationRun {
  id: string
  projectId: string
  outlineId: string
  firstChapter: number
  lastChapter: number
  currentChapter: number
  provider: ModelProvider
  qualityReviewEnabled: boolean
  maxAutoRevisionRounds: number
  maxGenerationSteps: number
  usedGenerationSteps: number
  usedAutoRevisionRounds: number
  status: 'PENDING' | 'RUNNING' | 'WAITING_FOR_USER' | 'FAILED' | 'CANCELLED' | 'SUCCEEDED'
  cancelRequested: boolean
  attempt: number
  waitingReason: string | null
  errorCode: string | null
  steps: AutomationStep[]
  createdAt: string
  updatedAt: string
}

export interface CreateAutomationInput {
  firstChapter: number
  lastChapter: number
  provider: ModelProvider
  instruction?: string
  qualityReviewEnabled?: boolean
  maxAutoRevisionRounds?: number
  maxGenerationSteps?: number
}

export interface AutomationChapterTarget {
  chapter: number
  mode: 'manuscript' | 'review'
  provider: ModelProvider
}

export function automationChapterTarget(run: AutomationRun): AutomationChapterTarget {
  const steps = run.steps.filter(step => step.chapterNumber === run.currentChapter)
  const stage = steps[steps.length - 1]?.stage
  return { chapter: run.currentChapter, provider: run.provider,
    mode: stage === 'MANUSCRIPT' || stage === 'QUALITY_REVIEW' || stage === 'QUALITY_REVISION' ? 'manuscript'
      : stage === 'REVIEW' ? 'review' : 'manuscript' }
}

export function listAutomationRuns(projectId: string) {
  return apiRequest<AutomationRun[]>(`/api/v1/projects/${projectId}/automation-runs`)
}

export function createAutomationRun(projectId: string, input: CreateAutomationInput, requestKey: string) {
  return apiRequest<AutomationRun>(`/api/v1/projects/${projectId}/automation-runs`, {
    method: 'POST', headers: { 'Idempotency-Key': requestKey }, body: JSON.stringify(input),
  })
}

export function resumeAutomationRun(projectId: string, id: string) {
  return apiRequest<AutomationRun>(`/api/v1/projects/${projectId}/automation-runs/${id}/actions/resume`, { method: 'POST' })
}

export function cancelAutomationRun(projectId: string, id: string) {
  return apiRequest<AutomationRun>(`/api/v1/projects/${projectId}/automation-runs/${id}/actions/cancel`, { method: 'POST' })
}

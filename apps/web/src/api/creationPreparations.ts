import { apiRequest } from '@/api/http'
import type { CharacterBlueprint, ModelProvider, ReaderExperienceSeed } from '@/api/planning'

export interface PreparationEntity {
  key: string; type: 'ITEM' | 'LOCATION' | 'ORGANIZATION'; name: string; description: string; initialState: string; owner: string
}
export interface PlotUnit {
  key: string; title: string; startChapter: number; endChapter: number; objective: string; conflict: string
  turningPoint: string; endCondition: string; characters: string[]; planKeys: string[]
}
export interface PlannedRelation { source: string; target: string; type: string; description: string; fromChapter: number }
export interface PlannedKnowledge { character: string; information: string; knownFromChapter: number; source: string }
export interface PlannedTime { key: string; chapter: number; storyTime: string; event: string; participants: string[] }
export interface PreparationWorld { characters: CharacterBlueprint[]; entities: PreparationEntity[] }
export interface PreparationPlot {
  units: PlotUnit[]; relationships: PlannedRelation[]; knowledge: PlannedKnowledge[]
  timeline: PlannedTime[]; readerExperiencePlans: ReaderExperienceSeed[]
}
export interface PreparationIssue { key: string; severity: 'WARNING' | 'BLOCKING'; category: string; description: string; sourceRef: string; evidence: string; suggestion: string }
export interface FutureAdjustment { chapterNumber: number; objective: string; coreEvent: string; reveal: string; endingHook: string; reason: string }
export interface PreparationReview {
  summary: string; issues: PreparationIssue[]; adjustments: FutureAdjustment[]
  planLinks: { planId: string; factId: string; state: string; evidence: string }[]
}
export interface PreparationTask {
  id: string; projectId: string; mode: 'PREPARE' | 'REVIEW'; provider: ModelProvider; instruction: string
  sourceBibleId: string; sourceOutlineId: string; sourceHash: string; sourceSnapshot: Record<string, unknown>
  startChapter: number; endChapter: number; status: 'READY' | 'RUNNING' | 'FAILED' | 'AWAITING_CONFIRMATION' | 'CONFIRMED' | 'CANCELLED'
  nextStep: number; worldDesign: PreparationWorld | null; plotDesign: PreparationPlot | null
  reviewReport: PreparationReview | null; resultOutlineId: string | null; errorMessage: string | null; version: number; updatedAt: string
}
export interface PreparationView { task: PreparationTask; stale: boolean; ruleWarnings: string[] }
export interface ChapterPlanLink { id: string; planId: string; title: string; chapterNumber: number; state: string; evidence: string; stale: boolean; recorded: boolean }
export interface PreparationCheckpoint { key: string; title: string; startChapter: number; endChapter: number; ready: boolean; reviewed: boolean; stale: boolean }
const root = (projectId: string) => `/api/v1/projects/${projectId}/creation-preparations`
export const listPreparations = (projectId: string) => apiRequest<PreparationView[]>(root(projectId))
export const listPreparationCheckpoints = (projectId: string) => apiRequest<PreparationCheckpoint[]>(`${root(projectId)}/checkpoints`)
export const getPreparation = (projectId: string, id: string) => apiRequest<PreparationView>(`${root(projectId)}/${id}`)
export function createPreparation(projectId: string, input: { requestId: string; mode: 'PREPARE' | 'REVIEW'; provider: ModelProvider; startChapter?: number; endChapter?: number; instruction: string }) {
  return apiRequest<PreparationView>(root(projectId), { method: 'POST', body: JSON.stringify(input) })
}
export function preparationAction(projectId: string, task: PreparationTask, action: 'run-next' | 'resume' | 'cancel') {
  return apiRequest<PreparationView>(`${root(projectId)}/${task.id}/actions/${action}`, { method: 'POST', body: JSON.stringify({ version: task.version }) })
}
export function editPreparation(projectId: string, task: PreparationTask, world: PreparationWorld, plot: PreparationPlot) {
  return apiRequest<PreparationView>(`${root(projectId)}/${task.id}`, { method: 'PUT', body: JSON.stringify({ version: task.version, world, plot }) })
}
export function confirmPreparation(projectId: string, task: PreparationTask, acceptWarnings: boolean, selectedChapters: number[]) {
  return apiRequest<PreparationView>(`${root(projectId)}/${task.id}/actions/confirm`, { method: 'POST', body: JSON.stringify({ version: task.version, authorConfirmed: true, acceptWarnings, selectedChapters }) })
}
export const listChapterPlanLinks = (projectId: string) => apiRequest<ChapterPlanLink[]>(`${root(projectId)}/plan-links`)
export const confirmChapterPlanLink = (projectId: string, link: ChapterPlanLink, planVersion: number, requestId: string) =>
  apiRequest<void>(`${root(projectId)}/plan-links/${link.id}/actions/confirm`, { method: 'POST', body: JSON.stringify({ requestId, planVersion, authorConfirmed: true }) })

import { apiRequest } from '@/api/http'
import type { ImportPlanningMode } from '@/api/imports'
import type { ModelProvider } from '@/api/planning'

export interface AnalysisEvidence { chapterId: string; quote: string; occurrence: number }
export interface AnalysisItem {
  key: string; category: 'CHARACTER' | 'WORLD' | 'RELATIONSHIP' | 'EVENT' | 'CLUE' | 'FORESHADOW'
  certainty: 'FACT' | 'INFERENCE' | 'UNKNOWN'; title: string; description: string; subjects: string[]
  progress: 'NOT_APPLICABLE' | 'SET_UP' | 'REINFORCED' | 'PAYOFF' | 'UNRESOLVED' | 'UNKNOWN'
  evidence: AnalysisEvidence[]
}
export interface AnalysisDecision { key: string; action: 'KEEP' | 'REWORK' | 'DROP'; note: string }
export interface AnalysisReport {
  id: string; projectId: string; importId: string; provider: ModelProvider; sourceHash: string
  slices: { chapterId: string; ordinal: number; title: string; start: number; end: number }[]
  nextSlice: number; status: 'READY' | 'RUNNING' | 'FAILED' | 'REVIEW' | 'CONFIRMED' | 'CANCELLED'
  content: { summaries: string[]; items: AnalysisItem[] }; decisions: AnalysisDecision[]
  confirmedMode: ImportPlanningMode | null; errorMessage: string | null; version: number; updatedAt: string
}
export interface AnalysisView { report: AnalysisReport; stale: boolean }
export interface AnalysisProof { id: string; version: number; mode: ImportPlanningMode }
const base = (p: string, i: string) => `/api/v1/projects/${p}/imports/${i}/analyses`
export const listImportAnalyses = (p: string, i: string) => apiRequest<AnalysisView[]>(base(p, i))
export const getImportAnalysis = (p: string, i: string, id: string) => apiRequest<AnalysisView>(`${base(p, i)}/${id}`)
export const createImportAnalysis = (p: string, i: string, provider: ModelProvider, requestId: string) => apiRequest<AnalysisView>(base(p, i), { method: 'POST', body: JSON.stringify({ provider, requestId }) })
export const importAnalysisAction = (p: string, i: string, v: AnalysisView, action: 'run-next' | 'resume' | 'cancel') => apiRequest<AnalysisView>(`${base(p, i)}/${v.report.id}/actions/${action}`, { method: 'POST', body: JSON.stringify({ version: v.report.version }) })
export const confirmImportAnalysis = (p: string, i: string, v: AnalysisView, mode: ImportPlanningMode, decisions: AnalysisDecision[], authorConfirmed: boolean) => apiRequest<AnalysisView>(`${base(p, i)}/${v.report.id}/actions/confirm`, { method: 'POST', body: JSON.stringify({ version: v.report.version, mode, decisions, authorConfirmed }) })

import { apiRequest } from './http'
import type { ModelProvider } from './planning'
import type { ChapterContractContent } from './writing'
import type { QualityReview } from './writingQuality'

export type OpeningDimension = 'FIRST_CHAPTER' | 'CAUSAL_CONTINUITY' | 'PAYOFF' | 'REPETITION' | 'CHARACTER' | 'STYLE' | 'LOGIC' | 'SCENE'
export interface OpeningEvidence { chapterNumber: number; quote: string }
export interface OpeningContent {
  summary: string
  assessments: { dimension: OpeningDimension; status: 'OBSERVATION' | 'NOT_ASSESSED'; observation: string; evidence: OpeningEvidence[] }[]
  issues: { id: string; dimension: OpeningDimension; description: string; suggestion: string; evidence: OpeningEvidence[] }[]
}
export interface OpeningChapter {
  chapterNumber: number; manuscriptId: string | null; versionNumber: number; rowVersion: number
  status: string; title: string; body: string | null
  contractId: string | null; contractVersionNumber: number; contractRowVersion: number; contractStatus: string
  contract: ChapterContractContent | null
  versions: { id: string; versionNumber: number; rowVersion: number; status: string }[]
  qualityReview: QualityReview['content'] | null; qualityReviewCurrent: boolean
}
export interface OpeningSource {
  projectId: string; outlineId: string | null; outlineRowVersion: number; bibleId: string | null
  bibleRowVersion: number; canonVersion: number; strategy: string; outlineContext: string; bibleContext: string
  styleContext: string; profileContext: string; chapters: OpeningChapter[]; unavailableReasons: string[]; fingerprint: string
}
export interface OpeningBudget {
  estimatedInputTokens: number; maxOutputTokens: number; contextWindowTokens: number
  safetyMarginTokens: number; inputLimitTokens: number; fits: boolean; modelCalls: number; notice: string
}
export interface OpeningReport {
  id: string; versionNumber: number; provider: ModelProvider; reviewMode: 'MODEL' | 'RULES_ONLY'; current: boolean
  fingerprint: string; source: OpeningSource; content: OpeningContent; budget: OpeningBudget; createdAt: string
}
export interface OpeningView {
  source: OpeningSource; available: boolean; budget: OpeningBudget
  latestReport: OpeningReport | null; latestValidReport: OpeningReport | null
}
export interface OpeningCheckInput {
  manuscriptIds: string[]; provider: ModelProvider; instruction: string; expectedFingerprint: string; maxInputTokens: number
}
const url = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/opening-review`
export function getFirstThreeChapters(projectId: string, provider: ModelProvider, manuscriptIds: string[] = [], instruction = '') {
  const params = new URLSearchParams({ provider, instruction })
  if (manuscriptIds.length) params.set('manuscriptIds', manuscriptIds.join(','))
  return apiRequest<OpeningView>(`${url(projectId)}?${params}`)
}
export const checkFirstThreeChapters = (projectId: string, input: OpeningCheckInput) =>
  apiRequest<OpeningReport>(`${url(projectId)}/actions/check`, { method: 'POST', body: JSON.stringify(input) })

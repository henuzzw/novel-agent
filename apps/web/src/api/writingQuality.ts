import { apiRequest } from './http'
import type { ModelProvider } from './planning'
import type { ManuscriptVersion, ReviewIssue } from './writing'

export interface WritingStyleProfile {
  name: string
  narrativeVoice: string
  sentenceRhythm: string
  descriptionFocus: string
  dialogueStyle: string
  emotionalExpression: string
  pacing: string
  avoidPatterns: string[]
  basePresetId?: string | null
  basePresetVersion?: number | null
  craft?: WritingStyleCraft | null
}
export interface WritingStyleCraft {
  narratorPosition: string
  paragraphMoves: string
  sentenceMoves: string
  wordChoice: string
  dialogueMoves: string
  rhetoricMoves: string
  sceneVariants: string
  revisionChecks: string
  examples: { scene: string; facts: string; positive: string; nearMiss: string; explanation: string }[]
  evidence: { dimension: string; quote: string; explanation: string }[]
}
export interface StyleState { profile: WritingStyleProfile | null; version: number }
export interface StyleAnalysis { profile: WritingStyleProfile; analysisMode: 'TEXT_METRICS' | 'MODEL'; sampleCharacters: number }
export interface StylePreviewInput {
  outlineVersionId: string; expectedOutlineVersion: number; profile: WritingStyleProfile
  provider: ModelProvider; targetWords: number; instruction: string
}
export interface StylePreview {
  sourceOutlineVersionId: string; sourceOutlineRowVersion: number; outlineGenerationNumber: number
  sourceBibleVersionId: string; profile: WritingStyleProfile; provider: ModelProvider
  targetWords: number; previewMode: 'MODEL' | 'TEMPLATE'; content: { title: string; body: string }
}
export interface StyleRecommendationInput {
  bibleVersionId: string; expectedBibleVersion: number; provider: ModelProvider; instruction: string
}
export interface StyleRecommendation {
  sourceBibleVersionId: string; sourceBibleRowVersion: number; bibleGenerationNumber: number
  provider: ModelProvider; recommendationMode: 'MODEL' | 'TEMPLATE'; summary: string
  recommendations: { profile: WritingStyleProfile; reason: string; tradeoff: string; evidence: { field: string; quote: string }[] }[]
}
const styleUrl = (project: string) => `/api/v1/projects/${project}/writing-style`
export const getWritingStyle = (project: string) => apiRequest<StyleState>(styleUrl(project))
export const getStylePresets = (project: string) => apiRequest<WritingStyleProfile[]>(`${styleUrl(project)}/presets`)
export const applyWritingStyle = (project: string, profile: WritingStyleProfile | null, expectedVersion: number) =>
  apiRequest<StyleState>(styleUrl(project), { method: 'PUT', body: JSON.stringify({ profile, expectedVersion }) })
export const analyzeWritingStyle = (project: string, sampleText: string, provider: ModelProvider) =>
  apiRequest<StyleAnalysis>(`${styleUrl(project)}/actions/analyze`, { method: 'POST', body: JSON.stringify({ sampleText, provider }) })
export const generateStylePreview = (project: string, input: StylePreviewInput) =>
  apiRequest<StylePreview>(`${styleUrl(project)}/actions/preview`, { method: 'POST', body: JSON.stringify(input) })
export const recommendWritingStyle = (project: string, input: StyleRecommendationInput) =>
  apiRequest<StyleRecommendation>(`${styleUrl(project)}/actions/recommend`, { method: 'POST', body: JSON.stringify(input) })
export async function uploadWritingStyle(project: string, file: File, provider: ModelProvider): Promise<StyleAnalysis> {
  const body = new FormData()
  body.append('file', file)
  body.append('provider', provider)
  return apiRequest(`${styleUrl(project)}/actions/upload`, { method: 'POST', body })
}
export type QualityDimension = 'STYLE' | 'FLUENCY' | 'LOGIC' | 'SCENE'
export type QualityRevisionScope = 'EXPRESSION_ONLY' | 'SCENE_STRUCTURE'
export type QualityReportState = 'valid' | 'stale' | 'unrun' | 'rechecking' | 'skipped'
export function validateQualitySelection(issueIds: string[], scope: QualityRevisionScope, issues?: ReviewIssue[]) {
  if (!['EXPRESSION_ONLY', 'SCENE_STRUCTURE'].includes(scope)) throw new Error('请选择有效的修订范围')
  if (!issueIds.length || issueIds.length > 20 || new Set(issueIds).size !== issueIds.length
    || issueIds.some(id => typeof id !== 'string' || !id.trim())) throw new Error('请选择 1 至 20 条不重复的质量问题')
  if (issues) for (const id of issueIds) {
    const issue = issues.find(item => item.id === id)
    if (!issue || issue.resolved) throw new Error('请选择报告中尚未处理的质量问题')
    if (!['STYLE', 'FLUENCY', 'LOGIC', 'SCENE'].includes(issue.category)) throw new Error('不支持的质量问题类别')
    if (scope === 'EXPRESSION_ONLY' && !['STYLE', 'FLUENCY'].includes(issue.category)) {
      throw new Error('逻辑或场景问题需要显式选择场景结构修订范围')
    }
  }
}
export interface QualityReview {
  id: string; projectId: string; chapterNumber: number; versionNumber: number
  sourceManuscriptId: string; sourceManuscriptRowVersion: number; generatorType: string; current: boolean
  content: { summary: string; scores: { dimension: QualityDimension; score: number | null; rationale: string }[]; issues: ReviewIssue[] }
  createdAt: string
}
export interface StylePreviewReview {
  id: string; preview: StylePreview; provider: ModelProvider; reviewMode: 'MODEL' | 'RULES'
  content: QualityReview['content']; revisionAttempted: boolean
}
export interface StylePreviewCheckInput { source: StylePreviewInput; content: StylePreview['content'] }
export const stylePreviewKey = (value: StylePreview) => JSON.stringify([
  value.sourceOutlineVersionId, value.sourceOutlineRowVersion, value.profile, value.content,
])
export const checkStylePreview = (project: string, input: StylePreviewCheckInput) =>
  apiRequest<StylePreviewReview>(`${styleUrl(project)}/actions/check-preview`, { method: 'POST', body: JSON.stringify(input) })
export const reviseStylePreview = (project: string, id: string, provider: ModelProvider, issueIds: string[], instruction: string) =>
  apiRequest<StylePreview>(`${styleUrl(project)}/preview-reviews/${id}/actions/revise`, {
    method: 'POST', body: JSON.stringify({ provider, issueIds, instruction }),
  })
const qualityUrl = (project: string, chapter: number) => `/api/v1/projects/${project}/chapters/${chapter}/quality-reviews`
export const getQualityReview = (project: string, chapter: number) => apiRequest<QualityReview | null>(`${qualityUrl(project, chapter)}/latest`)
export const generateQualityReview = (project: string, chapter: number, provider: ModelProvider, instruction: string) =>
  apiRequest<QualityReview>(`${qualityUrl(project, chapter)}/actions/generate`, { method: 'POST', body: JSON.stringify({ provider, instruction }) })
export async function reviseFromQuality(project: string, chapter: number, id: string, provider: ModelProvider,
  issueIds: string[], instruction: string, scope: QualityRevisionScope = 'EXPRESSION_ONLY') {
  validateQualitySelection(issueIds, scope)
  if (instruction.length > 2000) throw new Error('补充要求不能超过 2000 字')
  return apiRequest<ManuscriptVersion>(`${qualityUrl(project, chapter)}/${id}/actions/revise`, {
    method: 'POST', body: JSON.stringify({ provider, issueIds, instruction, scope }),
  })
}

import { apiRequest } from '@/api/http'
import { createUuid } from '@/lib/uuid'

export type ModelProvider = 'LOCAL_CODEX' | 'DEEPSEEK' | 'LOCAL_TEMPLATE'
export type GenerationMode = 'REVISE' | 'REGENERATE'

export interface OutlineWordBudget {
  targetWords: number
  acceptableMinWords: number
  acceptableMaxWords: number
  recommendedVolumeCount: number
  recommendedChapterCount: number
  averageChapterWords: number
  recommendedChapterMinWords: number
  recommendedChapterMaxWords: number
}

export interface CharacterBlueprint {
  gender?: string
  ageDescription?: string
  name: string
  role: 'PROTAGONIST' | 'SUPPORTING' | 'MINOR'
  identity: string
  appearance: string
  background: string
  externalPersonality: string
  internalPersonality: string
  coreDesire: string
  fear: string
  flaw: string
  values: string
  speechStyle: string
  behaviorHabits: string
  abilitiesAndLimits: string
  behaviorBoundaries: string
  secret: string
  characterArc: string
  openingState: string
  initialRelationships: string[]
  initialPossessions: string[]
  knowledgeBoundaries: string[]
}

export interface StoryBibleContent {
  developmentNotes?: string | null
  readerExperiencePlans?: ReaderExperienceSeed[]
  logline: string
  theme: string
  worldSetting: string
  worldRules: string[]
  protagonist: string
  protagonistArc: string
  supportingCharacters: string[]
  relationshipDynamics: string[]
  centralConflict: string
  stakes: string
  narrativeStyle: string
  endingDirection: string
  hardConstraints: string[]
  openQuestions: string[]
  characterBlueprints?: CharacterBlueprint[]
}

export interface StoryBibleVersion {
  id: string
  projectId: string
  generationNumber: number
  schemaVersion: string
  status: 'DRAFT' | 'PUBLISHED'
  generatorType: ModelProvider | string
  authorInstruction: string | null
  sourceDirectionSetId: string | null
  sourceCandidateId: string | null
  sourceImportId: string | null
  sourceSnowflakeId?: string | null
  baseBibleVersionId: string | null
  content: StoryBibleContent
  changeSummary: string[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface ChapterPlan {
  number: number
  title: string
  content: string
  status: 'OCCURRED' | 'PLANNED'
  contentNeedsUpdate?: boolean
}

export interface OutlineArc {
  ordinal: number
  title: string
  objective: string
  mainConflict: string
  turningPoint: string
  outcome: string
  suggestedMinWords: number
  suggestedMaxWords: number
  chapters: ChapterPlan[]
}

export interface OutlineContent {
  readerExperiencePlans?: ReaderExperienceSeed[]
  title: string
  premise: string
  structureSummary: string
  pacingStrategy: string
  suggestedMinWords: number
  suggestedMaxWords: number
  arcs: OutlineArc[]
}

export interface ReaderExperienceSeed {
  key: string; kind: 'PROMISE' | 'FORESHADOW'; title: string; promise: string
  setup: string; payoff: string; aftermath: string; plannedChapter: number | null
}

export interface OutlineVersion {
  id: string
  projectId: string
  generationNumber: number
  schemaVersion: string
  status: 'DRAFT' | 'PUBLISHED'
  generatorType: ModelProvider | string
  authorInstruction: string | null
  sourceBibleVersionId: string
  baseOutlineVersionId: string | null
  wordBudget: OutlineWordBudget
  content: OutlineContent
  changeSummary: string[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface OutlineVersionSummary {
  id: string
  generationNumber: number
  status: 'DRAFT' | 'PUBLISHED'
  title: string
  chapterCount: number
  sourceBibleVersionId: string
  baseOutlineVersionId: string | null
  createdAt: string
}

export function getPlanningSnapshot(projectId: string): Promise<StoryBibleVersion | null> {
  return apiRequest<StoryBibleVersion | null>(`/api/v1/projects/${projectId}/planning-snapshot`)
}

export function getLatestOutline(projectId: string): Promise<OutlineVersion | null> {
  return apiRequest<OutlineVersion | null>(`/api/v1/projects/${projectId}/outlines/latest`)
}

export function getCurrentOutline(projectId: string): Promise<OutlineVersion | null> {
  return apiRequest<OutlineVersion | null>(`/api/v1/projects/${projectId}/outlines/current`)
}

export function listOutlineVersions(projectId: string): Promise<OutlineVersionSummary[]> {
  return apiRequest<OutlineVersionSummary[]>(`/api/v1/projects/${projectId}/outlines`)
}

export function getOutlineVersion(projectId: string, outlineId: string): Promise<OutlineVersion> {
  return apiRequest<OutlineVersion>(`/api/v1/projects/${projectId}/outlines/${outlineId}`)
}

export function generateOutline(
  projectId: string,
  instruction: string,
  provider: ModelProvider,
  mode: GenerationMode,
  baseOutlineVersionId: string | null = null,
): Promise<OutlineVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/outlines/actions/generate`, {
    method: 'POST',
    headers: { 'Idempotency-Key': createUuid() },
    body: JSON.stringify({
      instruction: instruction.trim() || null,
      provider,
      mode,
      baseOutlineVersionId: mode === 'REVISE' ? baseOutlineVersionId : null,
    }),
  })
}

export function updateOutline(
  projectId: string,
  outline: OutlineVersion,
  content: OutlineContent,
): Promise<OutlineVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/outlines/${outline.id}`, {
    method: 'PUT',
    headers: { 'If-Match': `"${outline.version}"` },
    body: JSON.stringify({ content }),
  })
}

export function publishOutline(projectId: string, outline: OutlineVersion): Promise<OutlineVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/outlines/${outline.id}/actions/publish`, {
    method: 'POST',
    headers: { 'If-Match': `"${outline.version}"` },
  })
}

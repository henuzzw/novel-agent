import { apiRequest } from '@/api/projects'

export type ModelProvider = 'LOCAL_CODEX' | 'DEEPSEEK' | 'LOCAL_TEMPLATE'
export type GenerationMode = 'REVISE' | 'REGENERATE'

export interface StoryDirectionCandidate {
  id: string
  title: string
  premise: string
  centralConflict: string
  protagonistArc: string
  structure: string
  endingDirection: string
  audienceFit: string
  strengths: string[]
  risks: string[]
  distinctiveFeatures: string[]
}

export interface StoryDirectionSet {
  id: string
  projectId: string
  generationNumber: number
  schemaVersion: string
  status: 'DRAFT' | 'SELECTED'
  generatorType: 'LOCAL_TEMPLATE' | string
  authorInstruction: string | null
  sourceIntentVersion: number
  wordBudget?: {
    targetWords: number
    acceptableMinWords: number
    acceptableMaxWords: number
    recommendedVolumeCount: number
    recommendedChapterCount: number
    averageChapterWords: number
    recommendedChapterMinWords: number
    recommendedChapterMaxWords: number
  }
  directions: StoryDirectionCandidate[]
  questionsForAuthor: string[]
  changeSummary: string[]
  selectedCandidateId: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface StoryBibleContent {
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
  content: StoryBibleContent
  changeSummary: string[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface ChapterPlan {
  number: number
  title: string
  pov: string
  objective: string
  coreEvent: string
  reveal: string
  endingHook: string
  suggestedMinWords: number
  suggestedMaxWords: number
  status: 'OCCURRED' | 'PLANNED'
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
  title: string
  premise: string
  structureSummary: string
  pacingStrategy: string
  suggestedMinWords: number
  suggestedMaxWords: number
  arcs: OutlineArc[]
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
  wordBudget: NonNullable<StoryDirectionSet['wordBudget']>
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

export function getLatestStoryDirections(projectId: string): Promise<StoryDirectionSet | null> {
  return apiRequest<StoryDirectionSet | null>(`/api/v1/projects/${projectId}/story-directions/latest`)
}

export function generateStoryDirections(
  projectId: string,
  instruction: string,
  provider: ModelProvider,
  mode: GenerationMode,
): Promise<StoryDirectionSet> {
  return apiRequest(`/api/v1/projects/${projectId}/story-directions/actions/generate`, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ instruction: instruction.trim() || null, provider, mode }),
  })
}

export function selectStoryDirection(
  projectId: string,
  set: StoryDirectionSet,
  candidateId: string,
): Promise<StoryDirectionSet> {
  return apiRequest(`/api/v1/projects/${projectId}/story-directions/${set.id}/actions/select`, {
    method: 'POST',
    headers: { 'If-Match': `"${set.version}"` },
    body: JSON.stringify({ candidateId }),
  })
}

export function getLatestStoryBible(projectId: string): Promise<StoryBibleVersion | null> {
  return apiRequest<StoryBibleVersion | null>(`/api/v1/projects/${projectId}/story-bibles/latest`)
}

export function generateStoryBible(
  projectId: string,
  instruction: string,
  provider: ModelProvider,
  mode: GenerationMode,
): Promise<StoryBibleVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/story-bibles/actions/generate`, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ instruction: instruction.trim() || null, provider, mode }),
  })
}

export function updateStoryBible(
  projectId: string,
  bible: StoryBibleVersion,
  content: StoryBibleContent,
): Promise<StoryBibleVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/story-bibles/${bible.id}`, {
    method: 'PUT',
    headers: { 'If-Match': `"${bible.version}"` },
    body: JSON.stringify({ content }),
  })
}

export function publishStoryBible(
  projectId: string,
  bible: StoryBibleVersion,
): Promise<StoryBibleVersion> {
  return apiRequest(`/api/v1/projects/${projectId}/story-bibles/${bible.id}/actions/publish`, {
    method: 'POST',
    headers: { 'If-Match': `"${bible.version}"` },
  })
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
    headers: { 'Idempotency-Key': crypto.randomUUID() },
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

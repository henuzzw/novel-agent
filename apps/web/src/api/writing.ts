import { apiRequest } from '@/api/projects'
import type { GenerationMode, ModelProvider } from '@/api/planning'

export interface ChapterContractContent {
  chapterTitle: string
  pov: string
  objective: string
  storyTime: string
  locations: string[]
  requiredBeats: string[]
  requiredReveals: string[]
  forbiddenFacts: string[]
  expectedExitState: string
  foreshadowActions: string[]
  hook: string
  suggestedMinWords: number
  suggestedMaxWords: number
}

export interface ChapterContractVersion {
  id: string
  projectId: string
  sourceOutlineVersionId: string
  chapterNumber: number
  versionNumber: number
  schemaVersion: string
  status: 'DRAFT' | 'APPROVED'
  generatorType: string
  authorInstruction: string | null
  content: ChapterContractContent
  version: number
  createdAt: string
  updatedAt: string
}

export interface ManuscriptContent {
  title: string
  body: string
  summary: string
  continuityNotes: string[]
}

export interface ManuscriptVersion {
  id: string
  projectId: string
  sourceContractVersionId: string
  chapterNumber: number
  versionNumber: number
  schemaVersion: string
  status: 'DRAFT' | 'AUTHOR_ACCEPTED'
  generatorType: string
  authorInstruction: string | null
  content: ManuscriptContent
  changeSummary: string[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface ReviewIssue {
  id: string
  severity: 'BLOCKING' | 'WARNING' | 'INFO'
  category: string
  description: string
  evidence: string
  suggestion: string
  resolved: boolean
}
export interface FactProposal {
  id: string
  factType: 'ENTITY_UPSERT' | 'EVENT_CREATE' | 'STATE_CHANGE' | 'RELATION_CHANGE' | 'KNOWLEDGE_CHANGE' | 'FORESHADOW_CHANGE' | 'EVENT' | 'STATE'
  subject: string
  predicate: string
  object: string
  evidence: string
  confidence: number | null
  payload: TypedFactPayload | null
  decision: 'PENDING' | 'ACCEPTED' | 'REJECTED'
}
export interface TypedFactPayload {
  entityType?: string; stateEntityType?: string; entityName?: string; entityId?: string | null
  eventTitle?: string; eventSummary?: string; storyTime?: string; participants?: string[]
  fieldKey?: string; beforeValue?: string; afterValue?: string
  sourceEntityName?: string; targetEntityName?: string; sourceEntityId?: string | null; targetEntityId?: string | null; relationType?: string
  characterName?: string; characterId?: string | null; knowledgeType?: string; beliefTruth?: string; statement?: string
  foreshadowTitle?: string; targetEffect?: string; foreshadowStatus?: string; plannedResolveChapter?: number
}
export interface StoryEntity {
  id: string
  type: 'CHARACTER' | 'LOCATION' | 'ORGANIZATION' | 'ITEM' | 'SECRET' | 'RULE'
  name: string
  status: string
  canonVersionFrom: number
}
export interface CharacterName {
  id: string
  roleKey: string | null
  sourceName: string
  canonicalName: string
  nickname: string | null
  title: string | null
  version: number
}
export interface CharacterProfile {
  characterId: string
  roleKey: string | null
  canonicalName: string
  gender: string | null
  ageDescription: string | null
  identity: string | null
  appearance: string | null
  background: string | null
  externalPersonality: string | null
  internalPersonality: string | null
  coreDesire: string | null
  fear: string | null
  flaw: string | null
  values: string | null
  speechStyle: string | null
  behaviorHabits: string | null
  secret: string | null
  characterArc: string | null
  behaviorBoundaries: string | null
  notes: string | null
  version: number
}
export interface EntityState {
  changeId: string
  field: string
  value: unknown
  chapterNumber: number
  canonVersionFrom: number
  evidence: string | null
}
export interface EntityAlias {
  id: string
  entityId: string
  alias: string
  aliasType: 'NICKNAME' | 'TITLE' | 'FORMER_NAME' | 'OTHER'
  canonVersionFrom: number
}
export interface EntityMention {
  id: string
  proposalId: string
  role: string
  mention: string
  resolvedEntityId: string
  resolutionMethod: string
  confidence: number
  evidence: string | null
}
export interface StoryEvent {
  id: string
  title: string
  summary: string
  storyTime: string | null
  chapterNumber: number
  importance: string | null
  canonVersionFrom: number
  evidence: string | null
}
export interface Foreshadow {
  id: string
  title: string
  targetEffect: string
  status: string
  plannedResolveChapter: number | null
  canonVersionFrom: number
  evidence: string | null
}
export interface StoryRelationship {
  id: string
  sourceEntityId: string
  sourceEntityName: string
  targetEntityId: string
  targetEntityName: string
  relationType: string
  attributes: unknown
  chapterNumber: number
  canonVersionFrom: number
  evidence: string | null
}
export interface CharacterKnowledge {
  id: string
  characterId: string
  characterName: string
  factId: string
  subject: string
  predicate: string
  object: string
  knowledgeType: string
  beliefTruth: string
  confidence: number | null
  chapterNumber: number
  canonVersionFrom: number
  evidence: string | null
}
export interface ChapterReviewContent { summary: string; issues: ReviewIssue[]; factProposals: FactProposal[] }
export interface ChapterReviewVersion {
  id: string; projectId: string; chapterNumber: number; sourceManuscriptVersionId: string
  versionNumber: number; schemaVersion: string; status: 'DRAFT' | 'APPROVED'; generatorType: string
  authorInstruction: string | null; content: ChapterReviewContent; version: number; createdAt: string; updatedAt: string
}
export interface CanonCommit {
  id: string; projectId: string; chapterNumber: number; manuscriptVersionId: string
  reviewVersionId: string; canonVersion: number; acceptedFacts: FactProposal[]; createdAt: string
}
export interface SemanticMemory { chapterNumber: number; canonVersion: number; similarity: number; summary: string; content: string }
export interface GraphFact { canonVersion: number; subject: string; predicate: string; object: string; evidence: string }
export interface MemoryUsage {
  stage: 'CHAPTER_CONTRACT' | 'MANUSCRIPT' | 'CHAPTER_REVIEW'
  provider: ModelProvider
  contextWindowTokens: number
  fixedInputTokens: number
  reservedOutputTokens: number
  safetyMarginTokens: number
  desiredMemoryTokens: number
  minimumMemoryTokens: number
  budgetTokens: number
  estimatedTokens: number
  truncated: boolean
  toolsUsed: string[]
}
export interface NovelMemoryContext { semanticMemories: SemanticMemory[]; graphFacts: GraphFact[]; usage: MemoryUsage }

const root = (projectId: string, chapter: number) => `/api/v1/projects/${projectId}/chapters/${chapter}`

export const listCharacterNames = (projectId: string) =>
  apiRequest<CharacterName[]>(`/api/v1/projects/${projectId}/characters`)
export const initializeCharacterNames = (projectId: string) =>
  apiRequest<CharacterName[]>(`/api/v1/projects/${projectId}/characters/actions/initialize-from-bible`, {
    method: 'POST',
  })
export const updateCharacterName = (projectId: string, value: CharacterName,
  input: Pick<CharacterName, 'canonicalName' | 'nickname' | 'title'>) =>
  apiRequest<CharacterName>(`/api/v1/projects/${projectId}/characters/${value.id}`, {
    method: 'PUT', headers: { 'If-Match': `"${value.version}"` }, body: JSON.stringify(input),
  })
export const listCharacterProfiles = (projectId: string) =>
  apiRequest<CharacterProfile[]>(`/api/v1/projects/${projectId}/character-profiles`)
export const updateCharacterProfile = (projectId: string, value: CharacterProfile,
  input: Omit<CharacterProfile, 'characterId' | 'roleKey' | 'canonicalName' | 'version'>) =>
  apiRequest<CharacterProfile>(`/api/v1/projects/${projectId}/character-profiles/${value.characterId}`, {
    method: 'PUT', headers: { 'If-Match': `"${value.version}"` }, body: JSON.stringify(input),
  })
export const manuscriptExportUrl = (projectId: string, chapter: number, manuscriptId: string) =>
  `${root(projectId, chapter)}/manuscripts/${manuscriptId}/export`

export const listCanonEntities = (projectId: string) =>
  apiRequest<StoryEntity[]>(`/api/v1/projects/${projectId}/canon/entities`)
export const getEntityState = (projectId: string, entityId: string) =>
  apiRequest<EntityState[]>(`/api/v1/projects/${projectId}/canon/entities/${entityId}/state`)
export const listEntityAliases = (projectId: string, entityId: string) =>
  apiRequest<EntityAlias[]>(`/api/v1/projects/${projectId}/canon/entities/${entityId}/aliases`)
export const addEntityAlias = (projectId: string, entityId: string, alias: string, aliasType: EntityAlias['aliasType']) =>
  apiRequest<EntityAlias>(`/api/v1/projects/${projectId}/canon/entities/${entityId}/aliases`, {
    method: 'POST', body: JSON.stringify({ alias, aliasType }),
  })
export const listEntityMentions = (projectId: string, entityId: string) =>
  apiRequest<EntityMention[]>(`/api/v1/projects/${projectId}/canon/entities/${entityId}/mentions`)
export const listCanonTimeline = (projectId: string) =>
  apiRequest<StoryEvent[]>(`/api/v1/projects/${projectId}/canon/timeline`)
export const listForeshadows = (projectId: string) =>
  apiRequest<Foreshadow[]>(`/api/v1/projects/${projectId}/canon/foreshadows`)
export const listRelationships = (projectId: string, entityId?: string) =>
  apiRequest<StoryRelationship[]>(`/api/v1/projects/${projectId}/canon/relationships${entityId ? `?entityId=${entityId}` : ''}`)
export const listCharacterKnowledge = (projectId: string, characterId?: string) =>
  apiRequest<CharacterKnowledge[]>(`/api/v1/projects/${projectId}/canon/knowledge${characterId ? `?characterId=${characterId}` : ''}`)

export const getLatestContract = (projectId: string, chapter: number) =>
  apiRequest<ChapterContractVersion | null>(`${root(projectId, chapter)}/contracts/latest`)
export const generateContract = (projectId: string, chapter: number, provider: ModelProvider, instruction: string) =>
  apiRequest<ChapterContractVersion>(`${root(projectId, chapter)}/contracts/actions/generate`, {
    method: 'POST', body: JSON.stringify({ provider, instruction: instruction.trim() || null }),
  })
export const updateContract = (projectId: string, value: ChapterContractVersion, content: ChapterContractContent) =>
  apiRequest<ChapterContractVersion>(`${root(projectId, value.chapterNumber)}/contracts/${value.id}`, {
    method: 'PUT', headers: { 'If-Match': `"${value.version}"` }, body: JSON.stringify({ content }),
  })
export const approveContract = (projectId: string, value: ChapterContractVersion) =>
  apiRequest<ChapterContractVersion>(`${root(projectId, value.chapterNumber)}/contracts/${value.id}/actions/approve`, {
    method: 'POST', headers: { 'If-Match': `"${value.version}"` },
  })
export const getLatestManuscript = (projectId: string, chapter: number) =>
  apiRequest<ManuscriptVersion | null>(`${root(projectId, chapter)}/manuscripts/latest`)
export const generateManuscript = (projectId: string, chapter: number, provider: ModelProvider,
  instruction: string, mode: GenerationMode) =>
  apiRequest<ManuscriptVersion>(`${root(projectId, chapter)}/manuscripts/actions/generate`, {
    method: 'POST', body: JSON.stringify({ provider, instruction: instruction.trim() || null, mode }),
  })
export const updateManuscript = (projectId: string, value: ManuscriptVersion, content: ManuscriptContent) =>
  apiRequest<ManuscriptVersion>(`${root(projectId, value.chapterNumber)}/manuscripts/${value.id}`, {
    method: 'PUT', headers: { 'If-Match': `"${value.version}"` }, body: JSON.stringify({ content }),
  })
export const acceptManuscript = (projectId: string, value: ManuscriptVersion) =>
  apiRequest<ManuscriptVersion>(`${root(projectId, value.chapterNumber)}/manuscripts/${value.id}/actions/accept`, {
    method: 'POST', headers: { 'If-Match': `"${value.version}"` },
  })
export const getLatestReview = (projectId: string, chapter: number) =>
  apiRequest<ChapterReviewVersion | null>(`${root(projectId, chapter)}/reviews/latest`)
export const generateReview = (projectId: string, chapter: number, provider: ModelProvider, instruction: string) =>
  apiRequest<ChapterReviewVersion>(`${root(projectId, chapter)}/reviews/actions/generate`, {
    method: 'POST', body: JSON.stringify({ provider, instruction: instruction.trim() || null }),
  })
export const updateReview = (projectId: string, value: ChapterReviewVersion, content: ChapterReviewContent) =>
  apiRequest<ChapterReviewVersion>(`${root(projectId, value.chapterNumber)}/reviews/${value.id}`, {
    method: 'PUT', headers: { 'If-Match': `"${value.version}"` }, body: JSON.stringify({ content }),
  })
export const approveReview = (projectId: string, value: ChapterReviewVersion) =>
  apiRequest<ChapterReviewVersion>(`${root(projectId, value.chapterNumber)}/reviews/${value.id}/actions/approve`, {
    method: 'POST', headers: { 'If-Match': `"${value.version}"` },
  })
export const commitCanon = (projectId: string, chapter: number, reviewVersionId: string, expectedCanonVersion: number) =>
  apiRequest<CanonCommit>(`${root(projectId, chapter)}/canon-commits`, {
    method: 'POST', body: JSON.stringify({ reviewVersionId, expectedCanonVersion }),
  })
export const getMemoryPreview = (projectId: string, chapter: number, query: string, provider: ModelProvider) =>
  apiRequest<NovelMemoryContext>(`/api/v1/projects/${projectId}/memory/preview?chapterNumber=${chapter}&stage=MANUSCRIPT&provider=${provider}&query=${encodeURIComponent(query)}`)

import { apiRequest } from './http'

export type ReaderExperienceState = 'PLANNED' | 'SET_UP' | 'REINFORCED' | 'PAYOFF' | 'ABANDONED' | 'OPEN'
export type ReaderExperienceKind = 'PROMISE' | 'FORESHADOW'
export interface ReaderExperiencePlan {
  id: string; projectId: string; kind: ReaderExperienceKind; title: string; promise: string
  setup: string; payoff: string; aftermath: string; plannedChapter: number | null; version: number
  schemaVersion: string; deleted: boolean; createdAt: string; updatedAt: string
}
export interface ReaderExperienceEvent {
  id: string; projectId: string; planId: string; entryVersion: number; planSnapshot: ReaderExperiencePlan
  state: ReaderExperienceState; manuscriptId: string; manuscriptRowVersion: number; chapterNumber: number
  sourceFingerprint: string; evidence: string; evidenceTokenized: string; authorNote: string; chapterCanonCommitId: string | null; canonAtSubmission: boolean
  submittedBy: string; schemaVersion: string; createdAt: string
}
export interface ReaderExperienceEntry {
  plan: ReaderExperiencePlan; state: ReaderExperienceState; stale: boolean
  history: { event: ReaderExperienceEvent; stale: boolean; staleReason: string | null; canon: boolean }[]
}
export interface ReaderExperiencePlanInput {
  requestId: string; expectedVersion: number | null; kind: ReaderExperienceKind; title: string; promise: string
  setup: string; payoff: string; aftermath: string; plannedChapter: number | null
}
export interface ReaderExperienceSubmission {
  requestId: string; expectedVersion: number; state: Exclude<ReaderExperienceState, 'PLANNED'>
  manuscriptId: string; manuscriptRowVersion: number; sourceFingerprint: string; evidence: string; authorNote: string; authorConfirmed: boolean
}
export interface ReaderExperienceManuscript {
  id: string; rowVersion: number; chapterNumber: number; title: string; canon: boolean; superseded: boolean
}
export interface ReaderExperienceSource {
  id: string; projectId: string; rowVersion: number; chapterNumber: number; status: string; body: string; fingerprint: string
  chapterCanonCommitId: string | null; canonManuscriptId: string | null; canonVersion: number | null; superseded: boolean
}
export interface ReaderExperienceChapterSummary {
  chapterNumber: number; manuscriptId: string; manuscriptRowVersion: number; manuscriptSchemaVersion: string
  canonCommitId: string; canonVersion: number; title: string; summary: string | null
}
export interface ReaderExperienceMemory {
  schemaVersion: string; mode: 'EXISTING_CANON_SUMMARIES'; outlineId: string | null; outlineRowVersion: number | null
  arcs: { number: number; title: string; chapters: ReaderExperienceChapterSummary[] }[]
  unassignedChapters: ReaderExperienceChapterSummary[]
}

const base = (project: string) => `/api/v1/projects/${encodeURIComponent(project)}/reader-experiences`
const item = (project: string, id: string) => `${base(project)}/${encodeURIComponent(id)}`
export const listReaderExperiences = (project: string) => apiRequest<ReaderExperienceEntry[]>(base(project))
export const getReaderExperience = (project: string, id: string) => apiRequest<ReaderExperienceEntry>(item(project, id))
export const listReaderExperienceSources = (project: string) => apiRequest<ReaderExperienceManuscript[]>(`${base(project)}/sources`)
export const getReaderExperienceSource = (project: string, id: string) => apiRequest<ReaderExperienceSource>(`${base(project)}/sources/${encodeURIComponent(id)}`)
export const getReaderExperienceMemory = (project: string) => apiRequest<ReaderExperienceMemory>(`${base(project)}/memory`)
export const createReaderExperience = (project: string, input: ReaderExperiencePlanInput) =>
  apiRequest<ReaderExperienceEntry>(base(project), { method: 'POST', body: JSON.stringify(input) })
export const updateReaderExperience = (project: string, id: string, input: ReaderExperiencePlanInput) =>
  apiRequest<ReaderExperienceEntry>(item(project, id), { method: 'PUT', body: JSON.stringify(input) })
export const deleteReaderExperience = (project: string, id: string, version: number, requestId: string) =>
  apiRequest<void>(`${item(project, id)}?${new URLSearchParams({ expectedVersion: String(version), requestId })}`, { method: 'DELETE' })
export const submitReaderExperience = (project: string, id: string, input: ReaderExperienceSubmission) =>
  apiRequest<ReaderExperienceEntry>(`${item(project, id)}/events`, { method: 'POST', body: JSON.stringify(input) })

export function readerExperienceTransitions(entry: ReaderExperienceEntry): Exclude<ReaderExperienceState, 'PLANNED'>[] {
  const state = entry.state
  let values: Exclude<ReaderExperienceState, 'PLANNED'>[]
  if (state === 'PLANNED') values = ['SET_UP', 'OPEN', 'ABANDONED']
  else if (state === 'PAYOFF' || state === 'ABANDONED') values = []
  else if (state === 'OPEN') values = ['SET_UP', 'REINFORCED', 'PAYOFF', 'OPEN', 'ABANDONED']
  else values = ['REINFORCED', 'PAYOFF', 'OPEN', 'ABANDONED']
  if (entry.stale && state !== 'PLANNED' && !values.includes(state)) values.unshift(state)
  return values
}

export function validateReaderExperienceEvidence(input: ReaderExperienceSubmission, source: ReaderExperienceSource, project: string) {
  if (!input.authorConfirmed) throw new Error('请明确确认提交实际进展')
  if (source.projectId !== project || source.id !== input.manuscriptId || source.status !== 'AUTHOR_ACCEPTED') throw new Error('请选择本项目作者已确认的正文')
  if (source.rowVersion !== input.manuscriptRowVersion || source.superseded) throw new Error('来源正文已变化或正史已替换，请重新读取')
  if (source.fingerprint !== input.sourceFingerprint) throw new Error('正文显示或人物姓名已变化，请重新读取')
  if (!input.evidence.trim() || !source.body.includes(input.evidence)) throw new Error('证据须逐字存在于所选正文中')
}

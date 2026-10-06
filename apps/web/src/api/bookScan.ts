import { apiRequest } from './http'
export interface BookScan {
  mode: 'RULES_SUMMARY_ONLY'; notice: string; outlineId: string; outlineRowVersion: number
  canonVersion: number; fingerprint: string; plannedChapters: number; canonChapters: number
  chapters: { number: number; title: string; manuscriptId: string | null; manuscriptRowVersion: number | null
    canonCommitId: string | null; summary: string | null; missingSummary: boolean }[]
  observations: { kind: string; chapters: number[]; planId: string | null; detail: string }[]
}
export function getBookScan(projectId: string) {
  return apiRequest<BookScan>(`/api/v1/projects/${encodeURIComponent(projectId)}/book-scan`)
}

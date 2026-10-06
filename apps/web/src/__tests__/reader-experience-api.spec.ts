import { afterEach, describe, expect, it, vi } from 'vitest'
import { createReaderExperience, deleteReaderExperience, getReaderExperienceMemory, getReaderExperienceSource,
  listReaderExperienceSources, listReaderExperiences, submitReaderExperience, updateReaderExperience } from '@/api/readerExperience'

afterEach(() => vi.unstubAllGlobals())
describe('reader experience API', () => {
  it('uses independent source and memory endpoints with project scope', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] })
    vi.stubGlobal('fetch', fetch)
    await listReaderExperiences('project')
    await listReaderExperienceSources('project')
    await getReaderExperienceSource('project', 'manuscript')
    await getReaderExperienceMemory('project')
    expect(fetch.mock.calls.map(call => call[0])).toEqual([
      '/api/v1/projects/project/reader-experiences', '/api/v1/projects/project/reader-experiences/sources',
      '/api/v1/projects/project/reader-experiences/sources/manuscript', '/api/v1/projects/project/reader-experiences/memory',
    ])
  })
  it('passes explicit versions, evidence fingerprint and idempotency keys without generating them', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) })
    vi.stubGlobal('fetch', fetch)
    const plan = { requestId: 'same-key', expectedVersion: 3, kind: 'PROMISE' as const, title: '约定', promise: '回应约定',
      setup: '', payoff: '', aftermath: '', plannedChapter: null }
    await createReaderExperience('project', plan)
    await updateReaderExperience('project', 'id', plan)
    const input = { requestId: 'event-key', expectedVersion: 4, state: 'SET_UP' as const, manuscriptId: 'manuscript',
      manuscriptRowVersion: 2, sourceFingerprint: 'a'.repeat(64), evidence: '逐字原文', authorNote: '', authorConfirmed: true }
    await submitReaderExperience('project', 'id', input)
    expect(JSON.parse(fetch.mock.calls[2]![1].body)).toEqual(input)
    await deleteReaderExperience('project', 'id', 5, 'delete-key')
    expect(fetch.mock.calls[3]![0]).toBe('/api/v1/projects/project/reader-experiences/id?expectedVersion=5&requestId=delete-key')
    expect(fetch.mock.calls[3]![1].method).toBe('DELETE')
  })
})

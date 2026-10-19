import { afterEach, describe, expect, it, vi } from 'vitest'

import { createManuscriptRevision, getCanonCommitStatus, publishManuscript,
  type ManuscriptVersion } from '@/api/writing'

describe('manual manuscript revision API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('creates a draft from a confirmed chapter with a version precondition', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'revision' }) })
    vi.stubGlobal('fetch', fetchMock)

    await createManuscriptRevision('project-1', {
      id: 'accepted', chapterNumber: 2, version: 3,
    } as ManuscriptVersion)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/chapters/2/manuscripts/accepted/actions/create-revision')
    expect(request.method).toBe('POST')
    expect(new Headers(request.headers).get('If-Match')).toBe('"3"')
    expect(request.body).toBeUndefined()
  })

  it('reads whether the chapter was already committed', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve({ committed: true, activeCommitId: 'commit-1',
        activeManuscriptVersionId: 'manuscript-1', canonVersion: 2 }) })
    vi.stubGlobal('fetch', fetchMock)

    expect(await getCanonCommitStatus('project-1', 2)).toEqual({ committed: true,
      activeCommitId: 'commit-1', activeManuscriptVersionId: 'manuscript-1', canonVersion: 2 })
    expect(fetchMock.mock.calls[0]?.[0]).toContain('/chapters/2/canon-commits/status')
  })

  it('publishes a replacement manuscript with version preconditions', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve({ id: 'replacement', canonVersion: 3 }) })
    vi.stubGlobal('fetch', fetchMock)

    await publishManuscript('project-1', { id: 'manuscript-2', chapterNumber: 2, version: 4 } as ManuscriptVersion, 2, 'commit-1', 'LOCAL_CODEX')

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/chapters/2/canon-commits/actions/publish')
    expect(request.method).toBe('POST')
    expect(JSON.parse(request.body as string)).toEqual({ manuscriptVersionId: 'manuscript-2', expectedManuscriptVersion: 4,
      expectedActiveCommitId: 'commit-1', expectedCanonVersion: 2, provider: 'LOCAL_CODEX' })
  })
})

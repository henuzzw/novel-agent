import { afterEach, describe, expect, it, vi } from 'vitest'

import { approveReview, createManuscriptRevision, getCanonCommitStatus, replaceCanon, returnReviewToWriting,
  type ManuscriptVersion, type ChapterReviewVersion, type ChapterReviewContent } from '@/api/writing'

describe('manual manuscript revision API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('submits unsaved fact decisions together with review approval', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve({ status: 'APPROVED' }) })
    vi.stubGlobal('fetch', fetchMock)
    const content: ChapterReviewContent = { summary: '无问题', issues: [], factProposals: [{
      id: 'F1', factType: 'EVENT', subject: '许言川', predicate: '得知', object: '名次',
      evidence: '名单', confidence: 1, payload: null, decision: 'ACCEPTED',
    }] }

    await approveReview('project-1', { id: 'review-4', chapterNumber: 2, version: 1 } as ChapterReviewVersion, content)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/chapters/2/reviews/review-4/actions/approve')
    expect(new Headers(request.headers).get('If-Match')).toBe('"1"')
    expect(JSON.parse(request.body as string)).toEqual({ content })
  })

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

  it('replaces the active canon using an approved review and version preconditions', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve({ id: 'replacement', canonVersion: 3 }) })
    vi.stubGlobal('fetch', fetchMock)

    await replaceCanon('project-1', 2, 'review-2', 'commit-1', 2)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/chapters/2/canon-commits/actions/replace')
    expect(request.method).toBe('POST')
    expect(JSON.parse(request.body as string)).toEqual({ reviewVersionId: 'review-2',
      expectedActiveCommitId: 'commit-1', expectedCanonVersion: 2 })
  })

  it('sends selected issues with an optimistic review version for rewriting', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'new-draft' }) })
    vi.stubGlobal('fetch', fetchMock)

    await returnReviewToWriting('project-1', { id: 'review-2', chapterNumber: 1,
      version: 4 } as ChapterReviewVersion, 'LOCAL_CODEX', 'REVISE', ['I1'], ' 保留开头 ')

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/chapters/1/reviews/review-2/actions/return-to-writing')
    expect(new Headers(request.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(request.body as string)).toEqual({ provider: 'LOCAL_CODEX', mode: 'REVISE',
      issueIds: ['I1'], instruction: '保留开头' })
  })
})

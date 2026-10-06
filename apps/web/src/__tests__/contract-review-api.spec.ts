import { afterEach, describe, expect, it, vi } from 'vitest'
import { approveContractReview, generateContractReview, getLatestContractReview,
  type ChapterContractReviewVersion } from '@/api/writing'

describe('contract review API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('reads, generates and confirms review under the selected chapter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve({ id: 'review-1' }) })
    vi.stubGlobal('fetch', fetchMock)
    await getLatestContractReview('project-1', 3)
    await generateContractReview('project-1', 3, 'DEEPSEEK', '核对座位')
    await approveContractReview('project-1', {
      id: 'review-1', chapterNumber: 3, version: 2,
    } as ChapterContractReviewVersion, { summary: '通过', issues: [] })

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][]
    expect(calls[0]?.[0]).toContain('/chapters/3/contract-reviews/latest')
    expect(JSON.parse(calls[1]![1].body as string)).toMatchObject({ provider: 'DEEPSEEK', instruction: '核对座位' })
    expect(new Headers(calls[2]![1].headers).get('If-Match')).toBe('"2"')
  })
})

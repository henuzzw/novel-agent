import { afterEach, describe, expect, it, vi } from 'vitest'

import { generateManuscript, getManuscriptVersion, listManuscriptVersions } from '@/api/writing'

describe('manuscript version API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the selected revision base', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'new' }) })
    vi.stubGlobal('fetch', fetchMock)

    await generateManuscript('project-1', 1, 'LOCAL_CODEX', '微调', 'REVISE', 'older')

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string)).toMatchObject({
      mode: 'REVISE', instruction: '微调', baseManuscriptVersionId: 'older',
    })
  })

  it('does not send a base when regenerating', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'new' }) })
    vi.stubGlobal('fetch', fetchMock)

    await generateManuscript('project-1', 1, 'LOCAL_CODEX', '', 'REGENERATE', 'older')

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string).baseManuscriptVersionId).toBeNull()
  })

  it('reads versions only under the selected chapter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve([]) })
    vi.stubGlobal('fetch', fetchMock)

    await listManuscriptVersions('project-1', 3)
    await getManuscriptVersion('project-1', 3, 'older')

    const requests = fetchMock.mock.calls as unknown as [string, RequestInit][]
    expect(requests[0]?.[0]).toContain('/chapters/3/manuscripts')
    expect(requests[1]?.[0]).toContain('/chapters/3/manuscripts/older')
  })
})

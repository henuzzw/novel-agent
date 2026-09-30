import { afterEach, describe, expect, it, vi } from 'vitest'

import { generateOutline } from '@/api/planning'

describe('outline version selection', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the chosen historical version for revision', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: () => Promise.resolve({ id: 'new-version' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await generateOutline('project-1', '突出人物性格', 'LOCAL_CODEX', 'REVISE', 'older-version')

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string)).toMatchObject({
      instruction: '突出人物性格',
      mode: 'REVISE',
      baseOutlineVersionId: 'older-version',
    })
  })

  it('omits the base when regenerating', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: () => Promise.resolve({ id: 'new-version' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await generateOutline('project-1', '', 'LOCAL_CODEX', 'REGENERATE', 'older-version')

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string).baseOutlineVersionId).toBeNull()
  })
})

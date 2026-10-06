import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiRequest, updateCreativeIntent, type ProjectSummary } from '@/api/projects'

describe('apiRequest', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns null for an empty 204 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204 }))

    await expect(apiRequest<null>('/api/empty')).resolves.toBeNull()
  })

  it('creates a missing creative intent with version zero', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ id: 'project-1' }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const project = { id: 'project-1', creativeIntent: null } as ProjectSummary

    await updateCreativeIntent(project, {
      premise: '故事', genres: ['校园'], protagonistBrief: '主角', centralConflict: '冲突',
      tones: ['真实'], targetWords: 120000, mustHave: [], avoid: [], stylePreferences: [],
    })

    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit]
    expect(url).toBe('/api/v1/projects/project-1/creative-intent')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"0"')
  })
})

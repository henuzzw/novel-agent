import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiRequest } from '@/api/http'
import { updateCreativeIntent, type ProjectSummary } from '@/api/projects'

describe('apiRequest', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('returns null for an empty 204 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204 }))
    await expect(apiRequest<null>('/api/empty')).resolves.toBeNull()
  })

  it('creates a missing creative intent with version zero', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ id: 'project-1' }) })
    vi.stubGlobal('fetch', fetchMock)
    await updateCreativeIntent({ id: 'project-1', creativeIntent: null } as ProjectSummary, {
      premise: '故事', genres: ['校园'], protagonistBrief: '主角', centralConflict: '冲突',
      tones: ['真实'], targetWords: 120000, mustHave: [], avoid: [], stylePreferences: [],
    })
    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit]
    expect(url).toBe('/api/v1/projects/project-1/creative-intent')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('If-Match')).toBe('"0"')
  })

  it('adds JSON content type while preserving caller headers', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/test', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'request-1' },
      body: JSON.stringify({ value: 1 }),
    })

    const init = fetchMock.mock.calls[0]![1] as RequestInit
    const headers = new Headers(init.headers)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Idempotency-Key')).toBe('request-1')
  })

  it('lets the browser set the multipart boundary for FormData', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
    vi.stubGlobal('fetch', fetchMock)
    const body = new FormData()
    body.append('file', new File(['sample'], 'sample.txt'))

    await apiRequest('/api/upload', { method: 'POST', body })

    const init = fetchMock.mock.calls[0]![1] as RequestInit
    expect(new Headers(init.headers).has('Content-Type')).toBe(false)
    expect(init.body).toBe(body)
  })

  it('does not label URL encoded bodies as JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
    vi.stubGlobal('fetch', fetchMock)
    const body = new URLSearchParams({ query: 'chapter one' })

    await apiRequest('/api/search', { method: 'POST', body })

    const init = fetchMock.mock.calls[0]![1] as RequestInit
    expect(init.headers).toBeUndefined()
  })

  it.each([403, 409])('normalizes problem details errors with HTTP %i', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status,
      json: async () => ({ detail: '禁止访问', code: 'FORBIDDEN' }),
    }))

    const error = await apiRequest('/api/test').catch((reason) => reason)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ message: '禁止访问', status, code: 'FORBIDDEN' })
  })
})

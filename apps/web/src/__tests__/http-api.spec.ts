import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiRequest } from '@/api/http'

describe('apiRequest', () => {
  afterEach(() => vi.unstubAllGlobals())

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

  it('normalizes problem details errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ detail: '禁止访问', code: 'FORBIDDEN' }),
    }))

    const error = await apiRequest('/api/test').catch((reason) => reason)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ message: '禁止访问', status: 403, code: 'FORBIDDEN' })
  })
})

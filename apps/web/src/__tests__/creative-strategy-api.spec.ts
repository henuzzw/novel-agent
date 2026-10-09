import { afterEach, describe, expect, it, vi } from 'vitest'
import { getCreativeStrategy, updateCreativeStrategy } from '@/api/projects'

afterEach(() => vi.unstubAllGlobals())

describe('creative strategy API', () => {
  it('reads strategy, policy version and project row version', async () => {
    const settings = { strategy: 'STANDARD', policyVersion: 1, version: 7 }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(settings)))
    vi.stubGlobal('fetch', fetchMock)
    await expect(getCreativeStrategy('project-a')).resolves.toEqual(settings)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project-a/settings/creative-strategy', expect.objectContaining({ cache: 'no-store' }))
  })

  it('sends only strategy and expected row version to the project settings endpoint', async () => {
    const settings = { strategy: 'FANQIE_GRIPPING', policyVersion: 1, version: 8 }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(settings)))
    vi.stubGlobal('fetch', fetchMock)
    await expect(updateCreativeStrategy('project-a', { strategy: 'FANQIE_GRIPPING', version: 7 })).resolves.toEqual(settings)
    const [url, init] = fetchMock.mock.calls[0]! as [string, RequestInit]
    expect(url).toBe('/api/v1/projects/project-a/settings/creative-strategy')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(String(init.body))).toEqual({ strategy: 'FANQIE_GRIPPING', version: 7 })
  })

})

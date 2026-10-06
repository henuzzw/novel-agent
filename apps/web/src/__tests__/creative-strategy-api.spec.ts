import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createProject, getCreativeStrategy, updateCreativeStrategy } from '@/api/projects'

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

  it.each([403, 409])('preserves HTTP %i for permission and conflict handling', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: '无法更新', code: 'CONFLICT' }), { status })))
    const failure = await updateCreativeStrategy('project-a', { strategy: 'STANDARD', version: 0 }).catch(error => error)
    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).toMatchObject({ status, message: '无法更新', code: 'CONFLICT' })
  })

  it.each(['STANDARD', 'FANQIE_GRIPPING'] as const)('creates a project with %s', async (creativeStrategy) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'created', creativeStrategy })))
    vi.stubGlobal('fetch', fetchMock)
    await expect(createProject({ name: '项目', entryMode: 'IDEA', creativeStrategy })).resolves.toMatchObject({ creativeStrategy })
    const [, init] = fetchMock.mock.calls[0]! as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({ name: '项目', entryMode: 'IDEA', creativeStrategy })
    expect(new Headers(init.headers).get('Idempotency-Key')).toBeTruthy()
  })

  it('keeps strategy optional for existing project creation callers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'legacy', creativeStrategy: 'STANDARD' })))
    vi.stubGlobal('fetch', fetchMock)
    await expect(createProject({ name: '旧调用', entryMode: 'MATERIALS' })).resolves.toMatchObject({ creativeStrategy: 'STANDARD' })
    expect(JSON.parse(String(fetchMock.mock.calls[0]![1].body))).not.toHaveProperty('creativeStrategy')
  })
})

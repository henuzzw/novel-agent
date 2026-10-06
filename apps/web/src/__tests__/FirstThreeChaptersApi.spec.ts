import { afterEach, describe, expect, it, vi } from 'vitest'
import { checkFirstThreeChapters, getFirstThreeChapters } from '@/api/firstThreeChapters'
describe('first three chapters API', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('reads selections and budget without implicitly requesting a model check', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    await getFirstThreeChapters('project-1', 'DEEPSEEK', ['m1', 'm2', 'm3'], '查因果')
    const request = new URL(fetch.mock.calls[0]![0] as string, 'http://localhost')
    expect(request.pathname).toBe('/api/v1/projects/project-1/opening-review')
    expect(request.searchParams.get('manuscriptIds')).toBe('m1,m2,m3')
    expect(request.searchParams.get('instruction')).toBe('查因果')
    expect(request.searchParams.get('provider')).toBe('DEEPSEEK')
    expect(fetch.mock.calls).toHaveLength(1)
  })
  it('posts explicit selected IDs, fingerprint and author budget, never client-written report facts', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    const input = { manuscriptIds: ['m1', 'm2', 'm3'], provider: 'DEEPSEEK' as const, instruction: '', expectedFingerprint: 'hash', maxInputTokens: 15000 }
    await checkFirstThreeChapters('p', input)
    expect(fetch.mock.calls[0]![0]).toBe('/api/v1/projects/p/opening-review/actions/check')
    const options = fetch.mock.calls[0]![1] as RequestInit
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body as string)).toEqual(input)
  })
})

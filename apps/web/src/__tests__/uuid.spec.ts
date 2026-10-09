import { afterEach, describe, expect, it, vi } from 'vitest'
import { createUuid } from '@/lib/uuid'
import { createProject } from '@/api/projects'
import { beginGenerationRequest, generationRequests } from '@/lib/generation-activity'

const browserCrypto = globalThis.crypto
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function withoutNativeUuid() {
  const getRandomValues = vi.fn((bytes: Uint8Array<ArrayBuffer>) => browserCrypto.getRandomValues(bytes))
  vi.stubGlobal('crypto', { getRandomValues })
  return getRandomValues
}

describe('createUuid', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    generationRequests.value = []
  })

  it('prefers the native UUID method and preserves its receiver', () => {
    const randomUUID = vi.fn(() => '9d080ede-e8db-4f06-86d5-3fa131aac8a2')
    const getRandomValues = vi.fn()
    const cryptoApi = { randomUUID, getRandomValues }
    vi.stubGlobal('crypto', cryptoApi)
    expect(createUuid()).toBe('9d080ede-e8db-4f06-86d5-3fa131aac8a2')
    expect(randomUUID.mock.contexts[0]).toBe(cryptoApi)
    expect(getRandomValues).not.toHaveBeenCalled()
  })

  it('sets UUID v4 version and variant bits when randomUUID is unavailable', () => {
    for (const fill of [0, 255]) {
      const getRandomValues = vi.fn((bytes: Uint8Array) => bytes.fill(fill))
      vi.stubGlobal('crypto', { getRandomValues })
      expect(createUuid()).toBe(fill === 0 ? '00000000-0000-4000-8000-000000000000'
        : 'ffffffff-ffff-4fff-bfff-ffffffffffff')
      expect(getRandomValues).toHaveBeenCalledWith(expect.any(Uint8Array))
    }
  })

  it('generates independent identifiers using secure random bytes instead of Math.random', () => {
    const getRandomValues = withoutNativeUuid()
    const weakRandom = vi.spyOn(Math, 'random')
    const ids = Array.from({ length: 128 }, createUuid)
    expect(ids.every(id => uuidV4.test(id))).toBe(true)
    expect(new Set(ids).size).toBe(128)
    expect(getRandomValues).toHaveBeenCalledTimes(128)
    expect(weakRandom).not.toHaveBeenCalled()
  })

  it('gives an actionable error if secure randomness is unavailable altogether', () => {
    for (const cryptoApi of [undefined, {}]) {
      vi.stubGlobal('crypto', cryptoApi)
      expect(() => createUuid()).toThrow('当前浏览器不支持安全随机数')
    }
  })

  it('creates projects with a valid idempotency key without the native UUID method', async () => {
    withoutNativeUuid()
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ id: 'project' }) })
    vi.stubGlobal('fetch', fetchMock)
    await createProject({ name: '校园小说', entryMode: 'MATERIALS' })
    const [url, request] = fetchMock.mock.calls[0]! as [string, RequestInit]
    expect(url).toBe('/api/v1/projects')
    expect(new Headers(request.headers).get('Idempotency-Key')).toMatch(uuidV4)
  })

  it('tracks generation requests without the native UUID method', () => {
    withoutNativeUuid()
    const id = beginGenerationRequest('/api/v1/projects/project/story-bibles/actions/generate', 'POST')
    expect(id).toMatch(uuidV4)
    expect(generationRequests.value[0]).toMatchObject({ id, stage: 'STORY_BIBLE', status: 'RUNNING' })
  })
})

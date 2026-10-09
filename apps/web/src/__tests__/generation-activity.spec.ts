import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '@/api/http'
import { beginGenerationRequest, endGenerationRequest, generationRequests, generationRows } from '@/lib/generation-activity'
import type { AgentRun } from '@/api/agentRuns'

afterEach(() => { generationRequests.value = []; vi.unstubAllGlobals(); vi.useRealTimers() })
describe('generation request state', () => {
  it.each([
    ['story-bibles/actions/generate', 'STORY_BIBLE'], ['outlines/actions/generate', 'OUTLINE'],
    ['chapters/2/manuscripts/actions/generate', 'MANUSCRIPT'],
    ['chapters/2/reviews/actions/generate', 'CHAPTER_REVIEW'],
    ['imports/i/actions/prepare-directions', 'IMPORT_PLANNING'],
  ])('immediately tracks %s without storing request text', (path, stage) => {
    beginGenerationRequest(`/api/v1/projects/p/${path}`, 'POST')
    expect(generationRequests.value[0]).toMatchObject({ stage, status: 'RUNNING', projectId: 'p' })
    expect(Object.keys(generationRequests.value[0]!)).not.toContain('body')
  })
  it('ignores reads, saves and author confirmations', () => {
    expect(beginGenerationRequest('/api/v1/projects/p/story-bibles/actions/generate')).toBeNull()
    expect(beginGenerationRequest('/api/v1/projects/p/story-bibles/b/actions/publish', 'POST')).toBeNull()
    expect(beginGenerationRequest('/api/v1/projects/p/imports/i/analyses/a/actions/confirm', 'POST')).toBeNull()
  })
  it('waits for response decoding and records network and JSON failures', async () => {
    let resolve!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => new Promise(r => { resolve = r }) }))
    const promise = apiRequest('/api/v1/projects/p/story-bibles/actions/generate', { method: 'POST' })
    await vi.waitFor(() => expect(resolve).toBeTypeOf('function'))
    expect(generationRequests.value[0]?.status).toBe('RUNNING')
    resolve({ id: 'b' }); await promise
    expect(generationRequests.value[0]?.status).toBe('SUCCEEDED')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('网络断开')))
    await expect(apiRequest('/api/v1/projects/p/outlines/actions/generate', { method: 'POST' })).rejects.toThrow('网络断开')
    expect(generationRequests.value[0]).toMatchObject({ status: 'FAILED', errorMessage: '网络断开' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => { throw new Error('JSON 不完整') } }))
    await expect(apiRequest('/api/v1/projects/p/outlines/actions/generate', { method: 'POST' })).rejects.toThrow('JSON 不完整')
    expect(generationRequests.value[0]?.status).toBe('FAILED')
  })
  it('does not mistake HTTP 200 failed reports for successful analysis', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: async () => ({ report: { status: 'FAILED', errorMessage: '证据不匹配' } }) }))
    const response = await apiRequest('/api/v1/projects/p/imports/i/analyses/a/actions/run-next', { method: 'POST' })
    expect(response).toMatchObject({ report: { status: 'FAILED' } })
    expect(generationRequests.value[0]).toMatchObject({ status: 'FAILED', errorMessage: '证据不匹配' })
  })
  it('does not overwrite pending saves or HTTP failures with model success', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-06T00:00:00Z'))
    const id = beginGenerationRequest('/api/v1/projects/p/story-bibles/actions/generate', 'POST')
    const run = { id: 'r', stage: 'STORY_BIBLE', status: 'SUCCEEDED', startedAt: '2026-10-06T00:00:01Z' } as AgentRun
    vi.setSystemTime(new Date('2026-10-06T00:00:02Z'))
    expect(generationRows('p', [run]).find(row => row.stage === 'STORY_BIBLE')?.activity?.status).toBe('RUNNING')
    endGenerationRequest(id, new Error('保存失败'))
    expect(generationRows('p', [run]).find(row => row.stage === 'STORY_BIBLE')?.activity?.status).toBe('FAILED')
    expect(generationRows('other', []).every(row => !row.activity)).toBe(true)
  })
  it('keeps latest request after an older concurrent request completes', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-06T00:00:00Z'))
    const old = beginGenerationRequest('/api/v1/projects/p/chapters/1/manuscripts/actions/generate', 'POST')
    vi.advanceTimersByTime(1000)
    beginGenerationRequest('/api/v1/projects/p/chapters/2/manuscripts/actions/generate', 'POST')
    endGenerationRequest(old, new Error('旧请求失败'))
    expect(generationRows('p', []).find(row => row.stage === 'MANUSCRIPT')?.activity).toMatchObject({ status: 'RUNNING', chapter: 2 })
  })
  it('passes the exact request identifier to the backend and recognizes cancellation responses', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false, status: 409,
      json: async () => ({ detail: '生成已停止', code: 'GENERATION_CANCELLED' }) })
    vi.stubGlobal('fetch', fetch)
    await expect(apiRequest('/api/v1/projects/p/story-bibles/actions/generate', { method: 'POST' })).rejects.toThrow('生成已停止')
    const headers = fetch.mock.calls[0]?.[1].headers as Headers
    expect(headers.get('X-Generation-Request-Id')).toBe(generationRequests.value[0]?.id)
    expect(generationRequests.value[0]?.status).toBe('CANCELLED')
  })
  it('recognizes stopped analysis reports and does not replace stopped requests with late success', () => {
    const id = beginGenerationRequest('/api/v1/projects/p/story-bibles/actions/generate', 'POST')
    endGenerationRequest(id, Object.assign(new Error('生成已停止'), { code: 'GENERATION_CANCELLED' }))
    const run = { id: 'r', stage: 'STORY_BIBLE', status: 'SUCCEEDED', startedAt: generationRequests.value[0]!.startedAt } as AgentRun
    expect(generationRows('p', [run]).find(row => row.stage === 'STORY_BIBLE')?.activity?.status).toBe('CANCELLED')
  })
})

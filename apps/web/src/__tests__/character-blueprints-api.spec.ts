import { afterEach, describe, expect, it, vi } from 'vitest'
import { completeStoryBibleCharacters } from '@/api/planning'
import { bible } from './character-blueprint-fixtures'

describe('character completion API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends one explicit completion request with the source version and selected provider', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => bible() })
    vi.stubGlobal('fetch', request)
    await completeStoryBibleCharacters('project-1', bible(), 'DEEPSEEK', ' 保留背景 ')
    expect(request).toHaveBeenCalledOnce()
    const [url, options] = request.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/story-bibles/bible-1/actions/complete-characters')
    expect(new Headers(options.headers).get('If-Match')).toBe('"3"')
    expect(JSON.parse(options.body as string)).toEqual({ provider: 'DEEPSEEK', instruction: '保留背景' })
  })

  it('does not automatically retry an ambiguous model failure', async () => {
    const request = vi.fn().mockResolvedValue({ ok: false, status: 502,
      json: async () => ({ title: '模型调用失败', detail: '请检查运行记录' }) })
    vi.stubGlobal('fetch', request)
    await expect(completeStoryBibleCharacters('project-1', bible(), 'LOCAL_CODEX')).rejects.toThrow()
    expect(request).toHaveBeenCalledOnce()
  })
})

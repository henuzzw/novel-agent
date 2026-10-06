import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReviewIssue } from '@/api/writing'
import { analyzeWritingStyle, applyWritingStyle, generateQualityReview, reviseFromQuality, uploadWritingStyle,
  validateQualitySelection, type QualityRevisionScope } from '@/api/writingQuality'

describe('writing style and quality API', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('keeps style analysis separate from application and sends version guards', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) })
    vi.stubGlobal('fetch', fetchMock)
    await analyzeWritingStyle('p', '样本', 'LOCAL_CODEX')
    await applyWritingStyle('p', null, 3)
    expect(fetchMock.mock.calls[0]![0]).toContain('/actions/analyze')
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({ profile: null, expectedVersion: 3 })
  })
  it('uploads multipart samples without overriding the boundary header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) })
    vi.stubGlobal('fetch', fetchMock)
    await uploadWritingStyle('p', new File(['样本文字'], 'style.txt'), 'LOCAL_TEMPLATE')
    const init = fetchMock.mock.calls[0]![1] as RequestInit
    expect((init.headers as Headers).has('Content-Type')).toBe(false)
    expect((init.headers as Headers).get('X-Generation-Request-Id')).toBeTruthy()
    expect(init.body).toBeInstanceOf(FormData)
    expect((init.body as FormData).get('provider')).toBe('LOCAL_TEMPLATE')
  })
  it('binds revision to report and selected issues and surfaces stale errors', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 'r' }) })
      .mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ detail: '请重新检查' }) })
    vi.stubGlobal('fetch', fetchMock)
    await generateQualityReview('p', 2, 'LOCAL_TEMPLATE', '')
    await expect(reviseFromQuality('p', 2, 'r', 'LOCAL_CODEX', ['Q1'], '')).rejects.toThrow('请重新检查')
    expect(fetchMock.mock.calls[1]![0]).toContain('/chapters/2/quality-reviews/r/actions/revise')
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body).issueIds).toEqual(['Q1'])
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body).scope).toBe('EXPRESSION_ONLY')
  })
  it('sends explicit scene authorization and rejects malformed inputs before requesting', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ({ status: 'DRAFT' }) })
    vi.stubGlobal('fetch', fetchMock)
    await reviseFromQuality('p', 1, 'q', 'DEEPSEEK', ['Q1'], '调整段落', 'SCENE_STRUCTURE')
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({ provider: 'DEEPSEEK', issueIds: ['Q1'], instruction: '调整段落', scope: 'SCENE_STRUCTURE' })
    await expect(reviseFromQuality('p', 1, 'q', 'DEEPSEEK', ['Q1'], '', 'ALL_FACTS' as QualityRevisionScope)).rejects.toThrow('修订范围')
    for (const ids of [[], ['Q1', 'Q1'], [' '], Array.from({ length: 21 }, (_, i) => `Q${i}`)]) {
      await expect(reviseFromQuality('p', 1, 'q', 'DEEPSEEK', ids, '')).rejects.toThrow('质量问题')
    }
    await expect(reviseFromQuality('p', 1, 'q', 'DEEPSEEK', ['Q1'], 'a'.repeat(2001))).rejects.toThrow('2000')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('requires structural authorization for logic and scene selections without inferring authority from text', () => {
    const issues: ReviewIssue[] = [{ id: 'Q1', category: 'SCENE', severity: 'WARNING', description: '顺序', evidence: '原文', suggestion: '重排', resolved: false }]
    expect(() => validateQualitySelection(['Q1'], 'EXPRESSION_ONLY', issues)).toThrow('显式选择')
    expect(() => validateQualitySelection(['Q1'], 'SCENE_STRUCTURE', issues)).not.toThrow()
    expect(() => validateQualitySelection(['unknown'], 'SCENE_STRUCTURE', issues)).toThrow('尚未处理')
    expect(() => validateQualitySelection(['Q1'], 'SCENE_STRUCTURE', [{ ...issues[0]!, resolved: true }])).toThrow('尚未处理')
  })
})

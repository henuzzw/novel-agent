import { afterEach, describe, expect, it, vi } from 'vitest'
import { automationChapterTarget, cancelAutomationRun, createAutomationRun, listAutomationRuns, resumeAutomationRun, type AutomationRun } from '@/api/automation'

describe('automation API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('creates an idempotent chapter range and supports resume and cancel', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 202, json: async () => ({ id: 'run-1' }) })
    vi.stubGlobal('fetch', fetchMock)
    await listAutomationRuns('project-1')
    await createAutomationRun('project-1', { firstChapter: 1, lastChapter: 3, provider: 'DEEPSEEK', qualityReviewEnabled: true, maxAutoRevisionRounds: 2, maxGenerationSteps: 30 }, 'request-1')
    await resumeAutomationRun('project-1', 'run-1')
    await cancelAutomationRun('project-1', 'run-1')
    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][]
    expect(new Headers(calls[1]![1].headers).get('Idempotency-Key')).toBe('request-1')
    expect(JSON.parse(calls[1]![1].body as string)).toMatchObject({ firstChapter: 1, lastChapter: 3, qualityReviewEnabled: true, maxAutoRevisionRounds: 2, maxGenerationSteps: 30 })
    expect(calls[2]![0]).toContain('/run-1/actions/resume')
    expect(calls[3]![0]).toContain('/run-1/actions/cancel')
  })

  it('opens the current chapter and matching editor rather than a previous chapter', () => {
    const run = { currentChapter: 3, provider: 'DEEPSEEK', steps: [
      { chapterNumber: 2, stage: 'REVIEW' }, { chapterNumber: 3, stage: 'QUALITY_REVIEW' },
    ] } as AutomationRun
    expect(automationChapterTarget(run)).toEqual({ chapter: 3, mode: 'manuscript', provider: 'DEEPSEEK' })
    run.steps[1]!.stage = 'QUALITY_REVISION'
    expect(automationChapterTarget(run).mode).toBe('manuscript')
    run.steps[1]!.stage = 'CONTRACT_REVIEW'
    expect(automationChapterTarget(run).mode).toBe('manuscript')
    run.steps = run.steps.slice(0, 1)
    expect(automationChapterTarget(run).mode).toBe('manuscript')
  })

  it('surfaces server validation errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, json: async () => ({ detail: '请先发布大纲' }) }))
    await expect(createAutomationRun('project-1', { firstChapter: 1, lastChapter: 3, provider: 'LOCAL_TEMPLATE' }, 'request-1'))
      .rejects.toThrow('请先发布大纲')
  })
})

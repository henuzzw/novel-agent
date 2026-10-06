import { afterEach, expect, it, vi } from 'vitest'
import { generateStylePreview, recommendWritingStyle, checkStylePreview, reviseStylePreview } from '@/api/writingQuality'

afterEach(() => vi.unstubAllGlobals())
it('posts a version-bound sample for checking and only server issue IDs for revision', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) })
  vi.stubGlobal('fetch', fetchMock)
  const profile = { name: '试写', narrativeVoice: '自然', sentenceRhythm: '变化', descriptionFocus: '动作',
    dialogueStyle: '口语', emotionalExpression: '反应', pacing: '紧凑', avoidPatterns: [] }
  const input = { source: { outlineVersionId: 'outline-1', expectedOutlineVersion: 2, profile, provider: 'DEEPSEEK' as const,
    targetWords: 800, instruction: '' }, content: { title: '标题', body: '样例正文' } }
  await checkStylePreview('project-1', input)
  await reviseStylePreview('project-1', 'report-1', 'DEEPSEEK', ['E1'], '保留关系')
  expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/projects/project-1/writing-style/actions/check-preview')
  expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual(input)
  expect(fetchMock.mock.calls[1]![0]).toBe('/api/v1/projects/project-1/writing-style/preview-reviews/report-1/actions/revise')
  expect(JSON.parse(fetchMock.mock.calls[1]![1].body)).toEqual({ provider: 'DEEPSEEK', issueIds: ['E1'], instruction: '保留关系' })
})
it('posts explicit candidate style and outline row version to isolated preview endpoint', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ previewMode: 'MODEL' }) })
  vi.stubGlobal('fetch', fetchMock)
  const profile = { name: '试写', narrativeVoice: '自然', sentenceRhythm: '变化', descriptionFocus: '动作',
    dialogueStyle: '口语', emotionalExpression: '反应', pacing: '紧凑', avoidPatterns: [] }
  const input = { outlineVersionId: 'outline-1', expectedOutlineVersion: 2, profile,
    provider: 'DEEPSEEK' as const, targetWords: 800, instruction: '只写开场' }
  await generateStylePreview('project-1', input)
  const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
  expect(url).toBe('/api/v1/projects/project-1/writing-style/actions/preview')
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body as string)).toEqual(input)
})

it('posts saved bible version and preferences to the style recommendation endpoint', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ recommendationMode: 'MODEL' }) })
  vi.stubGlobal('fetch', fetchMock)
  const input = { bibleVersionId: 'bible-1', expectedBibleVersion: 3, provider: 'DEEPSEEK' as const, instruction: '少议论' }
  await recommendWritingStyle('project-1', input)
  const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
  expect(url).toBe('/api/v1/projects/project-1/writing-style/actions/recommend')
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body as string)).toEqual(input)
})

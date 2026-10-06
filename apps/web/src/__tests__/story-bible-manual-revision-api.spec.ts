import { afterEach, describe, expect, it, vi } from 'vitest'

import { createStoryBibleRevision, generateStoryBible, getStoryBibleVersion, listStoryBibleVersions,
  type StoryBibleContent, type StoryBibleVersion } from '@/api/planning'

describe('story bible manual revision API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('reads version history and sends the selected base only in revise mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve({ id: 'bible-1' }) })
    vi.stubGlobal('fetch', fetchMock)

    await listStoryBibleVersions('project-1')
    await getStoryBibleVersion('project-1', 'bible-1')
    await generateStoryBible('project-1', '微调人物', 'LOCAL_CODEX', 'REVISE', 'bible-1')
    await generateStoryBible('project-1', '重新规划', 'LOCAL_CODEX', 'REGENERATE', 'bible-1')

    expect(fetchMock.mock.calls[0]?.[0]).toContain('/story-bibles')
    expect(fetchMock.mock.calls[1]?.[0]).toContain('/story-bibles/bible-1')
    expect(JSON.parse((fetchMock.mock.calls[2]?.[1] as RequestInit).body as string).baseBibleVersionId).toBe('bible-1')
    expect(JSON.parse((fetchMock.mock.calls[3]?.[1] as RequestInit).body as string).baseBibleVersionId).toBeNull()
  })

  it('posts edited content with the published version precondition', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'revision' }) })
    vi.stubGlobal('fetch', fetchMock)
    const published = { id: 'published', version: 3 } as StoryBibleVersion
    const content = { logline: '作者修改' } as StoryBibleContent

    await createStoryBibleRevision('project-1', published, content)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/story-bibles/published/actions/create-revision')
    expect(request.method).toBe('POST')
    expect(new Headers(request.headers).get('If-Match')).toBe('"3"')
    expect(JSON.parse(request.body as string).content.logline).toBe('作者修改')
  })
})

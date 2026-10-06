import { afterEach, expect, it, vi } from 'vitest'
import { editManuscriptSelection, type ManuscriptLocalEditInput } from '@/api/manuscriptLocalEdit'
import { ApiError } from '@/api/http'

afterEach(() => vi.unstubAllGlobals())
const input: ManuscriptLocalEditInput = { sourceManuscriptId: 'source', sourceRowVersion: 3, selection: 'old',
  occurrence: 2, offset: 15, provider: 'LOCAL_CODEX', instruction: 'clarify', authorized: true }
it('sends explicit source, exact text, occurrence, UTF-16 offset and authorization', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ assessment: 'DRAFT_CREATED' }), { status: 201 }))
  vi.stubGlobal('fetch', fetchMock)
  await editManuscriptSelection('project', 2, input)
  const [path, init] = fetchMock.mock.calls[0]! as [string, RequestInit]
  expect(path).toBe('/api/v1/projects/project/chapters/2/manuscripts/actions/local-edit')
  expect(init.method).toBe('POST')
  expect(JSON.parse(String(init.body))).toEqual(input)
})
it.each([403, 409, 502])('preserves %i failures for panel handling', async status => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'failed' }), { status })))
  await expect(editManuscriptSelection('project', 2, input)).rejects.toMatchObject({ status, message: 'failed' })
  await expect(editManuscriptSelection('project', 2, input)).rejects.toBeInstanceOf(ApiError)
})

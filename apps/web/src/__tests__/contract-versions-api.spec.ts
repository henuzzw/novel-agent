import { afterEach, describe, expect, it, vi } from 'vitest'

import { generateContract, getContractVersion, listContractVersions } from '@/api/writing'

describe('contract version API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the selected revision base', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'new' }) })
    vi.stubGlobal('fetch', fetchMock)

    await generateContract('project-1', 1, 'LOCAL_CODEX', '微调', 'REVISE', 'older')

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string)).toMatchObject({
      mode: 'REVISE', instruction: '微调', baseContractVersionId: 'older',
    })
  })

  it('does not send a base when regenerating', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201,
      json: () => Promise.resolve({ id: 'new' }) })
    vi.stubGlobal('fetch', fetchMock)

    await generateContract('project-1', 1, 'LOCAL_CODEX', '', 'REGENERATE', 'older')

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string).baseContractVersionId).toBeNull()
  })

  it('reads versions only under the selected chapter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: () => Promise.resolve([]) })
    vi.stubGlobal('fetch', fetchMock)

    await listContractVersions('project-1', 3)
    await getContractVersion('project-1', 3, 'older')

    const requests = fetchMock.mock.calls as unknown as [string, RequestInit][]
    expect(requests[0]?.[0]).toContain('/chapters/3/contracts')
    expect(requests[1]?.[0]).toContain('/chapters/3/contracts/older')
  })
})

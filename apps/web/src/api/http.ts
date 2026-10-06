import { beginGenerationRequest, completeGenerationResponse, endGenerationRequest } from '@/lib/generation-activity'

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message)
  }
}

function requestHeaders(init?: RequestInit) {
  const body = init?.body
  const isJsonBody = typeof body === 'string'
  if (init?.headers == null && !isJsonBody) return undefined

  const headers = new Headers(init?.headers)
  if (isJsonBody && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  return headers
}

async function errorFrom(response: Response) {
  const problem = await response.json().catch(() => null)
  const message = problem?.detail ?? problem?.message ?? `请求失败（HTTP ${response.status}）`
  return new ApiError(message, response.status, problem?.code)
}

export async function apiRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const activity = beginGenerationRequest(url, init?.method)
  try {
    let headers = requestHeaders(init)
    if (activity) {
      headers = new Headers(headers)
      headers.set('X-Generation-Request-Id', activity)
    }
    const response = await fetch(url, {
      cache: 'no-store',
      ...init,
      headers,
    })

    if (!response.ok) throw await errorFrom(response)
    const body = response.status === 204 ? null : await response.json()
    completeGenerationResponse(activity, body)
    return body as T
  } catch (error) {
    endGenerationRequest(activity, error)
    throw error
  }
}

export async function apiText(url: string, init?: RequestInit): Promise<string> {
  const response = await fetch(url, {
    cache: 'no-store',
    ...init,
    headers: requestHeaders(init),
  })
  if (!response.ok) throw await errorFrom(response)
  return response.text()
}

export type EntryMode = 'IDEA' | 'MANUSCRIPT' | 'MATERIALS'

export interface CreativeIntentInput {
  premise?: string
  genres: string[]
  targetAudience?: string
  protagonistBrief?: string
  centralConflict?: string
  tones: string[]
  targetWords?: number
  endingPreference?: string
  mustHave: string[]
  avoid: string[]
  stylePreferences: string[]
}

export interface ProjectSummary {
  id: string
  name: string
  entryMode: EntryMode
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETING'
  currentCanonVersion: number
  version: number
  createdAt: string
  updatedAt: string
  creativeIntent: (CreativeIntentInput & { version: number }) | null
}

export interface CreateProjectInput {
  name: string
  entryMode: EntryMode
  creativeIntent?: CreativeIntentInput
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message)
  }
}

export async function apiRequest<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    cache: 'no-store',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    const message = problem?.detail ?? problem?.message ?? `请求失败（HTTP ${response.status}）`
    throw new ApiError(message, response.status, problem?.code)
  }

  if (response.status === 204) {
    return null as T
  }

  return response.json() as Promise<T>
}

export function listProjects(): Promise<ProjectSummary[]> {
  return apiRequest('/api/v1/projects')
}

export function getProject(projectId: string): Promise<ProjectSummary> {
  return apiRequest(`/api/v1/projects/${projectId}`)
}

export function updateCreativeIntent(
  project: ProjectSummary,
  creativeIntent: CreativeIntentInput,
): Promise<ProjectSummary> {
  return apiRequest(`/api/v1/projects/${project.id}/creative-intent`, {
    method: 'PUT',
    headers: {
      'If-Match': `"${project.creativeIntent?.version ?? 0}"`,
    },
    body: JSON.stringify(creativeIntent),
  })
}

export function createProject(input: CreateProjectInput): Promise<ProjectSummary> {
  return apiRequest('/api/v1/projects', {
    method: 'POST',
    headers: {
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(input),
  })
}

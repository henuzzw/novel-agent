import { ApiError } from '@/api/projects'

export interface AgentRun {
  id: string
  stage: string
  provider: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  promptPreview: string
  inputTokens: number
  outputTokens: number
  tokenSource: 'ESTIMATED' | 'ACTUAL'
  estimatedCost: number
  currency: string
  durationMs: number | null
  errorMessage: string | null
  startedAt: string
  completedAt: string | null
}

export interface AgentRunPrompt {
  id: string
  systemPrompt: string | null
  userPrompt: string | null
  promptPreview: string | null
}

export interface AgentRunSummary {
  calls: number
  failures: number
  inputTokens: number
  outputTokens: number
  estimatedCost: number
  currency: string
  tokenSource: string
}

async function read<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new ApiError(problem?.detail ?? `请求失败（HTTP ${response.status}）`, response.status, problem?.code)
  }
  return response.json() as Promise<T>
}

export function listAgentRuns(projectId: string) {
  return fetch(`/api/v1/projects/${projectId}/agent-runs`, { cache: 'no-store' }).then(read<AgentRun[]>)
}

export function getAgentRunPrompt(projectId: string, runId: string) {
  return fetch(`/api/v1/projects/${projectId}/agent-runs/${runId}/prompt`, { cache: 'no-store' })
    .then(read<AgentRunPrompt>)
}

export function getAgentRunSummary(projectId: string) {
  return fetch(`/api/v1/projects/${projectId}/agent-runs/summary`, { cache: 'no-store' }).then(read<AgentRunSummary>)
}

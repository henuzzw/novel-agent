import { apiRequest } from '@/api/http'

export interface AgentRun {
  id: string
  stage: string
  provider: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  promptPreview: string
  inputTokens: number
  outputTokens: number
  tokenSource: 'ESTIMATED' | 'ACTUAL' | 'UNKNOWN'
  actualInputTokens?: number | null
  actualOutputTokens?: number | null
  estimatedInputTokens?: number | null
  estimatedOutputTokens?: number | null
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

export function listAgentRuns(projectId: string) {
  return apiRequest<AgentRun[]>(`/api/v1/projects/${projectId}/agent-runs`)
}

export function stopGeneration(projectId: string, id: string, source: 'request' | 'model') {
  const resource = source === 'request' ? 'generation-requests' : 'agent-runs'
  return apiRequest<{ status: 'STOP_REQUESTED' }>(`/api/v1/projects/${projectId}/${resource}/${id}/actions/stop`, { method: 'POST' })
}

export function getAgentRunPrompt(projectId: string, runId: string) {
  return apiRequest<AgentRunPrompt>(`/api/v1/projects/${projectId}/agent-runs/${runId}/prompt`)
}

export function getAgentRunSummary(projectId: string) {
  return apiRequest<AgentRunSummary>(`/api/v1/projects/${projectId}/agent-runs/summary`)
}

export interface AgentRunRequestSnapshot {
  id: string
  tokenSource: AgentRun['tokenSource']
  actualInputTokens: number | null
  actualOutputTokens: number | null
  estimatedInputTokens: number | null
  estimatedOutputTokens: number | null
  requestSnapshot: {
    effectiveSettings: { provider: string; model: string; effort: string | null; version: number | null }
    systemPromptHash: string; userPromptHash: string; schemaHash: string; schemaName: string
    requestedMaxOutputTokens: number; maxOutputTokens: number | null; sessionPolicy: string
    contextBudget?: { contextWindowTokens: number; safetyMarginTokens: number; estimatedInputTokens: number; reservedOutputTokens: number } | null
  } | null
  usage: { inputTokens: number; outputTokens: number; totalTokens: number | null; cachedInputTokens: number | null; reasoningOutputTokens: number | null } | null
}

export function getAgentRunRequestSnapshot(projectId: string, runId: string) {
  return apiRequest<AgentRunRequestSnapshot>(`/api/v1/projects/${projectId}/agent-runs/${runId}/request-snapshot`)
}

export interface AgentRunOutput {
  id: string
  status: AgentRun['status']
  responseText: string | null
  truncated: boolean
  errorType: string | null
  errorCategory: string | null
  errorDetail: string | null
  durationMs: number | null
}

export function getAgentRunOutput(projectId: string, runId: string) {
  return apiRequest<AgentRunOutput>(`/api/v1/projects/${projectId}/agent-runs/${runId}/response`)
}

export function subscribeAgentRunOutput(projectId: string, runId: string,
  onOutput: (output: AgentRunOutput) => void, onDisconnect: () => void) {
  const source = new EventSource(`/api/v1/projects/${projectId}/agent-runs/${runId}/events`)
  source.addEventListener('output', event => {
    try {
      const output = JSON.parse((event as MessageEvent).data) as AgentRunOutput
      if (output.id !== runId || !['RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(output.status)) throw new Error('Invalid event')
      onOutput(output)
      if (output.status !== 'RUNNING') source.close()
    } catch {
      source.close()
      onDisconnect()
    }
  })
  source.onerror = () => { source.close(); onDisconnect() }
  return () => source.close()
}

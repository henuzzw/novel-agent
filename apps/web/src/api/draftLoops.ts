import { apiRequest } from './http'
import type { ModelProvider } from './planning'
import type { ManuscriptContent } from './writing'

export interface DraftIssue {
  id: string; category: 'STYLE' | 'FLUENCY' | 'LOGIC' | 'SCENE'
  description: string; evidence: string; existingBasis: string; gap: string
  candidateDesign: string; impact: string; suggestion: string
}
export interface DraftJudgment {
  action: 'REVISED' | 'NO_CHANGE' | 'NEEDS_CONTEXT'
  decisions: { issueId: string; verdict: 'ACCEPT' | 'REJECT' | 'DEFER'; reason: string }[]
  content: ManuscriptContent | null; changeSummary: string[]
}
export interface DraftRound {
  number: number; beforeManuscriptId: string; before: ManuscriptContent
  check: { summary: string; issues: DraftIssue[] }
  judgment: DraftJudgment | null; afterManuscriptId: string | null
}
export interface DraftLoop {
  id: string; projectId: string; chapterNumber: number; provider: ModelProvider
  writeFirst: boolean; maxRounds: number; status: 'PENDING' | 'RUNNING' | 'STOPPED' | 'FAILED' | 'CANCELLED'
  phase: 'A' | 'B' | 'C'; stopReason: string | null; manuscriptId: string | null
  rounds: DraftRound[]; errorMessage: string | null; createdAt: string; updatedAt: string
}
const path = (project: string, chapter: number) => `/api/v1/projects/${project}/chapters/${chapter}/draft-loops`
export const listDraftLoops = (project: string, chapter: number) => apiRequest<DraftLoop[]>(path(project, chapter))
export function startDraftLoop(project: string, chapter: number, provider: ModelProvider, writeFirst: boolean, maxRounds: number, key: string) {
  if (provider === 'LOCAL_TEMPLATE' || maxRounds < 1 || maxRounds > 10 || !Number.isInteger(maxRounds)) throw new Error('请选择真实模型，轮次上限1至10')
  return apiRequest<DraftLoop>(path(project, chapter), { method: 'POST', headers: { 'Idempotency-Key': key },
    body: JSON.stringify({ provider, writeFirst, maxRounds }) })
}
export const stopDraftLoop = (project: string, chapter: number, id: string) => apiRequest<DraftLoop>(`${path(project, chapter)}/${id}/actions/stop`, { method: 'POST' })
export const draftLoopActive = (run: DraftLoop | null | undefined) => !!run && ['PENDING', 'RUNNING'].includes(run.status)
export const draftStopLabels: Record<string, string> = {
  B_CLEAR: 'B 未发现明确问题', C_NO_CHANGE: 'C 判断无需修改（不代表 B 检查通过）',
  NEEDS_CONTEXT: '资料或修订权限不足，已保留原稿', NO_PROGRESS: '修订未改变正文，已停止',
  CYCLE_DETECTED: '检测到来回修改，已停止', ROUND_LIMIT: '达到轮次上限，最后修订稿未复检',
  SOURCE_CHANGED: '正文或创作依据已变化，已停止', CANCELLED: '已停止', MODEL_ERROR: '模型调用或输出处理失败',
  INTERRUPTED: '服务重启中断，未自动重试',
}

/** 展示首尾共同文本之间的实际变化，保留少量上下文；不是模型给出的差异描述。 */
export function draftChange(before: string, after: string) {
  let start = 0
  while (start < Math.min(before.length, after.length) && before[start] === after[start]) start++
  let end = 0
  while (end < Math.min(before.length, after.length) - start && before[before.length - 1 - end] === after[after.length - 1 - end]) end++
  const left = Math.max(0, start - 80)
  return { before: before.slice(left, Math.min(before.length, before.length - end + 80)),
    after: after.slice(left, Math.min(after.length, after.length - end + 80)) }
}

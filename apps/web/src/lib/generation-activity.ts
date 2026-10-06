import { shallowRef } from 'vue'
import type { AgentRun } from '@/api/agentRuns'

export const generationStages: Record<string, string> = {
  STORY_DIRECTION: '故事方向', STORY_BIBLE: '故事圣经', OUTLINE: '分层大纲',
  CHAPTER_CONTRACT: '章节合同', CHAPTER_CONTRACT_REVIEW: '合同审阅', MANUSCRIPT: '正文',
  CHAPTER_REVIEW: '章节审稿', QUALITY_REVIEW: '质量检查', IMPORT_PLANNING: '导入规划',
  IMPORT_SOURCE_ANALYSIS: '原文解析', STYLE_ANALYSIS: '风格分析', STYLE_PREVIEW: '风格试写',
  STYLE_RECOMMENDATION: '风格推荐', STYLE_PREVIEW_REVIEW: '试写审阅', STYLE_PREVIEW_REVISION: '试写修订',
  FIRST_THREE_CHAPTERS_REVIEW: '前三章审阅', MANUSCRIPT_LOCAL_EDIT: '局部改写',
  CHARACTER_BLUEPRINT_COMPLETION: '人物补全', PLANNING_CHECKPOINT: '分块大纲',
  CREATION_PREPARATION_WORLD: '人物与世界准备', CREATION_PREPARATION_PLOT: '剧情准备', CREATION_PREPARATION_REVIEW: '准备检查',
}
const aliases: Record<string, string> = { IMPORT_REVERSE_BIBLE: 'STORY_BIBLE', IMPORT_REVERSE_OUTLINE: 'OUTLINE' }
const routes: [RegExp, string][] = [
  [/^story-directions\/actions\/generate$/, 'STORY_DIRECTION'],
  [/^story-bibles\/actions\/generate$/, 'STORY_BIBLE'],
  [/^story-bibles\/[^/]+\/actions\/complete-characters$/, 'CHARACTER_BLUEPRINT_COMPLETION'],
  [/^outlines\/actions\/generate$/, 'OUTLINE'],
  [/^imports\/[^/]+\/actions\/reverse-plan$/, 'IMPORT_PLANNING'],
  [/^imports\/[^/]+\/analyses\/[^/]+\/actions\/run-next$/, 'IMPORT_SOURCE_ANALYSIS'],
  [/^chapters\/\d+\/contracts\/actions\/generate$/, 'CHAPTER_CONTRACT'],
  [/^chapters\/\d+\/contract-reviews\/actions\/generate$/, 'CHAPTER_CONTRACT_REVIEW'],
  [/^chapters\/\d+\/manuscripts\/actions\/generate$/, 'MANUSCRIPT'],
  [/^chapters\/\d+\/reviews\/actions\/generate$/, 'CHAPTER_REVIEW'],
  [/^chapters\/\d+\/reviews\/[^/]+\/actions\/return-to-writing$/, 'MANUSCRIPT'],
  [/^chapters\/\d+\/quality-reviews\/actions\/generate$/, 'QUALITY_REVIEW'],
  [/^chapters\/\d+\/quality-reviews\/[^/]+\/actions\/revise$/, 'MANUSCRIPT'],
  [/^opening-review\/actions\/check$/, 'FIRST_THREE_CHAPTERS_REVIEW'],
  [/^chapters\/\d+\/manuscripts\/actions\/local-edit$/, 'MANUSCRIPT_LOCAL_EDIT'],
  [/^writing-style\/actions\/(analyze|upload)$/, 'STYLE_ANALYSIS'],
  [/^writing-style\/actions\/preview$/, 'STYLE_PREVIEW'],
  [/^writing-style\/actions\/recommend$/, 'STYLE_RECOMMENDATION'],
  [/^writing-style\/actions\/check-preview$/, 'STYLE_PREVIEW_REVIEW'],
  [/^writing-style\/preview-reviews\/[^/]+\/actions\/revise$/, 'STYLE_PREVIEW_REVISION'],
]
export interface GenerationActivity {
  id: string; projectId: string; stage: string; status: AgentRun['status']
  startedAt: string; completedAt: string | null; errorMessage: string | null
  source: 'request' | 'model'; chapter: number | null
  stopRequested?: boolean
}
// Memory-only request state contains no prompts or manuscript text.
export const generationRequests = shallowRef<GenerationActivity[]>([])

export function beginGenerationRequest(url: string, method = 'GET') {
  if (method.toUpperCase() !== 'POST') return null
  const match = /^\/api\/v1\/projects\/([^/]+)\/(.+?)(?:\?.*)?$/.exec(url)
  if (!match) return null
  const stage = routes.find(([pattern]) => pattern.test(match[2]!))?.[1]
  if (!stage) return null
  const entry: GenerationActivity = {
    id: crypto.randomUUID(), projectId: match[1]!, stage, status: 'RUNNING',
    startedAt: new Date().toISOString(), completedAt: null, errorMessage: null, source: 'request',
    chapter: match[2]!.match(/^chapters\/(\d+)\//)?.[1] ? Number(match[2]!.split('/')[1]) : null,
  }
  generationRequests.value = [entry, ...generationRequests.value.filter(item => item.status === 'RUNNING'
    || item.projectId !== entry.projectId || item.stage !== stage)].slice(0, 100)
  return entry.id
}
export function endGenerationRequest(id: string | null, error?: unknown) {
  if (!id) return
  const cancelled = error && ((error as { code?: string }).code === 'GENERATION_CANCELLED'
    || (error instanceof Error && error.message.startsWith('生成已停止')))
  generationRequests.value = generationRequests.value.map(item => item.id === id ? {
    ...item, status: cancelled ? 'CANCELLED' : error ? 'FAILED' : 'SUCCEEDED', completedAt: new Date().toISOString(),
    errorMessage: error ? (error instanceof Error ? error.message : '请求失败') : null,
  } : item)
}
export function markGenerationStopping(id: string) {
  generationRequests.value = generationRequests.value.map(item => item.id === id && item.status === 'RUNNING'
    ? { ...item, stopRequested: true } : item)
}
export function completeGenerationResponse(id: string | null, body: unknown) {
  const value = body as { status?: string; errorMessage?: string; report?: { status?: string; errorMessage?: string }; task?: { status?: string; errorMessage?: string } } | null
  const outcome = value?.report ?? value?.task ?? value
  endGenerationRequest(id, outcome?.status === 'CANCELLED' ? new Error('生成已停止')
    : outcome?.status === 'FAILED' ? new Error(outcome.errorMessage || '生成失败') : undefined)
}
export function generationRows(projectId: string, runs: AgentRun[]) {
  const models: GenerationActivity[] = runs.filter(run => generationStages[aliases[run.stage] ?? run.stage])
    .map(run => ({ id: run.id, projectId, stage: aliases[run.stage] ?? run.stage, status: run.status,
      startedAt: run.startedAt, completedAt: run.completedAt, errorMessage: run.errorMessage, source: 'model', chapter: null }))
  const local = generationRequests.value.filter(item => item.projectId === projectId)
  const core = ['STORY_DIRECTION', 'STORY_BIBLE', 'OUTLINE', 'CHAPTER_CONTRACT',
    'CHAPTER_CONTRACT_REVIEW', 'MANUSCRIPT', 'CHAPTER_REVIEW', 'QUALITY_REVIEW']
  const stages = [...new Set([...core, ...[...local, ...models].map(item => item.stage)])]
  return stages.map(stage => {
    const newest = (items: GenerationActivity[]) => items.filter(item => item.stage === stage)
      .sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt))[0]
    const request = newest(local), model = newest(models)
    // A completed model call must not override a still-pending HTTP save or an HTTP validation failure.
    const activity = request && (!model || Date.parse(model.startedAt) <= Date.parse(request.completedAt ?? new Date().toISOString()))
      ? request : model
    return { stage, label: generationStages[stage]!, activity: activity ?? null, model: model ?? null }
  })
}

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, RefreshCw, Sparkles, Square, RotateCcw } from 'lucide-vue-next'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import type { ImportedChapter, ImportPlanningMode } from '@/api/imports'
import { listImportAnalyses, createImportAnalysis, importAnalysisAction, confirmImportAnalysis, getImportAnalysis, type AnalysisView, type AnalysisDecision, type AnalysisProof, type AnalysisEvidence } from '@/api/importAnalyses'

const props = defineProps<{ projectId: string; importId: string; mode: ImportPlanningMode; chapters: ImportedChapter[]; disabled?: boolean }>()
const emit = defineEmits<{ ready: [proof: AnalysisProof | null]; confirmed: [] }>()
const client = useQueryClient()
const { provider } = useGlobalModelSettings()
const selectedId = ref<string | null>(null)
const current = ref<AnalysisView | null>(null)
const decisions = ref<Record<string, { action: AnalysisDecision['action'] | ''; note: string }>>({})
const consent = ref(false)
const busy = ref(false)
const error = ref('')
const dirty = ref(false)
const stopping = ref(false)
let alive = true
let operation = 0
onBeforeUnmount(() => { alive = false; operation++; stopping.value = true })
const category = ref('ALL')
const scope = computed(() => `${props.projectId}:${props.importId}`)
const query = useQuery({ queryKey: computed(() => ['import-analyses', props.projectId, props.importId]), queryFn: () => listImportAnalyses(props.projectId, props.importId), refetchInterval: q => q.state.data?.some(v => v.report.status === 'RUNNING') ? 1500 : false })
const categories: Record<string, string> = { ALL: '全部', CHARACTER: '人物', WORLD: '世界观', RELATIONSHIP: '关系', EVENT: '事件', CLUE: '埋点与线索', FORESHADOW: '伏笔' }
const certainties = { FACT: '原文明确信息', INFERENCE: '分析推测', UNKNOWN: '未知' }
const statuses = { READY: '待解析', RUNNING: '正在解析', FAILED: '解析失败', REVIEW: '待审核', CONFIRMED: '已确认', CANCELLED: '已取消' }
const progress = { NOT_APPLICABLE: '', SET_UP: '已出现', REINFORCED: '本段强化', PAYOFF: '本段有兑现依据', UNRESOLVED: '本段未见解决', UNKNOWN: '进度未知' }
const editable = computed(() => !!current.value && ['REVIEW', 'CONFIRMED'].includes(current.value.report.status) && !current.value.stale && !busy.value && !props.disabled)
const items = computed(() => current.value?.report.content.items ?? [])
const visible = computed(() => items.value.filter(v => category.value === 'ALL' || v.category === category.value))
const decided = computed(() => items.value.filter(v => decisions.value[v.key]?.action && (decisions.value[v.key]?.action !== 'REWORK' || decisions.value[v.key]?.note.trim())).length)
const canConfirm = computed(() => editable.value && decided.value === items.value.length && consent.value)
function load(v: AnalysisView) {
  current.value = v; selectedId.value = v.report.id; consent.value = false; dirty.value = false
  decisions.value = Object.fromEntries(v.report.content.items.map(item => { const d = v.report.decisions.find(d => d.key === item.key); return [item.key, { action: d?.action ?? '', note: d?.note ?? '' }] }))
}
watch(scope, () => { operation++; busy.value = false; selectedId.value = null; current.value = null; decisions.value = {}; consent.value = false; dirty.value = false; stopping.value = true; error.value = '' })
watch(() => query.data.value, values => {
  if (!values || busy.value || dirty.value) return
  const value = values.find(v => v.report.id === selectedId.value) ?? values.find(v => v.report.confirmedMode === props.mode) ?? values[0]
  if (value) load(value)
}, { immediate: true })
watch(() => props.mode, () => {
  consent.value = false
  if (props.mode === 'CONTINUE_MANUSCRIPT') for (const d of Object.values(decisions.value)) if (d.action === 'REWORK') { d.action = ''; dirty.value = true }
})
watch(() => [current.value, props.mode, dirty.value, busy.value] as const, () => {
  const v = current.value
  emit('ready', v && !v.stale && !dirty.value && !busy.value && v.report.status === 'CONFIRMED' && v.report.confirmedMode === props.mode ? { id: v.report.id, version: v.report.version, mode: props.mode } : null)
}, { immediate: true, deep: true })
function change(key: string, action?: AnalysisDecision['action']) {
  if (action) decisions.value[key]!.action = action
  dirty.value = true; consent.value = false
}
function bulk(action: AnalysisDecision['action']) { for (const item of visible.value) change(item.key, action) }
function selectReport(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  if (dirty.value && !window.confirm('放弃未确认的逐项处理？')) { (event.target as HTMLSelectElement).value = selectedId.value ?? ''; return }
  const value = query.data.value?.find(v => v.report.id === id); if (value) load(value)
}
async function run(create: boolean, resume = false) {
  if (create && dirty.value && !window.confirm('放弃未确认的逐项处理并重新解析？')) return
  const p = props.projectId, i = props.importId, key = scope.value
  const op = ++operation
  busy.value = true; error.value = ''; stopping.value = false
  try {
    if (provider.value === 'LOCAL_TEMPLATE' && create) throw new Error('原文分析需要选择 ChatGPT 或 DeepSeek。')
    let value = create ? await createImportAnalysis(p, i, provider.value, crypto.randomUUID()) : current.value!
    if (!alive || operation !== op || scope.value !== key) return
    load(value)
    if (resume) { value = await importAnalysisAction(p, i, value, 'resume'); if (!alive || operation !== op || scope.value !== key) return; load(value) }
    for (let n = 0; n < 40 && value.report.status === 'READY' && !stopping.value && scope.value === key && alive && operation === op; n++) {
      value = await importAnalysisAction(p, i, value, 'run-next')
      if (!alive || operation !== op || scope.value !== key) return
      load(value)
    }
  } catch (reason) { if (alive && operation === op && scope.value === key) error.value = reason instanceof Error ? reason.message : String(reason) }
  finally { if (alive && operation === op && scope.value === key) { busy.value = false; void client.invalidateQueries({ queryKey: ['import-analyses', p, i] }) } }
}
async function cancel() {
  const v = current.value, p = props.projectId, i = props.importId, key = scope.value
  if (!v) return
  stopping.value = true
  try { const latest = await getImportAnalysis(p, i, v.report.id); const result = await importAnalysisAction(p, i, latest, 'cancel'); if (scope.value === key) load(result) }
  catch (reason) { if (scope.value === key) error.value = reason instanceof Error ? reason.message : String(reason) }
}
async function confirm() {
  if (!canConfirm.value || !current.value) return
  const p = props.projectId, i = props.importId, key = scope.value, mode = props.mode
  const values = items.value.map(item => ({ key: item.key, action: decisions.value[item.key]!.action as AnalysisDecision['action'], note: decisions.value[item.key]!.note }))
  busy.value = true; error.value = ''
  try { const value = await confirmImportAnalysis(p, i, current.value, mode, values, consent.value); if (scope.value === key) { load(value); emit('confirmed') } }
  catch (reason) { if (scope.value === key) error.value = reason instanceof Error ? reason.message : String(reason) }
  finally { busy.value = false; void client.invalidateQueries({ queryKey: ['import-analyses', p, i] }) }
}
function evidenceContext(e: AnalysisEvidence) {
  const chapter = props.chapters.find(v => v.id === e.chapterId)
  if (!chapter) return null
  let start = -1
  for (let n = 0; n <= e.occurrence; n++) { start = chapter.content.indexOf(e.quote, start + 1); if (start < 0) return null }
  return { title: `第 ${chapter.ordinal} 章 · ${chapter.title}`, start, before: chapter.content.slice(Math.max(0, start - 100), start), after: chapter.content.slice(start + e.quote.length, start + e.quote.length + 100) }
}
</script>

<template>
  <section class="source-analysis">
    <div class="analysis-heading"><h3>原文解析</h3><button class="button icon-button" title="刷新解析报告" aria-label="刷新解析报告" :disabled="busy || disabled" @click="query.refetch()"><RefreshCw :size="16" /></button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="query.isError.value" class="form-error">解析报告加载失败：{{ query.error.value?.message }}</p>
    <div class="analysis-toolbar">
      <button class="button primary" :disabled="busy || disabled" @click="run(true)"><Sparkles :size="16" />{{ current ? '重新解析原文' : '解析原文' }}</button>
      <button v-if="current?.report.status === 'READY'" class="button" :disabled="busy || disabled || current.stale" @click="run(false)"><Sparkles :size="16" />继续解析</button>
      <button v-if="current && ['FAILED', 'RUNNING'].includes(current.report.status)" class="button" :disabled="busy || disabled || current.stale" @click="run(false, true)"><RotateCcw :size="16" />明确重试或恢复</button>
      <button v-if="current && ['READY', 'RUNNING', 'FAILED'].includes(current.report.status)" class="button" :disabled="disabled" @click="cancel"><Square :size="16" />取消解析</button>
    </div>
    <template v-if="current">
      <label class="analysis-history" v-if="query.data.value?.length"><span>解析版本</span><select :value="selectedId" :disabled="busy || disabled" @change="selectReport"><option v-for="v in query.data.value" :key="v.report.id" :value="v.report.id">{{ new Date(v.report.updatedAt).toLocaleString('zh-CN') }} · {{ statuses[v.report.status] }}</option></select></label>
      <p class="analysis-status">{{ statuses[current.report.status] }} · 已覆盖 {{ current.report.nextSlice }} / {{ current.report.slices.length }} 段 · {{ items.length }} 项</p>
      <p v-if="current.stale" class="form-error">原文或章节选择已变化，请重新解析。</p>
      <p v-if="current.report.errorMessage" class="form-error">{{ current.report.errorMessage }}</p>
      <details class="analysis-summary"><summary>分段结论与范围</summary><p v-for="(note, n) in current.report.content.summaries" :key="n">第 {{ n + 1 }} 段：{{ note }}</p></details>
      <nav class="analysis-tabs" aria-label="解析分类"><button v-for="(label, key) in categories" :key="key" :class="{ active: category === key }" @click="category = key">{{ label }} {{ key === 'ALL' ? items.length : items.filter(v => v.category === key).length }}</button></nav>
      <div v-if="editable && visible.length" class="analysis-toolbar"><button class="button" @click="bulk('KEEP')"><Check :size="16" />采用当前分类全部结论</button><button class="button" @click="bulk('DROP')">{{ mode === 'ADAPT_SOURCE' ? '当前分类全部不沿用' : '当前分类全部不采用' }}</button></div>
      <div class="analysis-item" v-for="item in visible" :key="item.key">
        <header><strong>{{ item.title }}</strong><span :class="['certainty', item.certainty.toLowerCase()]">{{ certainties[item.certainty] }}</span></header>
        <p class="analysis-description">{{ item.description }}</p><small v-if="progress[item.progress]">{{ progress[item.progress] }}</small>
        <details v-for="(e, n) in item.evidence" :key="n" class="analysis-evidence"><summary>{{ evidenceContext(e)?.title ?? '未知章节' }} · 原文依据 {{ n + 1 }}</summary><blockquote>{{ e.quote }}</blockquote><p v-if="evidenceContext(e)">{{ evidenceContext(e)?.before }}<mark>{{ e.quote }}</mark>{{ evidenceContext(e)?.after }}</p></details>
        <div class="decision-fields">
          <label><span>{{ mode === 'ADAPT_SOURCE' ? '改编处理' : '解析结论处理' }}</span><select v-model="decisions[item.key]!.action" :aria-label="`${item.title}处理`" :disabled="!editable" @change="change(item.key)"><option value="">待决定</option><option value="KEEP">{{ mode === 'ADAPT_SOURCE' ? '保留' : '采用' }}</option><option v-if="mode === 'ADAPT_SOURCE'" value="REWORK">重构</option><option value="DROP">{{ mode === 'ADAPT_SOURCE' ? '不沿用' : '不采用该解析结论' }}</option></select></label>
          <label><span>{{ decisions[item.key]!.action === 'REWORK' ? '重构要求（必填）' : '确认备注' }}</span><textarea v-model="decisions[item.key]!.note" :aria-label="`${item.title}备注`" rows="2" maxlength="1000" :disabled="!editable" @input="change(item.key)" /></label>
        </div>
      </div>
      <p v-if="!visible.length">此分类暂无解析项。</p>
      <footer v-if="['REVIEW', 'CONFIRMED'].includes(current.report.status)" class="analysis-confirm">
        <span>已决定 {{ decided }} / {{ items.length }} 项 · {{ mode === 'ADAPT_SOURCE' ? '改编' : '续写' }}</span>
        <label><input v-model="consent" type="checkbox" :disabled="!editable" />确认原文解析与逐项处理</label>
        <button class="button primary" :disabled="!canConfirm" @click="confirm"><Check :size="16" />确认解析报告</button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.source-analysis { margin: 24px 0; padding: 20px 0; border-top: 1px solid var(--border, #d8dfe5); border-bottom: 1px solid var(--border, #d8dfe5); min-width: 0; }
.analysis-heading, .analysis-toolbar, .analysis-item header, .analysis-confirm { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.analysis-heading { justify-content: space-between; }.analysis-heading h3 { margin: 0; font-size: 18px; }
.analysis-toolbar { margin: 14px 0; }.analysis-history { display: grid; gap: 6px; max-width: 560px; }
.analysis-history select, .decision-fields select, .decision-fields textarea { width: 100%; min-width: 0; padding: 8px; border: 1px solid #cad4dd; border-radius: 4px; background: white; font: inherit; }
.analysis-tabs { display: flex; flex-wrap: wrap; gap: 4px; margin: 16px 0; border-bottom: 1px solid #d8dfe5; }
.analysis-tabs button { border: 0; background: transparent; padding: 10px 12px; border-bottom: 2px solid transparent; cursor: pointer; font: inherit; }.analysis-tabs button.active { border-bottom-color: #326a61; color: #24584f; }
.analysis-item { padding: 18px 0; border-top: 1px solid #e0e6eb; overflow-wrap: anywhere; }.analysis-item header strong { flex: 1; min-width: 140px; }
.certainty { font-size: 12px; padding: 3px 6px; background: #edf3f6; }.certainty.inference { background: #fff3d3; color: #725017; }.certainty.unknown { background: #f0edf5; color: #655475; }
.analysis-description { white-space: pre-wrap; line-height: 1.7; }.analysis-evidence { margin: 12px 0; line-height: 1.7; }.analysis-evidence summary { cursor: pointer; color: #326a61; }
.analysis-evidence blockquote { margin: 10px 0; padding: 8px 12px; border-left: 3px solid #63897f; background: #f3f7f6; white-space: pre-wrap; }.analysis-evidence p { white-space: pre-wrap; }.analysis-evidence mark { background: #f9e8a5; }
.decision-fields { display: grid; grid-template-columns: minmax(150px, 1fr) minmax(0, 3fr); gap: 16px; }.decision-fields label { display: grid; align-content: start; gap: 6px; }
.analysis-confirm { border-top: 1px solid #d8dfe5; padding-top: 20px; }.analysis-confirm label { display: flex; align-items: center; gap: 8px; }
@media (max-width: 640px) { .decision-fields { grid-template-columns: minmax(0, 1fr); }.analysis-confirm { align-items: start; flex-direction: column; }.analysis-toolbar .button { max-width: 100%; white-space: normal; } }
</style>

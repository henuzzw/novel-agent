<script setup lang="ts">
import { computed, nextTick, ref, watch, onUnmounted } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ChevronLeft, ChevronRight, FileSearch, LocateFixed, SkipForward, WandSparkles } from 'lucide-vue-next'
import type { ModelProvider } from '@/api/planning'
import { getManuscriptVersion, type ManuscriptVersion } from '@/api/writing'
import { generateQualityReview, getQualityReview, reviseFromQuality, validateQualitySelection,
  type QualityReportState, type QualityRevisionScope } from '@/api/writingQuality'

const props = defineProps<{ projectId: string; chapterNumber: number; manuscript: ManuscriptVersion; provider: ModelProvider; instruction: string; hasUnsavedChanges: boolean; externalBusy: boolean }>()
const emit = defineEmits<{ revised: [value: ManuscriptVersion]; 'busy-change': [value: boolean]; 'state-change': [state: QualityReportState, label: string] }>()
const client = useQueryClient()
const projectId = props.projectId
const chapter = props.chapterNumber
const key = ['quality-review', projectId, chapter]
const query = useQuery({ queryKey: key, queryFn: () => getQualityReview(projectId, chapter), retry: false })
const invalidated = ref(client.getQueryState(key)?.isInvalidated ?? false)
const unsubscribe = client.getQueryCache().subscribe(event => {
  if (event.type === 'updated' && event.query.queryKey[1] === projectId
    && ((event.query.queryKey[0] === 'project' && event.action.type === 'invalidate')
      || (event.query.queryKey[0] === 'creative-strategy' && ['invalidate', 'success'].includes(event.action.type)))) {
    void client.invalidateQueries({ queryKey: key })
  }
  if (event.query.queryKey[0] === key[0] && event.query.queryKey[1] === projectId && event.query.queryKey[2] === chapter) {
    invalidated.value = event.query.state.isInvalidated
  }
})
const skipKey = computed(() => ['quality-skip', projectId, chapter, props.manuscript.id, props.manuscript.version])
const skipped = ref(client.getQueryData<boolean>(skipKey.value) ?? false)
const selected = ref<string[]>([])
const scope = ref<QualityRevisionScope>('EXPRESSION_ONLY')
const error = ref('')
const locating = ref('')
const occurrence = ref(0)
const highlight = ref<HTMLElement | null>(null)
let active = true
const labels: Record<string, string> = { STYLE: '风格一致性', FLUENCY: '语句通顺', LOGIC: '情节逻辑', SCENE: '场景与节奏' }
const report = computed(() => query.data.value)
const matchesText = computed(() => report.value?.sourceManuscriptId === props.manuscript.id
  && report.value?.sourceManuscriptRowVersion === props.manuscript.version)
const current = computed(() => !!report.value?.current && matchesText.value && !invalidated.value
  && !query.isFetching.value && !query.isError.value && !props.hasUnsavedChanges)
const needsRecheck = computed(() => !!report.value && props.manuscript.baseManuscriptVersionId === report.value.sourceManuscriptId && !matchesText.value)
const sourceQuery = useQuery({
  queryKey: computed(() => ['quality-source', projectId, chapter, report.value?.sourceManuscriptId, report.value?.sourceManuscriptRowVersion]),
  queryFn: () => getManuscriptVersion(projectId, chapter, report.value!.sourceManuscriptId),
  enabled: computed(() => !!report.value && report.value.sourceManuscriptId !== props.manuscript.id), retry: false,
})
const source = computed(() => {
  if (matchesText.value) return props.manuscript
  const value = sourceQuery.data.value
  return value?.id === report.value?.sourceManuscriptId && value?.version === report.value?.sourceManuscriptRowVersion ? value : null
})
function positions(evidence: string) {
  const body = source.value?.content.body ?? ''
  const result: number[] = []
  if (!evidence) return result
  for (let start = body.indexOf(evidence); start >= 0; start = body.indexOf(evidence, start + evidence.length)) result.push(start)
  return result
}
const locations = computed(() => positions(locating.value))
const offset = computed(() => locations.value[occurrence.value] ?? -1)
const paragraph = computed(() => offset.value < 0 ? 0 : (source.value?.content.body.slice(0, offset.value).split(/\n\s*\n/).length ?? 0))
async function locate(evidence: string, index = 0) {
  locating.value = evidence; occurrence.value = index
  await nextTick()
  highlight.value?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
watch(() => [props.manuscript.id, props.manuscript.version], () => {
  selected.value = []; locating.value = ''; scope.value = 'EXPRESSION_ONLY'
  skipped.value = client.getQueryData<boolean>(skipKey.value) ?? false
  query.refetch()
})
watch(() => report.value?.id, () => { selected.value = []; locating.value = '' })
watch(scope, () => { selected.value = [] })
watch(current, value => { if (!value) selected.value = [] })
function skip() {
  skipped.value = true; selected.value = []
  client.setQueryData(skipKey.value, true)
}
const generate = useMutation({ mutationFn: () => {
  if (props.hasUnsavedChanges || props.externalBusy) throw new Error('请先保存正文并等待当前操作完成')
  if (props.instruction.length > 2000) throw new Error('补充要求不能超过 2000 字')
  skipped.value = false; client.setQueryData(skipKey.value, false)
  return generateQualityReview(projectId, chapter, props.provider, props.instruction)
}, onSuccess: value => {
  client.setQueryData(key, value)
  if (active) error.value = ''
}, onError: (e: Error) => { if (active) { error.value = e.message; query.refetch() } } })
const revise = useMutation({ mutationFn: () => {
  if (!report.value || !current.value || skipped.value) throw new Error('质量检查已过期或尚未核对，请重新检查')
  if (props.externalBusy) throw new Error('请等待当前操作完成')
  validateQualitySelection(selected.value, scope.value, report.value.content.issues)
  return reviseFromQuality(projectId, chapter, report.value.id, props.provider, selected.value, props.instruction, scope.value)
}, onSuccess: value => {
  client.invalidateQueries({ queryKey: key })
  if (!active) return
  emit('revised', value); selected.value = []; error.value = ''
}, onError: (e: Error) => { if (active) { error.value = e.message; query.refetch() } } })
const busy = computed(() => generate.isPending.value || revise.isPending.value)
const state = computed<QualityReportState>(() => {
  if (generate.isPending.value || (report.value && query.isFetching.value)) return 'rechecking'
  if (skipped.value) return 'skipped'
  if (!report.value) return 'unrun'
  return current.value ? 'valid' : 'stale'
})
const stateLabel = computed(() => {
  if (generate.isPending.value) return report.value ? '正在复检' : '检查中'
  if (report.value && query.isFetching.value) return '核对报告来源中'
  return { valid: '报告有效', stale: needsRecheck.value ? '修订后需复检' : '结果过期', unrun: '未检查', rechecking: '检查中', skipped: '本次主动跳过' }[state.value]
})
const selectionError = computed(() => {
  if (!selected.value.length) return ''
  try { validateQualitySelection(selected.value, scope.value, report.value?.content.issues); return '' }
  catch (e) { return (e as Error).message }
})
function selectable(category: string, resolved: boolean) {
  return !resolved && (scope.value === 'SCENE_STRUCTURE' || ['STYLE', 'FLUENCY'].includes(category))
}
watch([state, stateLabel], () => emit('state-change', state.value, stateLabel.value), { immediate: true })
watch(busy, value => emit('busy-change', value))
onUnmounted(() => { active = false; unsubscribe(); emit('busy-change', false) })
</script>

<template>
  <section class="quality-panel" aria-label="检查与润色" :data-state="state">
    <header><h3>检查与润色</h3><div class="quality-actions">
      <button class="button secondary" type="button" :disabled="busy || externalBusy || hasUnsavedChanges || query.isFetching.value" @click="generate.mutate()"><FileSearch :size="16" />{{ generate.isPending.value ? '正在检查…' : report ? '复检正文' : '检查正文' }}</button>
      <button class="button secondary" type="button" :disabled="busy || externalBusy || skipped" @click="skip"><SkipForward :size="16" />本次跳过</button>
    </div></header>
    <p class="quality-status" role="status">{{ stateLabel }}<span v-if="skipped"> · 未完成检查</span></p>
    <p class="quality-limit">质量检查可以跳过；作者核对后可直接确认并发布。修订候选的事实、人物知识、关系及退出状态仍需作者核对。</p>
    <p v-if="hasUnsavedChanges" class="form-error">正文有未保存修改，请先保存。</p>
    <p v-if="provider === 'LOCAL_TEMPLATE'" class="quality-limit">本地规则仅检查有限文本问题；本地模板生成稿仅验证版本流程，不执行语义润色。</p>
    <p v-if="query.isError.value" class="form-error" role="alert">{{ query.error.value?.message }}；报告来源未核对，不能修订。</p>
    <template v-if="report">
      <p class="quality-source">报告第 {{ report.versionNumber ?? '—' }} 版 · 来源正文 {{ report.sourceManuscriptId }} · 保存版本 {{ report.sourceManuscriptRowVersion }}<template v-if="source"> · 正文第 {{ source.versionNumber }} 版</template></p>
      <p v-if="state === 'stale'" class="form-error">{{ needsRecheck ? '新草稿尚未复检。' : '正文或写作依据已变化。' }}旧报告不能用于当前稿修订。</p>
      <p>{{ report.content.summary }}</p>
      <dl class="quality-scores"><div v-for="score in report.content.scores" :key="score.dimension"><dt>{{ labels[score.dimension] }} <strong>{{ score.score == null ? '未评分' : `${score.score}/100` }}</strong></dt><dd>{{ score.rationale }}</dd></div></dl>
      <fieldset v-if="report.content.issues.length" class="quality-scope" :disabled="busy || externalBusy || !current || skipped">
        <legend>本次修订授权</legend>
        <label><input v-model="scope" type="radio" value="EXPRESSION_ONLY" />仅表达润色</label>
        <label><input v-model="scope" type="radio" value="SCENE_STRUCTURE" />选中问题的场景结构修订</label>
      </fieldset>
      <p v-if="report.content.issues.length" class="quality-limit">{{ scope === 'EXPRESSION_ONLY' ? '仅处理风格与语句问题，保留场景和事件顺序。' : '允许调整既有场景的叙述顺序、组织和节奏；不授权新故事事实，不改变事件发生顺序或结果。' }} 补充要求不能扩大授权。</p>
      <article v-for="issue in report.content.issues" :key="issue.id" class="quality-issue">
        <label><input v-model="selected" type="checkbox" :value="issue.id" :disabled="!current || skipped || busy || externalBusy || !selectable(issue.category, issue.resolved)" /><strong>{{ labels[issue.category] }} · {{ issue.description }}</strong></label>
        <blockquote>{{ issue.evidence }}</blockquote><p>{{ issue.suggestion }}</p>
        <div class="quality-actions"><button type="button" class="button secondary compact" :disabled="!positions(issue.evidence).length" @click="locate(issue.evidence)"><LocateFixed :size="15" />定位原文</button><small>{{ positions(issue.evidence).length ? `来源正文中 ${positions(issue.evidence).length} 处` : '绑定版本原文不可定位' }}</small></div>
        <small v-if="scope === 'EXPRESSION_ONLY' && ['LOGIC', 'SCENE'].includes(issue.category)">此问题需显式授权场景结构修订。</small>
      </article>
      <p v-if="!report.content.issues.length">本次检查未提出修改建议。</p>
      <p v-if="selectionError" class="form-error" role="alert">{{ selectionError }}</p>
      <button v-if="report.content.issues.length" type="button" class="button primary" :disabled="!current || skipped || !selected.length || !!selectionError || busy || externalBusy" @click="revise.mutate()"><WandSparkles :size="16" />{{ revise.isPending.value ? '正在生成候选…' : '按建议生成润色稿' }}</button>
      <section v-if="source && offset >= 0" class="quality-original" aria-label="问题原文">
        <header><strong>来源正文第 {{ source.versionNumber }} 版 · 第 {{ paragraph }} 段</strong><div class="quality-actions">
          <button class="button secondary compact" type="button" aria-label="上一处原文" title="上一处原文" :disabled="occurrence === 0" @click="locate(locating, occurrence - 1)"><ChevronLeft :size="16" /></button>
          <span>{{ occurrence + 1 }} / {{ locations.length }}</span>
          <button class="button secondary compact" type="button" aria-label="下一处原文" title="下一处原文" :disabled="occurrence + 1 >= locations.length" @click="locate(locating, occurrence + 1)"><ChevronRight :size="16" /></button>
        </div></header>
        <p class="quality-original-body">{{ source.content.body.slice(0, offset) }}<mark ref="highlight">{{ locating }}</mark>{{ source.content.body.slice(offset + locating.length) }}</p>
      </section>
    </template>
    <p v-else-if="!query.isFetching.value && !skipped" class="quality-limit">当前正文第 {{ manuscript.versionNumber }} 版尚未检查。</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.quality-panel { min-width: 0; }
.quality-limit, .quality-source { color: #66717d; font-size: 13px; }
.quality-status { font-weight: 600; }
header, .quality-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
header { justify-content: space-between; }
.quality-scores { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
dt { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; }
dd { margin: 8px 0 0; }
.quality-scope { display: flex; flex-wrap: wrap; gap: 12px 20px; border: 0; border-top: 1px solid #ddd; padding: 16px 0; margin: 0; min-width: 0; }
.quality-scope legend { padding: 0; font-weight: 600; }
.quality-issue { padding: 14px 0; border-top: 1px solid #eee; }
label { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
input { flex: 0 0 auto; }
blockquote { margin: 12px 0; padding-left: 12px; border-left: 3px solid #bbb; white-space: pre-wrap; }
p, dd, strong, blockquote, small { overflow-wrap: anywhere; }
.button { max-width: 100%; white-space: normal; }
.button svg { flex-shrink: 0; }
.quality-original { margin-top: 24px; border-top: 1px solid #ddd; padding-top: 16px; }
.quality-original-body { max-height: 420px; overflow: auto; white-space: pre-wrap; line-height: 1.8; }
mark { background: #ffe594; color: #242424; }
@media (max-width: 640px) { .quality-scores { grid-template-columns: 1fr; } }
</style>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Play, Square, LoaderCircle } from 'lucide-vue-next'
import { draftChange, draftLoopActive, draftStopLabels, listDraftLoops, startDraftLoop, stopDraftLoop } from '@/api/draftLoops'
import type { ModelProvider } from '@/api/planning'
import type { ManuscriptVersion } from '@/api/writing'

const props = defineProps<{ projectId: string; chapter: number; provider: ModelProvider; manuscript: ManuscriptVersion | null | undefined; externalBusy: boolean; unsaved: boolean }>()
const emit = defineEmits<{ 'busy-change': [busy: boolean]; 'refresh-requested': [] }>()
const client = useQueryClient()
const key = ['draft-loops', props.projectId, props.chapter]
const selected = ref('')
const writeFirst = ref(props.manuscript?.status !== 'DRAFT')
const maxRounds = ref(10)
const error = ref('')
let requestKey = crypto.randomUUID()
const query = useQuery({ queryKey: key, queryFn: () => listDraftLoops(props.projectId, props.chapter), retry: false,
  refetchInterval: q => q.state.data?.some(draftLoopActive) ? 2000 : false, refetchIntervalInBackground: true })
const run = computed(() => query.data.value?.find(r => r.id === selected.value) ?? query.data.value?.[0])
const active = computed(() => query.data.value?.some(draftLoopActive) ?? false)
const activeRun = computed(() => query.data.value?.find(draftLoopActive))
const phaseLabel = computed(() => ({ A: 'A · 正在创作', B: 'B · 正在检查', C: 'C · 正在裁决与修订' })[activeRun.value?.phase ?? 'A'])
const roundNumber = computed(() => Math.min(activeRun.value?.maxRounds ?? 10, (activeRun.value?.rounds.length ?? 0) + (activeRun.value?.phase === 'B' ? 1 : 0)))
watch(() => query.data.value, value => { if (!selected.value && value?.[0]) selected.value = value[0].id })
const start = useMutation({ mutationFn: () => {
  if (props.unsaved || props.externalBusy || active.value) throw new Error('请先保存正文并等待当前操作完成')
  return startDraftLoop(props.projectId, props.chapter, props.provider, writeFirst.value, maxRounds.value, requestKey)
}, onSuccess: value => {
  client.setQueryData(key, [value, ...(query.data.value ?? []).filter(r => r.id !== value.id)])
  selected.value = value.id; error.value = ''; requestKey = crypto.randomUUID()
}, onError: (failure: Error) => { error.value = failure.message; void query.refetch() } })
const stop = useMutation({ mutationFn: () => {
  const current = query.data.value?.find(draftLoopActive)
  if (!current) throw new Error('当前没有运行中的自动编辑任务')
  return stopDraftLoop(props.projectId, props.chapter, current.id)
}, onSuccess: value => {
  client.setQueryData(key, (query.data.value ?? []).map(r => r.id === value.id ? value : r))
  error.value = ''; emit('refresh-requested')
}, onError: (failure: Error) => { error.value = failure.message; void query.refetch() } })
const busy = computed(() => active.value || start.isPending.value || stop.isPending.value)
watch(busy, value => emit('busy-change', value), { immediate: true })
const refreshedIds = new Set<string>()
watch(() => ({ id: query.data.value?.[0]?.manuscriptId, current: props.manuscript?.id, unsaved: props.unsaved }), ({ id, current, unsaved }) => {
  if (id && id !== current && !unsaved && !refreshedIds.has(id)) {
    refreshedIds.add(id); emit('refresh-requested')
  }
})
onUnmounted(() => emit('busy-change', false))
const labels: Record<string, string> = { ACCEPT: '接受并修订', REJECT: '拒绝', DEFER: '暂缓', STYLE: '风格', FLUENCY: '语句', LOGIC: '逻辑', SCENE: '场景' }
</script>

<template>
  <section class="draft-loop-panel" aria-label="自动写作与检查">
    <header><strong>自动写作与检查</strong><span v-if="busy" role="status"><LoaderCircle :size="16" class="draft-loop-spinner" />{{ activeRun ? phaseLabel : '正在创建任务…' }}<template v-if="activeRun && activeRun.phase !== 'A'"> · 第 {{ roundNumber }} / {{ activeRun.maxRounds }} 轮</template></span><span v-else-if="run" role="status">{{ draftStopLabels[run.stopReason ?? ''] ?? run.status }}</span></header>
    <div class="draft-loop-controls">
      <fieldset :disabled="busy || externalBusy"><legend>起点</legend><label><input v-model="writeFirst" type="radio" :value="true" />从大纲创作</label><label><input v-model="writeFirst" type="radio" :value="false" :disabled="manuscript?.status !== 'DRAFT'" />检查当前草稿</label></fieldset>
      <label class="draft-loop-limit">轮次上限<input v-model.number="maxRounds" type="number" min="1" max="10" :disabled="busy || externalBusy" /></label>
      <button class="button secondary" :disabled="busy || externalBusy || unsaved || provider === 'LOCAL_TEMPLATE' || query.isFetching.value || query.isError.value" @click="start.mutate()"><Play :size="16" />开始自动创作</button>
      <button v-if="active" class="button secondary" :disabled="stop.isPending.value" @click="stop.mutate()"><Square :size="16" />{{ stop.isPending.value ? '正在停止…' : '停止' }}</button>
    </div>
    <div v-if="error || run?.errorMessage" class="form-error" role="alert">{{ error || run?.errorMessage }}</div>
    <div v-if="query.isError.value" class="form-error" role="alert">自动编辑任务读取失败：{{ query.error.value?.message }}<button class="button secondary compact" @click="query.refetch()">重试</button></div>
    <label v-if="(query.data.value?.length ?? 0) > 1" class="draft-loop-history">任务记录<select v-model="selected"><option v-for="item in query.data.value" :key="item.id" :value="item.id">{{ new Date(item.createdAt).toLocaleString() }} · {{ draftStopLabels[item.stopReason ?? ''] ?? item.status }}</option></select></label>
    <details v-for="round in run?.rounds ?? []" :key="round.number" class="draft-loop-round">
      <summary>第 {{ round.number }} 轮 · {{ round.check.issues.length }} 项检查意见 · {{ round.judgment ? round.afterManuscriptId ? '已修订' : '裁决完成' : round.check.issues.length ? '等待裁决' : '未发现明确问题' }}</summary>
      <p>{{ round.check.summary }}</p>
      <article v-for="issue in round.check.issues" :key="issue.id" class="draft-loop-issue">
        <strong>{{ issue.id }} · {{ labels[issue.category] }} · {{ issue.description }}</strong>
        <blockquote>{{ issue.evidence }}</blockquote>
        <p v-if="issue.existingBasis"><b>已有依据：</b>{{ issue.existingBasis }}</p>
        <p>{{ issue.suggestion }}</p>
        <p v-if="issue.gap"><b>资料缺口：</b>{{ issue.gap }}</p>
        <p v-if="issue.candidateDesign"><b>候选设计（非既定事实）：</b>{{ issue.candidateDesign }}</p>
        <p v-if="issue.impact"><b>影响：</b>{{ issue.impact }}</p>
        <p v-for="decision in round.judgment?.decisions.filter(d => d.issueId === issue.id) ?? []" :key="decision.issueId"><b>C · {{ labels[decision.verdict] }}：</b>{{ decision.reason }}</p>
      </article>
      <ul v-if="round.judgment?.changeSummary.length"><li v-for="change in round.judgment.changeSummary" :key="change">{{ change }}</li></ul>
      <details v-if="round.afterManuscriptId && round.judgment?.content"><summary>正文修改对比</summary><div class="draft-loop-diff"><div><strong>修订前</strong><pre>{{ draftChange(round.before.body, round.judgment.content.body).before }}</pre></div><div><strong>修订后</strong><pre>{{ draftChange(round.before.body, round.judgment.content.body).after }}</pre></div></div></details>
    </details>
  </section>
</template>

<style scoped>
.draft-loop-panel { border-block: 1px solid #dce3e1; padding: 16px 0; margin-bottom: 20px; scroll-margin-top: 88px; }
header, header span, .draft-loop-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
header { justify-content: space-between; margin-bottom: 12px; }
header span { font-size: 13px; color: #48615a; }
fieldset { display: flex; flex-wrap: wrap; gap: 12px; border: 0; padding: 0; margin: 0; }
legend { font-size: 12px; color: #697b75; margin-bottom: 4px; }
fieldset label { display: flex; align-items: center; gap: 5px; }
.draft-loop-limit { display: grid; gap: 4px; font-size: 12px; }
.draft-loop-limit input { width: 72px; height: 34px; }
.draft-loop-history { display: grid; gap: 6px; margin-top: 12px; }
select { max-width: 100%; }
.draft-loop-round { border-top: 1px solid #dce3e1; margin-top: 12px; padding-top: 12px; overflow-wrap: anywhere; }
summary { cursor: pointer; }
.draft-loop-issue { padding: 12px 0; border-bottom: 1px solid #e6ebe9; }
blockquote { margin: 8px 0; border-left: 3px solid #859b91; padding-left: 12px; white-space: pre-wrap; }
.draft-loop-diff { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 12px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; font-size: 14px; }
.draft-loop-spinner { animation: draft-loop-spin 1s linear infinite; }
@keyframes draft-loop-spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) { .draft-loop-diff { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { .draft-loop-spinner { animation: none; } }
</style>

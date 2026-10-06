<script setup lang="ts">
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { FileSearch, WandSparkles } from 'lucide-vue-next'
import type { ModelProvider } from '@/api/planning'
import { checkStylePreview, reviseStylePreview, stylePreviewKey, type StylePreview, type StylePreviewCheckInput, type StylePreviewReview } from '@/api/writingQuality'

const props = defineProps<{ projectId: string; value: StylePreview; current: boolean; externalBusy: boolean; instruction: string }>()
const emit = defineEmits<{ revised: [value: StylePreview]; 'busy-change': [busy: boolean] }>()
const client = useQueryClient()
const { provider: provider } = useGlobalModelSettings()
const selected = ref<string[]>([])
const key = computed(() => ['style-preview-review', props.projectId, stylePreviewKey(props.value)])
interface State { report: StylePreviewReview | null; error: string; attempted: boolean }
const state = useQuery<State>({ queryKey: key, enabled: false, queryFn: async () => ({ report: null, error: '', attempted: false }),
  initialData: () => ({ report: null, error: '', attempted: false }) })
const labels: Record<string, string> = { STYLE: '风格', FLUENCY: '语义与通顺', LOGIC: '逻辑与依据', SCENE: '细节与场景' }
const check = useMutation({
  mutationFn: (v: { projectId: string; key: string[]; input: StylePreviewCheckInput }) => checkStylePreview(v.projectId, v.input),
  onSuccess: (report, v) => { client.setQueryData(v.key, { report, error: '', attempted: true }); selected.value = [] },
  onError: (e: Error, v) => { client.setQueryData<State>(v.key, (previous) => ({ report: previous?.report ?? null, error: e.message, attempted: true })) },
})
const revise = useMutation({
  mutationFn: (v: { projectId: string; key: string[]; id: string; provider: ModelProvider; ids: string[]; instruction: string }) =>
    reviseStylePreview(v.projectId, v.id, v.provider, v.ids, v.instruction),
  onSuccess: (value, v) => { if (v.projectId === props.projectId) emit('revised', value) },
  onError: (e: Error, v) => { client.setQueryData<State>(v.key, (previous) => ({ report: previous?.report ?? null,
    error: `${e.message}；原样例保留，请重新检查后再修订。`, attempted: true })) },
})
const busy = computed(() => check.isPending.value || revise.isPending.value)
const disabled = computed(() => busy.value || props.externalBusy || !props.current)
watch(busy, (value) => emit('busy-change', value))
watch(() => state.data.value?.report?.id, () => { selected.value = [] })
onUnmounted(() => emit('busy-change', false))
function runCheck() {
  if (!props.current || busy.value) return
  client.setQueryData<State>(key.value, (previous) => ({ report: previous?.report ?? null, error: '', attempted: true }))
  check.mutate({ projectId: props.projectId, key: [...key.value], input: {
    source: { outlineVersionId: props.value.sourceOutlineVersionId, expectedOutlineVersion: props.value.sourceOutlineRowVersion,
      profile: props.value.profile, provider: provider.value, targetWords: props.value.targetWords, instruction: props.instruction.trim() },
    content: props.value.content,
  } })
}
function runRevision() {
  const report = state.data.value?.report
  if (!report || report.revisionAttempted || !selected.value.length || disabled.value || provider.value === 'LOCAL_TEMPLATE') return
  client.setQueryData<State>(key.value, { ...state.data.value!, report: { ...report, revisionAttempted: true }, error: '' })
  revise.mutate({ projectId: props.projectId, key: [...key.value], id: report.id, provider: provider.value,
    ids: [...selected.value], instruction: props.instruction.trim() })
}
function autoCheck() {
  if (props.current && !state.data.value?.attempted && client.getQueryData(['style-preview-auto-check', props.projectId, stylePreviewKey(props.value)])) runCheck()
}
onMounted(autoCheck)
watch(() => props.current, autoCheck)
</script>

<template>
  <section class="preview-editor">
    <header><h5>试写编辑检查</h5><div class="editor-actions">
      <GlobalModelBadge />
      <button type="button" class="button secondary" :disabled="disabled" @click="runCheck"><FileSearch :size="16" />{{ check.isPending.value ? '正在检查…' : '检查试写' }}</button>
    </div></header>
    <p v-if="!current" class="form-error">大纲已更新，旧样例不能检查或修订。</p>
    <p v-if="state.data.value?.error" class="form-error" role="alert">{{ state.data.value.error }}</p>
    <template v-if="state.data.value?.report">
      <p>{{ state.data.value.report.content.summary }}</p>
      <p v-if="state.data.value.report.reviewMode === 'RULES'" class="editor-status">仅完成有限文本规则检查，未判断语义逻辑。</p>
      <dl><div v-for="score in state.data.value.report.content.scores" :key="score.dimension"><dt>{{ labels[score.dimension] }} · {{ score.score == null ? '未评分' : `${score.score}/100` }}</dt><dd>{{ score.rationale }}</dd></div></dl>
      <div v-for="issue in state.data.value.report.content.issues" :key="issue.id" class="editor-issue">
        <label><input v-model="selected" type="checkbox" :value="issue.id" :disabled="disabled || state.data.value.report.revisionAttempted" /><strong>{{ labels[issue.category] }} · {{ issue.description }}</strong></label>
        <blockquote>{{ issue.evidence }}</blockquote><p>{{ issue.suggestion }}</p>
      </div>
      <p v-if="!state.data.value.report.content.issues.length">本次检查未提出修改建议。</p>
      <p v-if="state.data.value.report.revisionAttempted" class="editor-status">本报告已尝试修订，再次修改前需重新检查。</p>
      <button v-if="state.data.value.report.content.issues.length" type="button" class="button primary"
        :disabled="disabled || !selected.length || state.data.value.report.revisionAttempted || provider === 'LOCAL_TEMPLATE'" @click="runRevision">
        <WandSparkles :size="16" />{{ revise.isPending.value ? '正在修订…' : '按选中建议生成修订样例' }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.preview-editor { display: grid; gap: 12px; border-top: 1px solid #ddd; padding-top: 16px; min-width: 0; }
header, .editor-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
header { justify-content: space-between; }
h5, p, dl { margin: 0; }
h5 { font-size: 14px; }
select { max-width: 100%; padding: 8px; border: 1px solid #cbd2d9; border-radius: 4px; background: white; font: inherit; }
dl { display: grid; gap: 12px; font-size: 13px; }
dt { font-weight: 600; }
dd { margin: 4px 0 0; }
.editor-issue { border-top: 1px solid #e2e5e8; padding-top: 12px; }
label { display: flex; align-items: baseline; gap: 8px; }
input { flex: 0 0 auto; }
blockquote { margin: 10px 0; border-left: 3px solid #bbb; padding-left: 10px; white-space: pre-wrap; }
p, dd, strong, blockquote { overflow-wrap: anywhere; }
.editor-status { font-size: 13px; color: #52606b; }
</style>

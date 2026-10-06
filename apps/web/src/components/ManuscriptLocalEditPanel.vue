<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useMutation } from '@tanstack/vue-query'
import { RefreshCw, Scissors } from 'lucide-vue-next'
import { ApiError } from '@/api/http'
import { editManuscriptSelection, type ManuscriptLocalEditInput, type ManuscriptLocalEditResult } from '@/api/manuscriptLocalEdit'
import type { ManuscriptVersion } from '@/api/writing'
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'

const props = withDefaults(defineProps<{ projectId: string; source: ManuscriptVersion | null; externalBusy?: boolean }>(), { externalBusy: false })
const emit = defineEmits<{ drafted: [value: ManuscriptVersion]; refreshRequested: []; 'busy-change': [value: boolean] }>()
const { provider } = useGlobalModelSettings()
const selection = ref('')
const occurrence = ref(1)
const instruction = ref('')
const authorized = ref(false)
const error = ref('')
const stale = ref(false)
const result = ref<ManuscriptLocalEditResult | null>(null)
const session = ref(0)
watch(() => [props.projectId, props.source?.id, props.source?.version, props.source?.content.body], () => {
  const draft = result.value?.manuscript
  const adoptedResult = draft && draft.projectId === props.projectId && props.source?.projectId === props.projectId
    && props.source.id === draft.id && props.source.version === draft.version && props.source.content.body === draft.content.body
  session.value++
  selection.value = ''; occurrence.value = 1; instruction.value = ''; authorized.value = false
  error.value = ''; stale.value = false
  if (!adoptedResult) result.value = null
}, { flush: 'sync' })

const matches = computed(() => {
  const text = selection.value
  const body = props.source?.content.body ?? ''
  const offsets: number[] = []
  if (!text) return offsets
  for (let offset = body.indexOf(text); offset >= 0; offset = body.indexOf(text, offset + 1)) offsets.push(offset)
  return offsets
})
const offset = computed(() => matches.value[occurrence.value - 1])
interface Variables { projectId: string; chapter: number; session: number; input: ManuscriptLocalEditInput }
const mutation = useMutation({
  mutationFn: (variables: Variables) => editManuscriptSelection(variables.projectId, variables.chapter, variables.input),
  onSuccess: (value, variables) => {
    if (variables.session !== session.value || variables.projectId !== props.projectId) return
    result.value = value
    error.value = ''
    authorized.value = false
    if (value.manuscript) emit('drafted', value.manuscript)
  },
  onError: (failure: Error, variables) => {
    if (variables.session !== session.value || variables.projectId !== props.projectId) return
    stale.value = failure instanceof ApiError && failure.status === 409
    error.value = stale.value ? '源稿或写作依据已变化，请重新读取原稿。'
      : failure instanceof ApiError && failure.status === 403 ? '无权修改此项目正文。' : failure.message
  },
})
const busy = computed(() => mutation.isPending.value && mutation.variables.value?.session === session.value)
watch(busy, value => emit('busy-change', value), { immediate: true })
onUnmounted(() => emit('busy-change', false))
const blocked = computed(() => busy.value || props.externalBusy)
const valid = computed(() => !!props.source && props.source.projectId === props.projectId && offset.value !== undefined
  && selection.value.trim().length > 0 && selection.value.length <= 12000 && Number.isInteger(occurrence.value)
  && instruction.value.trim().length > 0 && instruction.value.length <= 2000 && authorized.value && !stale.value && !blocked.value)

function selectText(event: Event) {
  if (blocked.value) return
  const textarea = event.target as HTMLTextAreaElement
  if (textarea.selectionStart === textarea.selectionEnd) return
  selection.value = textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)
  occurrence.value = matches.value.indexOf(textarea.selectionStart) + 1
  result.value = null
}
function submit() {
  if (!valid.value || blocked.value || !props.source) return
  error.value = ''; result.value = null
  mutation.mutate({ projectId: props.projectId, chapter: props.source.chapterNumber, session: session.value, input: {
    sourceManuscriptId: props.source.id, sourceRowVersion: props.source.version, selection: selection.value,
    occurrence: occurrence.value, offset: offset.value, provider: provider.value, instruction: instruction.value.trim(), authorized: true,
  } })
}
</script>

<template>
  <section class="local-edit-panel" aria-label="正文局部编辑" :aria-busy="blocked">
    <header><h3>局部编辑</h3><GlobalModelBadge /></header>
    <p v-if="!source" role="status">尚未选择正文版本</p>
    <form v-else @submit.prevent="submit">
      <label><span>原稿（第 {{ source.versionNumber }} 版）</span><textarea :value="source.content.body" readonly rows="8" aria-label="原稿正文" @select="selectText" /></label>
      <label><span>精确选区</span><textarea v-model="selection" maxlength="12000" rows="4" :disabled="blocked || stale" @input="occurrence = 1; result = null" /></label>
      <label class="occurrence"><span>第几处匹配</span><input v-model.number="occurrence" type="number" min="1" :max="Math.max(1, matches.length)" :disabled="blocked || stale" /></label>
      <p v-if="selection && offset === undefined" class="form-error" role="alert">选区与原稿不匹配</p>
      <label><span>修改要求</span><textarea v-model="instruction" required maxlength="2000" rows="3" :disabled="blocked || stale" /></label>
      <label class="authorization"><input v-model="authorized" type="checkbox" :disabled="blocked || stale" /><span>授权局部编辑并创建新草稿</span></label>
      <button class="button primary" type="submit" :disabled="!valid || blocked"><Scissors :size="16" />{{ busy ? '正在局部编辑…' : '生成局部编辑草稿' }}</button>
    </form>
    <div v-if="result?.assessment === 'DRAFT_CREATED'" class="local-diff" aria-label="局部编辑差异">
      <div><h4>原选区</h4><del>{{ result.selection }}</del></div>
      <div><h4>修改段落</h4><ins>{{ result.replacement }}</ins></div>
    </div>
    <p v-if="result" role="status">{{ result.message }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <button v-if="stale" type="button" class="button secondary" :disabled="blocked" @click="emit('refreshRequested')"><RefreshCw :size="16" />重新读取原稿</button>
  </section>
</template>

<style scoped>
.local-edit-panel, form, label { display: grid; gap: 10px; min-width: 0; }
.local-edit-panel { gap: 16px; padding-top: 18px; border-top: 1px solid #d8dce0; }
header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; }
h3, h4, p { margin: 0; }
h3 { font-size: 16px; } h4 { font-size: 14px; }
textarea, input[type=number] { width: 100%; min-width: 0; box-sizing: border-box; padding: 10px 12px; border: 1px solid #cbd2d9; border-radius: 4px; background: #fff; color: #23333d; font: inherit; line-height: 1.7; }
textarea { resize: vertical; }
textarea:focus-visible, input:focus-visible { outline: 2px solid #176b63; outline-offset: 2px; }
.occurrence { max-width: 180px; }
.authorization { display: flex; align-items: center; gap: 8px; }
.authorization input { width: auto; flex-shrink: 0; }
.local-diff { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.local-diff > div { min-width: 0; display: grid; align-content: start; gap: 8px; }
del, ins { display: block; white-space: pre-wrap; overflow-wrap: anywhere; padding: 12px; }
del { background: #fff1f2; color: #9f2938; } ins { background: #edf8f3; color: #176b63; text-decoration: none; }
p, span { overflow-wrap: anywhere; }
button { justify-self: start; }
@media (max-width: 640px) { .local-diff { grid-template-columns: 1fr; } }
</style>

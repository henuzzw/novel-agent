<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { BookOpen, Check, RefreshCw, Save, Sparkles } from 'lucide-vue-next'
import { ApiError, getCreativeStrategy, updateCreativeStrategy, type CreativeStrategy, type CreativeStrategySettings, type ProjectSummary, type UpdateCreativeStrategyInput } from '@/api/projects'

const props = defineProps<{ projectId: string }>()
const client = useQueryClient()
const draft = ref<CreativeStrategy>('STANDARD')
const baseline = ref<CreativeStrategySettings | null>(null)
const error = ref('')
const conflict = ref(false)
const saved = ref(false)
const reloading = ref(false)
let session = 0

watch(() => props.projectId, () => {
  session += 1
  baseline.value = null
  draft.value = 'STANDARD'
  error.value = ''
  conflict.value = false
  saved.value = false
  reloading.value = false
}, { flush: 'sync' })

const query = useQuery({
  queryKey: computed(() => ['creative-strategy', props.projectId]),
  queryFn: ({ queryKey }) => getCreativeStrategy(String(queryKey[1])),
  retry: false,
})

function load(value: CreativeStrategySettings) {
  baseline.value = { ...value }
  draft.value = value.strategy
}
watch([() => props.projectId, query.data], ([, value]) => { if (value && !baseline.value) load(value) }, { immediate: true })
watch(draft, () => { saved.value = false })

interface SaveVariables {
  projectId: string
  session: number
  input: UpdateCreativeStrategyInput
}
const save = useMutation({
  mutationFn: (variables: SaveVariables) => updateCreativeStrategy(variables.projectId, variables.input),
  onSuccess: (value, variables) => {
    client.setQueryData<CreativeStrategySettings>(['creative-strategy', variables.projectId], (previous) =>
      previous && previous.version > value.version ? previous : value)
    client.setQueryData<ProjectSummary>(['project', variables.projectId], (previous) =>
      previous && previous.version <= value.version ? { ...previous, creativeStrategy: value.strategy, version: value.version } : previous)
    void client.invalidateQueries({ queryKey: ['project', variables.projectId] })
    void client.invalidateQueries({ queryKey: ['projects'] })
    if (variables.projectId !== props.projectId || variables.session !== session) return
    load(value)
    error.value = ''
    conflict.value = false
    saved.value = true
  },
  onError: (failure: Error, variables) => {
    if (variables.projectId !== props.projectId || variables.session !== session) return
    conflict.value = failure instanceof ApiError && failure.status === 409
    error.value = conflict.value ? '创作策略已更新，请重新读取后再保存。' : failure.message
  },
})
const saving = computed(() => save.isPending.value && save.variables.value?.session === session && save.variables.value?.projectId === props.projectId)
const busy = computed(() => saving.value || reloading.value || query.isFetching.value)
const changed = computed(() => !!baseline.value && draft.value !== baseline.value.strategy)
const canSave = computed(() => changed.value && !busy.value && !query.isError.value && !conflict.value)

function submit() {
  if (!canSave.value || !baseline.value) return
  error.value = ''
  saved.value = false
  save.mutate({ projectId: props.projectId, session, input: { strategy: draft.value, version: baseline.value.version } })
}
async function reload() {
  if (busy.value) return
  const projectId = props.projectId
  const requestSession = session
  saved.value = false
  reloading.value = true
  try {
    const result = await query.refetch()
    if (projectId !== props.projectId || requestSession !== session || !result.isSuccess || !result.data) return
    load(result.data)
    error.value = ''
    conflict.value = false
  } finally {
    if (projectId === props.projectId && requestSession === session) reloading.value = false
  }
}
</script>

<template>
  <section class="creative-strategy-panel" aria-label="项目创作策略" :aria-busy="busy">
    <header>
      <h2>创作策略</h2>
      <button type="button" class="icon-button" title="重新读取创作策略" aria-label="重新读取创作策略" :disabled="busy" @click="reload"><RefreshCw :size="16" /></button>
    </header>
    <p v-if="query.isPending.value" role="status">正在读取创作策略…</p>
    <div v-if="query.isError.value" class="strategy-error">
      <p class="form-error" role="alert">{{ query.error.value?.message }}</p>
      <button type="button" class="button secondary" :disabled="busy" @click="reload"><RefreshCw :size="16" />重试读取创作策略</button>
    </div>
    <form v-if="baseline" @submit.prevent="submit">
      <fieldset :disabled="busy || query.isError.value || conflict" aria-label="创作策略">
        <div class="strategy-segment" role="radiogroup" aria-label="创作策略">
          <label :class="{ selected: draft === 'STANDARD' }">
            <input v-model="draft" type="radio" name="creative-strategy" value="STANDARD" />
            <BookOpen :size="18" aria-hidden="true" /><span>标准创作</span>
          </label>
          <label :class="{ selected: draft === 'FANQIE_GRIPPING' }">
            <input v-model="draft" type="radio" name="creative-strategy" value="FANQIE_GRIPPING" />
            <Sparkles :size="18" aria-hidden="true" /><span>番茄强开篇</span>
          </label>
        </div>
      </fieldset>
      <div class="strategy-actions">
        <button type="submit" class="button primary" :disabled="!canSave"><Save :size="16" />{{ saving ? '正在保存…' : '保存创作策略' }}</button>
        <span v-if="saved && !changed" class="save-status" role="status"><Check :size="15" />已保存</span>
      </div>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.creative-strategy-panel { display: grid; gap: 16px; min-width: 0; padding-bottom: 24px; border-bottom: 1px solid #d8dce0; }
header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
h2 { margin: 0; font-size: 16px; }
form, .strategy-error { display: grid; gap: 14px; min-width: 0; }
fieldset { min-width: 0; padding: 0; margin: 0; border: 0; }
.strategy-segment { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.strategy-segment label { display: flex; align-items: center; gap: 8px; min-width: 0; min-height: 44px; padding: 10px 12px; border: 1px solid #cbd2d9; border-radius: 4px; cursor: pointer; }
.strategy-segment label.selected { border-color: #176b63; background: #f3faf8; }
.strategy-segment label:focus-within { outline: 2px solid #176b63; outline-offset: 2px; }
.strategy-segment input { width: auto; margin: 0; accent-color: #176b63; }
.strategy-segment svg { flex-shrink: 0; }
fieldset:disabled label { opacity: 0.65; cursor: default; }
.strategy-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.strategy-error button { justify-self: start; }
p { margin: 0; }
p, span { overflow-wrap: anywhere; }
@media (max-width: 480px) { .strategy-segment { grid-template-columns: 1fr; } }
</style>

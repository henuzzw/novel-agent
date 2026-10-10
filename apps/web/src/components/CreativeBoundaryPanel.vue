<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { RefreshCw, RotateCcw, Save } from 'lucide-vue-next'
import CreativeBoundaryFields from '@/components/CreativeBoundaryFields.vue'
import { ApiError, getCreativeBoundaries, updateCreativeBoundaries, type CreativeBoundarySettings, type WorldMode, type AdaptationScope } from '@/api/projects'
import { adaptationLabel, worldLabel } from '@/lib/creative-boundaries'

const props = defineProps<{ projectId: string }>()
const client = useQueryClient()
const world = ref<WorldMode>('AUTO')
const scope = ref<AdaptationScope>('AUTO')
const baseline = ref<CreativeBoundarySettings>()
const error = ref('')
const saved = ref(false)
const changed = computed(() => !!baseline.value && (world.value !== baseline.value.worldMode || scope.value !== baseline.value.adaptationScope))
const query = useQuery({
  queryKey: computed(() => ['creative-boundaries', props.projectId]),
  queryFn: ({ queryKey }) => getCreativeBoundaries(String(queryKey[1])), retry: false,
  refetchInterval: state => state.state.data?.worldMode === 'AUTO' && !state.state.data?.assessed ? 15000 : false,
})
function load(value: CreativeBoundarySettings) { baseline.value = value; world.value = value.worldMode; scope.value = value.adaptationScope }
watch(query.data, value => { if (value && !changed.value) load(value) }, { immediate: true })
watch([world, scope], () => { saved.value = false })
const save = useMutation({
  mutationFn: (variables: { projectId: string; input: Parameters<typeof updateCreativeBoundaries>[1] }) => updateCreativeBoundaries(variables.projectId, variables.input),
  onSuccess: (value, variables) => {
    client.setQueryData(['creative-boundaries', variables.projectId], value)
    void client.invalidateQueries({ queryKey: ['project', variables.projectId] })
    void client.invalidateQueries({ queryKey: ['creative-strategy', variables.projectId] })
    if (props.projectId !== variables.projectId) return
    load(value); error.value = ''; saved.value = true
  },
  onError: (failure: Error) => { error.value = failure instanceof ApiError && failure.status === 409 ? '项目设置已变化，请重新读取后保存。' : failure.message },
})
const busy = computed(() => save.isPending.value || query.isFetching.value)
function submit(resetAssessment = false) {
  if (!baseline.value || busy.value) return
  error.value = ''; saved.value = false
  save.mutate({ projectId: props.projectId, input: { worldMode: world.value, adaptationScope: scope.value, version: baseline.value.version, resetAssessment } })
}
async function reload() { const result = await query.refetch(); if (result.data) { load(result.data); error.value = ''; saved.value = false } }
</script>

<template>
  <section class="boundary-panel" aria-label="世界设定与改编幅度" :aria-busy="busy">
    <header><h2>世界设定与改编幅度</h2><button class="icon-button" type="button" title="重新读取设置" aria-label="重新读取设置" :disabled="busy" @click="reload"><RefreshCw :size="16" /></button></header>
    <p v-if="query.isPending.value" role="status">正在读取设置…</p>
    <p v-if="query.isError.value" class="form-error" role="alert">{{ query.error.value?.message }}</p>
    <template v-if="baseline">
      <CreativeBoundaryFields v-model:world-mode="world" v-model:adaptation-scope="scope" :disabled="busy" />
      <dl>
        <dt>实际采用</dt><dd>{{ worldLabel(baseline.effectiveWorld) }} · {{ adaptationLabel(baseline.effectiveScope) }}</dd>
        <dt v-if="baseline.worldMode === 'AUTO'">判断状态</dt><dd v-if="baseline.worldMode === 'AUTO'">{{ baseline.assessed ? '已判断' : '待完整解析，暂用现实写实' }}</dd>
        <dt v-if="baseline.reason">判断依据</dt><dd v-if="baseline.reason">{{ baseline.reason }}</dd>
      </dl>
      <div class="actions">
        <button class="button primary" type="button" :disabled="busy || !changed" @click="submit()"><Save :size="16" />{{ save.isPending.value ? '正在保存…' : '保存设置' }}</button>
        <button v-if="baseline.assessed" class="icon-button" type="button" title="清除判断，下次完整原文解析时重新判断；不重写已有内容" aria-label="清除自动判断" :disabled="busy" @click="submit(true)"><RotateCcw :size="17" /></button>
        <span v-if="saved && !changed" role="status">已保存</span>
      </div>
    </template>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.boundary-panel { display: grid; gap: 16px; min-width: 0; padding-bottom: 24px; border-bottom: 1px solid #d8dce0; }
header, .actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
header { justify-content: space-between; }
h2 { font-size: 16px; margin: 0; }
p { margin: 0; }
dl { display: grid; grid-template-columns: 80px minmax(0, 1fr); gap: 8px 12px; margin: 0; font-size: 14px; }
dt { color: #59656b; } dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
</style>

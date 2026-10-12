<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, Play, RefreshCw } from 'lucide-vue-next'
import { resumeImportedSnowflake, type WorkImport } from '@/api/imports'
import { confirmSnowflakePlan, getLatestSnowflakePlan } from '@/api/snowflake'
import { getPlanningSnapshot } from '@/api/planning'
import { generationRequests } from '@/lib/generation-activity'

const props = defineProps<{ projectId: string; importId?: string; planningStatus?: WorkImport['planningStatus'] }>()
const emit = defineEmits<{ planningGenerated: []; planningConfirmed: [] }>()
const queryClient = useQueryClient()
const resumeError = ref('')
const pending = computed(() => generationRequests.value.some(item => item.projectId === props.projectId
  && ['IMPORT_PLANNING', 'SNOWFLAKE_PLANNING'].includes(item.stage) && item.status === 'RUNNING'))
const query = useQuery({
  queryKey: computed(() => ['snowflake-plan', props.projectId]),
  queryFn: () => getLatestSnowflakePlan(props.projectId),
  refetchInterval: state => pending.value || state.state.data?.status === 'RUNNING' ? 3000 : false,
})
watch(pending, () => query.refetch())
const canResume = computed(() => props.importId && props.importId === query.data.value?.sourceImportId
  && ['FAILED', 'CANCELLED'].includes(props.planningStatus ?? '')
  && ['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(query.data.value?.status ?? '') && !pending.value)
const snapshotQuery = useQuery({
  queryKey: computed(() => ['planning-snapshot', props.projectId]),
  queryFn: () => getPlanningSnapshot(props.projectId),
})
const isConfirmed = computed(() => Boolean(query.data.value
  && snapshotQuery.data.value?.sourceSnowflakeId === query.data.value.id))
const hasInvalidSteps = computed(() => Object.keys(query.data.value?.invalidSteps ?? {}).length > 0)
const canConfirm = computed(() => query.data.value?.status === 'SUCCEEDED' && !pending.value
  && (!props.importId || query.data.value.sourceImportId === props.importId) && !isConfirmed.value && !hasInvalidSteps.value)
const confirmMutation = useMutation({
  mutationFn: async (source: { projectId: string; planId: string }) => ({
    ...source, result: await confirmSnowflakePlan(source.projectId, source.planId),
  }),
  onSuccess: value => {
    queryClient.setQueryData(['planning-snapshot', value.projectId], value.result)
    for (const key of ['project', 'planning-characters', 'character-profiles', 'canon-entities', 'character-names', 'reader-experience-plans']) {
      queryClient.invalidateQueries({ queryKey: [key, value.projectId] })
    }
    if (props.projectId === value.projectId && query.data.value?.id === value.planId) emit('planningConfirmed')
  },
  onError: (error: Error, source) => {
    if (props.projectId === source.projectId && query.data.value?.id === source.planId) resumeError.value = error.message
  },
})
function confirm() {
  if (!canConfirm.value || !query.data.value || confirmMutation.isPending.value) return
  resumeError.value = ''
  confirmMutation.mutate({ projectId: props.projectId, planId: query.data.value.id })
}
const resumeMutation = useMutation({
  mutationFn: async (source: { projectId: string; importId: string; planId: string }) => ({
    ...source, result: await resumeImportedSnowflake(source.projectId, source.importId, source.planId),
  }),
  onSuccess: value => {
    queryClient.setQueryData(['snowflake-plan', value.projectId], value.result)
    queryClient.invalidateQueries({ queryKey: ['project', value.projectId] })
    if (props.projectId === value.projectId && props.importId === value.importId) emit('planningGenerated')
  },
  onError: (error: Error, source) => {
    if (props.projectId === source.projectId && props.importId === source.importId) resumeError.value = error.message
  },
  onSettled: (_data, _error, source) => {
    queryClient.invalidateQueries({ queryKey: ['work-imports', source.projectId] })
    queryClient.invalidateQueries({ queryKey: ['snowflake-plan', source.projectId] })
  },
})
watch(() => [props.projectId, props.importId], () => { resumeMutation.reset(); confirmMutation.reset(); resumeError.value = '' })
function resume() {
  const plan = query.data.value
  if (!canResume.value || !plan || !props.importId || resumeMutation.isPending.value) return
  resumeError.value = ''
  resumeMutation.mutate({ projectId: props.projectId, importId: props.importId, planId: plan.id })
}
const stages = [
  { key: 'CORE', label: '1 · 一句话故事核心' },
  { key: 'SYNOPSIS', label: '2 · 一段故事梗概' },
  { key: 'CHARACTER_ARCS', label: '3 · 主要角色与弧线' },
  { key: 'PLOT_SUMMARY', label: '4 · 数页情节概要' },
  { key: 'CHARACTER_BIOGRAPHIES', label: '5 · 人物经历与各自故事线' },
  { key: 'DETAILED_OUTLINE', label: '6 · 详细故事发展' },
  { key: 'CHARACTER_SETTINGS', label: '7 · 完整人物设定' },
  { key: 'SCENE_LIST', label: '8 · 全部场景清单' },
  { key: 'SCENE_EXPANSION', label: '9 · 关键场景展开（可选）' },
] as const
const statusNames = { RUNNING: '请求中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已停止' }
</script>

<template>
  <section v-if="query.data.value || query.isError.value" class="snowflake-panel" aria-label="雪花渐进规划">
    <header>
      <h3>雪花渐进规划</h3>
      <span v-if="query.data.value" role="status">{{ statusNames[query.data.value.status] }}</span>
      <span v-if="isConfirmed" role="status">已确认</span>
      <span v-else-if="query.data.value?.status === 'SUCCEEDED' && !hasInvalidSteps">待确认</span>
      <button v-if="canConfirm || confirmMutation.isPending.value" class="button primary" type="button"
        :disabled="confirmMutation.isPending.value || pending" @click="confirm">
        <Check :size="16" />{{ confirmMutation.isPending.value ? '正在确认…' : '确认雪花规划' }}
      </button>
      <button v-if="canResume || resumeMutation.isPending.value" class="button primary" type="button"
        :disabled="resumeMutation.isPending.value || pending" title="复用有效步骤；资料读取失败的步骤及后续依赖会重新生成" @click="resume">
        <Play :size="16" />{{ resumeMutation.isPending.value ? '正在继续…' : '继续生成' }}
      </button>
      <button class="icon-button" type="button" aria-label="刷新雪花规划" title="刷新雪花规划" :disabled="query.isFetching.value" @click="query.refetch()"><RefreshCw :size="16" /></button>
    </header>
    <p v-if="query.isError.value" class="form-error" role="alert">规划记录加载失败：{{ query.error.value?.message }}</p>
    <p v-if="resumeError" class="form-error" role="alert">{{ resumeError }}</p>
    <template v-if="query.data.value">
      <p v-if="query.data.value.errorMessage" class="form-error" role="alert">{{ query.data.value.errorMessage }}</p>
      <p v-if="hasInvalidSteps" class="form-error" role="alert">部分步骤未获得创作资料，不是有效底稿。{{ query.data.value.status === 'SUCCEEDED' ? '请重新生成规划。' : '继续生成将从第一个无效步骤重做，保留此前有效内容。' }}</p>
      <details v-for="stage in stages" :key="stage.key">
        <summary><span>{{ stage.label }}</span><small>{{ query.data.value.invalidSteps?.[stage.key] ? '需重新生成' : query.data.value.steps?.[stage.key] ? '成功' : query.data.value.activeStage === stage.key ? statusNames[query.data.value.status] : stage.key === 'SCENE_EXPANSION' && query.data.value.status === 'SUCCEEDED' ? '未启用' : '待生成' }}</small></summary>
        <div v-if="query.data.value.steps?.[stage.key]" class="snowflake-text">{{ query.data.value.steps[stage.key] }}</div>
      </details>
    </template>
  </section>
</template>

<style scoped>
.snowflake-panel { border-block: 1px solid var(--border-color, #d9e1e3); margin-block: 18px; padding-block: 12px; }
header { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
h3 { font-size: 16px; margin: 0; margin-right: auto; }
header > span, small { color: #59656b; font-size: 13px; }
details { border-top: 1px solid var(--border-color, #d9e1e3); margin-top: 12px; }
summary { cursor: pointer; padding-block: 12px; }
summary small { margin-left: 12px; }
.snowflake-text { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.75; max-height: 440px; overflow: auto; padding-bottom: 12px; }
.icon-button { display: inline-flex; padding: 6px; border: 0; background: transparent; cursor: pointer; color: inherit; }
</style>

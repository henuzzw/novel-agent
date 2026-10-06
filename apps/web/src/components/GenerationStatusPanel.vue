<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { CheckCircle2, CircleAlert, Clock3, LoaderCircle, RefreshCw, Square } from 'lucide-vue-next'
import { listAgentRuns, stopGeneration } from '@/api/agentRuns'
import { generationRows, markGenerationStopping, type GenerationActivity } from '@/lib/generation-activity'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ busy: [value: boolean]; openTasks: [] }>()
const query = useQuery({
  queryKey: computed(() => ['agent-runs', props.projectId]),
  queryFn: () => listAgentRuns(props.projectId), refetchInterval: 5000, retry: false,
})
const rows = computed(() => generationRows(props.projectId, query.data.value ?? []))
const stopping = ref<Record<string, boolean>>({})
const stopErrors = ref<Record<string, string>>({})
watch(() => props.projectId, () => { stopping.value = {}; stopErrors.value = {} })
async function stop(activity: GenerationActivity) {
  const projectId = props.projectId
  if (stopping.value[activity.id] || activity.stopRequested) return
  stopping.value = { ...stopping.value, [activity.id]: true }
  stopErrors.value = { ...stopErrors.value, [activity.id]: '' }
  try {
    await stopGeneration(projectId, activity.id, activity.source)
    if (props.projectId !== projectId) return
    if (activity.source === 'request') markGenerationStopping(activity.id)
    await query.refetch()
  } catch (error) {
    if (props.projectId !== projectId) return
    stopping.value = { ...stopping.value, [activity.id]: false }
    stopErrors.value = { ...stopErrors.value, [activity.id]: error instanceof Error ? error.message : '停止失败，请重试。' }
    await query.refetch()
  }
}
const busy = computed(() => rows.value.some(row => row.activity?.status === 'RUNNING'))
watch(busy, value => emit('busy', value), { immediate: true })
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { clearInterval(timer) })
function elapsed(activity: GenerationActivity) {
  const seconds = Math.max(0, Math.floor(((activity.completedAt ? Date.parse(activity.completedAt) : now.value) - Date.parse(activity.startedAt)) / 1000))
  if (!Number.isFinite(seconds)) return ''
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}
function status(activity: GenerationActivity | null) {
  if (!activity) return query.isPending.value ? '读取中' : query.isError.value ? '未知' : '暂无记录'
  if (activity.status === 'RUNNING') return activity.stopRequested || stopping.value[activity.id] ? '正在停止' : '请求中'
  if (activity.status === 'FAILED') return '失败'
  if (activity.status === 'CANCELLED') return '已停止'
  return '成功'
}
</script>

<template>
  <section class="generation-status" aria-label="生成状态">
    <header>
      <strong>生成状态</strong>
      <span v-if="busy" class="generation-current"><LoaderCircle :size="14" class="generation-spin" />请求中</span>
      <button type="button" class="generation-tasks" @click="emit('openTasks')">查看任务</button>
      <button type="button" class="icon-button" title="刷新生成状态" aria-label="刷新生成状态" :disabled="query.isFetching.value" @click="query.refetch()"><RefreshCw :size="15" /></button>
    </header>
    <p v-if="query.isError.value" class="generation-error" role="alert">任务状态读取失败：{{ query.error.value?.message }}</p>
    <div class="generation-grid">
      <div v-for="row in rows" :key="row.stage" class="generation-stage" :data-stage="row.stage" :aria-busy="row.activity?.status === 'RUNNING'">
        <strong>{{ row.label }}<small v-if="row.activity?.chapter"> · 第 {{ row.activity.chapter }} 章</small></strong>
        <div :class="['generation-result', row.activity?.status.toLowerCase()]">
          <LoaderCircle v-if="row.activity?.status === 'RUNNING'" :size="14" class="generation-spin" />
          <CircleAlert v-else-if="row.activity?.status === 'FAILED'" :size="14" />
          <CheckCircle2 v-else-if="row.activity?.status === 'SUCCEEDED'" :size="14" />
          <Clock3 v-else :size="14" />
          <span role="status">{{ status(row.activity) }}</span>
          <small v-if="row.activity">{{ elapsed(row.activity) }}</small>
          <button v-if="row.activity?.status === 'RUNNING'" type="button" class="generation-stop"
            :title="`停止${row.label}`" :aria-label="`停止${row.label}`"
            :disabled="stopping[row.activity.id] || row.activity.stopRequested" @click="stop(row.activity)">
            <Square :size="13" /><span>停止</span>
          </button>
        </div>
        <span v-if="row.activity" class="generation-origin">{{ row.activity.source === 'model' ? '模型任务' : '生成请求' }}</span>
        <p v-if="row.activity?.errorMessage" class="generation-error">{{ row.activity.errorMessage }}</p>
        <p v-if="row.activity && stopErrors[row.activity.id]" class="generation-error" role="alert">{{ stopErrors[row.activity.id] }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.generation-status { border-bottom: 1px solid #dde2e5; padding: 12px 0 16px; margin-bottom: 20px; }
.generation-status header { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; font-size: 13px; }
.generation-current, .generation-result { display: flex; align-items: center; gap: 5px; }
.generation-current { color: #326967; }
.generation-tasks { margin-left: auto; background: none; border: 0; color: #326967; cursor: pointer; font-size: 12px; padding: 4px; }
.generation-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px 14px; }
.generation-stage { min-width: 0; min-height: 62px; font-size: 12px; overflow-wrap: anywhere; }
.generation-stage > strong { display: block; font-size: 13px; line-height: 20px; }
.generation-result { min-height: 20px; flex-wrap: wrap; color: #76818a; }
.generation-result svg { flex-shrink: 0; }
.generation-result.running { color: #326967; }
.generation-result.failed { color: #b33d44; }
.generation-result.succeeded { color: #367d56; }
.generation-result.cancelled { color: #76818a; }
.generation-stop { display: inline-flex; align-items: center; gap: 3px; min-height: 28px; padding: 3px 5px; border: 1px solid #ccd5d8; border-radius: 4px; background: transparent; color: #943e45; cursor: pointer; font-size: 11px; }
.generation-stop:disabled { cursor: wait; opacity: 0.6; }
.generation-result small, .generation-origin { color: #76818a; font-size: 11px; }
.generation-error { color: #b33d44; margin: 4px 0; font-size: 12px; overflow-wrap: anywhere; }
.generation-spin { animation: generation-spin 1.2s linear infinite; }
@keyframes generation-spin { to { transform: rotate(360deg); } }
@media (max-width: 380px) { .generation-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; } }
@media (prefers-reduced-motion: reduce) { .generation-spin { animation: none; } }
</style>

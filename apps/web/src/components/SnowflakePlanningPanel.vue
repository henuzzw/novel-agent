<script setup lang="ts">
import { computed, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { RefreshCw } from 'lucide-vue-next'
import { getLatestSnowflakePlan } from '@/api/snowflake'
import { generationRequests } from '@/lib/generation-activity'

const props = defineProps<{ projectId: string }>()
const pending = computed(() => generationRequests.value.some(item => item.projectId === props.projectId
  && ['STORY_BIBLE', 'IMPORT_PLANNING'].includes(item.stage) && item.status === 'RUNNING'))
const query = useQuery({
  queryKey: computed(() => ['snowflake-plan', props.projectId]),
  queryFn: () => getLatestSnowflakePlan(props.projectId),
  refetchInterval: state => pending.value || state.state.data?.status === 'RUNNING' ? 3000 : false,
})
watch(pending, () => query.refetch())
const stages = [
  { key: 'CORE', field: 'core', label: '故事核心与梗概' },
  { key: 'CHARACTERS', field: 'characters', label: '人物设计' },
  { key: 'WORLD', field: 'world', label: '世界构建' },
  { key: 'PLOT', field: 'plot', label: '三幕情节与悬念节奏' },
] as const
const statusNames = { RUNNING: '请求中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已停止' }
</script>

<template>
  <section v-if="query.data.value || query.isError.value" class="snowflake-panel" aria-label="雪花渐进规划">
    <header>
      <h3>雪花渐进规划</h3>
      <span v-if="query.data.value" role="status">{{ statusNames[query.data.value.status] }}</span>
      <button class="icon-button" type="button" aria-label="刷新雪花规划" title="刷新雪花规划" :disabled="query.isFetching.value" @click="query.refetch()"><RefreshCw :size="16" /></button>
    </header>
    <p v-if="query.isError.value" class="form-error" role="alert">规划记录加载失败：{{ query.error.value?.message }}</p>
    <template v-if="query.data.value">
      <p v-if="query.data.value.errorMessage" class="form-error" role="alert">{{ query.data.value.errorMessage }}</p>
      <details v-for="stage in stages" :key="stage.key">
        <summary><span>{{ stage.label }}</span><small>{{ query.data.value[stage.field] ? '成功' : query.data.value.activeStage === stage.key ? statusNames[query.data.value.status] : '待生成' }}</small></summary>
        <div v-if="query.data.value[stage.field]" class="snowflake-text">{{ query.data.value[stage.field] }}</div>
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

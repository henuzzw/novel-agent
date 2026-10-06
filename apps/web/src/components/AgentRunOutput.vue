<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { RefreshCw } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getAgentRunOutput, subscribeAgentRunOutput, type AgentRun } from '@/api/agentRuns'

const props = defineProps<{ projectId: string; runId: string; status: AgentRun['status'] }>()
const opened = ref(props.status === 'RUNNING')
const streamError = ref('')
const client = useQueryClient()
let disconnect: (() => void) | null = null
let subscriptionNumber = 0
const query = useQuery({
  queryKey: computed(() => ['agent-run-output', props.projectId, props.runId]),
  queryFn: () => getAgentRunOutput(props.projectId, props.runId),
  enabled: opened,
  retry: false,
})
const display = computed(() => {
  const text = query.data.value?.responseText
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch { return text }
})
const categories: Record<string, string> = {
  TIMEOUT: '等待超时', USAGE_LIMIT: '额度或限流', AUTHENTICATION: '认证或权限', NETWORK: '网络连接', OTHER: '调用失败',
  CANCELLED: '已停止',
}

function stop() { subscriptionNumber++; disconnect?.(); disconnect = null }
function connect() {
  if (!opened.value || query.data.value?.status !== 'RUNNING' || disconnect) return
  const { projectId, runId } = props
  const subscription = ++subscriptionNumber
  disconnect = subscribeAgentRunOutput(projectId, runId, output => {
    if (subscription !== subscriptionNumber || props.projectId !== projectId || props.runId !== runId || !opened.value) return
    client.setQueryData(['agent-run-output', projectId, runId], output)
    if (output.status !== 'RUNNING') {
      stop()
      client.invalidateQueries({ queryKey: ['agent-runs', projectId] })
      client.invalidateQueries({ queryKey: ['agent-run-summary', projectId] })
    }
  }, () => {
    if (subscription !== subscriptionNumber || props.projectId !== projectId || props.runId !== runId || !opened.value) return
    stop()
    streamError.value = '实时连接已断开，刷新可重新读取响应。'
  })
}
watch([opened, () => query.data.value], () => {
  if (!opened.value || query.data.value?.status !== 'RUNNING') stop()
  else if (!streamError.value) connect()
})
watch(() => [props.projectId, props.runId], () => {
  stop(); streamError.value = ''; opened.value = props.status === 'RUNNING'
})
async function refresh() {
  stop(); streamError.value = ''
  await query.refetch()
  connect()
}
onBeforeUnmount(stop)
</script>

<template>
  <details class="run-prompt run-output" :open="opened" @toggle="opened = ($event.target as HTMLDetailsElement).open">
    <summary>响应与错误详情</summary>
    <div class="output-heading">
      <strong>{{ query.data.value?.status === 'RUNNING' ? display ? '正在接收响应' : '等待模型响应' : '模型响应' }}</strong>
      <button class="icon-button" type="button" title="刷新响应" aria-label="刷新响应" @click="refresh"><RefreshCw :size="15" /></button>
    </div>
    <p v-if="query.isPending.value">正在读取响应…</p>
    <p v-else-if="query.isError.value" role="alert">响应读取失败：{{ query.error.value?.message }}</p>
    <template v-else-if="query.data.value">
      <p v-if="query.data.value.errorType" class="output-error" role="alert">{{ categories[query.data.value.errorCategory ?? 'OTHER'] }} · {{ query.data.value.errorType }}</p>
      <pre v-if="query.data.value.errorDetail" class="output-error-detail">{{ query.data.value.errorDetail }}</pre>
      <p v-if="query.data.value.status === 'FAILED' && !query.data.value.errorType">这条历史任务未保存具体错误原因。</p>
      <p v-if="query.data.value.status === 'RUNNING' && !display">尚未收到正文输出。</p>
      <p v-else-if="!display">{{ query.data.value.responseText == null ? '这条历史任务未保存模型响应。' : '未收到可展示的模型响应。' }}</p>
      <p v-if="['FAILED', 'CANCELLED'].includes(query.data.value.status) && display">未完成响应，不作为有效规划或正文。</p>
      <p v-if="query.data.value.truncated">响应超过展示上限，仅保留前 200,000 字符。</p>
      <pre v-if="display" class="model-response">{{ display }}</pre>
    </template>
    <p v-if="streamError" role="alert">{{ streamError }}</p>
  </details>
</template>

<style scoped>
.output-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 8px 0; }
.output-error-detail { margin: 8px 0; }
.run-output { min-width: 0; }
</style>

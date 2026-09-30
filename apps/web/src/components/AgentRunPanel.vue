<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { Activity, AlertCircle, CheckCircle2, Clock3, Coins, RefreshCw } from 'lucide-vue-next'
import { computed } from 'vue'

import { getAgentRunSummary, listAgentRuns } from '@/api/agentRuns'
import AgentRunPrompt from '@/components/AgentRunPrompt.vue'

const props = defineProps<{ projectId: string }>()
const runsQuery = useQuery({ queryKey: computed(() => ['agent-runs', props.projectId]), queryFn: () => listAgentRuns(props.projectId) })
const summaryQuery = useQuery({ queryKey: computed(() => ['agent-run-summary', props.projectId]), queryFn: () => getAgentRunSummary(props.projectId) })
const stageNames: Record<string, string> = {
  STORY_DIRECTION: '故事方向', STORY_BIBLE: '故事圣经', OUTLINE: '分层大纲',
  CHAPTER_CONTRACT: '章节合同', MANUSCRIPT: '正文生成', CHAPTER_REVIEW: '章节审稿',
}
const providerNames: Record<string, string> = { LOCAL_CODEX: '服务端 Codex', DEEPSEEK: 'DeepSeek', LOCAL_TEMPLATE: '本地模板' }
function tokens(value: number) { return new Intl.NumberFormat('zh-CN').format(value) }
function time(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
function refresh() { runsQuery.refetch(); summaryQuery.refetch() }
</script>

<template>
  <div class="run-workbench">
    <div class="section-heading">
      <div><span class="eyebrow">运行观测</span><h2>Agent 任务与成本</h2></div>
      <button class="icon-button" type="button" title="刷新" aria-label="刷新任务" @click="refresh"><RefreshCw :size="17" /></button>
    </div>
    <p class="run-notice">Token 与费用当前为估算值；配置模型单价后才会产生费用金额，不代表供应商账单。</p>
    <div v-if="summaryQuery.data.value" class="run-summary">
      <span><Activity :size="18" /><strong>{{ summaryQuery.data.value.calls }}</strong><small>模型任务</small></span>
      <span><Clock3 :size="18" /><strong>{{ tokens(summaryQuery.data.value.inputTokens + summaryQuery.data.value.outputTokens) }}</strong><small>估算 Token</small></span>
      <span><Coins :size="18" /><strong>¥{{ Number(summaryQuery.data.value.estimatedCost).toFixed(4) }}</strong><small>估算费用</small></span>
      <span><AlertCircle :size="18" /><strong>{{ summaryQuery.data.value.failures }}</strong><small>失败任务</small></span>
    </div>
    <div v-if="runsQuery.isPending.value" class="editor-empty">正在读取任务记录…</div>
    <div v-else-if="runsQuery.isError.value" class="status-panel error-panel">任务记录加载失败：{{ runsQuery.error.value?.message }}</div>
    <div v-else-if="!runsQuery.data.value?.length" class="import-empty"><Activity :size="30" /><h3>还没有模型任务</h3><p>生成故事方向、故事圣经、大纲或正文后，运行记录会出现在这里。</p></div>
    <div v-else class="run-list">
      <article v-for="run in runsQuery.data.value" :key="run.id" class="run-row">
        <component :is="run.status === 'SUCCEEDED' ? CheckCircle2 : run.status === 'FAILED' ? AlertCircle : Clock3" :size="19" :class="run.status.toLowerCase()" />
        <div class="run-main"><strong>{{ stageNames[run.stage] ?? run.stage }}</strong><small>{{ providerNames[run.provider] ?? run.provider }} · {{ time(run.startedAt) }}</small><p v-if="run.errorMessage">{{ run.errorMessage }}</p><AgentRunPrompt :project-id="projectId" :run-id="run.id" /></div>
        <div class="run-metrics"><strong>{{ tokens(run.inputTokens + run.outputTokens) }} Token</strong><span>输入 {{ tokens(run.inputTokens) }} · 输出 {{ tokens(run.outputTokens) }}</span><span>{{ run.durationMs == null ? '运行中' : `${(run.durationMs / 1000).toFixed(1)} 秒` }} · ¥{{ Number(run.estimatedCost).toFixed(4) }}</span></div>
      </article>
    </div>
  </div>
</template>

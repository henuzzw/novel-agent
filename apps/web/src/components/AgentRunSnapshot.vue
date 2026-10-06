<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { getAgentRunRequestSnapshot } from '@/api/agentRuns'
const props = defineProps<{ projectId: string; runId: string }>()
const opened = ref(false)
watch(() => [props.projectId, props.runId], () => { opened.value = false })
const query = useQuery({ queryKey: computed(() => ['agent-run-snapshot', props.projectId, props.runId]),
  queryFn: () => getAgentRunRequestSnapshot(props.projectId, props.runId), enabled: opened })
const snapshot = computed(() => query.data.value?.requestSnapshot)
</script>

<template>
  <details class="run-snapshot" :open="opened" @toggle="opened = ($event.target as HTMLDetailsElement).open">
    <summary>调用依据与用量</summary>
    <p v-if="query.isPending.value">正在读取…</p>
    <p v-else-if="query.isError.value" role="alert">{{ query.error.value?.message }}</p>
    <template v-else>
      <dl v-if="snapshot">
        <div><dt>实际模型</dt><dd>{{ snapshot.effectiveSettings.model }} · {{ snapshot.effectiveSettings.effort ?? '未设置强度' }}</dd></div>
        <div><dt>设置版本</dt><dd>{{ snapshot.effectiveSettings.version ?? '环境初始值' }}</dd></div>
        <div><dt>输出上限</dt><dd>{{ snapshot.maxOutputTokens ?? '供应商未应用令牌上限' }} <small>请求参考 {{ snapshot.requestedMaxOutputTokens }}</small></dd></div>
        <div><dt>会话策略</dt><dd>{{ snapshot.sessionPolicy }}</dd></div>
        <div><dt>输出结构</dt><dd>{{ snapshot.schemaName }}<code>{{ snapshot.schemaHash }}</code></dd></div>
        <div><dt>系统指纹</dt><dd><code>{{ snapshot.systemPromptHash }}</code></dd></div>
        <div><dt>输入指纹</dt><dd><code>{{ snapshot.userPromptHash }}</code></dd></div>
        <div v-if="snapshot.contextBudget"><dt>上下文预算</dt><dd>输入约 {{ snapshot.contextBudget.estimatedInputTokens }} / 容量 {{ snapshot.contextBudget.contextWindowTokens }} / 安全余量 {{ snapshot.contextBudget.safetyMarginTokens }}</dd></div>
      </dl>
      <p v-else>历史任务未记录调用依据。</p>
      <p v-if="query.data.value?.tokenSource === 'ACTUAL'">供应商用量：输入 {{ query.data.value.actualInputTokens }}，输出 {{ query.data.value.actualOutputTokens }} Token</p>
      <p v-else>供应商实际用量未知。</p>
      <p v-if="query.data.value?.estimatedInputTokens != null">估算：输入 {{ query.data.value.estimatedInputTokens }}，输出 {{ query.data.value.estimatedOutputTokens ?? '未知' }} Token</p>
    </template>
  </details>
</template>

<style scoped>
.run-snapshot { margin-top: 8px; font-size: 13px; min-width: 0; }
summary { cursor: pointer; } dl { display: grid; gap: 8px; }
dl > div { display: grid; grid-template-columns: 80px minmax(0, 1fr); gap: 12px; }
dt { color: #68716b; } dd { margin: 0; overflow-wrap: anywhere; }
code { display: block; overflow-wrap: anywhere; font-size: 12px; }
@media (max-width: 600px) { dl > div { grid-template-columns: minmax(0, 1fr); gap: 3px; } }
</style>

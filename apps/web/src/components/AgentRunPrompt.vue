<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { getAgentRunPrompt } from '@/api/agentRuns'

const props = defineProps<{ projectId: string; runId: string }>()
const opened = ref(false)
const promptQuery = useQuery({
  queryKey: computed(() => ['agent-run-prompt', props.projectId, props.runId]),
  queryFn: () => getAgentRunPrompt(props.projectId, props.runId),
  enabled: opened,
})

function onToggle(event: Event) {
  opened.value = (event.target as HTMLDetailsElement).open
}
</script>

<template>
  <details class="run-prompt" @toggle="onToggle">
    <summary>查看完整 Prompt</summary>
    <p v-if="promptQuery.isPending.value">正在读取 Prompt…</p>
    <p v-else-if="promptQuery.isError.value" role="alert">Prompt 加载失败：{{ promptQuery.error.value?.message }}</p>
    <template v-else-if="promptQuery.data.value?.userPrompt != null">
      <section v-if="promptQuery.data.value.systemPrompt">
        <h4>系统 Prompt</h4>
        <pre>{{ promptQuery.data.value.systemPrompt }}</pre>
      </section>
      <section>
        <h4>用户 Prompt</h4>
        <pre>{{ promptQuery.data.value.userPrompt }}</pre>
      </section>
    </template>
    <template v-else>
      <p>这条历史任务只保存了前 500 个字符，无法还原完整 Prompt。</p>
      <pre v-if="promptQuery.data.value?.promptPreview">{{ promptQuery.data.value.promptPreview }}</pre>
    </template>
  </details>
</template>

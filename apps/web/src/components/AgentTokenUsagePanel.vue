<script setup lang="ts">
import { computed } from 'vue'
import type { AgentTokenUsage } from '@/api/agentRuns'

const props = defineProps<{ usage: AgentTokenUsage }>()
const periods = computed(() => [
  { label: '项目累计 Token', value: props.usage.projectTotal },
  { label: '最近 5 小时 Token', value: props.usage.lastFiveHours },
])
const tokens = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const time = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
}).format(new Date(value))
</script>

<template>
  <div class="token-usage" aria-label="项目 Token 统计">
    <section v-for="period in periods" :key="period.label" :aria-label="period.label">
      <h3>{{ period.label }}</h3>
      <dl>
        <div><dt>供应商已报告</dt><dd>{{ period.value.calls > 0 && period.value.actualCalls === 0 ? '未知' : tokens(period.value.actualInputTokens + period.value.actualOutputTokens) }}</dd></div>
        <div><dt>实际输入 / 输出</dt><dd>{{ period.value.actualCalls === 0 && period.value.calls > 0 ? '未知 / 未知' : `${tokens(period.value.actualInputTokens)} / ${tokens(period.value.actualOutputTokens)}` }}</dd></div>
        <div><dt>本地估算</dt><dd>{{ tokens(period.value.estimatedInputTokens + period.value.estimatedOutputTokens) }}</dd></div>
        <div><dt>估算输入 / 输出</dt><dd>{{ tokens(period.value.estimatedInputTokens) }} / {{ tokens(period.value.estimatedOutputTokens) }}</dd></div>
      </dl>
      <p>{{ period.value.calls }} 次调用 · {{ period.value.actualCalls }} 次已报告实际用量<template v-if="period.value.calls > period.value.actualCalls"> · {{ period.value.calls - period.value.actualCalls }} 次实际用量未知</template></p>
    </section>
  </div>
  <p class="usage-range">{{ time(usage.recentFrom) }} 至 {{ time(usage.asOf) }} · 按调用开始时间统计本项目；失败、停止调用的已记录用量也计入。供应商已报告数值不含未知调用，不代表账号剩余额度。</p>
</template>

<style scoped>
.token-usage { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border-block: 1px solid var(--border); }
.token-usage section { min-width: 0; padding: 16px 20px 16px 0; }
.token-usage section + section { border-left: 1px solid var(--border); padding-left: 20px; }
h3 { margin: 0 0 12px; font-size: 15px; }
dl { margin: 0; display: grid; gap: 8px; }
dl > div { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; }
dt, p { color: var(--text-secondary); font-size: 12px; }
dd { margin: 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
dl > div:first-child dd { font-size: 20px; font-weight: 700; }
p { margin: 12px 0 0; overflow-wrap: anywhere; }
.usage-range { margin: 10px 0 22px; line-height: 1.6; }
@media (max-width: 760px) {
  .token-usage { grid-template-columns: minmax(0, 1fr); }
  .token-usage section { padding: 16px 0; }
  .token-usage section + section { border-left: 0; border-top: 1px solid var(--border); padding-left: 0; }
}
</style>

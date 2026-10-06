<script setup lang="ts">
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Play, RotateCcw, Square, ChevronRight, FileSearch } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import { automationChapterTarget, cancelAutomationRun, createAutomationRun, listAutomationRuns, resumeAutomationRun, type AutomationChapterTarget, type AutomationRun } from '@/api/automation'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ 'open-chapter': [target: AutomationChapterTarget] }>()
const queryClient = useQueryClient()
const firstChapter = ref(1)
const lastChapter = ref(3)
const { provider: provider } = useGlobalModelSettings()
const instruction = ref('')
const qualityReviewEnabled = ref(true)
const maxAutoRevisionRounds = ref(0)
const maxGenerationSteps = ref(100)
const busy = ref(false)
const error = ref('')
let creationKey = crypto.randomUUID()
const runs = useQuery({
  queryKey: computed(() => ['automation-runs', props.projectId]),
  queryFn: () => listAutomationRuns(props.projectId),
  refetchInterval: (query) => query.state.data?.some(run => run.status === 'RUNNING' || run.status === 'PENDING') ? 2000 : false,
})
const active = computed(() => runs.data.value?.some(run => !['SUCCEEDED', 'CANCELLED'].includes(run.status)))
watch(() => runs.data.value, (items) => {
  const run = items?.find(item => !['SUCCEEDED', 'CANCELLED'].includes(item.status))
  if (!run) return
  firstChapter.value = run.firstChapter
  lastChapter.value = run.lastChapter
  qualityReviewEnabled.value = run.qualityReviewEnabled
  maxAutoRevisionRounds.value = run.maxAutoRevisionRounds ?? 0
  maxGenerationSteps.value = run.maxGenerationSteps ?? 100
}, { immediate: true })
const validRange = computed(() => Number.isInteger(firstChapter.value) && Number.isInteger(lastChapter.value)
  && firstChapter.value >= 1 && lastChapter.value >= firstChapter.value && lastChapter.value - firstChapter.value < 20
  && Number.isInteger(maxGenerationSteps.value) && maxGenerationSteps.value >= 1 && maxGenerationSteps.value <= 500)
const statusNames = {
  PENDING: '待开始', RUNNING: '运行中', WAITING_FOR_USER: '等待确认', FAILED: '失败', CANCELLED: '已取消', SUCCEEDED: '已完成',
}
const stageNames = { CONTRACT: '章节合同', CONTRACT_REVIEW: '合同审阅', MANUSCRIPT: '正文生成', QUALITY_REVIEW: '正文质量检查', QUALITY_REVISION: '语句润色', REVIEW: '正文审稿' }
const stepNames = { RUNNING: '运行中', SUCCEEDED: '完成', FAILED: '失败' }
watch([firstChapter, lastChapter, provider, instruction, qualityReviewEnabled, maxAutoRevisionRounds, maxGenerationSteps, () => props.projectId], () => { creationKey = crypto.randomUUID() })
watch([qualityReviewEnabled, provider], () => {
  if (!active.value && (!qualityReviewEnabled.value || provider.value === 'LOCAL_TEMPLATE')) maxAutoRevisionRounds.value = 0
})

function canResume(run: AutomationRun) {
  return ['WAITING_FOR_USER', 'FAILED', 'PENDING'].includes(run.status)
    || (run.status === 'RUNNING' && !run.cancelRequested && Date.now() - Date.parse(run.updatedAt) >= 20 * 60 * 1000)
}

function canCancel(run: AutomationRun) {
  return !run.cancelRequested || Date.now() - Date.parse(run.updatedAt) >= 20 * 60 * 1000
}

async function act(action: () => Promise<unknown>) {
  busy.value = true
  error.value = ''
  try {
    await action()
    await runs.refetch()
    await queryClient.invalidateQueries({ queryKey: ['agent-runs', props.projectId] })
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '任务操作失败'
  } finally {
    busy.value = false
  }
}

function create() {
  if (!validRange.value) return
  return act(async () => {
    await createAutomationRun(props.projectId, {
      firstChapter: firstChapter.value, lastChapter: lastChapter.value, provider: provider.value,
      instruction: instruction.value.trim() || undefined,
      qualityReviewEnabled: qualityReviewEnabled.value,
      maxAutoRevisionRounds: maxAutoRevisionRounds.value,
      maxGenerationSteps: maxGenerationSteps.value,
    }, creationKey)
    creationKey = crypto.randomUUID()
  })
}
</script>

<template>
  <section class="automation-panel" aria-label="自动创作任务">
    <h3>自动创作任务</h3>
    <form class="automation-form" @submit.prevent="create">
      <label>起始章<input v-model.number="firstChapter" type="number" min="1" max="100000" required :disabled="busy || active" /></label>
      <label>结束章<input v-model.number="lastChapter" type="number" :min="firstChapter" :max="Math.min(firstChapter + 19, 100000)" required :disabled="busy || active" /></label>
      <GlobalModelBadge />
      <label class="automation-instruction">创作要求<input v-model="instruction" maxlength="2000" :disabled="busy || active" /></label>
      <label class="automation-quality"><input v-model="qualityReviewEnabled" type="checkbox" :disabled="busy || active" />正文质量检查</label>
      <label>自动语句润色<select v-model.number="maxAutoRevisionRounds" :disabled="busy || active || !qualityReviewEnabled || provider === 'LOCAL_TEMPLATE'"><option :value="0">关闭</option><option :value="1">每章最多 1 轮</option><option :value="2">每章最多 2 轮</option><option :value="3">每章最多 3 轮</option></select></label>
      <label>生成次数上限<input v-model.number="maxGenerationSteps" type="number" min="1" max="500" required :disabled="busy || active" /></label>
      <button class="icon-button" type="submit" title="开始自动创作" aria-label="开始自动创作" :disabled="busy || active || !validRange || runs.isPending.value || runs.isError.value"><Play :size="18" /></button>
    </form>
    <p v-if="error" class="status-panel error-panel" role="alert">{{ error }}</p>
    <p v-if="runs.isError.value" class="status-panel error-panel">任务加载失败：{{ runs.error.value?.message }}</p>
    <p v-else-if="runs.isPending.value">正在读取自动任务…</p>
    <div v-for="run in runs.data.value" :key="run.id" class="automation-row">
      <div class="automation-heading">
        <strong>第 {{ run.firstChapter }} 至 {{ run.lastChapter }} 章</strong>
        <span>{{ run.cancelRequested && run.status === 'RUNNING' ? '正在取消' : statusNames[run.status] }} · 当前第 {{ run.currentChapter }} 章</span>
        <span v-if="run.qualityReviewEnabled">含质量检查</span>
        <span>生成额度已用 {{ run.usedGenerationSteps ?? run.steps.length }} / {{ run.maxGenerationSteps ?? 100 }} 次</span>
        <span v-if="run.maxAutoRevisionRounds > 0">本章润色 {{ run.usedAutoRevisionRounds }} / {{ run.maxAutoRevisionRounds }} 轮</span>
        <div class="automation-actions">
          <button v-if="['WAITING_FOR_USER', 'FAILED'].includes(run.status)" class="icon-button" type="button" title="处理当前章节" aria-label="处理当前章节" @click="emit('open-chapter', automationChapterTarget(run))"><FileSearch :size="18" /></button>
          <button v-if="canResume(run)" class="icon-button" type="button" :disabled="busy" :title="run.status === 'FAILED' || run.status === 'RUNNING' ? '重试任务' : '继续任务'" :aria-label="run.status === 'FAILED' || run.status === 'RUNNING' ? '重试任务' : '继续任务'" @click="act(() => resumeAutomationRun(projectId, run.id))"><component :is="run.status === 'FAILED' || run.status === 'RUNNING' ? RotateCcw : ChevronRight" :size="18" /></button>
          <button v-if="!['SUCCEEDED', 'CANCELLED'].includes(run.status)" class="icon-button" type="button" title="取消任务" aria-label="取消任务" :disabled="busy || !canCancel(run)" @click="act(() => cancelAutomationRun(projectId, run.id))"><Square :size="16" /></button>
        </div>
      </div>
      <p v-if="run.waitingReason">{{ run.waitingReason }}</p>
      <p v-if="run.errorCode" role="alert">失败原因：{{ run.errorCode }}</p>
      <ol v-if="run.steps.length" class="automation-steps">
        <li v-for="(step, index) in run.steps" :key="index">第 {{ step.chapterNumber }} 章 · {{ stageNames[step.stage] }} · {{ stepNames[step.status] }}</li>
      </ol>
    </div>
  </section>
</template>

<style scoped>
.automation-panel { margin-bottom: 24px; border-bottom: 1px solid #d8dee3; padding-bottom: 20px; }
.automation-panel h3 { margin: 0 0 14px; font-size: 17px; }
.automation-form { display: flex; align-items: end; gap: 12px; flex-wrap: wrap; }
.automation-form label { display: grid; gap: 6px; font-size: 13px; min-width: 0; }
.automation-form input, .automation-form select { height: 38px; min-width: 0; width: 100%; border: 1px solid #c7d0d8; border-radius: 4px; padding: 6px 9px; box-sizing: border-box; background: #fff; color: #26343c; }
.automation-form input[type=number] { width: 88px; }
.automation-form .automation-quality { display: flex; align-items: center; gap: 6px; min-height: 38px; }
.automation-form input[type=checkbox] { width: 16px; height: 16px; padding: 0; }
.automation-instruction { flex: 1; min-width: 160px !important; }
.automation-row { border-top: 1px solid #e3e7ea; margin-top: 16px; padding-top: 14px; }
.automation-heading { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.automation-heading span, .automation-steps { font-size: 13px; color: #53616a; }
.automation-actions { display: flex; gap: 8px; margin-left: auto; }
.automation-row p { margin: 10px 0; font-size: 14px; overflow-wrap: anywhere; }
.automation-steps { display: flex; gap: 8px 20px; flex-wrap: wrap; list-style: none; padding: 0; }
@media (max-width: 540px) { .automation-instruction { flex-basis: 100%; } }
</style>

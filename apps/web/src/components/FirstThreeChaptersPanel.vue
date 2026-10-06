<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { FileSearch, RefreshCw, Calculator } from 'lucide-vue-next'
import type { ModelProvider } from '@/api/planning'
import { checkFirstThreeChapters, getFirstThreeChapters, type OpeningDimension, type OpeningReport, type OpeningView } from '@/api/firstThreeChapters'

const props = withDefaults(defineProps<{ projectId: string; provider: ModelProvider; externalBusy?: boolean; refreshKey?: string | number }>(), { externalBusy: false, refreshKey: 0 })
const emit = defineEmits<{ checked: [report: OpeningReport]; 'busy-change': [busy: boolean] }>()
const view = ref<OpeningView | null>(null)
const selected = ref<string[]>([])
const instruction = ref('')
const maxInputTokens = ref(0)
const loading = ref(false)
const checking = ref(false)
const error = ref('')
const budgetInstruction = ref('')
let generation = 0
let mounted = true
const labels: Record<OpeningDimension, string> = { FIRST_CHAPTER: '首章进展', CAUSAL_CONTINUITY: '跨章因果', PAYOFF: '阶段兑现', REPETITION: '重复内容', CHARACTER: '人物连续性', STYLE: '风格', LOGIC: '情节逻辑', SCENE: '场景与节奏' }
const report = computed(() => view.value?.latestValidReport ?? view.value?.latestReport ?? null)
const current = computed(() => !!view.value?.available && report.value?.current && report.value.fingerprint === view.value.source.fingerprint)
const budgetFresh = computed(() => budgetInstruction.value === instruction.value)
const disabled = computed(() => loading.value || checking.value || props.externalBusy)
const canCheck = computed(() => !disabled.value && !!view.value?.available && view.value.budget.fits && budgetFresh.value
  && Number.isInteger(maxInputTokens.value) && maxInputTokens.value >= view.value.budget.estimatedInputTokens)

async function load(reset = false) {
  const token = ++generation
  loading.value = true
  error.value = ''
  if (reset) { selected.value = []; view.value = null }
  const requestedInstruction = instruction.value
  try {
    const result = await getFirstThreeChapters(props.projectId, props.provider, selected.value, requestedInstruction)
    if (!mounted || token !== generation) return
    view.value = result
    budgetInstruction.value = requestedInstruction
    if (result.source.chapters.every(c => c.manuscriptId)) selected.value = result.source.chapters.map(c => c.manuscriptId!)
    if (!maxInputTokens.value) maxInputTokens.value = result.budget.inputLimitTokens
  } catch (e) {
    if (mounted && token === generation) { view.value = null; error.value = e instanceof Error ? e.message : '读取失败' }
  } finally {
    if (mounted && token === generation) loading.value = false
  }
}
function choose(number: number, event: Event) {
  const ids = view.value?.source.chapters.map(c => c.manuscriptId ?? '') ?? []
  ids[number - 1] = (event.target as HTMLSelectElement).value
  if (ids.every(Boolean)) { selected.value = ids; void load() }
}
async function check() {
  if (!canCheck.value || !view.value) return
  const token = generation
  const project = props.projectId
  const provider = props.provider
  checking.value = true
  error.value = ''
  try {
    const value = await checkFirstThreeChapters(project, { manuscriptIds: selected.value, provider,
      instruction: instruction.value, expectedFingerprint: view.value.source.fingerprint, maxInputTokens: maxInputTokens.value })
    if (!mounted || token !== generation || project !== props.projectId || provider !== props.provider) return
    emit('checked', value)
    await load()
  } catch (e) {
    if (mounted && token === generation) {
      const message = e instanceof Error ? e.message : '检查失败'
      await load()
      if (mounted && project === props.projectId && provider === props.provider) error.value = message
    }
  } finally { if (mounted) checking.value = false }
}
watch(() => [props.projectId, props.provider, props.refreshKey], () => { maxInputTokens.value = 0; void load(true) }, { immediate: true })
watch(checking, busy => emit('busy-change', busy))
onUnmounted(() => { mounted = false; generation++; emit('busy-change', false) })
</script>

<template>
  <section class="opening-panel" aria-label="前三章连读">
    <header><h3>前三章连读</h3><button type="button" class="button secondary" :disabled="disabled" @click="load()"><RefreshCw :size="16" />刷新来源</button></header>
    <p v-if="loading" role="status">正在读取完整正文与依据…</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <template v-if="view">
      <p class="source-meta">当前已发布大纲 {{ view.source.outlineId ?? '缺失' }} · 行版本 {{ view.source.outlineRowVersion }} · 正史版本 {{ view.source.canonVersion }}</p>
      <p v-for="reason in view.source.unavailableReasons" :key="reason" class="form-error">{{ reason }}</p>
      <nav aria-label="章节定位"><a v-for="chapter in view.source.chapters" :key="chapter.chapterNumber" :href="`#opening-${projectId}-${chapter.chapterNumber}`">第 {{ chapter.chapterNumber }} 章</a></nav>
      <div class="opening-controls">
        <label>通读备注<textarea v-model="instruction" maxlength="4000" rows="2" :disabled="checking || externalBusy" /></label>
        <label>输入预算上限（Token）<input v-model.number="maxInputTokens" type="number" min="1" :max="view.budget.inputLimitTokens" step="1" :disabled="checking || externalBusy" /></label>
        <button class="button secondary" type="button" :disabled="disabled" @click="load()"><Calculator :size="16" />核对完整输入预算</button>
        <button class="button primary" type="button" :disabled="!canCheck" @click="check()"><FileSearch :size="16" />{{ checking ? '正在通读…' : provider === 'LOCAL_TEMPLATE' ? '核对来源与版本' : '检查完整三章' }}</button>
      </div>
      <p class="budget">完整输入约 {{ view.budget.estimatedInputTokens.toLocaleString() }} Token · 输出预留 {{ view.budget.maxOutputTokens.toLocaleString() }} · 安全余量 {{ view.budget.safetyMarginTokens.toLocaleString() }} · 本次模型调用 {{ view.budget.modelCalls }} 次</p>
      <p class="source-meta">{{ view.budget.notice }}</p>
      <p v-if="!budgetFresh" class="form-error">备注已变化，请重新核对预算。</p>
      <p v-if="!view.budget.fits || maxInputTokens < view.budget.estimatedInputTokens" class="form-error">预算不足，无法完整检查三章。</p>
      <p v-if="provider === 'LOCAL_TEMPLATE'" class="source-meta">本地规则仅核对缺章、来源与版本，未评估文学效果。</p>
      <section class="opening-report" aria-label="三章检查报告">
        <h4>三章检查报告</h4>
        <p v-if="view.latestValidReport && view.latestReport && view.latestValidReport.id !== view.latestReport.id" class="source-meta">最近一次报告已过期，当前展示所选版本的有效报告。</p>
        <p v-if="!report">未完成检查</p>
        <template v-else>
          <p v-if="!current" class="form-error">报告已过期，正文或写作依据已变化。</p>
          <p v-if="report.reviewMode === 'RULES_ONLY'" class="form-error">未完成文学检查：仅核对来源与版本。</p>
          <p class="source-meta">报告 v{{ report.versionNumber }} · {{ report.provider }} · {{ report.source.chapters.map(c => `第${c.chapterNumber}章 v${c.versionNumber}`).join(' / ') }}</p>
          <p>{{ report.content.summary }}</p>
          <div v-for="item in report.content.assessments" :key="item.dimension" class="opening-finding">
            <h5>{{ labels[item.dimension] }} · {{ item.status === 'NOT_ASSESSED' ? '未评估' : '定位观察' }}</h5><p>{{ item.observation }}</p>
            <blockquote v-for="(evidence, index) in item.evidence" :key="index"><cite>第 {{ evidence.chapterNumber }} 章</cite>{{ evidence.quote }}</blockquote>
          </div>
          <div v-for="issue in report.content.issues" :key="issue.id" class="opening-finding">
            <h5>{{ labels[issue.dimension] }} · {{ issue.description }}</h5>
            <blockquote v-for="(evidence, index) in issue.evidence" :key="index"><cite>第 {{ evidence.chapterNumber }} 章</cite>{{ evidence.quote }}</blockquote><p>{{ issue.suggestion }}</p>
          </div>
          <p v-if="report.reviewMode === 'MODEL' && !report.content.issues.length">本次未提出修改建议，仍需作者判断。</p>
          <details v-if="!current"><summary>报告对应的原正文与合同</summary><article v-for="c in report.source.chapters" :key="c.chapterNumber"><h5>第 {{ c.chapterNumber }} 章 · v{{ c.versionNumber }}</h5><div class="opening-body">{{ c.body }}</div><pre>{{ JSON.stringify(c.contract, null, 2) }}</pre></article></details>
        </template>
      </section>
      <article v-for="chapter in view.source.chapters" :id="`opening-${projectId}-${chapter.chapterNumber}`" :key="chapter.chapterNumber" class="opening-chapter">
        <header><h4>第 {{ chapter.chapterNumber }} 章 · {{ chapter.title || '正文缺失' }}</h4>
          <label>第 {{ chapter.chapterNumber }} 章正文版本<select :value="chapter.manuscriptId ?? ''" :disabled="disabled || !chapter.versions.length" @change="choose(chapter.chapterNumber, $event)"><option v-if="!chapter.versions.length" value="">缺少正文</option><option v-for="version in chapter.versions" :key="version.id" :value="version.id">v{{ version.versionNumber }} · {{ version.status === 'AUTHOR_ACCEPTED' ? '作者已确认' : '草稿' }}</option></select></label>
        </header>
        <p class="source-meta">正文 {{ chapter.manuscriptId ?? '缺失' }} · 行版本 {{ chapter.rowVersion }} · 合同 v{{ chapter.contractVersionNumber }} / 行版本 {{ chapter.contractRowVersion }} · {{ chapter.contractStatus === 'APPROVED' ? '已确认合同' : '合同未确认' }}</p>
        <details v-if="chapter.contract"><summary>对应合同 · {{ chapter.contractId }}</summary><dl class="contract-fields"><template v-for="(value, field) in chapter.contract" :key="field"><dt>{{ ({ chapterTitle: '章名', pov: '视角', objective: '目标', storyTime: '故事时间', locations: '地点', requiredBeats: '必要节拍', requiredReveals: '必要揭示', forbiddenFacts: '禁止事实', expectedExitState: '出口状态', foreshadowActions: '伏笔动作', hook: '钩子', suggestedMinWords: '建议最小字数', suggestedMaxWords: '建议最大字数' } as Record<string, string>)[field] }}</dt><dd>{{ Array.isArray(value) ? value.join('\n') : value }}</dd></template></dl></details>
        <details v-if="chapter.qualityReview"><summary>已有单章质量问题 · {{ chapter.qualityReviewCurrent ? '当前有效' : '已过期或对应其他版本' }}</summary><p>{{ chapter.qualityReview.summary }}</p><div v-for="issue in chapter.qualityReview.issues" :key="issue.id"><h5>{{ issue.description }}</h5><blockquote>{{ issue.evidence }}</blockquote><p>{{ issue.suggestion }}</p></div></details>
        <p v-else class="source-meta">本章尚无质量报告</p>
        <div class="opening-body">{{ chapter.body ?? '缺少正文，合同不代替正文。' }}</div>
      </article>
    </template>
  </section>
</template>

<style scoped>
.opening-panel { min-width: 0; border-top: 1px solid #d9dddf; padding-top: 20px; }
header, nav { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
nav { justify-content: flex-start; padding: 12px 0; gap: 24px; }
.opening-controls { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; }
.opening-controls label:first-child { flex: 1 1 280px; }
label { display: grid; gap: 6px; min-width: 0; font-size: 14px; }
textarea, select, input { box-sizing: border-box; width: 100%; max-width: 100%; border: 1px solid #bfc7cc; border-radius: 4px; padding: 8px; font: inherit; background: #fff; color: #252d32; }
input { width: 180px; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 36px; }
button:disabled { opacity: .55; cursor: not-allowed; }
h3 { font-size: 20px; } h4 { font-size: 18px; } h5 { font-size: 15px; margin: 10px 0; }
.source-meta { color: #59676d; font-size: 13px; }
.opening-report, .opening-chapter { border-top: 1px solid #d9dddf; margin-top: 20px; padding-top: 16px; }
.opening-finding { border-bottom: 1px solid #e3e6e7; padding: 8px 0; }
.opening-body { white-space: pre-wrap; font-size: 16px; line-height: 1.9; max-width: 80ch; margin: 24px auto; }
p, h4, h5, dd, blockquote, .opening-body, summary { overflow-wrap: anywhere; }
blockquote { margin: 12px 0; padding: 4px 12px; border-left: 3px solid #879c91; white-space: pre-wrap; }
cite { display: block; font-style: normal; font-size: 13px; color: #59676d; }
summary { cursor: pointer; padding: 10px 0; }
.contract-fields { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 8px; }
dd { margin: 0; white-space: pre-wrap; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.form-error { color: #a1353a; }
@media (max-width: 640px) { .opening-controls > * { flex: 1 1 100%; } input { width: 100%; } .contract-fields { grid-template-columns: 1fr; } }
</style>

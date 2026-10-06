<script setup lang="ts">
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Save, Upload, WandSparkles, RotateCcw, Check, PenLine } from 'lucide-vue-next'
import StylePreviewEditor from './StylePreviewEditor.vue'
import WritingStyleCraftEditor from './WritingStyleCraftEditor.vue'
import { getLatestOutline, getLatestStoryBible } from '@/api/planning'
import { analyzeWritingStyle, applyWritingStyle, generateStylePreview, getStylePresets, getWritingStyle, recommendWritingStyle, uploadWritingStyle, stylePreviewKey, type StyleAnalysis, type StylePreview, type StylePreviewInput, type StyleRecommendation, type StyleRecommendationInput, type WritingStyleProfile } from '@/api/writingQuality'

const props = defineProps<{ projectId: string }>()
const client = useQueryClient()
const query = useQuery({ queryKey: computed(() => ['writing-style', props.projectId]), queryFn: () => getWritingStyle(props.projectId) })
const presets = useQuery({ queryKey: computed(() => ['writing-style-presets', props.projectId]), queryFn: () => getStylePresets(props.projectId) })
const draft = ref<WritingStyleProfile | null>(null)
const avoid = ref('')
const sample = ref('')
const file = ref<File | null>(null)
const { provider: provider } = useGlobalModelSettings()
const error = ref('')
const status = ref('')
const selectedPreset = ref('')
const basePresetName = computed(() => presets.data.value?.find(p => p.basePresetId === draft.value?.basePresetId)?.name ?? '已保存风格')
const { provider: previewProvider } = useGlobalModelSettings()
const targetWords = ref(800)
const previewInstruction = ref('')
const checkAfterPreview = ref(true)
const editorsBusy = ref(new Set<string>())
const { provider: recommendationProvider } = useGlobalModelSettings()
const recommendationInstruction = ref('')
const bible = useQuery({ queryKey: computed(() => ['story-bible', props.projectId]), queryFn: () => getLatestStoryBible(props.projectId) })
const recommendationResult = useQuery<StyleRecommendation | null>({
  queryKey: computed(() => ['style-recommendation', props.projectId]), queryFn: async () => null, initialData: null, enabled: false,
})
const recommendationCurrent = computed(() => !!bible.data.value && !bible.isError.value && !bible.isFetching.value &&
  recommendationResult.data.value?.sourceBibleVersionId === bible.data.value.id &&
  recommendationResult.data.value?.sourceBibleRowVersion === bible.data.value.version)
const evidenceLabels: Record<string, string> = { logline: '故事核心', theme: '主题', worldSetting: '世界设定',
  protagonist: '主角', protagonistArc: '人物弧光', centralConflict: '核心冲突', stakes: '失败代价',
  narrativeStyle: '叙事风格', endingDirection: '结局方向' }
const outline = useQuery({ queryKey: computed(() => ['outline', props.projectId]), queryFn: () => getLatestOutline(props.projectId) })
const previewResults = useQuery<StylePreview[]>({
  queryKey: computed(() => ['style-previews', props.projectId]), queryFn: async () => [], initialData: [], enabled: false,
})
const firstChapter = computed(() => outline.data.value?.content.arcs.flatMap((arc) => arc.chapters).find((chapter) => chapter.number === 1))
const fields = [
  { key: 'narrativeVoice', label: '叙述语气' }, { key: 'sentenceRhythm', label: '句式节奏' },
  { key: 'descriptionFocus', label: '描写重点' }, { key: 'dialogueStyle', label: '对白方式' },
  { key: 'emotionalExpression', label: '情感表达' }, { key: 'pacing', label: '叙事速度' },
] as const
function load(profile: WritingStyleProfile | null) {
  draft.value = profile ? { ...profile, avoidPatterns: [...profile.avoidPatterns],
    ...(profile.craft ? { craft: { ...profile.craft,
      examples: profile.craft.examples.map(example => ({ ...example })),
      evidence: profile.craft.evidence.map(item => ({ ...item })),
    } } : {}),
  } : null
  avoid.value = profile?.avoidPatterns.join('\n') ?? ''
}
watch(() => query.data.value, (value) => { if (value && !draft.value) load(value.profile) }, { immediate: true })
watch(() => props.projectId, () => { draft.value = null; sample.value = ''; file.value = null; error.value = ''; status.value = ''; selectedPreset.value = ''; previewInstruction.value = ''; recommendationInstruction.value = ''; editorsBusy.value = new Set() })
function choosePreset() {
  load(presets.data.value?.find((p) => p.name === selectedPreset.value) ?? null)
  status.value = '预设已载入，尚未应用。'
}
function analyzed(result: StyleAnalysis) {
  load(result.profile)
  selectedPreset.value = ''
  error.value = ''
  status.value = result.analysisMode === 'TEXT_METRICS' ? '本地结果仅包含句式指标，尚未应用；语气、情感与描写偏好未作推断。' : '样本风格已提取，尚未应用。'
}
const analyze = useMutation({ mutationFn: () => file.value
  ? uploadWritingStyle(props.projectId, file.value, provider.value)
  : analyzeWritingStyle(props.projectId, sample.value, provider.value),
onSuccess: analyzed, onError: (e: Error) => { error.value = e.message } })
const save = useMutation({ mutationFn: (clear: boolean) => {
  if (!query.data.value) throw new Error('风格档案尚未载入')
  const profile = clear ? null : draft.value && { ...draft.value, avoidPatterns: avoid.value.split('\n').map((s) => s.trim()).filter(Boolean) }
  return applyWritingStyle(props.projectId, profile, query.data.value.version)
}, onSuccess: (value) => {
  client.setQueryData(['writing-style', props.projectId], value)
  client.invalidateQueries({ queryKey: ['project', props.projectId] })
  client.invalidateQueries({ queryKey: ['quality-review', props.projectId] })
  load(value.profile); error.value = ''; status.value = value.profile ? '项目风格已应用。' : '已恢复故事设定中的风格。'
}, onError: (e: Error) => { error.value = e.message; query.refetch() } })
const preview = useMutation({
  mutationFn: (variables: { projectId: string; input: StylePreviewInput; checkAfter: boolean }) => generateStylePreview(variables.projectId, variables.input),
  onSuccess: (value, variables) => {
    client.setQueryData(['style-preview-auto-check', variables.projectId, stylePreviewKey(value)], variables.checkAfter)
    client.setQueryData<StylePreview[]>(['style-previews', variables.projectId], (previous) => [value,
      ...(previous ?? []).filter((item) => stylePreviewKey(item) !== stylePreviewKey(value))].slice(0, 3))
    if (variables.projectId === props.projectId) { error.value = ''; status.value = '第一章试写已生成。' }
  },
  onError: (e: Error, variables) => { if (variables.projectId === props.projectId) error.value = e.message },
})
const recommend = useMutation({
  mutationFn: (variables: { projectId: string; input: StyleRecommendationInput }) => recommendWritingStyle(variables.projectId, variables.input),
  onSuccess: (value, variables) => {
    client.setQueryData(['style-recommendation', variables.projectId], value)
    if (variables.projectId === props.projectId) { error.value = ''; status.value = value.recommendationMode === 'TEMPLATE' ? '本地模板未作风格适配判断。' : '风格建议已生成，尚未应用。' }
  },
  onError: (e: Error, variables) => {
    if (variables.projectId === props.projectId) { error.value = e.message; bible.refetch() }
  },
})
const busy = computed(() => save.isPending.value || analyze.isPending.value || preview.isPending.value || recommend.isPending.value || editorsBusy.value.size > 0)
function generateRecommendation() {
  if (!bible.data.value) return
  error.value = ''; status.value = ''
  recommend.mutate({ projectId: props.projectId, input: {
    bibleVersionId: bible.data.value.id, expectedBibleVersion: bible.data.value.version,
    provider: recommendationProvider.value, instruction: recommendationInstruction.value.trim(),
  } })
}
function chooseRecommendation(profile: WritingStyleProfile) {
  if (!recommendationCurrent.value) return
  load(profile); selectedPreset.value = ''; error.value = ''; status.value = '推荐风格已载入，尚未应用。'
}
function generatePreview() {
  if (!draft.value || !outline.data.value) return
  error.value = ''; status.value = ''
  preview.mutate({ projectId: props.projectId, checkAfter: checkAfterPreview.value, input: {
    outlineVersionId: outline.data.value.id, expectedOutlineVersion: outline.data.value.version,
    profile: { ...draft.value, avoidPatterns: avoid.value.split('\n').map((s) => s.trim()).filter(Boolean) },
    provider: previewProvider.value, targetWords: targetWords.value, instruction: previewInstruction.value.trim(),
  } })
}
function adoptPreview(value: StylePreview) {
  load(value.profile)
  selectedPreset.value = ''
  save.mutate(false)
}
function editorBusy(key: string, value: boolean) {
  const next = new Set(editorsBusy.value)
  if (value) next.add(key); else next.delete(key)
  editorsBusy.value = next
}
function revisedPreview(value: StylePreview, original: StylePreview) {
  const previous = client.getQueryData<StylePreview[]>(['style-previews', props.projectId]) ?? []
  if (previous.some((item) => stylePreviewKey(item) === stylePreviewKey(value))) {
    status.value = '模型未产生不同文本，原样例保留，可重新检查。'
    return
  }
  client.setQueryData(['style-preview-auto-check', props.projectId, stylePreviewKey(value)], true)
  client.setQueryData<StylePreview[]>(['style-previews', props.projectId], [value, original,
    ...previous.filter((item) => stylePreviewKey(item) !== stylePreviewKey(original))].slice(0, 3))
  status.value = '修订样例已生成，原样例保留。'
}
function selectFile(event: Event) {
  const value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (value && (!/\.(txt|md)$/i.test(value.name) || value.size > 100000)) {
    error.value = '请选择不超过 100 KB 的 UTF-8 TXT 或 Markdown 文件。'; file.value = null
    ;(event.target as HTMLInputElement).value = ''; return
  }
  file.value = value; error.value = ''
}
</script>

<template>
  <section class="style-editor">
    <header class="section-heading"><h2>写作风格</h2><span>{{ query.data.value?.profile?.name ?? '沿用故事设定' }}</span></header>
    <p v-if="query.isError.value" class="form-error" role="alert">{{ query.error.value?.message }}</p>
    <label><span>风格预设</span><select v-model="selectedPreset" :disabled="busy" @change="choosePreset"><option value="">选择预设</option><option v-for="p in presets.data.value" :key="p.name" :value="p.name">{{ p.name }}</option></select></label>
    <section class="style-recommendation">
      <h3>圣经风格建议</h3>
      <p v-if="bible.isPending.value" role="status">正在读取故事圣经…</p>
      <p v-else-if="bible.isError.value" class="form-error" role="alert">{{ bible.error.value?.message }}</p>
      <p v-else-if="!bible.data.value" class="preview-status">尚无已保存的故事圣经</p>
      <p v-else class="preview-status">第 {{ bible.data.value.generationNumber }} 版圣经 · {{ bible.data.value.status === 'PUBLISHED' ? '已发布' : '草稿' }}</p>
      <div class="style-fields">
        <GlobalModelBadge />
        <label><span>推荐偏好</span><input v-model="recommendationInstruction" maxlength="1000" :disabled="busy" /></label>
      </div>
      <div class="style-actions"><button type="button" class="button secondary" :disabled="busy || !bible.data.value" @click="generateRecommendation"><WandSparkles :size="16" />{{ recommend.isPending.value ? '正在判断…' : '根据圣经推荐风格' }}</button></div>
      <template v-if="recommendationResult.data.value">
        <p class="preview-status">第 {{ recommendationResult.data.value.bibleGenerationNumber }} 版圣经 · {{ recommendationResult.data.value.recommendationMode === 'TEMPLATE' ? '演示模板' : recommendationResult.data.value.provider === 'LOCAL_CODEX' ? 'Codex' : 'DeepSeek' }}</p>
        <p v-if="bible.isFetching.value" class="preview-status">正在核对圣经版本…</p>
        <p v-else-if="!recommendationCurrent && !bible.isError.value" class="form-error">故事圣经已更新，请重新推荐</p>
        <p v-if="outline.data.value && recommendationResult.data.value.sourceBibleVersionId !== outline.data.value.sourceBibleVersionId" class="preview-status">建议依据与试写大纲关联的圣经不同</p>
        <p>{{ recommendationResult.data.value.summary }}</p>
        <ol class="recommendation-list">
          <li v-for="item in recommendationResult.data.value.recommendations" :key="item.profile.name">
            <h4>{{ item.profile.name }}</h4><p>{{ item.reason }}</p><p class="preview-status">取舍：{{ item.tradeoff }}</p>
            <ul class="recommendation-evidence"><li v-for="(evidence, index) in item.evidence" :key="index">{{ evidenceLabels[evidence.field] ?? evidence.field }}：{{ evidence.quote }}</li></ul>
            <button type="button" class="button secondary" :disabled="busy || !recommendationCurrent" @click="chooseRecommendation(item.profile)"><Check :size="16" />载入此风格</button>
          </li>
        </ol>
      </template>
    </section>
    <form v-if="draft" @submit.prevent="save.mutate(false)">
      <label><span>风格名称</span><input v-model="draft.name" required maxlength="80" :disabled="busy" /></label>
      <p v-if="draft.basePresetId" class="preview-status">基础风格：{{ basePresetName }} · 第 {{ draft.basePresetVersion }} 版</p>
      <details class="style-details"><summary>调整风格</summary>
        <div class="style-fields"><label v-for="field in fields" :key="field.key"><span>{{ field.label }}</span><textarea v-model="draft[field.key]" required maxlength="600" rows="3" :disabled="busy" /></label></div>
        <label><span>避免的表达</span><textarea v-model="avoid" rows="3" :disabled="busy" /></label>
      </details>
      <WritingStyleCraftEditor v-model="draft.craft" :disabled="busy" />
      <div class="style-actions"><button class="button primary" type="submit" :disabled="busy || !query.data.value"><Save :size="16" />应用风格</button><button class="button secondary" type="button" :disabled="busy || !query.data.value?.profile" @click="save.mutate(true)"><RotateCcw :size="16" />恢复默认</button></div>
    </form>
    <section class="style-preview">
      <h3>第一章试写</h3>
      <p v-if="outline.isPending.value" role="status">正在读取大纲…</p>
      <p v-else-if="outline.isError.value" class="form-error" role="alert">{{ outline.error.value?.message }}</p>
      <p v-else-if="!firstChapter" class="preview-status">尚无第一章大纲</p>
      <p v-else class="preview-status">第 {{ outline.data.value?.generationNumber }} 版大纲 · 第一章：{{ firstChapter.title }}</p>
      <div class="style-fields">
        <GlobalModelBadge />
        <label><span>目标字数</span><input v-model.number="targetWords" type="number" min="300" max="1500" step="100" :disabled="busy" /></label>
      </div>
      <label><span>试写要求</span><textarea v-model="previewInstruction" rows="2" maxlength="1000" :disabled="busy" /></label>
      <label class="preview-check-toggle"><input v-model="checkAfterPreview" type="checkbox" :disabled="busy" /><span>试写后检查</span></label>
      <div class="style-actions"><button type="button" class="button primary" :disabled="busy || !draft || !firstChapter || targetWords < 300 || targetWords > 1500" @click="generatePreview"><PenLine :size="16" />{{ preview.isPending.value ? '正在试写…' : '试写第一章开头' }}</button></div>
      <div class="preview-grid">
        <article v-for="value in previewResults.data.value" :key="stylePreviewKey(value)" class="preview-result">
          <header><h4>{{ value.profile.name }}</h4><span>第 {{ value.outlineGenerationNumber }} 版大纲 · {{ value.previewMode === 'TEMPLATE' ? '演示模板' : value.provider === 'LOCAL_CODEX' ? 'Codex' : 'DeepSeek' }}</span></header>
          <p v-if="value.sourceOutlineVersionId !== outline.data.value?.id || value.sourceOutlineRowVersion !== outline.data.value?.version" class="preview-status">来自旧版大纲</p>
          <h5>{{ value.content.title }}</h5>
          <div class="preview-body">{{ value.content.body }}</div>
          <button type="button" class="button secondary" :disabled="busy || !query.data.value" @click="adoptPreview(value)"><Check :size="16" />采用此风格</button>
          <StylePreviewEditor :project-id="projectId" :value="value" :instruction="previewInstruction"
            :current="!outline.isFetching.value && !outline.isError.value && value.sourceOutlineVersionId === outline.data.value?.id && value.sourceOutlineRowVersion === outline.data.value?.version"
            :external-busy="busy && !editorsBusy.has(stylePreviewKey(value))"
            @busy-change="editorBusy(stylePreviewKey(value), $event)" @revised="revisedPreview($event, value)" />
        </article>
      </div>
    </section>
    <section class="style-sample">
      <h3>文字样本</h3>
      <label><span>样本文字</span><textarea v-model="sample" rows="7" maxlength="12000" :disabled="busy || !!file" /></label>
      <div class="style-actions"><label class="sample-upload"><Upload :size="16" /><span>上传样本</span><input type="file" accept=".txt,.md" :disabled="busy" @change="selectFile" /></label><span v-if="file">{{ file.name }}</span><button v-if="file" type="button" class="button secondary" :disabled="busy" @click="file = null">取消文件</button></div>
      <div class="style-actions"><GlobalModelBadge /><button type="button" class="button primary" :disabled="busy || (!file && sample.trim().length < 80)" @click="analyze.mutate()"><WandSparkles :size="16" />{{ analyze.isPending.value ? '正在分析…' : '分析风格' }}</button></div>
    </section>
    <p v-if="status" role="status">{{ status }}</p><p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.style-editor { display: grid; gap: 18px; min-width: 0; }
form, label, .style-sample { display: grid; gap: 8px; min-width: 0; }
form { gap: 16px; }
.style-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.style-details summary { cursor: pointer; color: #176b63; }
.style-details[open] > div, .style-details[open] > label { margin-top: 14px; }
.style-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.style-sample { border-top: 1px solid #ddd; padding-top: 20px; }
.style-recommendation { display: grid; gap: 14px; border-top: 1px solid #ddd; padding-top: 18px; min-width: 0; }
.style-recommendation h3, .style-recommendation p, .recommendation-list h4 { margin: 0; }
.recommendation-list { margin: 0; padding-left: 22px; }
.recommendation-list > li { padding: 14px 0; border-top: 1px solid #e2e5e8; }
.recommendation-list > li > * + * { margin-top: 10px; }
.recommendation-list h4 { font-size: 15px; overflow-wrap: anywhere; }
.recommendation-evidence { padding-left: 18px; font-size: 13px; color: #52606b; overflow-wrap: anywhere; }
.style-preview { display: grid; gap: 14px; border-top: 1px solid #ddd; padding-top: 20px; min-width: 0; }
.style-preview h3, .style-preview p, .preview-result h4, .preview-result h5 { margin: 0; }
.preview-status, .preview-result header span { color: #52606b; font-size: 13px; }
.preview-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 16px; }
.preview-result { display: grid; align-content: start; gap: 14px; min-width: 0; border: 1px solid #cbd2d9; border-radius: 6px; padding: 18px; }
.preview-result header { display: grid; gap: 6px; }
.preview-result h4 { font-size: 16px; overflow-wrap: anywhere; }
.preview-result h5 { font-size: 14px; overflow-wrap: anywhere; }
.preview-body { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; max-height: 560px; overflow-y: auto; }
.preview-result button { justify-self: start; }
.preview-check-toggle { display: flex; align-items: center; gap: 8px; }
.preview-check-toggle input { width: auto; }
input, textarea, select { width: 100%; min-width: 0; box-sizing: border-box; }
input:not([type=file]), textarea, select { padding: 10px 12px; border: 1px solid #cbd2d9; border-radius: 4px; background: #fff; color: #23333d; font: inherit; line-height: 1.5; }
textarea { resize: vertical; }
input:focus-visible, textarea:focus-visible, select:focus-visible { outline: 2px solid #176b63; outline-offset: 2px; }
label > span { color: #52606b; font-size: 13px; }
.style-actions select { width: auto; max-width: 100%; }
.sample-upload { display: flex; flex-wrap: wrap; align-items: center; }
.sample-upload input { max-width: 240px; }
p { overflow-wrap: anywhere; }
@media (max-width: 640px) { .style-fields { grid-template-columns: 1fr; } }
</style>

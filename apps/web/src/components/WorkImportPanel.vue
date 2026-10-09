<script setup lang="ts">
import SnowflakePlanningPanel from '@/components/SnowflakePlanningPanel.vue'
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import ImportAnalysisPanel from '@/components/ImportAnalysisPanel.vue'
import type { AnalysisProof } from '@/api/importAnalyses'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { AlertTriangle, Check, Download, FileText, LoaderCircle, Sparkles, Upload } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import { listWorkImports, prepareImportedDirections, pasteWork, uploadWork, workImportSourceUrl, type ImportPlanningMode, type WorkImport } from '@/api/imports'

const props = defineProps<{ projectId: string; autoAnalyze?: boolean }>()
const emit = defineEmits<{ planningGenerated: [] }>()
const queryClient = useQueryClient()
const selectedImportId = ref<string | null>(null)
const newlyImportedId = ref<string | null>(null)
const actionError = ref('')
const { provider: planningProvider } = useGlobalModelSettings()
const planningMode = ref<ImportPlanningMode>('ADAPT_SOURCE')
const planningInstruction = ref('')
const sourceMode = ref<'file' | 'text'>('file')
const pastedText = ref('')
const targetWords = ref(50000)
const expandScenes = ref(false)
const analysisProof = ref<AnalysisProof | null>(null)
let generationScope = ''
watch(() => [props.projectId, selectedImportId.value, planningMode.value], () => { analysisProof.value = null })

const importsQuery = useQuery({
  queryKey: computed(() => ['work-imports', props.projectId]),
  queryFn: () => listWorkImports(props.projectId),
  refetchInterval: 5000,
})
const selectedImport = computed(() => importsQuery.data.value?.find((item) => item.id === selectedImportId.value) ?? null)
const planningBusy = computed(() => confirmMutation.isPending.value || selectedImport.value?.planningStatus === 'GENERATING')
watch(() => importsQuery.data.value, (items) => {
  if (!items?.some((item) => item.id === selectedImportId.value)) selectedImportId.value = items?.[0]?.id ?? null
}, { immediate: true })

function updateImport(value: WorkImport) {
  queryClient.setQueryData<WorkImport[]>(['work-imports', props.projectId], (current) => {
    const items = current ?? []
    return [value, ...items.filter((item) => item.id !== value.id)]
  })
  selectedImportId.value = value.id
  newlyImportedId.value = value.id
}

const uploadMutation = useMutation({
  mutationFn: (file: File) => uploadWork(props.projectId, file),
  onSuccess: (value) => { updateImport(value); actionError.value = '' },
  onError: (reason: Error) => { actionError.value = reason.message },
})
const pasteMutation = useMutation({
  mutationFn: () => pasteWork(props.projectId, pastedText.value),
  onSuccess: value => { updateImport(value); pastedText.value = ''; actionError.value = '' },
  onError: (reason: Error) => { actionError.value = reason.message },
})
const confirmMutation = useMutation({
  mutationFn: async () => {
    generationScope = `${props.projectId}:${selectedImportId.value}`
    if (planningProvider.value === 'LOCAL_TEMPLATE') throw new Error('反推规划需要在全局设置中选择 ChatGPT 或 DeepSeek。')
    if (!selectedImportId.value) throw new Error('没有可确认的导入记录。')
    const importId = selectedImportId.value
    const projectId = props.projectId
    const proof = analysisProof.value
    if (!proof || proof.mode !== planningMode.value) throw new Error('请先完成并确认当前使用方式的原文解析。')
    const result = await prepareImportedDirections(projectId, importId, planningProvider.value, planningMode.value,
      planningInstruction.value, proof.id, proof.version, targetWords.value, expandScenes.value)
    return { projectId, importId, result }
  },
  onSuccess: (value) => {
    queryClient.setQueryData(['story-directions', value.projectId], value.result)
    queryClient.invalidateQueries({ queryKey: ['project', value.projectId] })
    queryClient.invalidateQueries({ queryKey: ['work-imports', value.projectId] })
    if (props.projectId !== value.projectId || selectedImportId.value !== value.importId) return
    actionError.value = ''
    emit('planningGenerated')
  },
  onError: (reason: Error) => { if (generationScope === `${props.projectId}:${selectedImportId.value}`) actionError.value = reason.message },
})

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) uploadMutation.mutate(file)
  input.value = ''
}
function sizeLabel(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
function contentTypeLabel(value: string) {
  return ({ MANUSCRIPT: '小说正文', OUTLINE: '故事大纲', MATERIALS: '人物与世界设定' } as Record<string, string>)[value] ?? value
}
</script>

<template>
  <div class="import-workbench">
    <div class="section-heading">
      <div><span class="eyebrow">已有作品</span><h2>导入与章节识别</h2></div>
      <div class="import-source-options" role="radiogroup" aria-label="导入方式"><label><input v-model="sourceMode" type="radio" value="file" />导入文件</label><label><input v-model="sourceMode" type="radio" value="text" />粘贴文字</label></div>
    </div>
    <label v-if="sourceMode === 'file'" class="button primary import-upload"><Upload :size="16" />{{ uploadMutation.isPending.value ? '正在导入…' : '选择文件' }}<input type="file" accept=".txt,.md,.docx,.pdf" :disabled="uploadMutation.isPending.value" @change="selectFile" /></label>
    <form v-else class="paste-source" @submit.prevent="pasteMutation.mutate()"><label class="instruction-field"><span>故事文字</span><textarea v-model="pastedText" rows="8" :disabled="pasteMutation.isPending.value" /></label><button class="button primary" :disabled="!pastedText.trim() || pasteMutation.isPending.value"><FileText :size="16" />{{ pasteMutation.isPending.value ? '正在导入…' : '导入文字' }}</button></form>
    <SnowflakePlanningPanel :project-id="projectId" />
    <div v-if="actionError" class="form-error" role="alert">{{ actionError }}</div>

    <div v-if="importsQuery.isPending.value" class="editor-empty">正在读取导入记录…</div>
    <div v-else-if="importsQuery.isError.value" class="status-panel error-panel">导入记录加载失败：{{ importsQuery.error.value?.message }}</div>
    <div v-else-if="!importsQuery.data.value?.length" class="import-empty"><Upload :size="30" /><h3>导入已经写好的内容</h3><p>系统会识别章节并生成报告，确认前不会写入正史。</p></div>

    <div v-else class="import-layout">
      <aside class="import-list">
        <button v-for="item in importsQuery.data.value" :key="item.id" type="button" :class="{ active: selectedImportId === item.id }" @click="selectedImportId = item.id">
          <FileText :size="17" /><span><strong>{{ item.originalFilename }}</strong><small>{{ item.chapters.length }} 个章节 · {{ sizeLabel(item.sizeBytes) }}</small></span><Check v-if="item.status === 'CONFIRMED'" :size="16" />
        </button>
      </aside>

      <main v-if="selectedImport" class="import-report">
        <header><div><span class="eyebrow">导入报告</span><h2>{{ selectedImport.originalFilename }}</h2></div><span :class="['import-status', selectedImport.status.toLowerCase()]">{{ selectedImport.status === 'CONFIRMED' ? '已确认' : '待确认' }}</span></header>
        <div class="import-summary">
          <span><strong>{{ contentTypeLabel(selectedImport.detectedContentType) }}</strong>识别类型</span>
          <span><strong>{{ selectedImport.chapters.length }}</strong>章节数</span>
          <span><strong>{{ selectedImport.chapters.reduce((sum, item) => sum + item.characterCount, 0).toLocaleString('zh-CN') }}</strong>正文字符</span>
          <a :href="workImportSourceUrl(projectId, selectedImport.id)"><Download :size="14" />原始文件</a>
        </div>

        <section v-if="selectedImport.warnings.length" class="import-warnings"><h3><AlertTriangle :size="16" />需要注意</h3><p v-for="warning in selectedImport.warnings" :key="warning">{{ warning }}</p></section>

        <section class="import-chapters">
          <h3>章节识别结果</h3>
          <details v-for="chapter in selectedImport.chapters" :key="chapter.id">
            <summary><span>{{ chapter.ordinal }}</span><strong>{{ chapter.title }}</strong><small>{{ chapter.characterCount.toLocaleString('zh-CN') }} 字符</small></summary>
            <p>{{ chapter.content }}</p>
          </details>
        </section>

        <div class="import-actions">
          <div v-if="planningBusy" class="acceptance-note" role="status"><LoaderCircle :size="16" />雪花规划请求中 · 依次生成底稿和故事方向</div>
          <div v-if="selectedImport.planningStatus === 'DIRECTIONS_READY'" class="acceptance-note"><Check :size="16" />雪花底稿与故事方向已生成</div>
          <div class="planning-mode-field">
            <span>这份内容怎么使用</span>
            <div class="planning-mode-options">
              <label :class="{ selected: planningMode === 'ADAPT_SOURCE' }"><input v-model="planningMode" type="radio" value="ADAPT_SOURCE" /><strong>作为故事素材改编</strong><small>扩写、优化和改变为小说，从第一章重新创作</small></label>
              <label :class="{ selected: planningMode === 'CONTINUE_MANUSCRIPT' }"><input v-model="planningMode" type="radio" value="CONTINUE_MANUSCRIPT" /><strong>作为已有正文续写</strong><small>保留已经发生的内容，在其后继续写作</small></label>
            </div>
          </div>
            <ImportAnalysisPanel :key="`${projectId}:${selectedImport.id}`" :project-id="projectId" :import-id="selectedImport.id" :mode="planningMode" :chapters="selectedImport.chapters" :auto-start="autoAnalyze || newlyImportedId === selectedImport.id" :disabled="planningBusy" @ready="analysisProof = $event" @confirmed="importsQuery.refetch()" />
            <GlobalModelBadge />
            <label class="instruction-field"><span>改编或续写要求</span><textarea v-model="planningInstruction" rows="2" maxlength="1000" placeholder="可选，例如：扩写为青春校园成长小说，增强人物弧光" /></label>
            <label class="instruction-field"><span>目标字数</span><input v-model.number="targetWords" type="number" min="1000" max="10000000" step="1000" :disabled="planningBusy" /></label>
            <label><input v-model="expandScenes" type="checkbox" :disabled="planningBusy" />执行第9步：关键场景展开</label>
            <button class="button primary" type="button" :disabled="planningBusy || !analysisProof || targetWords < 1000" @click="confirmMutation.mutate()"><Sparkles :size="16" />{{ planningBusy ? '正在生成雪花底稿…' : '生成雪花底稿与故事方向' }}</button>
            <p v-if="selectedImport.planningError" class="form-error">上次反推失败：{{ selectedImport.planningError }}</p>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.import-source-options { display: flex; gap: 16px; flex-wrap: wrap; }
.import-source-options label { display: flex; gap: 6px; align-items: center; cursor: pointer; }
.import-upload { margin-block: 12px; }
.paste-source { display: grid; gap: 12px; margin-block: 16px; }
.paste-source button { justify-self: start; }
.paste-source textarea { width: 100%; min-width: 0; resize: vertical; }
</style>

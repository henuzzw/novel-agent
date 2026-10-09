<script setup lang="ts">
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import GenerationModeControl from '@/components/GenerationModeControl.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import PlanningCheckpointPanel from '@/components/PlanningCheckpointPanel.vue'
import ReaderExperienceSeedEditor from './ReaderExperienceSeedEditor.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, ChevronDown, RefreshCw, Save, Sparkles } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import {
  generateOutline,
  getCurrentOutline,
  getLatestOutline,
  getOutlineVersion,
  listOutlineVersions,
  publishOutline,
  updateOutline,
  type GenerationMode,
  type ChapterPlan,
  type OutlineContent,
  type OutlineVersion,
} from '@/api/planning'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ 'choose-style': [] }>()
const queryClient = useQueryClient()
const { provider: provider } = useGlobalModelSettings()
const generationMode = ref<GenerationMode>('REVISE')
const baseOutlineVersionId = ref('')
const instruction = ref('')
const actionError = ref('')
const draft = ref<OutlineContent | null>(null)
const planningBusy = ref(false)
const editingBusy = computed(() => generateMutation.isPending.value || saveMutation.isPending.value ||
  publishMutation.isPending.value || restoreMutation.isPending.value)

const outlineQuery = useQuery({
  queryKey: computed(() => ['outline', props.projectId]),
  queryFn: () => getLatestOutline(props.projectId),
})
const currentOutlineQuery = useQuery({
  queryKey: computed(() => ['current-outline', props.projectId]),
  queryFn: () => getCurrentOutline(props.projectId),
})
const versionsQuery = useQuery({
  queryKey: computed(() => ['outline-versions', props.projectId]),
  queryFn: () => listOutlineVersions(props.projectId),
  enabled: computed(() => !!outlineQuery.data.value),
})
const basePreviewQuery = useQuery({
  queryKey: computed(() => ['outline-version', props.projectId, baseOutlineVersionId.value]),
  queryFn: () => getOutlineVersion(props.projectId, baseOutlineVersionId.value),
  enabled: computed(() => !!baseOutlineVersionId.value),
})

function copyContent(value: OutlineContent): OutlineContent {
  return {
    ...value,
    readerExperiencePlans: value.readerExperiencePlans?.map(plan => ({ ...plan })),
    arcs: value.arcs.map((arc) => ({
      ...arc,
      chapters: arc.chapters.map((chapter) => ({ ...chapter, sceneOutline: chapter.sceneOutline ?? '',
        sceneOutlineNeedsUpdate: chapter.sceneOutlineNeedsUpdate ?? !chapter.sceneOutline?.trim() })),
    })),
  }
}

watch(() => outlineQuery.data.value, (value) => {
  draft.value = value ? copyContent(value.content) : null
}, { immediate: true })
watch(() => props.projectId, () => { baseOutlineVersionId.value = ''; planningBusy.value = false })

const chapterCount = computed(() => draft.value?.arcs.reduce((sum, arc) => sum + arc.chapters.length, 0) ?? 0)
const editable = computed(() => outlineQuery.data.value?.status === 'DRAFT')
const hasUnsavedChanges = computed(() => editable.value && !!draft.value && !!outlineQuery.data.value
  && JSON.stringify(draft.value) !== JSON.stringify(copyContent(outlineQuery.data.value.content)))
useUnsavedChanges(hasUnsavedChanges, ['section', 'planning'])
const selectedBase = computed(() => versionsQuery.data.value?.find(
  (version) => version.id === baseOutlineVersionId.value,
))
const historicalVersions = computed(() => versionsQuery.data.value?.filter(
  (version) => version.id !== outlineQuery.data.value?.id,
) ?? [])
const currentIsOlder = computed(() => !!currentOutlineQuery.data.value &&
  currentOutlineQuery.data.value.id !== outlineQuery.data.value?.id)
const currentBaseNumber = computed(() => versionsQuery.data.value?.find(
  (version) => version.id === outlineQuery.data.value?.baseOutlineVersionId,
)?.generationNumber)
const isLatestCurrent = computed(() => outlineQuery.data.value?.id === currentOutlineQuery.data.value?.id)

function sceneStatus(chapter: ChapterPlan) {
  if (!chapter.sceneOutline?.trim()) return '待补充'
  const original = outlineQuery.data.value?.content
  const oldArc = original?.arcs.find(arc => arc.chapters.some(value => value.number === chapter.number))
  const arc = draft.value?.arcs.find(value => value.chapters.some(item => item.number === chapter.number))
  const previous = oldArc?.chapters.find(value => value.number === chapter.number)
  if (previous && chapter.sceneOutline !== previous.sceneOutline) return ''
  const chapterChanged = previous && ['title', 'pov', 'objective', 'coreEvent', 'reveal', 'endingHook', 'status']
    .some(key => chapter[key as keyof ChapterPlan] !== previous[key as keyof ChapterPlan])
  const bookChanged = original && draft.value && (original.premise !== draft.value.premise ||
    original.structureSummary !== draft.value.structureSummary || original.pacingStrategy !== draft.value.pacingStrategy ||
    JSON.stringify(original.readerExperiencePlans ?? []) !== JSON.stringify(draft.value.readerExperiencePlans ?? []))
  const arcChanged = arc && oldArc && (arc.objective !== oldArc.objective || arc.mainConflict !== oldArc.mainConflict ||
    arc.turningPoint !== oldArc.turningPoint || arc.outcome !== oldArc.outcome)
  return chapter.sceneOutlineNeedsUpdate || chapterChanged || bookChanged || arcChanged ? '依据已变化，场景底稿待核对' : ''
}

function updateCache(value: OutlineVersion) {
  queryClient.setQueryData(['outline', props.projectId], value)
  queryClient.invalidateQueries({ queryKey: ['outline-versions', props.projectId] })
  queryClient.invalidateQueries({ queryKey: ['outline-version', props.projectId, value.id] })
  draft.value = copyContent(value.content)
}

function selectAssembledOutline(value: OutlineVersion) {
  if (value.projectId !== props.projectId) return
  if (draft.value && outlineQuery.data.value &&
    hasUnsavedChanges.value &&
    !window.confirm('当前大纲有未保存修改。确认切换到拼装结果？未保存修改将被丢弃。')) return
  if (outlineQuery.data.value && value.generationNumber < outlineQuery.data.value.generationNumber) {
    baseOutlineVersionId.value = value.id
    return
  }
  updateCache(value)
  baseOutlineVersionId.value = ''
  actionError.value = ''
}

const generateMutation = useMutation({
  mutationFn: () => generateOutline(
    props.projectId,
    instruction.value,
    provider.value,
    outlineQuery.data.value ? generationMode.value : 'REGENERATE',
    baseOutlineVersionId.value || (currentIsOlder.value ? currentOutlineQuery.data.value!.id : null),
  ),
  onSuccess: (value) => { updateCache(value); instruction.value = ''; actionError.value = '' },
  onError: (error: Error) => { actionError.value = error.message },
})

const saveMutation = useMutation({
  mutationFn: () => {
    if (!outlineQuery.data.value || !draft.value) throw new Error('没有可保存的大纲。')
    return updateOutline(props.projectId, outlineQuery.data.value, draft.value)
  },
  onSuccess: (value) => { updateCache(value); actionError.value = '' },
  onError: (error: Error) => { actionError.value = error.message },
})

const publishMutation = useMutation({
  mutationFn: () => {
    if (!outlineQuery.data.value) throw new Error('没有可发布的大纲。')
    return publishOutline(props.projectId, outlineQuery.data.value)
  },
  onSuccess: (value) => {
    updateCache(value)
    queryClient.setQueryData(['current-outline', props.projectId], value)
    queryClient.invalidateQueries({ queryKey: ['chapter-contract', props.projectId] })
    queryClient.invalidateQueries({ predicate: query => query.queryKey.includes(props.projectId) })
    actionError.value = ''
  },
  onError: (error: Error) => { actionError.value = error.message },
})

const restoreMutation = useMutation({
  mutationFn: async () => {
    const selected = basePreviewQuery.data.value
    if (!selected || selected.id !== baseOutlineVersionId.value) throw new Error('请先选择并加载历史大纲。')
    if (!window.confirm(`确认将第 ${selected.generationNumber} 版恢复为当前写作大纲？已有正文可能需要按此版调整；正文和正史不会自动回退。`)) return null
    return publishOutline(props.projectId, selected)
  },
  onSuccess: (value) => {
    if (!value) return
    queryClient.setQueryData(['current-outline', props.projectId], value)
    queryClient.setQueryData(['outline-version', props.projectId, value.id], value)
    queryClient.invalidateQueries({ queryKey: ['outline-versions', props.projectId] })
    queryClient.invalidateQueries({ queryKey: ['chapter-contract', props.projectId] })
    actionError.value = ''
  },
  onError: (error: Error) => { actionError.value = error.message },
})

function formatWords(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}
</script>

<template>
  <div class="outline-panel">
    <div class="section-heading">
      <div><span class="eyebrow">全书规划</span><h2>分层大纲</h2></div>
      <span v-if="outlineQuery.data.value" class="version-label">最新生成：第 {{ outlineQuery.data.value.generationNumber }} 版 · {{ outlineQuery.data.value.status === 'PUBLISHED' ? '已发布' : '草稿' }}</span>
    </div>

    <div class="direction-actions generation-toolbar">
      <label class="instruction-field"><span>本次调整要求</span><textarea v-model="instruction" rows="2" maxlength="1000" placeholder="可选，例如：前十章节奏更快，减少解释性章节" /></label>
      <GenerationModeControl v-if="outlineQuery.data.value" v-model="generationMode" revise-label="基于选定版本调整" :disabled="editingBusy || planningBusy" />
      <label v-if="outlineQuery.data.value" class="provider-field outline-base-field"><span>选择历史版本</span><select v-model="baseOutlineVersionId"><option value="">{{ currentOutlineQuery.data.value ? `当前写作大纲（第 ${currentOutlineQuery.data.value.generationNumber} 版）` : `最新版本（第 ${outlineQuery.data.value.generationNumber} 版）` }}</option><option v-if="currentIsOlder" :value="outlineQuery.data.value.id">最新生成（第 {{ outlineQuery.data.value.generationNumber }} 版）</option><option v-for="version in historicalVersions" :key="version.id" :value="version.id">第 {{ version.generationNumber }} 版 · {{ version.status === 'PUBLISHED' ? '已发布' : '草稿' }} · {{ version.title }}</option></select></label>
      <GlobalModelBadge />
      <div class="direction-action-buttons">
        <button class="button secondary" type="button" :disabled="editingBusy || planningBusy" @click="generateMutation.mutate()"><RefreshCw :size="16" />{{ generateMutation.isPending.value ? '正在生成…' : outlineQuery.data.value ? generationMode === 'REVISE' ? '按要求调整' : '重新生成' : '生成分层大纲' }}</button>
        <button v-if="editable" class="button secondary" type="button" :disabled="editingBusy || planningBusy" @click="saveMutation.mutate()"><Save :size="16" />{{ saveMutation.isPending.value ? '正在保存…' : '保存修改' }}</button>
        <button v-if="editable" class="button primary" type="button" :disabled="editingBusy || planningBusy || hasUnsavedChanges" @click="publishMutation.mutate()"><Check :size="16" />{{ publishMutation.isPending.value ? '正在发布…' : '确认并发布' }}</button>
        <button v-if="outlineQuery.data.value" type="button" class="button secondary" @click="emit('choose-style')"><Sparkles :size="16" />选择风格并试写</button>
      </div>
    </div>

    <div v-if="actionError" class="form-error" role="alert">{{ actionError }}</div>
    <p v-if="hasUnsavedChanges" class="acceptance-note" role="status">大纲有未保存修改，请先保存再发布。</p>
    <PlanningCheckpointPanel :key="projectId" :project-id="projectId" :provider="provider" :external-busy="editingBusy" @busy-change="planningBusy = $event" @assembled="selectAssembledOutline" />

    <div v-if="outlineQuery.isPending.value" class="direction-loading">正在读取分层大纲…</div>
    <div v-else-if="outlineQuery.isError.value" class="status-panel error-panel"><strong>大纲加载失败</strong><span>{{ outlineQuery.error.value?.message }}</span></div>

    <template v-else-if="draft && outlineQuery.data.value">
      <section v-if="outlineQuery.data.value.changeSummary.length" class="change-summary">
        <strong>本版修改说明</strong>
        <ul><li v-for="item in outlineQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
      </section>
      <div class="outline-summary-bar">
        <span><strong>{{ draft.arcs.length }}</strong>卷 / 幕</span>
        <span><strong>{{ chapterCount }}</strong>章节</span>
        <span><strong>{{ formatWords(draft.suggestedMinWords) }}～{{ formatWords(draft.suggestedMaxWords) }}</strong>建议总字数</span>
        <small>目标约 {{ formatWords(outlineQuery.data.value.wordBudget.targetWords) }} 字，允许上下约 1 万字浮动</small>
      </div>
      <div v-if="currentOutlineQuery.data.value" class="selection-notice"><Check :size="17" />当前用于写作：第 {{ currentOutlineQuery.data.value.generationNumber }} 版 · {{ currentOutlineQuery.data.value.content.title }}<span v-if="!isLatestCurrent">（下方展示的是最新生成版本）</span></div>
      <div v-else-if="currentOutlineQuery.isError.value" class="form-error" role="alert">当前写作大纲加载失败：{{ currentOutlineQuery.error.value?.message }}</div>

      <div class="outline-book-fields">
        <label class="bible-field"><span>大纲标题</span><input v-model="draft.title" :disabled="!editable" /></label>
        <label class="bible-field full"><span>全书前提</span><textarea v-model="draft.premise" :disabled="!editable" rows="2" /></label>
        <label class="bible-field"><span>结构路线</span><textarea v-model="draft.structureSummary" :disabled="!editable" rows="4" /></label>
        <label class="bible-field"><span>节奏策略</span><textarea v-model="draft.pacingStrategy" :disabled="!editable" rows="4" /></label>
      </div>

      <ReaderExperienceSeedEditor :model-value="draft.readerExperiencePlans ?? []" :disabled="!editable || editingBusy" @update:model-value="draft.readerExperiencePlans = $event" />
      <div class="outline-arcs">
        <section v-for="arc in draft.arcs" :key="arc.ordinal" class="outline-arc">
          <header>
            <span class="arc-index">{{ arc.ordinal }}</span>
            <div><input v-model="arc.title" :disabled="!editable" aria-label="卷标题" /><small>{{ arc.chapters.length }} 章 · 约 {{ formatWords(arc.suggestedMinWords) }}～{{ formatWords(arc.suggestedMaxWords) }} 字</small></div>
          </header>
          <div class="arc-fields">
            <label><span>阶段目标</span><textarea v-model="arc.objective" :disabled="!editable" rows="2" /></label>
            <label><span>主要对抗</span><textarea v-model="arc.mainConflict" :disabled="!editable" rows="2" /></label>
            <label><span>关键转折</span><textarea v-model="arc.turningPoint" :disabled="!editable" rows="2" /></label>
            <label><span>退出状态</span><textarea v-model="arc.outcome" :disabled="!editable" rows="2" /></label>
          </div>
          <div class="chapter-list">
            <details v-for="chapter in arc.chapters" :key="chapter.number">
              <summary><span>第 {{ chapter.number }} 章</span><strong>{{ chapter.title }}</strong><em :class="['chapter-plan-status', (chapter.status ?? 'PLANNED').toLowerCase()]">{{ chapter.status === 'OCCURRED' ? '已发生' : '待规划' }}</em><small>{{ chapter.pov }} · {{ formatWords(chapter.suggestedMinWords) }}～{{ formatWords(chapter.suggestedMaxWords) }} 字</small><ChevronDown :size="16" /></summary>
              <div class="chapter-fields">
                <label><span>标题</span><input v-model="chapter.title" :disabled="!editable" /></label>
                <label><span>POV</span><input v-model="chapter.pov" :disabled="!editable" /></label>
                <label><span>章节目标</span><textarea v-model="chapter.objective" :disabled="!editable" rows="2" /></label>
                <label><span>核心事件</span><textarea v-model="chapter.coreEvent" :disabled="!editable" rows="2" /></label>
                <label><span>必要揭示</span><textarea v-model="chapter.reveal" :disabled="!editable" rows="2" /></label>
                <label><span>结尾钩子</span><textarea v-model="chapter.endingHook" :disabled="!editable" rows="2" /></label>
                <label class="chapter-scene-field"><span>场景清单与展开</span><textarea v-model="chapter.sceneOutline" :disabled="!editable" rows="8" /><small v-if="sceneStatus(chapter)" class="scene-outline-status" role="status">{{ sceneStatus(chapter) }}</small></label>
              </div>
            </details>
          </div>
        </section>
      </div>
    </template>

    <div v-else class="direction-empty"><Sparkles :size="30" /><h3>从已发布故事圣经生成分层大纲</h3><p>先搭建全书路线，再拆分卷/幕和章节；字数只作容量参考。</p></div>

    <div v-if="versionsQuery.isError.value && outlineQuery.data.value" class="form-error" role="alert">历史版本加载失败：{{ versionsQuery.error.value?.message }}</div>
    <details v-if="baseOutlineVersionId" class="outline-base-preview">
      <summary>查看第 {{ selectedBase?.generationNumber ?? '…' }} 版历史大纲</summary>
      <div v-if="basePreviewQuery.isPending.value" class="direction-loading">正在读取历史大纲…</div>
      <div v-else-if="basePreviewQuery.isError.value" class="form-error" role="alert">历史大纲加载失败：{{ basePreviewQuery.error.value?.message }}</div>
      <div v-else-if="basePreviewQuery.data.value" class="outline-base-preview-body">
        <h3>{{ basePreviewQuery.data.value.content.title }}</h3>
        <div class="outline-restore-action"><button v-if="basePreviewQuery.data.value.id !== currentOutlineQuery.data.value?.id" class="button secondary" type="button" :disabled="editingBusy || planningBusy" @click="restoreMutation.mutate()"><RefreshCw :size="16" />{{ restoreMutation.isPending.value ? '正在恢复…' : '恢复此版为当前大纲' }}</button><span v-else>此版正用于写作</span></div>
        <p>{{ basePreviewQuery.data.value.content.premise }}</p>
        <p>{{ basePreviewQuery.data.value.content.structureSummary }}</p>
        <section v-for="arc in basePreviewQuery.data.value.content.arcs" :key="arc.ordinal">
          <h4>第 {{ arc.ordinal }} 卷 · {{ arc.title }}</h4>
          <p>{{ arc.objective }} · {{ arc.turningPoint }}</p>
          <ol><li v-for="chapter in arc.chapters" :key="chapter.number">第 {{ chapter.number }} 章 {{ chapter.title }}：{{ chapter.coreEvent }}<p v-if="chapter.sceneOutline" class="scene-outline-text">{{ chapter.sceneOutline }}</p></li></ol>
        </section>
      </div>
    </details>
    <p v-if="outlineQuery.data.value" class="generator-note">本版本由 {{ outlineQuery.data.value.generatorType }} 生成<span v-if="currentBaseNumber"> · 基于第 {{ currentBaseNumber }} 版调整</span></p>
  </div>
</template>

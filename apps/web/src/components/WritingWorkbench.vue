<script setup lang="ts">
import { navigateWorkspaceButtons } from '@/lib/workspace-keyboard'
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import GenerationModeControl from '@/components/GenerationModeControl.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useWorkspaceChapter, useWorkspaceChoice, writingViews } from '@/composables/useWorkspaceLocation'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { BrainCircuit, Check, Download, FileSearch, FileText, RefreshCw, Save } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import QualityReviewPanel from './QualityReviewPanel.vue'
import PublishedMemoryPanel from './PublishedMemoryPanel.vue'
import DraftLoopPanel from './DraftLoopPanel.vue'
import FirstThreeChaptersPanel from './FirstThreeChaptersPanel.vue'
import ManuscriptLocalEditPanel from './ManuscriptLocalEditPanel.vue'
import type { QualityReportState } from '@/api/writingQuality'
import type { AutomationChapterTarget } from '@/api/automation'
import { getCurrentOutline, type GenerationMode } from '@/api/planning'
import { getProject } from '@/api/projects'
import {
  publishManuscript, createManuscriptRevision,
  generateManuscript,
  getLatestManuscript, getManuscriptVersion, getMemoryPreview,
  getCanonCommitStatus,
  listManuscriptVersions, updateManuscript,
  manuscriptExportUrl,
  type ManuscriptContent, type ManuscriptVersion,
} from '@/api/writing'

const props = defineProps<{ projectId: string; initialTarget?: AutomationChapterTarget | null }>()
const queryClient = useQueryClient()
const selectedChapter = useWorkspaceChapter()
const mode = useWorkspaceChoice('writing', writingViews, 'manuscript')
watch(() => props.initialTarget, target => {
  if (!target) return
  selectedChapter.value = target.chapter
  mode.value = target.mode
}, { immediate: true })
const { provider: provider } = useGlobalModelSettings()
const manuscriptGenerationMode = ref<GenerationMode>('REVISE')
const baseManuscriptVersionId = ref('')
const instruction = ref('')
const qualityBusy = ref(false)
const draftLoopBusy = ref(false)
const draftLoopRefreshes = ref(0)
const draftLoopEditingLocked = computed(() => draftLoopBusy.value || draftLoopRefreshes.value > 0)
const localEditBusy = ref(false)
const openingBusy = ref(false)
const openingOpened = ref(false)
const openingRefresh = ref(0)
watch(mode, value => { if (value === 'opening') openingOpened.value = true })
const qualityState = ref<QualityReportState>('unrun')
const qualityLabel = ref('未检查')
function setQualityState(state: QualityReportState, label: string) {
  if (state === 'valid' && qualityState.value !== 'valid') openingRefresh.value++
  qualityState.value = state; qualityLabel.value = label
}
const error = ref('')
const manuscriptDraft = ref<ManuscriptContent | null>(null)
const outlineQuery = useQuery({ queryKey: computed(() => ['current-outline', props.projectId]), queryFn: () => getCurrentOutline(props.projectId) })
const projectQuery = useQuery({ queryKey: computed(() => ['project', props.projectId]), queryFn: () => getProject(props.projectId) })
const currentChapter = computed(() => outlineQuery.data.value?.content.arcs
  .flatMap((arc) => arc.chapters).find((chapter) => chapter.number === selectedChapter.value))
const memorySearchText = computed(() => {
  const chapter = currentChapter.value
  return chapter ? `${chapter.title} ${chapter.objective} ${chapter.coreEvent}` : `第 ${selectedChapter.value} 章`
})
const memoryQuery = useQuery({
  queryKey: computed(() => ['novel-memory', props.projectId, selectedChapter.value,
    projectQuery.data.value?.currentCanonVersion, provider.value]),
  queryFn: () => getMemoryPreview(props.projectId, selectedChapter.value, memorySearchText.value, provider.value),
  enabled: computed(() => mode.value === 'memory' && !!projectQuery.data.value),
})
const manuscriptQuery = useQuery({
  queryKey: computed(() => ['manuscript', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestManuscript(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const manuscriptVersionsQuery = useQuery({
  queryKey: computed(() => ['manuscript-versions', props.projectId, selectedChapter.value]),
  queryFn: () => listManuscriptVersions(props.projectId, selectedChapter.value),
  enabled: computed(() => mode.value === 'manuscript' && !!manuscriptQuery.data.value),
})
const baseManuscriptQuery = useQuery({
  queryKey: computed(() => ['manuscript-version', props.projectId, selectedChapter.value, baseManuscriptVersionId.value]),
  queryFn: () => getManuscriptVersion(props.projectId, selectedChapter.value, baseManuscriptVersionId.value),
  enabled: computed(() => mode.value === 'manuscript' && manuscriptGenerationMode.value === 'REVISE'
    && !!baseManuscriptVersionId.value),
})
const selectedBaseManuscript = computed(() => manuscriptVersionsQuery.data.value?.find(
  (item) => item.id === baseManuscriptVersionId.value,
))
const manuscriptBaseNumber = computed(() => manuscriptVersionsQuery.data.value?.find(
  (item) => item.id === manuscriptQuery.data.value?.baseManuscriptVersionId,
)?.versionNumber)
const canonStatusQuery = useQuery({
  queryKey: computed(() => ['canon-commit-status', props.projectId, selectedChapter.value]),
  queryFn: () => getCanonCommitStatus(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const currentManuscriptPublished = computed(() => !!manuscriptQuery.data.value
  && canonStatusQuery.data.value?.activeManuscriptVersionId === manuscriptQuery.data.value.id)
useUnsavedChanges(computed(() => !!manuscriptDraft.value && !!manuscriptQuery.data.value
  && JSON.stringify(manuscriptDraft.value) !== JSON.stringify(manuscriptQuery.data.value.content)), ['section', 'chapter'])
const openingRefreshKey = computed(() => JSON.stringify([
  outlineQuery.data.value?.id, outlineQuery.data.value?.version, projectQuery.data.value?.version,
  projectQuery.data.value?.currentCanonVersion, manuscriptQuery.data.value?.id, manuscriptQuery.data.value?.version,
  openingRefresh.value,
]))
function copyManuscript(value: ManuscriptContent): ManuscriptContent {
  return { ...value, continuityNotes: [...value.continuityNotes] }
}
watch(() => manuscriptQuery.data.value, (value) => { manuscriptDraft.value = value ? copyManuscript(value.content) : null }, { immediate: true })
watch(() => [props.projectId, selectedChapter.value], () => {
  error.value = ''; instruction.value = ''; baseManuscriptVersionId.value = ''
  qualityState.value = 'unrun'; qualityLabel.value = '未检查'
})

function setManuscript(value: ManuscriptVersion) {
  queryClient.setQueryData(['manuscript', props.projectId, selectedChapter.value], value)
  queryClient.invalidateQueries({ queryKey: ['manuscript-versions', props.projectId, selectedChapter.value] })
  manuscriptDraft.value = copyManuscript(value.content)
}
async function refreshDraftLoopManuscript() {
  draftLoopRefreshes.value++
  try {
    await manuscriptQuery.refetch()
    await manuscriptVersionsQuery.refetch()
  } finally { draftLoopRefreshes.value-- }
}
function lines(value: string) { return value.split('\n').map((item) => item.trim()).filter(Boolean) }
function joined(value: string[] | undefined) { return value?.join('\n') ?? '' }
function toolLabel(value: string) {
  return ({
    GET_RECENT_CHAPTER_SUMMARIES: '近期章节',
    SEARCH_STORY_MEMORY: '语义历史检索',
    GET_RELATED_CANON_FACTS: '相关正史事实',
  } as Record<string, string>)[value] ?? value
}
function updateNotes(event: Event) { if (manuscriptDraft.value) manuscriptDraft.value.continuityNotes = lines((event.target as HTMLTextAreaElement).value) }
const busy = computed(() => draftLoopEditingLocked.value || qualityBusy.value || localEditBusy.value || openingBusy.value || generateManuscriptMutation.isPending.value
  || saveManuscriptMutation.isPending.value || createManuscriptRevisionMutation.isPending.value
  || publishManuscriptMutation.isPending.value)
const fail = (reason: Error) => { error.value = reason.message }

const generateManuscriptMutation = useMutation({ mutationFn: () => {
  const generationMode = manuscriptQuery.data.value ? manuscriptGenerationMode.value : 'REGENERATE'
  if (generationMode === 'REVISE' && baseManuscriptVersionId.value
    && baseManuscriptQuery.data.value?.id !== baseManuscriptVersionId.value) {
    throw new Error('请先加载并确认选定的历史正文。')
  }
  return generateManuscript(props.projectId, selectedChapter.value, provider.value, instruction.value,
    generationMode, generationMode === 'REVISE' ? baseManuscriptVersionId.value || null : null)
}, onSuccess: (v) => { setManuscript(v); instruction.value = ''; error.value = '' }, onError: fail })
const saveManuscriptMutation = useMutation({ mutationFn: () => {
  if (!manuscriptQuery.data.value || !manuscriptDraft.value) throw new Error('没有可保存的正文。')
  return updateManuscript(props.projectId, manuscriptQuery.data.value, manuscriptDraft.value)
}, onSuccess: setManuscript, onError: fail })
const createManuscriptRevisionMutation = useMutation({ mutationFn: () => {
  if (!manuscriptQuery.data.value || manuscriptQuery.data.value.status !== 'AUTHOR_ACCEPTED') {
    throw new Error('请先由作者确认正文。')
  }
  return createManuscriptRevision(props.projectId, manuscriptQuery.data.value)
}, onSuccess: (value) => { setManuscript(value); error.value = '' }, onError: fail })
const publishManuscriptMutation = useMutation({ mutationFn: () => {
  const manuscript = manuscriptQuery.data.value
  const status = canonStatusQuery.data.value
  const project = projectQuery.data.value
  if (!manuscript || !manuscriptDraft.value) throw new Error('没有可发布的正文。')
  if (!status || !project) throw new Error('发布状态尚未读取，请稍后再试。')
  if (JSON.stringify(manuscriptDraft.value) !== JSON.stringify(manuscript.content)) {
    throw new Error('正文有未保存的修改，请先保存再确认并发布。')
  }
  return publishManuscript(props.projectId, manuscript, project.currentCanonVersion,
    status.activeCommitId, provider.value)
}, onSuccess: (commit) => {
  error.value = ''
  queryClient.setQueryData(['canon-commit-status', commit.projectId, commit.chapterNumber], {
    committed: true, activeCommitId: commit.id, activeManuscriptVersionId: commit.manuscriptVersionId,
    canonVersion: commit.canonVersion,
  })
  queryClient.invalidateQueries({ queryKey: ['manuscript', commit.projectId, commit.chapterNumber] })
  queryClient.invalidateQueries({ queryKey: ['manuscript-versions', commit.projectId, commit.chapterNumber] })
  queryClient.invalidateQueries({ queryKey: ['project', commit.projectId] })
  queryClient.invalidateQueries({ queryKey: ['published-memory', commit.projectId, commit.chapterNumber] })
  queryClient.invalidateQueries({ queryKey: ['novel-memory', commit.projectId] })
}, onError: fail })
</script>

<template>
  <div v-if="outlineQuery.isPending.value" class="direction-loading">正在读取已发布大纲…</div>
  <div v-else-if="!outlineQuery.data.value || outlineQuery.data.value.status !== 'PUBLISHED'" class="direction-empty">
    <FileText :size="30" /><h3>先发布分层大纲</h3><p>写作工作台会从已发布大纲中读取章节。</p>
  </div>
  <div v-else class="writing-workbench">
    <aside class="chapter-rail">
      <div class="chapter-rail-title">章节</div>
      <section v-for="arc in outlineQuery.data.value.content.arcs" :key="arc.ordinal">
        <strong>{{ arc.title }}</strong>
        <button v-for="chapter in arc.chapters" :key="chapter.number" type="button"
          :class="{ active: selectedChapter === chapter.number }" :disabled="busy" @click="selectedChapter = chapter.number">
          <span>{{ chapter.number }}</span><span>{{ chapter.title }}</span>
        </button>
      </section>
    </aside>

    <main class="chapter-editor">
      <div class="chapter-workflow-top">
        <div class="chapter-workflow-heading"><strong>第 {{ selectedChapter }} 章 · {{ currentChapter?.title }}</strong></div>
        <nav class="chapter-flow" aria-label="章节写作进度">
          <button type="button" :class="{ active: mode === 'manuscript', done: !!manuscriptQuery.data.value }" @click="mode = 'manuscript'"><span>01</span>正文草稿<small>{{ manuscriptQuery.data.value ? '第 ' + manuscriptQuery.data.value.versionNumber + ' 版' : '未开始' }}</small></button>
          <button type="button" :class="{ active: mode === 'quality', done: qualityState === 'valid' }" @click="mode = 'quality'"><span>02</span>检查与润色<small>{{ qualityLabel }}</small></button>
          <button type="button" :class="{ done: currentManuscriptPublished }" @click="mode = 'manuscript'"><span>03</span>作者确认并发布<small>{{ currentManuscriptPublished ? '已发布' : '待发布' }}</small></button>
        </nav>
        <div v-if="mode !== 'memory' && mode !== 'opening'" class="writing-actions">
          <label class="instruction-field"><span>本次调整要求</span><textarea v-model="instruction" rows="2" :maxlength="mode === 'quality' ? 2000 : undefined" placeholder="可选，例如：增强对话张力，减少解释" /></label>
          <GenerationModeControl v-if="mode === 'manuscript' && manuscriptQuery.data.value" v-model="manuscriptGenerationMode" revise-label="基于选定版本调整" regenerate-label="重新创作一版" :disabled="busy" />
          <label v-if="mode === 'manuscript' && manuscriptQuery.data.value && manuscriptGenerationMode === 'REVISE'" class="provider-field outline-base-field"><span>基准正文</span><select v-model="baseManuscriptVersionId"><option value="">最新保存版本（第 {{ manuscriptQuery.data.value.versionNumber }} 版）</option><option v-for="item in manuscriptVersionsQuery.data.value?.filter((version) => version.id !== manuscriptQuery.data.value?.id) ?? []" :key="item.id" :value="item.id">第 {{ item.versionNumber }} 版 · {{ item.status === 'AUTHOR_ACCEPTED' ? '已确认' : '草稿' }} · {{ item.title }}</option></select></label>
          <GlobalModelBadge />
          <div class="direction-action-buttons" v-if="mode === 'manuscript'">
            <button class="button secondary" type="button" :disabled="busy || !currentChapter" @click="generateManuscriptMutation.mutate()"><RefreshCw :size="16" />{{ generateManuscriptMutation.isPending.value ? '正在生成…' : manuscriptQuery.data.value ? manuscriptGenerationMode === 'REVISE' ? '按要求调整' : '重新创作' : '生成正文' }}</button>
            <button v-if="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" class="button secondary" type="button" :disabled="busy" @click="createManuscriptRevisionMutation.mutate()"><FileText :size="16" />{{ createManuscriptRevisionMutation.isPending.value ? '正在创建…' : '复制为修订草稿' }}</button>
            <button v-if="manuscriptQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy" @click="saveManuscriptMutation.mutate()"><Save :size="16" />保存</button>
            <button v-if="manuscriptQuery.data.value && !currentManuscriptPublished" class="button primary" type="button" :disabled="busy || !canonStatusQuery.data.value || !projectQuery.data.value" @click="publishManuscriptMutation.mutate()"><Check :size="16" />{{ publishManuscriptMutation.isPending.value ? '正在发布…' : canonStatusQuery.data.value?.committed ? '确认并替换已发布正文' : '作者确认并发布' }}</button>
          </div>
        </div>
        <GlobalModelBadge v-if="mode === 'opening'" />
        <div v-if="error" class="form-error" role="alert">{{ error }}</div>
      </div>
      <div class="writing-toolbar">
        <div class="planning-tabs" role="group" aria-label="写作视图" @keydown="navigateWorkspaceButtons">
          <button type="button" :aria-pressed="mode === 'manuscript'" :class="{ active: mode === 'manuscript' }" @click="mode = 'manuscript'"><FileText :size="16" />正文草稿</button>
          <button type="button" :aria-pressed="mode === 'quality'" :class="{ active: mode === 'quality' }" @click="mode = 'quality'"><FileSearch :size="16" />检查与润色</button>
          <button type="button" :aria-pressed="mode === 'opening'" :class="{ active: mode === 'opening' }" @click="mode = 'opening'"><FileSearch :size="16" />前三章连读<small v-if="openingBusy"> · 正在通读</small></button>
          <button type="button" :aria-pressed="mode === 'review'" :class="{ active: mode === 'review' }" @click="mode = 'review'"><FileSearch :size="16" />发布后记忆</button>
          <button type="button" :aria-pressed="mode === 'memory'" :class="{ active: mode === 'memory' }" @click="mode = 'memory'"><BrainCircuit :size="16" />长期记忆</button>
        </div>
        <span>第 {{ selectedChapter }} 章</span>
      </div>

      <PublishedMemoryPanel :key="`${projectId}-${selectedChapter}-memory`" :project-id="projectId"
        :chapter="selectedChapter" :published="!!canonStatusQuery.data.value?.committed"
        :compact="mode !== 'review'" @open="mode = 'review'" />
      <p v-if="mode === 'manuscript' && canonStatusQuery.isError.value" class="form-error">发布状态读取失败，请刷新后重试。</p>
      <p v-if="mode === 'manuscript' && currentManuscriptPublished" class="acceptance-note">本版正文已发布，可作为后续章节的写作依据。</p>
      <DraftLoopPanel v-if="outlineQuery.data.value?.status === 'PUBLISHED'" v-show="mode === 'manuscript' || mode === 'quality'"
        :key="`${projectId}-${selectedChapter}`" :project-id="projectId" :chapter="selectedChapter" :provider="provider"
        :manuscript="manuscriptQuery.data.value" :external-busy="busy && !draftLoopBusy"
        :unsaved="!!manuscriptQuery.data.value && JSON.stringify(manuscriptDraft) !== JSON.stringify(manuscriptQuery.data.value.content)"
        @busy-change="draftLoopBusy = $event" @refresh-requested="refreshDraftLoopManuscript" />
      <template v-if="mode === 'manuscript'">
        <div class="editor-status"><strong>正文草稿</strong><span v-if="manuscriptQuery.data.value">第 {{ manuscriptQuery.data.value.versionNumber }} 版 · {{ manuscriptQuery.data.value.status === 'AUTHOR_ACCEPTED' ? '作者已确认' : '草稿' }}<template v-if="manuscriptBaseNumber"> · 基于第 {{ manuscriptBaseNumber }} 版</template></span><a v-if="manuscriptQuery.data.value" class="button secondary compact" :href="manuscriptExportUrl(projectId, selectedChapter, manuscriptQuery.data.value.id)"><Download :size="15" />导出本章</a></div>
        <div v-if="manuscriptDraft" class="manuscript-form">
          <section v-if="manuscriptQuery.data.value?.changeSummary.length" class="change-summary">
            <strong>本版修改说明</strong>
            <ul><li v-for="item in manuscriptQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
          </section>
          <p v-if="manuscriptQuery.data.value?.sourceReviewVersionId" class="acceptance-note"><FileSearch :size="16" />本稿根据历史审稿意见生成，可在核对后直接确认并发布。</p>
          <input v-model="manuscriptDraft.title" class="manuscript-title" aria-label="正文标题" :disabled="draftLoopEditingLocked || manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" />
          <textarea v-model="manuscriptDraft.body" class="manuscript-body" aria-label="正文" :disabled="draftLoopEditingLocked || manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" />
          <label><span>章节摘要</span><textarea v-model="manuscriptDraft.summary" rows="3" :disabled="draftLoopEditingLocked || manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" /></label>
          <label><span>连续性备注</span><textarea :value="joined(manuscriptDraft.continuityNotes)" rows="3" :disabled="draftLoopEditingLocked || manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" @input="updateNotes" /></label>
          <p v-if="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" class="acceptance-note"><Check :size="16" />作者已确认。复制修订后，可通过“确认并替换已发布正文”发布新版；旧版保留供追溯。</p>
        </div>
        <div v-else class="editor-empty">发布大纲后，即可生成本章正文草稿。</div>
        <details v-if="manuscriptQuery.data.value" class="local-edit-entry">
          <summary>局部编辑</summary>
          <p v-if="JSON.stringify(manuscriptDraft) !== JSON.stringify(manuscriptQuery.data.value.content)" class="form-error">请先保存正文，再选择局部编辑依据。</p>
          <ManuscriptLocalEditPanel :key="`${projectId}-${selectedChapter}`" :project-id="projectId"
            :source="JSON.stringify(manuscriptDraft) === JSON.stringify(manuscriptQuery.data.value.content) ? manuscriptQuery.data.value : null"
            :external-busy="busy && !localEditBusy" @drafted="setManuscript" @busy-change="localEditBusy = $event"
            @refresh-requested="manuscriptQuery.refetch()" />
        </details>
      </template>

      <template v-else-if="mode === 'quality'">
        <div class="editor-status"><strong>正文质量检查</strong><span v-if="manuscriptQuery.data.value">当前正文第 {{ manuscriptQuery.data.value.versionNumber }} 版 · {{ manuscriptQuery.data.value.status === 'AUTHOR_ACCEPTED' ? '作者已确认' : '草稿' }}</span></div>
        <section v-if="manuscriptQuery.data.value?.changeSummary.length" class="change-summary quality-change-summary">
          <strong>本版修改说明（待作者核对）</strong>
          <ul><li v-for="item in manuscriptQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
        </section>
        <div v-if="!manuscriptQuery.data.value" class="editor-empty">当前章暂无已保存正文，尚未检查。</div>
      </template>

      <template v-else-if="mode === 'memory'">
        <div class="editor-status"><strong>第 {{ selectedChapter }} 章可用记忆</strong><span>正史 V{{ projectQuery.data.value?.currentCanonVersion ?? 0 }}</span></div>
        <div v-if="memoryQuery.isPending.value" class="editor-empty">正在召回相关正文与事实…</div>
        <div v-else-if="memoryQuery.data.value" class="memory-panel">
          <div class="memory-usage">
            <span>正文阶段约 {{ memoryQuery.data.value.usage.estimatedTokens }} / {{ memoryQuery.data.value.usage.budgetTokens }} Token</span>
            <strong v-if="memoryQuery.data.value.usage.truncated">已按预算裁剪</strong>
            <strong v-else>预算充足</strong>
          </div>
          <p class="quiet-text">只读工具：{{ memoryQuery.data.value.usage.toolsUsed.map(toolLabel).join('、') || '无' }}</p>
          <section>
            <h3>相关历史正文</h3>
            <article v-for="item in memoryQuery.data.value.semanticMemories" :key="`${item.chapterNumber}-${item.canonVersion}`" class="memory-item">
              <header><strong>第 {{ item.chapterNumber }} 章</strong><span>{{ item.recentChapter ? '前章衔接' : `相关度 ${Math.round(item.similarity * 100)}%` }}</span></header>
              <p class="memory-summary">{{ item.summary }}</p>
              <small v-if="item.canonVersion === 0">作者已确认，尚未提交正史</small>
              <small v-else-if="item.canonVersion < 0">正文尚未确认，不作为正史依据</small>
              <details v-if="item.content"><summary>{{ item.recentChapter ? '查看前章计划与正文' : '查看相关片段' }}</summary><p>{{ item.content }}</p></details>
            </article>
            <p v-if="!memoryQuery.data.value.semanticMemories.length" class="quiet-text">此前章节中暂无可召回的正文。</p>
          </section>
          <section>
            <h3>已确认事实</h3>
            <article v-for="(fact, index) in memoryQuery.data.value.graphFacts" :key="`${fact.canonVersion}-${index}`" class="memory-fact">
              <strong>{{ fact.subject }}</strong><span>{{ fact.predicate }}</span><p>{{ fact.object }}</p>
              <small v-if="fact.evidence">依据：{{ fact.evidence }}</small>
            </article>
            <p v-if="!memoryQuery.data.value.graphFacts.length" class="quiet-text">暂无已确认事实。</p>
          </section>
        </div>
      </template>

      <FirstThreeChaptersPanel v-if="openingOpened" v-show="mode === 'opening'" :key="projectId" :project-id="projectId" :provider="provider" :external-busy="busy && !openingBusy" :refresh-key="openingRefreshKey" @busy-change="openingBusy = $event" />
      <QualityReviewPanel v-if="manuscriptQuery.data.value" v-show="mode === 'quality'" :key="`${projectId}-${selectedChapter}`" :project-id="projectId" :chapter-number="selectedChapter" :manuscript="manuscriptQuery.data.value" :provider="provider" :instruction="instruction" :has-unsaved-changes="JSON.stringify(manuscriptDraft) !== JSON.stringify(manuscriptQuery.data.value.content)" :external-busy="busy && !qualityBusy" @revised="setManuscript" @busy-change="qualityBusy = $event" @state-change="setQualityState" />

      <div v-if="mode === 'manuscript' && manuscriptVersionsQuery.isError.value" class="form-error" role="alert">历史正文加载失败：{{ manuscriptVersionsQuery.error.value?.message }}</div>
      <details v-if="mode === 'manuscript' && manuscriptGenerationMode === 'REVISE' && baseManuscriptVersionId" class="outline-base-preview">
        <summary>查看第 {{ selectedBaseManuscript?.versionNumber ?? '…' }} 版基准正文</summary>
        <div v-if="baseManuscriptQuery.isPending.value" class="direction-loading">正在读取历史正文…</div>
        <div v-else-if="baseManuscriptQuery.isError.value" class="form-error" role="alert">历史正文加载失败：{{ baseManuscriptQuery.error.value?.message }}</div>
        <div v-else-if="baseManuscriptQuery.data.value" class="outline-base-preview-body manuscript-base-preview-body">
          <h3>{{ baseManuscriptQuery.data.value.content.title }}</h3>
          <p class="manuscript-base-text">{{ baseManuscriptQuery.data.value.content.body }}</p>
          <h4>章节摘要</h4><p>{{ baseManuscriptQuery.data.value.content.summary }}</p>
        </div>
      </details>
    </main>
  </div>
</template>

<style scoped>
.quality-change-summary { margin-bottom: 20px; overflow-wrap: anywhere; }
.chapter-flow button { min-width: 120px; }
.planning-tabs { flex-wrap: wrap; }
.writing-toolbar { flex-wrap: wrap; gap: 10px; }
</style>

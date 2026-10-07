<script setup lang="ts">
import { navigateWorkspaceButtons } from '@/lib/workspace-keyboard'
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import GenerationModeControl from '@/components/GenerationModeControl.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useWorkspaceChapter, useWorkspaceChoice, writingViews } from '@/composables/useWorkspaceLocation'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { BrainCircuit, Check, Download, FileSearch, FileText, RefreshCw, Save, ScrollText } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import QualityReviewPanel from './QualityReviewPanel.vue'
import ContractExecutionPanel from './ContractExecutionPanel.vue'
import FirstThreeChaptersPanel from './FirstThreeChaptersPanel.vue'
import ManuscriptLocalEditPanel from './ManuscriptLocalEditPanel.vue'
import type { QualityReportState } from '@/api/writingQuality'
import type { AutomationChapterTarget } from '@/api/automation'
import { getCurrentOutline, type GenerationMode } from '@/api/planning'
import { getProject } from '@/api/projects'
import {
  acceptManuscript, approveContract, approveContractReview, approveReview, commitCanon, replaceCanon, returnReviewToWriting, createManuscriptRevision,
  generateContract, generateContractReview, generateManuscript, generateReview,
  getLatestContract, getLatestContractReview, getContractVersion, getLatestManuscript, getManuscriptVersion, getLatestReview, getMemoryPreview,
  getCanonCommitStatus,
  listContractVersions, listManuscriptVersions, updateContract, updateManuscript, updateReview,
  listCanonEntities, manuscriptExportUrl,
  type ChapterContractContent, type ChapterContractVersion, type ChapterContractReviewContent, type ChapterContractReviewVersion,
  type ChapterReviewContent, type ChapterReviewVersion, type FactProposal, type ManuscriptContent, type ManuscriptVersion,
  type StoryEntity,
} from '@/api/writing'

const props = defineProps<{ projectId: string; initialTarget?: AutomationChapterTarget | null }>()
const queryClient = useQueryClient()
const selectedChapter = useWorkspaceChapter()
const mode = useWorkspaceChoice('writing', writingViews, 'contract')
watch(() => props.initialTarget, target => {
  if (!target) return
  selectedChapter.value = target.chapter
  mode.value = target.mode
}, { immediate: true })
const { provider: provider } = useGlobalModelSettings()
const contractGenerationMode = ref<GenerationMode>('REVISE')
const baseContractVersionId = ref('')
const manuscriptGenerationMode = ref<GenerationMode>('REVISE')
const baseManuscriptVersionId = ref('')
const instruction = ref('')
const qualityBusy = ref(false)
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
const contractDraft = ref<ChapterContractContent | null>(null)
const manuscriptDraft = ref<ManuscriptContent | null>(null)
const reviewDraft = ref<ChapterReviewContent | null>(null)
const contractReviewDraft = ref<ChapterContractReviewContent | null>(null)
const selectedReviewIssueIds = ref<string[]>([])
const returnMode = ref<GenerationMode>('REVISE')
const committedCanonVersion = ref<number | null>(null)

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
const contractQuery = useQuery({
  queryKey: computed(() => ['chapter-contract', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestContract(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const contractReviewQuery = useQuery({
  queryKey: computed(() => ['contract-review', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestContractReview(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const contractReviewMatches = computed(() => !!contractReviewQuery.data.value && !!contractQuery.data.value
  && contractQuery.data.value.sourceOutlineVersionId === outlineQuery.data.value?.id
  && contractReviewQuery.data.value.sourceContractVersionId === contractQuery.data.value.id
  && contractReviewQuery.data.value.sourceContractRowVersion
    + (contractQuery.data.value.status === 'APPROVED' ? 1 : 0) === contractQuery.data.value.version)
const contractHasUnsavedChanges = computed(() => !!contractDraft.value && !!contractQuery.data.value
  && JSON.stringify(contractDraft.value) !== JSON.stringify(contractQuery.data.value.content))
const contractVersionsQuery = useQuery({
  queryKey: computed(() => ['contract-versions', props.projectId, selectedChapter.value]),
  queryFn: () => listContractVersions(props.projectId, selectedChapter.value),
  enabled: computed(() => mode.value === 'contract' && !!contractQuery.data.value),
})
const baseContractQuery = useQuery({
  queryKey: computed(() => ['contract-version', props.projectId, selectedChapter.value, baseContractVersionId.value]),
  queryFn: () => getContractVersion(props.projectId, selectedChapter.value, baseContractVersionId.value),
  enabled: computed(() => mode.value === 'contract' && contractGenerationMode.value === 'REVISE'
    && !!baseContractVersionId.value),
})
const selectedBaseContract = computed(() => contractVersionsQuery.data.value?.find(
  (item) => item.id === baseContractVersionId.value,
))
const contractBaseNumber = computed(() => contractVersionsQuery.data.value?.find(
  (item) => item.id === contractQuery.data.value?.baseContractVersionId,
)?.versionNumber)
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
const reviewQuery = useQuery({
  queryKey: computed(() => ['chapter-review', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestReview(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const canonStatusQuery = useQuery({
  queryKey: computed(() => ['canon-commit-status', props.projectId, selectedChapter.value]),
  queryFn: () => getCanonCommitStatus(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const reviewMatchesCurrentManuscript = computed(() => !!reviewQuery.data.value && !!manuscriptQuery.data.value
  && reviewQuery.data.value.sourceManuscriptVersionId === manuscriptQuery.data.value.id)
useUnsavedChanges(computed(() => contractHasUnsavedChanges.value
  || (!!manuscriptDraft.value && !!manuscriptQuery.data.value
    && JSON.stringify(manuscriptDraft.value) !== JSON.stringify(manuscriptQuery.data.value.content))
  || (!!reviewDraft.value && reviewQuery.data.value?.status === 'DRAFT'
    && JSON.stringify(reviewDraft.value) !== JSON.stringify(reviewQuery.data.value.content))
  || (!!contractReviewDraft.value && contractReviewQuery.data.value?.status === 'DRAFT'
    && JSON.stringify(contractReviewDraft.value) !== JSON.stringify(contractReviewQuery.data.value.content))), ['section', 'chapter'])
const openingRefreshKey = computed(() => JSON.stringify([
  outlineQuery.data.value?.id, outlineQuery.data.value?.version, projectQuery.data.value?.version,
  projectQuery.data.value?.currentCanonVersion, manuscriptQuery.data.value?.id, manuscriptQuery.data.value?.version,
  contractQuery.data.value?.id, contractQuery.data.value?.version, openingRefresh.value,
]))
const entityQuery = useQuery({
  queryKey: computed(() => ['canon-entities', props.projectId]),
  queryFn: () => listCanonEntities(props.projectId),
  enabled: computed(() => mode.value === 'review'),
})

function copyContract(value: ChapterContractContent): ChapterContractContent {
  return {
    ...value,
    locations: [...value.locations], requiredBeats: [...value.requiredBeats],
    requiredReveals: [...value.requiredReveals], forbiddenFacts: [...value.forbiddenFacts],
    foreshadowActions: [...value.foreshadowActions],
  }
}
function copyManuscript(value: ManuscriptContent): ManuscriptContent {
  return { ...value, continuityNotes: [...value.continuityNotes] }
}
function copyReview(value: ChapterReviewContent): ChapterReviewContent {
  return { ...value, issues: value.issues.map((item) => ({ ...item })), factProposals: value.factProposals.map((item) => ({ ...item, payload: item.payload ? { ...item.payload } : null })) }
}
watch(() => contractQuery.data.value, (value) => { contractDraft.value = value ? copyContract(value.content) : null }, { immediate: true })
watch(() => manuscriptQuery.data.value, (value) => { manuscriptDraft.value = value ? copyManuscript(value.content) : null }, { immediate: true })
watch(() => reviewQuery.data.value, (value) => { reviewDraft.value = value ? copyReview(value.content) : null }, { immediate: true })
watch(() => contractReviewQuery.data.value, (value) => {
  contractReviewDraft.value = value ? { summary: value.content.summary,
    issues: value.content.issues.map((issue) => ({ ...issue })) } : null
}, { immediate: true })
watch(() => reviewQuery.data.value?.id, () => { selectedReviewIssueIds.value = [] })
watch(() => [props.projectId, selectedChapter.value], () => {
  error.value = ''; instruction.value = ''; baseContractVersionId.value = ''; baseManuscriptVersionId.value = ''
  selectedReviewIssueIds.value = []; returnMode.value = 'REVISE'
  committedCanonVersion.value = null
  qualityState.value = 'unrun'; qualityLabel.value = '未检查'
})

function setContract(value: ChapterContractVersion) {
  queryClient.setQueryData(['chapter-contract', props.projectId, selectedChapter.value], value)
  queryClient.invalidateQueries({ queryKey: ['contract-versions', props.projectId, selectedChapter.value] })
  contractDraft.value = copyContract(value.content)
}
function setContractReview(value: ChapterContractReviewVersion) {
  queryClient.setQueryData(['contract-review', props.projectId, selectedChapter.value], value)
  contractReviewDraft.value = { summary: value.content.summary,
    issues: value.content.issues.map((issue) => ({ ...issue })) }
}
function setManuscript(value: ManuscriptVersion) {
  queryClient.setQueryData(['manuscript', props.projectId, selectedChapter.value], value)
  queryClient.invalidateQueries({ queryKey: ['manuscript-versions', props.projectId, selectedChapter.value] })
  manuscriptDraft.value = copyManuscript(value.content)
}
function setReview(value: ChapterReviewVersion) {
  queryClient.setQueryData(['chapter-review', props.projectId, selectedChapter.value], value)
  reviewDraft.value = copyReview(value.content)
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
function factTypeLabel(value: string) {
  return ({
    ENTITY_UPSERT: '实体', EVENT_CREATE: '事件', STATE_CHANGE: '状态变化',
    RELATION_CHANGE: '关系变化', KNOWLEDGE_CHANGE: '人物认知', FORESHADOW_CHANGE: '伏笔',
    EVENT: '事件', STATE: '状态变化',
  } as Record<string, string>)[value] ?? value
}
type EntityRole = 'entity' | 'source' | 'target' | 'character'
function entityTypeFor(fact: FactProposal, role: EntityRole) {
  if (role === 'entity') return fact.payload?.entityType ?? 'CHARACTER'
  return 'CHARACTER'
}
function entityOptions(fact: FactProposal, role: EntityRole): StoryEntity[] {
  const type = entityTypeFor(fact, role)
  return (entityQuery.data.value ?? []).filter((entity) => entity.type === type)
}
function selectedEntityId(fact: FactProposal, role: EntityRole) {
  if (!fact.payload) return ''
  if (role === 'entity') return fact.payload.entityId ?? ''
  if (role === 'source') return fact.payload.sourceEntityId ?? ''
  if (role === 'target') return fact.payload.targetEntityId ?? ''
  return fact.payload.characterId ?? ''
}
function setEntityId(fact: FactProposal, role: EntityRole, event: Event) {
  if (!fact.payload) return
  const value = (event.target as HTMLSelectElement).value || null
  if (role === 'entity') fact.payload.entityId = value
  else if (role === 'source') fact.payload.sourceEntityId = value
  else if (role === 'target') fact.payload.targetEntityId = value
  else fact.payload.characterId = value
}
function mentionName(fact: FactProposal, role: EntityRole) {
  if (!fact.payload) return fact.subject
  if (role === 'entity') return fact.payload.entityName ?? fact.subject
  if (role === 'source') return fact.payload.sourceEntityName ?? fact.subject
  if (role === 'target') return fact.payload.targetEntityName ?? fact.object
  return fact.payload.characterName ?? fact.subject
}
function emptyEntityOption(fact: FactProposal, role: EntityRole) {
  return ['他', '她', '它', '他们', '她们', '它们', '自己', '对方', '那个人', '这个人'].includes(mentionName(fact, role))
    ? '必须选择具体实体'
    : '按名称匹配或创建新实体'
}
function updateList(field: keyof ChapterContractContent, event: Event) {
  if (contractDraft.value) (contractDraft.value[field] as string[]) = lines((event.target as HTMLTextAreaElement).value)
}
function updateNotes(event: Event) { if (manuscriptDraft.value) manuscriptDraft.value.continuityNotes = lines((event.target as HTMLTextAreaElement).value) }
const busy = computed(() => qualityBusy.value || localEditBusy.value || openingBusy.value || generateContractMutation.isPending.value || saveContractMutation.isPending.value
  || generateContractReviewMutation.isPending.value || approveContractReviewMutation.isPending.value
  || approveContractMutation.isPending.value || generateManuscriptMutation.isPending.value
  || saveManuscriptMutation.isPending.value || createManuscriptRevisionMutation.isPending.value
  || acceptManuscriptMutation.isPending.value
  || generateReviewMutation.isPending.value || saveReviewMutation.isPending.value || approveReviewMutation.isPending.value
  || commitCanonMutation.isPending.value || replaceCanonMutation.isPending.value
  || returnReviewMutation.isPending.value)
const fail = (reason: Error) => { error.value = reason.message }

const generateContractMutation = useMutation({ mutationFn: () => {
  const generationMode = contractQuery.data.value ? contractGenerationMode.value : 'REGENERATE'
  if (generationMode === 'REVISE' && baseContractVersionId.value
    && baseContractQuery.data.value?.id !== baseContractVersionId.value) {
    throw new Error('请先加载并确认选定的历史章节合同。')
  }
  return generateContract(props.projectId, selectedChapter.value, provider.value, instruction.value,
    generationMode, generationMode === 'REVISE' ? baseContractVersionId.value || null : null)
}, onSuccess: (v) => { setContract(v); instruction.value = ''; error.value = '' }, onError: fail })
const saveContractMutation = useMutation({ mutationFn: () => {
  if (!contractQuery.data.value || !contractDraft.value) throw new Error('没有可保存的章节合同。')
  return updateContract(props.projectId, contractQuery.data.value, contractDraft.value)
}, onSuccess: setContract, onError: fail })
const approveContractMutation = useMutation({ mutationFn: () => {
  if (!contractQuery.data.value) throw new Error('没有可确认的章节合同。')
  if (contractHasUnsavedChanges.value) throw new Error('请先保存合同修改，再重新审阅。')
  if (!contractReviewMatches.value || contractReviewQuery.data.value?.status !== 'APPROVED') {
    throw new Error('请先审阅并确认当前保存版本的合同。')
  }
  return approveContract(props.projectId, contractQuery.data.value)
}, onSuccess: setContract, onError: fail })
const generateContractReviewMutation = useMutation({
  mutationFn: () => {
    if (contractHasUnsavedChanges.value) throw new Error('请先保存合同修改，再开始审阅。')
    return generateContractReview(props.projectId, selectedChapter.value, provider.value, instruction.value)
  },
  onSuccess: (value) => { setContractReview(value); instruction.value = ''; error.value = '' }, onError: fail,
})
const approveContractReviewMutation = useMutation({ mutationFn: () => {
  if (contractHasUnsavedChanges.value) throw new Error('请先保存合同修改，再重新审阅。')
  if (!contractReviewMatches.value || !contractReviewQuery.data.value || !contractReviewDraft.value) {
    throw new Error('合同已变化，请先保存合同并重新审阅。')
  }
  return approveContractReview(props.projectId, contractReviewQuery.data.value, contractReviewDraft.value)
}, onSuccess: setContractReview, onError: fail })
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
const acceptManuscriptMutation = useMutation({ mutationFn: () => {
  if (!manuscriptQuery.data.value) throw new Error('没有可确认的正文。')
  return acceptManuscript(props.projectId, manuscriptQuery.data.value)
}, onSuccess: setManuscript, onError: fail })
const generateReviewMutation = useMutation({ mutationFn: () => generateReview(props.projectId, selectedChapter.value, provider.value, instruction.value), onSuccess: (v) => { setReview(v); instruction.value = ''; error.value = '' }, onError: fail })
const saveReviewMutation = useMutation({ mutationFn: () => {
  if (!reviewMatchesCurrentManuscript.value) throw new Error('此审稿对应旧版正文，请重新审稿。')
  if (!reviewQuery.data.value || !reviewDraft.value) throw new Error('没有可保存的审稿结果。')
  return updateReview(props.projectId, reviewQuery.data.value, reviewDraft.value)
}, onSuccess: setReview, onError: fail })
const approveReviewMutation = useMutation({ mutationFn: () => {
  if (!reviewMatchesCurrentManuscript.value) throw new Error('此审稿对应旧版正文，请重新审稿。')
  if (!reviewQuery.data.value || !reviewDraft.value) throw new Error('没有可确认的审稿结果。')
  const pending = reviewDraft.value.factProposals.filter((fact) => fact.decision === 'PENDING').length
  if (pending) throw new Error(`还有 ${pending} 条候选事实待决定，请逐条接受或拒绝。`)
  return approveReview(props.projectId, reviewQuery.data.value, reviewDraft.value)
}, onSuccess: setReview, onError: fail })
const returnReviewMutation = useMutation({ mutationFn: () => {
  if (!reviewMatchesCurrentManuscript.value || manuscriptQuery.data.value?.status !== 'AUTHOR_ACCEPTED') {
    throw new Error('请先确认当前正文并使用对应的审稿。')
  }
  if (!reviewQuery.data.value || reviewQuery.data.value.status !== 'DRAFT') {
    throw new Error('只有待处理审稿可以打回。')
  }
  if (!selectedReviewIssueIds.value.length) throw new Error('请先选择要打回的问题。')
  return returnReviewToWriting(props.projectId, reviewQuery.data.value, provider.value,
    returnMode.value, selectedReviewIssueIds.value, instruction.value)
}, onSuccess: (value) => {
  setManuscript(value); mode.value = 'manuscript'; instruction.value = ''; error.value = ''
  queryClient.invalidateQueries({ queryKey: ['chapter-review', props.projectId, selectedChapter.value] })
}, onError: fail })
const commitCanonMutation = useMutation({ mutationFn: () => {
  if (!reviewMatchesCurrentManuscript.value) throw new Error('此审稿对应旧版正文，请重新审稿。')
  if (canonStatusQuery.data.value?.committed !== false) throw new Error('本章正史状态未确认，或已提交过正史。')
  if (!reviewQuery.data.value || reviewQuery.data.value.status !== 'APPROVED') throw new Error('请先确认审稿结果。')
  return commitCanon(props.projectId, selectedChapter.value, reviewQuery.data.value.id, projectQuery.data.value?.currentCanonVersion ?? 0)
}, onSuccess: (value) => {
  committedCanonVersion.value = value.canonVersion; error.value = ''
  queryClient.invalidateQueries({ queryKey: ['canon-commit-status', props.projectId, selectedChapter.value] })
  queryClient.invalidateQueries({ queryKey: ['project', props.projectId] })
  queryClient.invalidateQueries({ queryKey: ['novel-memory', props.projectId] })
}, onError: fail })
const replaceCanonMutation = useMutation({ mutationFn: () => {
  if (!reviewMatchesCurrentManuscript.value) throw new Error('请先为新版正文重新审稿。')
  if (!reviewQuery.data.value || reviewQuery.data.value.status !== 'APPROVED') throw new Error('请先确认新版审稿。')
  const activeCommitId = canonStatusQuery.data.value?.activeCommitId
  if (!activeCommitId) throw new Error('本章正史状态未确认，请刷新后重试。')
  if (reviewQuery.data.value.sourceManuscriptVersionId === canonStatusQuery.data.value?.activeManuscriptVersionId) {
    throw new Error('请先确认新版正文并重新审稿。')
  }
  if (!window.confirm('替换本章正史？旧版会保留作历史记录，但不再参与后续写作检索。')) {
    throw new Error('已取消替换。')
  }
  return replaceCanon(props.projectId, selectedChapter.value, reviewQuery.data.value.id,
    activeCommitId, projectQuery.data.value?.currentCanonVersion ?? 0)
}, onSuccess: (value) => {
  committedCanonVersion.value = value.canonVersion; error.value = ''
  queryClient.invalidateQueries({ queryKey: ['canon-commit-status', props.projectId, selectedChapter.value] })
  queryClient.invalidateQueries({ queryKey: ['project', props.projectId] })
  queryClient.invalidateQueries({ queryKey: ['novel-memory', props.projectId] })
}, onError: (reason) => { if (reason.message !== '已取消替换。') fail(reason) } })
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
          <button type="button" :class="{ active: mode === 'contract', done: !!contractQuery.data.value }" @click="mode = 'contract'"><span>01</span>章节合同<small>{{ contractQuery.data.value ? contractQuery.data.value.status === 'APPROVED' ? '已确认' : '草稿' : '未开始' }}</small></button>
          <button type="button" :class="{ active: mode === 'contractReview', done: contractReviewMatches && contractReviewQuery.data.value?.status === 'APPROVED' }" @click="mode = 'contractReview'"><span>02</span>合同审阅<small>{{ contractReviewMatches ? contractReviewQuery.data.value?.status === 'APPROVED' ? '已通过' : '待确认' : contractReviewQuery.data.value ? '需重审' : '未开始' }}</small></button>
          <button type="button" :class="{ done: contractQuery.data.value?.status === 'APPROVED' }" @click="mode = 'contract'"><span>03</span>合同确认<small>{{ contractQuery.data.value?.status === 'APPROVED' ? '已完成' : '待完成' }}</small></button>
          <button type="button" :class="{ active: mode === 'manuscript', done: !!manuscriptQuery.data.value }" @click="mode = 'manuscript'"><span>04</span>正文草稿<small>{{ manuscriptQuery.data.value ? '第 ' + manuscriptQuery.data.value.versionNumber + ' 版' : '未开始' }}</small></button>
          <button type="button" :class="{ active: mode === 'quality', done: qualityState === 'valid' }" @click="mode = 'quality'"><span>05</span>检查与润色<small>{{ qualityLabel }}</small></button>
          <button type="button" :class="{ done: manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED' }" @click="mode = 'manuscript'"><span>06</span>作者确认<small>{{ manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED' ? '已完成' : '待完成' }}</small></button>
          <button type="button" :class="{ active: mode === 'review', done: reviewMatchesCurrentManuscript && reviewQuery.data.value?.status === 'APPROVED' }" @click="mode = 'review'"><span>07</span>正史审稿<small>{{ reviewMatchesCurrentManuscript ? reviewQuery.data.value?.status === 'APPROVED' ? '已通过' : '待处理' : reviewQuery.data.value ? '需重审' : '未开始' }}</small></button>
          <button type="button" :class="{ done: !!canonStatusQuery.data.value?.committed }" @click="mode = 'review'"><span>08</span>正史发布<small>{{ canonStatusQuery.data.value?.committed ? '已发布' : '待完成' }}</small></button>
        </nav>
        <div v-if="mode !== 'memory' && mode !== 'opening'" class="writing-actions">
          <label class="instruction-field"><span>本次调整要求</span><textarea v-model="instruction" rows="2" :maxlength="mode === 'quality' ? 2000 : undefined" placeholder="可选，例如：增强对话张力，减少解释" /></label>
          <GenerationModeControl v-if="mode === 'contract' && contractQuery.data.value" v-model="contractGenerationMode" revise-label="基于选定版本调整" :disabled="busy" />
          <label v-if="mode === 'contract' && contractQuery.data.value && contractGenerationMode === 'REVISE'" class="provider-field outline-base-field"><span>基准合同</span><select v-model="baseContractVersionId"><option value="">最新保存版本（第 {{ contractQuery.data.value.versionNumber }} 版）</option><option v-for="item in contractVersionsQuery.data.value?.filter((version) => version.id !== contractQuery.data.value?.id) ?? []" :key="item.id" :value="item.id">第 {{ item.versionNumber }} 版 · {{ item.status === 'APPROVED' ? '已确认' : '草稿' }} · {{ item.chapterTitle }}</option></select></label>
          <GenerationModeControl v-if="mode === 'manuscript' && manuscriptQuery.data.value" v-model="manuscriptGenerationMode" revise-label="基于选定版本调整" regenerate-label="重新创作一版" :disabled="busy" />
          <label v-if="mode === 'manuscript' && manuscriptQuery.data.value && manuscriptGenerationMode === 'REVISE'" class="provider-field outline-base-field"><span>基准正文</span><select v-model="baseManuscriptVersionId"><option value="">最新保存版本（第 {{ manuscriptQuery.data.value.versionNumber }} 版）</option><option v-for="item in manuscriptVersionsQuery.data.value?.filter((version) => version.id !== manuscriptQuery.data.value?.id) ?? []" :key="item.id" :value="item.id">第 {{ item.versionNumber }} 版 · {{ item.status === 'AUTHOR_ACCEPTED' ? '已确认' : '草稿' }} · {{ item.title }}</option></select></label>
          <GlobalModelBadge />
          <div class="direction-action-buttons" v-if="mode === 'contract'">
            <button class="button secondary" type="button" :disabled="busy" @click="generateContractMutation.mutate()"><RefreshCw :size="16" />{{ generateContractMutation.isPending.value ? '正在生成…' : contractQuery.data.value ? contractGenerationMode === 'REVISE' ? '按要求调整' : '重新生成' : '生成合同' }}</button>
            <button v-if="contractQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy" @click="saveContractMutation.mutate()"><Save :size="16" />保存合同</button>
            <button v-if="contractQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy || contractHasUnsavedChanges || !contractReviewMatches || contractReviewQuery.data.value?.status !== 'APPROVED'" @click="approveContractMutation.mutate()"><Check :size="16" />确认合同</button>
          </div>
          <div class="direction-action-buttons" v-else-if="mode === 'contractReview'">
            <button class="button secondary" type="button" :disabled="busy || contractHasUnsavedChanges || contractQuery.data.value?.status !== 'DRAFT'" @click="generateContractReviewMutation.mutate()"><FileSearch :size="16" />{{ generateContractReviewMutation.isPending.value ? '正在审阅…' : contractReviewQuery.data.value ? '重新审阅合同' : '开始合同审阅' }}</button>
            <button v-if="contractReviewQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy || contractHasUnsavedChanges || !contractReviewMatches" @click="approveContractReviewMutation.mutate()"><Check :size="16" />确认审阅</button>
          </div>
          <div class="direction-action-buttons" v-else-if="mode === 'manuscript'">
            <button class="button secondary" type="button" :disabled="busy || contractQuery.data.value?.status !== 'APPROVED'" @click="generateManuscriptMutation.mutate()"><RefreshCw :size="16" />{{ generateManuscriptMutation.isPending.value ? '正在生成…' : manuscriptQuery.data.value ? manuscriptGenerationMode === 'REVISE' ? '按要求调整' : '重新创作' : '生成正文' }}</button>
            <button v-if="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" class="button secondary" type="button" :disabled="busy" @click="createManuscriptRevisionMutation.mutate()"><FileText :size="16" />{{ createManuscriptRevisionMutation.isPending.value ? '正在创建…' : '复制为修订草稿' }}</button>
            <button v-if="manuscriptQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy" @click="saveManuscriptMutation.mutate()"><Save :size="16" />保存</button>
            <button v-if="manuscriptQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy" @click="acceptManuscriptMutation.mutate()"><Check :size="16" />作者确认</button>
          </div>
          <div class="direction-action-buttons" v-else-if="mode === 'review'">
            <GenerationModeControl v-if="reviewQuery.data.value?.status === 'DRAFT'" v-model="returnMode" label="打回方式" revise-label="按原稿修订" regenerate-label="重写整章" :disabled="busy" />
            <button class="button secondary" type="button" :disabled="busy || manuscriptQuery.data.value?.status !== 'AUTHOR_ACCEPTED'" @click="generateReviewMutation.mutate()"><RefreshCw :size="16" />{{ reviewQuery.data.value ? '重新审稿' : '开始审稿' }}</button>
            <button v-if="reviewQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy || !reviewMatchesCurrentManuscript || !selectedReviewIssueIds.length || manuscriptQuery.data.value?.status !== 'AUTHOR_ACCEPTED'" @click="returnReviewMutation.mutate()"><RefreshCw :size="16" />{{ returnReviewMutation.isPending.value ? '正在生成新稿…' : '打回并生成新稿' }}</button>
            <button v-if="reviewQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy || !reviewMatchesCurrentManuscript" @click="saveReviewMutation.mutate()"><Save :size="16" />保存处理</button>
            <button v-if="reviewQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy || !reviewMatchesCurrentManuscript" @click="approveReviewMutation.mutate()"><Check :size="16" />确认审稿</button>
            <button v-if="reviewQuery.data.value?.status === 'APPROVED' && canonStatusQuery.data.value?.committed === false" class="button primary" type="button" :disabled="busy || !reviewMatchesCurrentManuscript" @click="commitCanonMutation.mutate()"><Check :size="16" />{{ commitCanonMutation.isPending.value ? '正在提交…' : '提交正史' }}</button>
            <button v-if="reviewQuery.data.value?.status === 'APPROVED' && canonStatusQuery.data.value?.committed" class="button primary" type="button" :disabled="busy || !reviewMatchesCurrentManuscript || reviewQuery.data.value.sourceManuscriptVersionId === canonStatusQuery.data.value?.activeManuscriptVersionId" @click="replaceCanonMutation.mutate()"><RefreshCw :size="16" />{{ replaceCanonMutation.isPending.value ? '正在替换…' : '替换正史' }}</button>
          </div>
        </div>
        <GlobalModelBadge v-if="mode === 'opening'" />
        <div v-if="error" class="form-error" role="alert">{{ error }}</div>
        <div v-if="mode === 'contractReview' && contractReviewQuery.isError.value" class="form-error" role="alert">合同审阅读取失败：{{ contractReviewQuery.error.value?.message }}</div>
      </div>
      <div class="writing-toolbar">
        <div class="planning-tabs" role="group" aria-label="写作视图" @keydown="navigateWorkspaceButtons">
          <button type="button" :aria-pressed="mode === 'contract'" :class="{ active: mode === 'contract' }" @click="mode = 'contract'"><ScrollText :size="16" />章节合同</button>
          <button type="button" :aria-pressed="mode === 'contractReview'" :class="{ active: mode === 'contractReview' }" @click="mode = 'contractReview'"><FileSearch :size="16" />合同审阅</button>
          <button type="button" :aria-pressed="mode === 'manuscript'" :class="{ active: mode === 'manuscript' }" @click="mode = 'manuscript'"><FileText :size="16" />正文草稿</button>
          <button type="button" :aria-pressed="mode === 'quality'" :class="{ active: mode === 'quality' }" @click="mode = 'quality'"><FileSearch :size="16" />检查与润色</button>
          <button type="button" :aria-pressed="mode === 'opening'" :class="{ active: mode === 'opening' }" @click="mode = 'opening'"><FileSearch :size="16" />前三章连读<small v-if="openingBusy"> · 正在通读</small></button>
          <button type="button" :aria-pressed="mode === 'review'" :class="{ active: mode === 'review' }" @click="mode = 'review'"><FileSearch :size="16" />审稿与记忆</button>
          <button type="button" :aria-pressed="mode === 'memory'" :class="{ active: mode === 'memory' }" @click="mode = 'memory'"><BrainCircuit :size="16" />长期记忆</button>
        </div>
        <span>第 {{ selectedChapter }} 章</span>
      </div>

      <template v-if="mode === 'contract'">
        <p v-if="contractHasUnsavedChanges" class="acceptance-note">合同有未保存修改。请先保存，再运行合同审阅。</p>
        <div class="editor-status"><strong>章节合同</strong><span v-if="contractQuery.data.value">第 {{ contractQuery.data.value.versionNumber }} 版 · {{ contractQuery.data.value.status === 'APPROVED' ? '已确认' : '草稿' }}<template v-if="contractBaseNumber"> · 基于第 {{ contractBaseNumber }} 版</template></span></div>
        <div v-if="contractDraft" class="contract-grid">
          <label><span>章节标题</span><input v-model="contractDraft.chapterTitle" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>视角人物</span><input v-model="contractDraft.pov" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label class="full"><span>章节目标</span><textarea v-model="contractDraft.objective" rows="2" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>故事时间</span><input v-model="contractDraft.storyTime" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>退出状态</span><input v-model="contractDraft.expectedExitState" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>必写节拍</span><textarea :value="joined(contractDraft.requiredBeats)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('requiredBeats', $event)" /></label>
          <label><span>必要揭示</span><textarea :value="joined(contractDraft.requiredReveals)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('requiredReveals', $event)" /></label>
          <label><span>禁止事实</span><textarea :value="joined(contractDraft.forbiddenFacts)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('forbiddenFacts', $event)" /></label>
          <label><span>伏笔动作</span><textarea :value="joined(contractDraft.foreshadowActions)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('foreshadowActions', $event)" /></label>
          <label class="full"><span>结尾钩子</span><textarea v-model="contractDraft.hook" rows="2" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <div class="word-range">建议 {{ contractDraft.suggestedMinWords }}～{{ contractDraft.suggestedMaxWords }} 字，可按剧情自然浮动</div>
        </div>
        <div v-else class="editor-empty">为本章生成一份可编辑的写作合同。</div>
        <ContractExecutionPanel v-if="contractDraft" :content="contractDraft" />
      </template>

      <template v-else-if="mode === 'contractReview'">
        <div class="editor-status"><strong>合同审阅</strong><span v-if="contractReviewQuery.data.value">第 {{ contractReviewQuery.data.value.versionNumber }} 版 · {{ contractReviewQuery.data.value.status === 'APPROVED' ? '已确认' : '待处理' }}</span></div>
        <p v-if="contractReviewQuery.data.value && !contractReviewMatches" class="form-error" role="alert">此审阅对应旧版合同。请先保存当前合同，再重新审阅。</p>
        <div v-if="contractReviewDraft" class="review-panel">
          <p class="review-summary">{{ contractReviewDraft.summary }}</p>
          <section><h3>合同问题</h3>
            <article v-for="issue in contractReviewDraft.issues" :key="issue.id" class="review-item">
              <header><span :class="['severity', issue.severity.toLowerCase()]">{{ issue.severity }}</span><strong>{{ issue.category }}</strong></header>
              <p>{{ issue.description }}</p><blockquote>{{ issue.evidence }}</blockquote><small>{{ issue.suggestion }}</small>
              <label v-if="contractReviewQuery.data.value?.status === 'DRAFT'" class="resolve-check"><input v-model="issue.resolved" type="checkbox" />已处理</label>
            </article>
            <p v-if="!contractReviewDraft.issues.length" class="quiet-text">未发现需要处理的合同问题。</p>
          </section>
          <p v-if="contractReviewQuery.data.value?.status === 'APPROVED' && contractReviewMatches" class="acceptance-note"><Check :size="16" />合同审阅已通过。返回章节合同确认后可开始写正文。</p>
        </div>
        <div v-else class="editor-empty">保存章节合同后，可在顶部运行独立合同审阅。</div>
      </template>

      <template v-else-if="mode === 'manuscript'">
        <div class="editor-status"><strong>正文草稿</strong><span v-if="manuscriptQuery.data.value">第 {{ manuscriptQuery.data.value.versionNumber }} 版 · {{ manuscriptQuery.data.value.status === 'AUTHOR_ACCEPTED' ? '作者已确认' : '草稿' }}<template v-if="manuscriptBaseNumber"> · 基于第 {{ manuscriptBaseNumber }} 版</template></span><a v-if="manuscriptQuery.data.value" class="button secondary compact" :href="manuscriptExportUrl(projectId, selectedChapter, manuscriptQuery.data.value.id)"><Download :size="15" />导出本章</a></div>
        <div v-if="manuscriptDraft" class="manuscript-form">
          <section v-if="manuscriptQuery.data.value?.changeSummary.length" class="change-summary">
            <strong>本版修改说明</strong>
            <ul><li v-for="item in manuscriptQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
          </section>
          <p v-if="manuscriptQuery.data.value?.sourceReviewVersionId" class="acceptance-note"><FileSearch :size="16" />本稿根据审稿意见打回生成。作者确认后需重新审稿。</p>
          <input v-model="manuscriptDraft.title" class="manuscript-title" aria-label="正文标题" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" />
          <textarea v-model="manuscriptDraft.body" class="manuscript-body" aria-label="正文" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" />
          <label><span>章节摘要</span><textarea v-model="manuscriptDraft.summary" rows="3" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" /></label>
          <label><span>连续性备注</span><textarea :value="joined(manuscriptDraft.continuityNotes)" rows="3" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" @input="updateNotes" /></label>
          <p v-if="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" class="acceptance-note"><Check :size="16" />作者已确认。复制修订不会自动替换已提交的正史与长期记忆。</p>
        </div>
        <div v-else class="editor-empty">确认章节合同后，即可生成本章正文草稿。</div>
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

      <template v-else-if="mode === 'review'">
        <div class="editor-status"><strong>审稿与候选记忆</strong><span v-if="reviewQuery.data.value">第 {{ reviewQuery.data.value.versionNumber }} 版 · {{ reviewQuery.data.value.status === 'APPROVED' ? '已确认' : reviewQuery.data.value.status === 'RETURNED' ? '已打回' : '待处理' }}</span></div>
        <p v-if="reviewQuery.data.value && !reviewMatchesCurrentManuscript" class="form-error" role="alert">此审稿对应旧版正文。当前正文确认后需要重新审稿，旧结果不能用于新稿提交。</p>
        <p v-if="canonStatusQuery.data.value?.committed" class="acceptance-note"><Check :size="16" />本章已有正史 v{{ canonStatusQuery.data.value.canonVersion }}。确认新版正文并重新审稿后，可替换本章正史；旧版保留供追溯。</p>
        <p v-if="canonStatusQuery.isError.value" class="form-error" role="alert">正史状态读取失败，暂不能提交。</p>
        <div v-if="reviewDraft" class="review-panel">
          <p class="review-summary">{{ reviewDraft.summary }}</p>
          <section><h3>问题清单</h3>
            <article v-for="issue in reviewDraft.issues" :key="issue.id" class="review-item">
              <header><span :class="['severity', issue.severity.toLowerCase()]">{{ issue.severity }}</span><strong>{{ issue.category }}</strong></header>
              <p>{{ issue.description }}</p><blockquote>{{ issue.evidence }}</blockquote><small>{{ issue.suggestion }}</small>
              <label v-if="reviewQuery.data.value?.status === 'DRAFT' && reviewMatchesCurrentManuscript && (issue.description?.trim() || issue.suggestion?.trim())" class="resolve-check"><input v-model="selectedReviewIssueIds" type="checkbox" :value="issue.id" />打回重写</label>
              <label v-if="reviewQuery.data.value?.status === 'DRAFT'" class="resolve-check"><input v-model="issue.resolved" type="checkbox" />已处理</label>
            </article>
            <p v-if="!reviewDraft.issues.length" class="quiet-text">未发现需要处理的问题。</p>
          </section>
          <section><h3>候选事实</h3>
            <article v-for="fact in reviewDraft.factProposals" :key="fact.id" class="fact-item">
              <div><span>{{ factTypeLabel(fact.factType) }}</span><strong>{{ fact.subject }} · {{ fact.predicate }}</strong></div>
              <p>{{ fact.object }}</p><small>证据：{{ fact.evidence }}</small>
              <small v-if="fact.confidence != null">置信度：{{ Math.round(fact.confidence * 100) }}%</small>
              <div v-if="fact.payload && ['ENTITY_UPSERT', 'STATE_CHANGE'].includes(fact.factType)" class="entity-resolution-row">
                <label><span>“{{ mentionName(fact, 'entity') }}”对应实体</span>
                  <select :value="selectedEntityId(fact, 'entity')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'entity', $event)">
                    <option value="">{{ emptyEntityOption(fact, 'entity') }}</option>
                    <option v-for="entity in entityOptions(fact, 'entity')" :key="entity.id" :value="entity.id">{{ entity.name }}</option>
                  </select>
                </label>
              </div>
              <div v-else-if="fact.payload && fact.factType === 'RELATION_CHANGE'" class="entity-resolution-row two">
                <label><span>“{{ mentionName(fact, 'source') }}”</span><select :value="selectedEntityId(fact, 'source')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'source', $event)"><option value="">{{ emptyEntityOption(fact, 'source') }}</option><option v-for="entity in entityOptions(fact, 'source')" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
                <label><span>“{{ mentionName(fact, 'target') }}”</span><select :value="selectedEntityId(fact, 'target')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'target', $event)"><option value="">{{ emptyEntityOption(fact, 'target') }}</option><option v-for="entity in entityOptions(fact, 'target')" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
              </div>
              <div v-else-if="fact.payload && fact.factType === 'KNOWLEDGE_CHANGE'" class="entity-resolution-row">
                <label><span>“{{ mentionName(fact, 'character') }}”对应人物</span><select :value="selectedEntityId(fact, 'character')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'character', $event)"><option value="">{{ emptyEntityOption(fact, 'character') }}</option><option v-for="entity in entityOptions(fact, 'character')" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
              </div>
              <select v-model="fact.decision" :disabled="reviewQuery.data.value?.status === 'APPROVED'">
                <option value="PENDING">待决定</option><option value="ACCEPTED">接受</option><option value="REJECTED">拒绝</option>
              </select>
            </article>
          </section>
          <p v-if="reviewQuery.data.value?.status === 'APPROVED'" class="acceptance-note"><Check :size="16" />审稿门禁已通过，下一步可{{ canonStatusQuery.data.value?.committed ? '替换' : '提交' }}正史。</p>
          <p v-if="committedCanonVersion" class="acceptance-note"><Check :size="16" />已提交为正史 v{{ committedCanonVersion }}，检索与图谱投影正在后台同步。</p>
        </div>
        <div v-else class="editor-empty">作者确认正文后，运行一致性审稿并抽取候选事实。</div>
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
              <small v-else-if="item.canonVersion < 0">仅合同已确认，正文尚未确认</small>
              <details v-if="item.content"><summary>{{ item.recentChapter ? '查看前章合同与正文' : '查看相关片段' }}</summary><p>{{ item.content }}</p></details>
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

      <div v-if="mode === 'contract' && contractVersionsQuery.isError.value" class="form-error" role="alert">历史章节合同加载失败：{{ contractVersionsQuery.error.value?.message }}</div>
      <details v-if="mode === 'contract' && contractGenerationMode === 'REVISE' && baseContractVersionId" class="outline-base-preview">
        <summary>查看第 {{ selectedBaseContract?.versionNumber ?? '…' }} 版基准合同</summary>
        <div v-if="baseContractQuery.isPending.value" class="direction-loading">正在读取历史章节合同…</div>
        <div v-else-if="baseContractQuery.isError.value" class="form-error" role="alert">历史章节合同加载失败：{{ baseContractQuery.error.value?.message }}</div>
        <div v-else-if="baseContractQuery.data.value" class="outline-base-preview-body contract-base-preview-body">
          <h3>{{ baseContractQuery.data.value.content.chapterTitle }}</h3>
          <p v-if="baseContractQuery.data.value.sourceOutlineVersionId !== outlineQuery.data.value?.id" class="quiet-text">此版合同来自旧大纲；新合同仍以当前已发布大纲为准。</p>
          <p>视角：{{ baseContractQuery.data.value.content.pov }} · 时间：{{ baseContractQuery.data.value.content.storyTime }}</p>
          <p>章节目标：{{ baseContractQuery.data.value.content.objective }}</p>
          <p>地点：{{ joined(baseContractQuery.data.value.content.locations) || '未指定' }}</p>
          <h4>必写节拍</h4><ul><li v-for="(item, index) in baseContractQuery.data.value.content.requiredBeats" :key="index">{{ item }}</li></ul>
          <h4>必要揭示</h4><ul><li v-for="(item, index) in baseContractQuery.data.value.content.requiredReveals" :key="index">{{ item }}</li></ul>
          <h4>禁止事实</h4><ul><li v-for="(item, index) in baseContractQuery.data.value.content.forbiddenFacts" :key="index">{{ item }}</li></ul>
          <h4>伏笔动作</h4><ul><li v-for="(item, index) in baseContractQuery.data.value.content.foreshadowActions" :key="index">{{ item }}</li></ul>
          <p>退出状态：{{ baseContractQuery.data.value.content.expectedExitState }}</p>
          <p>结尾钩子：{{ baseContractQuery.data.value.content.hook }}</p>
          <p>建议字数：{{ baseContractQuery.data.value.content.suggestedMinWords }}～{{ baseContractQuery.data.value.content.suggestedMaxWords }}</p>
        </div>
      </details>
      <div v-if="mode === 'manuscript' && manuscriptVersionsQuery.isError.value" class="form-error" role="alert">历史正文加载失败：{{ manuscriptVersionsQuery.error.value?.message }}</div>
      <details v-if="mode === 'manuscript' && manuscriptGenerationMode === 'REVISE' && baseManuscriptVersionId" class="outline-base-preview">
        <summary>查看第 {{ selectedBaseManuscript?.versionNumber ?? '…' }} 版基准正文</summary>
        <div v-if="baseManuscriptQuery.isPending.value" class="direction-loading">正在读取历史正文…</div>
        <div v-else-if="baseManuscriptQuery.isError.value" class="form-error" role="alert">历史正文加载失败：{{ baseManuscriptQuery.error.value?.message }}</div>
        <div v-else-if="baseManuscriptQuery.data.value" class="outline-base-preview-body manuscript-base-preview-body">
          <h3>{{ baseManuscriptQuery.data.value.content.title }}</h3>
          <p v-if="baseManuscriptQuery.data.value.sourceContractVersionId !== contractQuery.data.value?.id" class="quiet-text">此版正文使用旧章节合同；新稿仍以当前已确认合同为准。</p>
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

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, Play, RefreshCw, Save, Square, Sparkles } from 'lucide-vue-next'
import {
  confirmPreparation, createPreparation, editPreparation, getPreparation, listPreparations, listPreparationCheckpoints, preparationAction,
  type PreparationEntity, type PreparationPlot, type PreparationView, type PreparationWorld,
  type PlotUnit, type PlannedRelation, type PlannedKnowledge, type PlannedTime,
} from '@/api/creationPreparations'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import CharacterBlueprintEditor from '@/components/CharacterBlueprintEditor.vue'
import ReaderExperienceSeedEditor from '@/components/ReaderExperienceSeedEditor.vue'
import PreparationListEditor, { type PreparationField } from '@/components/PreparationListEditor.vue'
import type { CharacterBlueprint, ReaderExperienceSeed } from '@/api/planning'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ 'outline-created': [] }>()
const cache = useQueryClient()
const { provider } = useGlobalModelSettings()
const mode = ref<'PREPARE' | 'REVIEW'>('PREPARE')
const manualRange = ref(false)
const startChapter = ref(1)
const endChapter = ref(20)
const instruction = ref('')
const selectedId = ref('')
const local = ref<PreparationView | null>(null)
const busy = ref(false)
const error = ref('')
const dirty = ref(false)
const tab = ref('characters')
const world = ref<PreparationWorld | null>(null)
const plot = ref<PreparationPlot | null>(null)
const authorConfirmed = ref(false)
const acceptWarnings = ref(false)
const selectedChapters = ref<number[]>([])
const query = useQuery({
  queryKey: computed(() => ['creation-preparations', props.projectId]), queryFn: () => listPreparations(props.projectId),
  refetchInterval: state => state.state.data?.some(view => view.task.status === 'RUNNING') ? 1500 : false,
})
const checkpoints = useQuery({ queryKey: computed(() => ['preparation-checkpoints', props.projectId]), queryFn: () => listPreparationCheckpoints(props.projectId) })
const selected = computed(() => {
  const remote = query.data.value?.find(view => view.task.id === selectedId.value)
  if (!local.value || local.value.task.id !== selectedId.value) return remote ?? null
  return remote && remote.task.version >= local.value.task.version ? remote : local.value
})
const task = computed(() => selected.value?.task)
const editable = computed(() => !busy.value && !selected.value?.stale && task.value?.mode === 'PREPARE' && ['READY', 'FAILED', 'AWAITING_CONFIRMATION'].includes(task.value.status))
const hasBlocking = computed(() => task.value?.reviewReport?.issues.some(issue => issue.severity === 'BLOCKING'))
const labels: Record<string, string> = { READY: '待执行', RUNNING: '生成中', FAILED: '失败', AWAITING_CONFIRMATION: '待确认', CONFIRMED: '已确认', CANCELLED: '已取消' }
const steps = ['人物与世界', '剧情协同', '一致性检查']
const visibleSteps = computed(() => steps.map((title, index) => ({ title, index })).filter(step => task.value?.mode !== 'REVIEW' || step.index === 2))
const tabs = [ ['characters', '人物'], ['entities', '实体与状态'], ['units', '剧情单元'], ['relations', '关系与知识'], ['timeline', '时间线与伏笔'], ['review', '复核报告'] ]
const fields = (items: [string, string, PreparationField['type']?][]): PreparationField[] => items.map(([key, label, type]) => ({ key, label, type }))
const entityFields: PreparationField[] = [ { key: 'key', label: '标识', max: 80 }, { key: 'type', label: '类型', options: ['ITEM', 'LOCATION', 'ORGANIZATION'] }, ...fields([['name', '名称'], ['description', '设定'], ['initialState', '开篇状态'], ['owner', '初始所属人物']]) ]
const unitFields = fields([['key', '标识'], ['title', '单元名称'], ['startChapter', '开始章节', 'number'], ['endChapter', '结束章节', 'number'], ['objective', '核心目标'], ['conflict', '主要冲突'], ['turningPoint', '关键转折'], ['endCondition', '结束条件'], ['characters', '涉及人物', 'list'], ['planKeys', '新增台账标识', 'list']])
const relationFields = fields([['source', '人物'], ['target', '关联人物'], ['type', '关系类型'], ['description', '规划变化'], ['fromChapter', '预计章节', 'number']])
const knowledgeFields = fields([['character', '人物'], ['information', '预计获知的信息'], ['knownFromChapter', '预计章节', 'number'], ['source', '获知途径与边界']])
const timeFields = fields([['key', '标识'], ['chapter', '章节', 'number'], ['storyTime', '故事时间'], ['event', '计划事件'], ['participants', '参与人物', 'list']])

watch(() => props.projectId, () => {
  selectedId.value = ''; local.value = null; busy.value = false; error.value = ''; dirty.value = false
  world.value = null; plot.value = null; authorConfirmed.value = false; acceptWarnings.value = false; selectedChapters.value = []
})
watch(() => query.data.value, views => { if (!selectedId.value && views?.length) selectedId.value = views[0]!.task.id })
watch(() => selected.value, view => {
  if (!view || dirty.value) return
  world.value = view.task.worldDesign ? JSON.parse(JSON.stringify(view.task.worldDesign)) : null
  plot.value = view.task.plotDesign ? JSON.parse(JSON.stringify(view.task.plotDesign)) : null
}, { immediate: true })
function choose(id: string) {
  if (dirty.value && !window.confirm('尚未保存的规划修改将被放弃，继续吗？')) return
  dirty.value = false; selectedId.value = id; authorConfirmed.value = false; acceptWarnings.value = false; selectedChapters.value = []
}
function changed() { dirty.value = true; authorConfirmed.value = false }
function updateCharacters(value: CharacterBlueprint[]) { if (world.value) { world.value.characters = value; changed() } }
function updateEntities(value: Record<string, unknown>[]) { if (world.value) { world.value.entities = value as unknown as PreparationEntity[]; changed() } }
function updateUnits(value: Record<string, unknown>[]) { if (plot.value) { plot.value.units = value as unknown as PlotUnit[]; changed() } }
function updateRelations(value: Record<string, unknown>[]) { if (plot.value) { plot.value.relationships = value as unknown as PlannedRelation[]; changed() } }
function updateKnowledge(value: Record<string, unknown>[]) { if (plot.value) { plot.value.knowledge = value as unknown as PlannedKnowledge[]; changed() } }
function updateTime(value: Record<string, unknown>[]) { if (plot.value) { plot.value.timeline = value as unknown as PlannedTime[]; changed() } }
function updateSeeds(value: ReaderExperienceSeed[]) { if (plot.value) { plot.value.readerExperiencePlans = value; changed() } }
function acceptResult(view: PreparationView, project: string) {
  if (project !== props.projectId) return
  local.value = view; selectedId.value = view.task.id
  cache.setQueryData<PreparationView[]>(['creation-preparations', project], old => [view, ...(old ?? []).filter(item => item.task.id !== view.task.id)])
  if (view.task.reviewReport) tab.value = 'review'
}
async function run(view: PreparationView, project: string) {
  let current = view
  for (let i = 0; i < 3 && current.task.status === 'READY' && project === props.projectId; i++) {
    current = await preparationAction(project, current.task, 'run-next')
    acceptResult(current, project)
  }
}
async function perform(action: (project: string) => Promise<void>) {
  const project = props.projectId; busy.value = true; error.value = ''
  try { await action(project) } catch (failure) { if (project === props.projectId) error.value = failure instanceof Error ? failure.message : '操作失败' }
  finally { if (project === props.projectId) { busy.value = false; void query.refetch() } }
}
async function create() {
  if (dirty.value) { error.value = '请先保存或放弃当前修改'; return }
  authorConfirmed.value = false; acceptWarnings.value = false; selectedChapters.value = []
  await perform(async project => {
    const view = await createPreparation(project, { requestId: crypto.randomUUID(), mode: mode.value, provider: provider.value, instruction: instruction.value,
      ...(manualRange.value ? { startChapter: startChapter.value, endChapter: endChapter.value } : {}) })
    acceptResult(view, project); await run(view, project)
  })
}
async function continueTask() {
  const view = selected.value; if (!view || dirty.value) return
  await perform(async project => {
    const resumed = view.task.status === 'FAILED' ? await preparationAction(project, view.task, 'resume') : view
    acceptResult(resumed, project); await run(resumed, project)
  })
}
async function recover() {
  const current = task.value; if (!current) return
  await perform(async project => acceptResult(await preparationAction(project, current, 'resume'), project))
}
async function cancel() {
  const current = task.value; if (!current) return
  const project = props.projectId
  try {
    const latest = await getPreparation(project, current.id)
    acceptResult(await preparationAction(project, latest.task, 'cancel'), project)
  } catch (failure) { if (project === props.projectId) error.value = failure instanceof Error ? failure.message : '取消失败' }
}
async function save() {
  const current = task.value; const w = world.value; const p = plot.value; if (!current || !w || !p) return
  await perform(async project => { const view = await editPreparation(project, current, w, p); if (project === props.projectId) dirty.value = false; acceptResult(view, project) })
}
async function confirm() {
  const current = task.value; if (!current || !authorConfirmed.value || dirty.value) return
  await perform(async project => {
    const view = await confirmPreparation(project, current, acceptWarnings.value, selectedChapters.value)
    acceptResult(view, project)
    if (project !== props.projectId) return
    await cache.invalidateQueries({ predicate: q => q.queryKey.includes(project) })
    if (view.task.resultOutlineId) emit('outline-created')
  })
}
</script>

<template>
  <section class="preparation">
    <header class="heading"><h2>创作准备与复核</h2><button type="button" title="刷新任务" @click="query.refetch()"><RefreshCw :size="16" /></button></header>
    <section v-if="checkpoints.data.value?.length" class="checkpoints"><h3>剧情复核节点</h3><article v-for="point in checkpoints.data.value" :key="point.key"><strong>{{ point.title }}</strong><span>第 {{ point.startChapter }}—{{ point.endChapter }} 章 · {{ point.stale ? '规划来源已变化' : point.reviewed ? '当前正史已复核' : point.ready ? '单元已完成 · 待复核' : '写作中' }}</span><button v-if="point.ready && !point.stale" type="button" :disabled="busy || dirty" @click="mode = 'REVIEW'; manualRange = true; startChapter = point.startChapter; endChapter = point.endChapter; create()"><RefreshCw :size="16" />复核此单元</button></article></section>
    <div class="create-form">
      <div class="segmented"><button type="button" :class="{ active: mode === 'PREPARE' }" :disabled="busy" @click="mode = 'PREPARE'">创作准备</button><button type="button" :class="{ active: mode === 'REVIEW' }" :disabled="busy" @click="mode = 'REVIEW'">剧情复核</button></div>
      <label class="check"><input v-model="manualRange" type="checkbox" :disabled="busy">指定章节范围</label>
      <div v-if="manualRange" class="range"><label>开始章节<input v-model.number="startChapter" type="number" min="1" :disabled="busy"></label><label>结束章节<input v-model.number="endChapter" type="number" :min="startChapter" :disabled="busy"></label></div>
      <label>本次要求<textarea v-model="instruction" rows="2" maxlength="4000" :disabled="busy" /></label>
      <button class="primary" type="button" :disabled="busy || dirty" @click="create"><Sparkles :size="16" />{{ mode === 'PREPARE' ? '准备并检查' : '生成复核报告' }}</button>
    </div>
    <p v-if="error || query.error.value" role="alert" class="error">{{ error || query.error.value?.message }}</p>
    <p v-if="query.isPending.value">正在读取任务…</p>
    <div v-if="query.data.value?.length" class="history"><label>任务<select :value="selectedId" :disabled="busy" @change="choose(($event.target as HTMLSelectElement).value)"><option v-for="view in query.data.value" :key="view.task.id" :value="view.task.id">{{ view.task.mode === 'PREPARE' ? '创作准备' : '剧情复核' }} · 第 {{ view.task.startChapter }}—{{ view.task.endChapter }} 章 · {{ labels[view.task.status] }}</option></select></label></div>
    <template v-if="task && selected">
      <div class="status"><strong>{{ labels[task.status] }}</strong><span>第 {{ task.startChapter }}—{{ task.endChapter }} 章</span><span>规划资料 · 不属于正文正史</span><span v-if="dirty">有未保存修改</span></div>
      <ol class="steps"><li v-for="step in visibleSteps" :key="step.index" :class="{ done: task.nextStep > step.index, current: task.nextStep === step.index }"><Check v-if="task.nextStep > step.index" :size="14" />{{ step.title }}</li></ol>
      <p v-if="selected.stale" role="alert" class="error">来源资料已变化，旧结果不可确认，请重新创建任务。</p>
      <p v-if="task.errorMessage" role="alert" class="error">{{ task.errorMessage }}</p>
      <ul v-if="selected.ruleWarnings.length" class="warnings"><li v-for="warning in selected.ruleWarnings" :key="warning">{{ warning }}</li></ul>
      <div class="actions">
        <button v-if="['READY', 'FAILED'].includes(task.status)" type="button" :disabled="busy || dirty || selected.stale" @click="continueTask"><Play :size="16" />{{ task.status === 'FAILED' ? '明确重试' : '继续生成' }}</button>
        <button v-if="task.status === 'RUNNING'" type="button" :disabled="busy" @click="recover"><RefreshCw :size="16" />恢复超时任务</button>
        <button v-if="!['CONFIRMED', 'CANCELLED'].includes(task.status)" type="button" @click="cancel"><Square :size="16" />取消任务</button>
        <button v-if="world && plot && task.mode === 'PREPARE'" type="button" :disabled="!editable || !dirty" @click="save"><Save :size="16" />保存修改并重新检查</button>
      </div>
      <nav class="tabs"><button v-for="entry in tabs" :key="entry[0]" type="button" :class="{ active: tab === entry[0] }" @click="tab = entry[0]!">{{ entry[1] }}</button></nav>
      <CharacterBlueprintEditor v-if="tab === 'characters' && world" :model-value="world.characters" :disabled="!editable" @update:model-value="updateCharacters" />
      <PreparationListEditor v-else-if="tab === 'entities' && world" :model-value="world.entities" :fields="entityFields" title="实体与开篇状态" :disabled="!editable" :max-items="40" @update:model-value="updateEntities" />
      <PreparationListEditor v-else-if="tab === 'units' && plot" :model-value="plot.units" :fields="unitFields" title="剧情单元" :disabled="!editable" :max-items="40" @update:model-value="updateUnits" />
      <template v-else-if="tab === 'relations' && plot">
        <PreparationListEditor :model-value="plot.relationships" :fields="relationFields" title="关系变化规划" :disabled="!editable" :max-items="80" @update:model-value="updateRelations" />
        <PreparationListEditor :model-value="plot.knowledge" :fields="knowledgeFields" title="知识边界规划" :disabled="!editable" :max-items="80" @update:model-value="updateKnowledge" />
      </template>
      <template v-else-if="tab === 'timeline' && plot">
        <PreparationListEditor :model-value="plot.timeline" :fields="timeFields" title="规划时间线" :disabled="!editable" :max-items="120" @update:model-value="updateTime" />
        <ReaderExperienceSeedEditor :model-value="plot.readerExperiencePlans" :disabled="!editable" @update:model-value="updateSeeds" />
      </template>
      <section v-else-if="tab === 'review' && task.reviewReport" class="report">
        <h3>复核结论</h3><p>{{ task.reviewReport.summary }}</p>
        <article v-for="issue in task.reviewReport.issues" :key="issue.key" class="issue"><strong>{{ issue.severity === 'BLOCKING' ? '阻断' : '待关注' }} · {{ issue.category }}</strong><p>{{ issue.description }}</p><blockquote>{{ issue.evidence }}</blockquote><p>{{ issue.suggestion }}</p><small>{{ issue.sourceRef }}</small></article>
        <p v-if="!task.reviewReport.issues.length">本次未发现有依据的问题。</p>
        <h3 v-if="task.reviewReport.adjustments.length">未来章节调整</h3>
        <article v-for="adjustment in task.reviewReport.adjustments" :key="adjustment.chapterNumber" class="adjustment"><label class="check"><input v-model="selectedChapters" type="checkbox" :value="adjustment.chapterNumber" :disabled="task.status !== 'AWAITING_CONFIRMATION' || busy || selected.stale">第 {{ adjustment.chapterNumber }} 章</label><p>{{ adjustment.reason }}</p><dl><dt>目标</dt><dd>{{ adjustment.objective }}</dd><dt>事件</dt><dd>{{ adjustment.coreEvent }}</dd><dt>揭示</dt><dd>{{ adjustment.reveal }}</dd><dt>结尾</dt><dd>{{ adjustment.endingHook }}</dd></dl></article>
        <h3 v-if="task.reviewReport.planLinks.length">待确认台账关联</h3><blockquote v-for="link in task.reviewReport.planLinks" :key="`${link.planId}:${link.factId}`">{{ link.evidence }}</blockquote>
      </section>
      <p v-else class="empty">本阶段暂无结果。</p>
      <footer v-if="task.status === 'AWAITING_CONFIRMATION'" class="confirmation"><label v-if="task.reviewReport?.issues.length" class="check"><input v-model="acceptWarnings" type="checkbox" :disabled="busy || hasBlocking">接受待关注问题</label><label class="check"><input v-model="authorConfirmed" type="checkbox" :disabled="busy || hasBlocking || dirty || selected.stale">{{ task.mode === 'PREPARE' ? '确认本次创作规划' : '确认本次复核与选定调整' }}</label><button type="button" class="primary" :disabled="busy || !authorConfirmed || hasBlocking || dirty || selected.stale || (!!task.reviewReport?.issues.length && !acceptWarnings)" @click="confirm"><Check :size="16" />{{ task.mode === 'PREPARE' ? '应用规划资料' : '确认报告并保存调整草稿' }}</button></footer>
      <p v-if="task.resultOutlineId" class="success">未来调整已保存为新大纲草稿，尚未发布。</p>
    </template>
  </section>
</template>

<style scoped>
.preparation { min-width: 0; }
.heading, .status, .actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.heading { justify-content: space-between; }
h2 { font-size: 22px; margin: 12px 0 20px; } h3 { font-size: 16px; }
.create-form { display: grid; gap: 14px; padding: 16px 0 24px; border-bottom: 1px solid #dce2e5; }
label { display: grid; gap: 6px; font-size: 14px; min-width: 0; }
input, select, textarea { box-sizing: border-box; max-width: 100%; width: 100%; padding: 9px; border: 1px solid #cbd3d8; border-radius: 4px; font: inherit; background: #fff; }
textarea { resize: vertical; } .check { display: flex; align-items: center; gap: 8px; } .check input { width: 16px; height: 16px; flex-shrink: 0; }
.range { display: grid; grid-template-columns: repeat(2, minmax(0, 180px)); gap: 12px; }
button { display: inline-flex; justify-content: center; align-items: center; gap: 7px; padding: 9px 12px; border: 1px solid #cbd3d8; border-radius: 4px; background: #fff; cursor: pointer; font: inherit; }
button:disabled { opacity: .5; cursor: default; } .primary { background: #346b61; color: #fff; border-color: #346b61; justify-self: start; }
.segmented { display: flex; gap: 0; } .segmented .active, .tabs .active { background: #eaf3f0; color: #285d52; border-color: #346b61; }
.history { margin: 20px 0; max-width: 600px; } .status { margin: 18px 0; color: #617079; font-size: 13px; } .status strong { color: #28373e; }
.steps { display: flex; flex-wrap: wrap; gap: 16px; list-style: none; padding: 0; font-size: 14px; } .steps li { display: flex; align-items: center; gap: 5px; color: #7c878e; } .steps .done { color: #346b61; } .steps .current { color: #26373d; font-weight: 600; }
.tabs { display: flex; gap: 8px; overflow-x: auto; margin: 20px 0; max-width: 100%; } .tabs button { white-space: nowrap; flex-shrink: 0; }
.report, .issue, .adjustment { padding: 14px 0; border-top: 1px solid #dce2e5; } p, dd, blockquote, small { overflow-wrap: anywhere; white-space: pre-wrap; line-height: 1.65; }
.checkpoints article { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; border-bottom: 1px solid #e5e9eb; padding: 12px 0; } .checkpoints span { font-size: 13px; color: #617079; }
blockquote { margin: 12px 0; border-left: 3px solid #b0bbc1; padding-left: 12px; color: #586970; } small { color: #7c878e; }
dl { display: grid; grid-template-columns: 50px minmax(0, 1fr); gap: 8px; } dd { margin: 0; }
.confirmation { border-top: 1px solid #dce2e5; padding: 20px 0; display: grid; gap: 14px; }
.error { color: #a63838; } .warnings { color: #8b661c; padding-left: 20px; } .success { color: #346b61; } .empty { color: #7c878e; }
@media (max-width: 700px) { h2 { font-size: 19px; } .actions button, .confirmation .primary { max-width: 100%; } }
</style>

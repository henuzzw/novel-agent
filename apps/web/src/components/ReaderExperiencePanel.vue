<script setup lang="ts">
import { createUuid } from '@/lib/uuid'
import { computed, ref, watch } from 'vue'
import { Check, Plus, RefreshCw, Save, Trash2, X } from 'lucide-vue-next'
import { ApiError } from '@/api/http'
import PlanningMaterialSyncButton from './PlanningMaterialSyncButton.vue'
import { listPlanOrigins, type PlanOrigin } from '@/api/planningMaterials'
import { listForeshadows, type Foreshadow } from '@/api/writing'
import {
  createReaderExperience, deleteReaderExperience, getReaderExperienceMemory, getReaderExperienceSource,
  listReaderExperiences, listReaderExperienceSources, readerExperienceTransitions, submitReaderExperience,
  updateReaderExperience, validateReaderExperienceEvidence,
  type ReaderExperienceEntry, type ReaderExperienceKind, type ReaderExperienceManuscript,
  type ReaderExperienceMemory, type ReaderExperienceSource, type ReaderExperienceState,
} from '@/api/readerExperience'

const props = defineProps<{ projectId: string }>()
const entries = ref<ReaderExperienceEntry[]>([])
const origins = ref<PlanOrigin[]>([])
const canonForeshadows = ref<Foreshadow[]>([])
function originLabel(id: string) {
  const origin = origins.value.find(item => item.planId === id)
  if (!origin) return '作者录入'
  const labels = { BIBLE: '故事圣经规划', OUTLINE: '大纲规划', CANON: '正史伏笔', PREPARATION: '创作准备规划' }
  return `${labels[origin.sourceKind]}${origin.current ? '' : ' · 来源已替换'}`
}
const manuscripts = ref<ReaderExperienceManuscript[]>([])
const memory = ref<ReaderExperienceMemory | null>(null)
const selected = ref<ReaderExperienceEntry | null>(null)
const editing = ref(false)
const loading = ref(false)
const loaded = ref(false)
const saving = ref(false)
const conflict = ref(false)
const error = ref('')
const notice = ref('')
const tab = ref<'ledger' | 'memory'>('ledger')
const filter = ref<'ALL' | ReaderExperienceState>('ALL')
const draft = ref(blank())
const sourceId = ref('')
const source = ref<ReaderExperienceSource | null>(null)
const sourceLoading = ref(false)
const evidence = ref('')
const authorNote = ref('')
const authorConfirmed = ref(false)
const nextState = ref<Exclude<ReaderExperienceState, 'PLANNED'>>('SET_UP')
let session = 0
let sourceSession = 0
let retryKey: { signature: string; id: string } | null = null
const busy = computed(() => loading.value || saving.value || sourceLoading.value)
const blocked = computed(() => busy.value || conflict.value || !loaded.value)
const visibleEntries = computed(() => entries.value.filter(item => filter.value === 'ALL' || item.state === filter.value))
const transitions = computed(() => selected.value ? readerExperienceTransitions(selected.value) : [])
const labels: Record<ReaderExperienceState, string> = {
  PLANNED: '计划中', SET_UP: '已埋设', REINFORCED: '已强化', PAYOFF: '已兑现', ABANDONED: '作者放弃', OPEN: '开放保留',
}

function blank() {
  return { kind: 'PROMISE' as ReaderExperienceKind, title: '', promise: '', setup: '', payoff: '', aftermath: '', plannedChapter: null as number | null }
}
function key(signature: string) {
  if (!retryKey || retryKey.signature !== signature) retryKey = { signature, id: createUuid() }
  return retryKey.id
}
function select(entry: ReaderExperienceEntry | null) {
  selected.value = entry
  editing.value = true
  draft.value = entry ? { kind: entry.plan.kind, title: entry.plan.title, promise: entry.plan.promise, setup: entry.plan.setup,
    payoff: entry.plan.payoff, aftermath: entry.plan.aftermath, plannedChapter: entry.plan.plannedChapter } : blank()
  sourceId.value = ''; source.value = null; evidence.value = ''; authorNote.value = ''; authorConfirmed.value = false
  nextState.value = entry ? readerExperienceTransitions(entry)[0] ?? 'SET_UP' : 'SET_UP'
  notice.value = ''; retryKey = null
}
async function load() {
  const project = props.projectId, token = session
  loading.value = true
  error.value = ''
  try {
    const [items, sources, summaries, planOrigins, foreshadows] = await Promise.all([
      listReaderExperiences(project), listReaderExperienceSources(project), getReaderExperienceMemory(project),
      listPlanOrigins(project), listForeshadows(project),
    ])
    if (token !== session) return
    entries.value = items; manuscripts.value = sources; memory.value = summaries
    origins.value = planOrigins; canonForeshadows.value = foreshadows
    loaded.value = true; conflict.value = false
    if (selected.value) {
      const updated = items.find(item => item.plan.id === selected.value?.plan.id)
      if (updated) select(updated)
      else { selected.value = null; editing.value = false }
    }
  } catch (failure) {
    if (token === session) { error.value = (failure as Error).message; loaded.value = false }
  } finally { if (token === session) loading.value = false }
}
watch(() => props.projectId, () => {
  session += 1; sourceSession += 1
  entries.value = []; manuscripts.value = []; memory.value = null; selected.value = null; editing.value = false
  origins.value = []; canonForeshadows.value = []
  loaded.value = false; saving.value = false; sourceLoading.value = false; conflict.value = false; error.value = ''; notice.value = ''
  sourceId.value = ''; source.value = null; retryKey = null; filter.value = 'ALL'; tab.value = 'ledger'
  draft.value = blank(); evidence.value = ''; authorNote.value = ''; authorConfirmed.value = false
  void load()
}, { immediate: true, flush: 'sync' })
watch(sourceId, async id => {
  const token = ++sourceSession, active = session, project = props.projectId
  source.value = null; authorConfirmed.value = false; sourceLoading.value = !!id
  if (!id) return
  try {
    const result = await getReaderExperienceSource(project, id)
    if (active === session && token === sourceSession) source.value = result
  } catch (failure) { if (active === session && token === sourceSession) error.value = (failure as Error).message }
  finally { if (active === session && token === sourceSession) sourceLoading.value = false }
})
watch([evidence, nextState, authorNote], () => { authorConfirmed.value = false })

async function mutate(action: (project: string) => Promise<ReaderExperienceEntry | void>) {
  if (blocked.value) return
  const project = props.projectId, token = session
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const result = await action(project)
    if (token !== session) return
    retryKey = null
    if (result) {
      entries.value = [...entries.value.filter(item => item.plan.id !== result.plan.id), result]
      select(result)
    } else {
      entries.value = entries.value.filter(item => item.plan.id !== selected.value?.plan.id)
      editing.value = false; selected.value = null
    }
    notice.value = '已保存'
  } catch (failure) {
    if (token === session) {
      conflict.value = failure instanceof ApiError && failure.status === 409
      error.value = conflict.value ? '台账版本已变化，请重新读取后再提交。' : (failure as Error).message
    }
  } finally { if (token === session) saving.value = false }
}
function savePlan() {
  if (blocked.value || !draft.value.title.trim() || !draft.value.promise.trim()) return
  const id = selected.value?.plan.id
  const input = { ...draft.value, plannedChapter: draft.value.plannedChapter || null,
    expectedVersion: selected.value?.plan.version ?? null }
  const requestId = key(JSON.stringify(['plan', id, input]))
  void mutate(project => id ? updateReaderExperience(project, id, { ...input, requestId }) : createReaderExperience(project, { ...input, requestId }))
}
function remove() {
  if (blocked.value || !selected.value) return
  const { id, version } = selected.value.plan
  const requestId = key(JSON.stringify(['delete', id, version]))
  void mutate(project => deleteReaderExperience(project, id, version, requestId))
}
function submitEvidence() {
  if (blocked.value || !selected.value || !source.value || !authorConfirmed.value) return
  const id = selected.value.plan.id
  const input = { expectedVersion: selected.value.plan.version, state: nextState.value, manuscriptId: source.value.id,
    manuscriptRowVersion: source.value.rowVersion, sourceFingerprint: source.value.fingerprint,
    evidence: evidence.value, authorNote: authorNote.value, authorConfirmed: authorConfirmed.value }
  const requestId = key(JSON.stringify(['event', id, input]))
  try { validateReaderExperienceEvidence({ ...input, requestId }, source.value, props.projectId) }
  catch (failure) { error.value = (failure as Error).message; return }
  void mutate(project => submitReaderExperience(project, id, { ...input, requestId }))
}
defineExpose({ reload: load })
</script>

<template>
  <section class="reader-experience-panel" aria-label="读者承诺与伏笔" :aria-busy="busy">
    <PlanningMaterialSyncButton :project-id="projectId" @synced="load" />
    <header><h2>读者承诺与伏笔</h2><div class="tools">
      <button type="button" title="新增计划" aria-label="新增计划" :disabled="blocked" @click="select(null)"><Plus :size="18" /></button>
      <button type="button" title="重新读取台账" aria-label="重新读取台账" :disabled="busy" @click="load"><RefreshCw :size="18" /></button>
    </div></header>
    <nav aria-label="台账视图"><button type="button" :aria-pressed="tab === 'ledger'" @click="tab = 'ledger'">承诺与伏笔</button>
      <button type="button" :aria-pressed="tab === 'memory'" @click="tab = 'memory'">有效正史摘要</button></nav>
    <p v-if="loading" role="status">正在读取…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="loaded && tab === 'ledger'">
      <label class="filter">进展筛选<select v-model="filter"><option value="ALL">全部</option><option v-for="(label, state) in labels" :key="state" :value="state">{{ label }}</option></select></label>
      <p v-if="!entries.length" class="empty">暂无承诺或伏笔记录</p>
      <p v-else-if="!visibleEntries.length" class="empty">没有符合筛选的记录</p>
      <ul class="entry-list"><li v-for="entry in visibleEntries" :key="entry.plan.id">
        <button type="button" class="entry" :aria-pressed="selected?.plan.id === entry.plan.id" :disabled="busy" @click="select(entry)">
          <strong>{{ entry.plan.title }}<small class="origin-label">{{ originLabel(entry.plan.id) }}</small></strong><span>{{ entry.plan.kind === 'PROMISE' ? '读者承诺' : '伏笔' }}</span>
          <span>{{ labels[entry.state] }}{{ entry.stale ? ' · 依据过期' : '' }}</span>
          <span>{{ entry.plan.plannedChapter ? `计划第 ${entry.plan.plannedChapter} 章` : '未设兑现章' }}</span>
        </button>
      </li></ul>
      <section v-if="canonForeshadows.length" class="canon-foreshadows">
        <h3>正文正史伏笔</h3>
        <article v-for="item in canonForeshadows" :key="item.id">
          <h4>{{ item.title }}</h4><p>{{ item.targetEffect }}</p>
          <small>{{ ({ PLANTED: '已埋设', REINFORCED: '已强化', PARTIALLY_REVEALED: '部分揭示', RESOLVED: '已回收', ABANDONED: '已放弃' } as Record<string, string>)[item.status] ?? item.status }} · 正史 V{{ item.canonVersionFrom }}</small>
          <blockquote v-if="item.evidence">{{ item.evidence }}</blockquote>
        </article>
      </section>
      <div v-if="editing" class="editor">
        <header><h3>{{ selected ? '编辑计划' : '新增计划' }}</h3><button type="button" title="关闭编辑" aria-label="关闭编辑" :disabled="busy" @click="editing = false"><X :size="16" /></button></header>
        <form aria-label="计划编辑" @submit.prevent="savePlan"><fieldset :disabled="blocked">
          <div class="fields"><label>类型<select v-model="draft.kind"><option value="PROMISE">读者承诺</option><option value="FORESHADOW">伏笔</option></select></label>
            <label>计划兑现章<input v-model.number="draft.plannedChapter" type="number" min="1" aria-label="计划兑现章" /></label></div>
          <label>标题<input v-model="draft.title" required maxlength="200" /></label>
          <label>承诺<textarea v-model="draft.promise" required maxlength="4000" rows="2" /></label>
          <label>铺垫计划<textarea v-model="draft.setup" maxlength="4000" rows="2" /></label>
          <label>兑现计划<textarea v-model="draft.payoff" maxlength="4000" rows="2" /></label>
          <label>余波计划<textarea v-model="draft.aftermath" maxlength="4000" rows="2" /></label>
          <div class="actions"><button type="submit" :disabled="!draft.title.trim() || !draft.promise.trim()"><Save :size="16" />保存计划</button>
            <button v-if="selected" type="button" title="删除计划" aria-label="删除计划" @click="remove"><Trash2 :size="16" /></button></div>
        </fieldset></form>
        <template v-if="selected">
          <h3>实际进展</h3>
          <p v-if="selected.stale" class="error">{{ selected.history[selected.history.length - 1]?.staleReason }}</p>
          <form v-if="transitions.length" aria-label="实际进展提交" @submit.prevent="submitEvidence"><fieldset :disabled="blocked">
            <label>进展<select v-model="nextState"><option v-for="state in transitions" :key="state" :value="state">{{ labels[state] }}</option></select></label>
            <label>来源正文<select v-model="sourceId"><option value="">选择作者已确认正文</option><option v-for="item in manuscripts" :key="item.id" :value="item.id" :disabled="item.superseded">
              第 {{ item.chapterNumber }} 章 · {{ item.title }} · v{{ item.rowVersion }} · {{ item.superseded ? '已替换' : item.canon ? '正史正文' : '未正史' }}
            </option></select></label>
            <details v-if="source"><summary>来源正文 · 行版本 {{ source.rowVersion }} · {{ source.canonManuscriptId === source.id ? '正史正文' : '未正史' }}</summary><pre>{{ source.body }}</pre></details>
            <label>逐字正文证据<textarea v-model="evidence" required maxlength="6000" rows="3" /></label>
            <label>作者说明<textarea v-model="authorNote" maxlength="4000" rows="2" /></label>
            <label class="confirmation"><input v-model="authorConfirmed" type="checkbox" />我确认以所选正文证据提交此实际进展</label>
            <button type="submit" :disabled="!source || !evidence.trim() || !authorConfirmed"><Check :size="16" />确认提交进展</button>
          </fieldset></form>
          <p v-if="!selected.history.length">尚未提交实际进展</p>
          <ol class="history"><li v-for="item in selected.history" :key="item.event.id">
            <strong>{{ labels[item.event.state] }}</strong> · 第 {{ item.event.chapterNumber }} 章 · {{ item.stale ? '依据过期' : item.canon ? '正史正文证据' : '未正史' }}
            <blockquote>{{ item.event.evidence }}</blockquote><p v-if="item.staleReason" class="error">{{ item.staleReason }}</p>
            <p>{{ item.event.authorNote }}</p><small>正文 {{ item.event.manuscriptId }} · 行版本 {{ item.event.manuscriptRowVersion }} · 计划版本 {{ item.event.planSnapshot.version }}</small>
          </li></ol>
        </template>
      </div>
    </template>
    <div v-if="loaded && tab === 'memory' && memory" class="memory">
      <p class="source-label">已有章节摘要 · 有效正史来源</p>
      <p v-if="memory.outlineId"><small>大纲 {{ memory.outlineId }} · 行版本 {{ memory.outlineRowVersion }}</small></p>
      <p v-if="!memory.arcs.some(arc => arc.chapters.length) && !memory.unassignedChapters.length">暂无有效正史章节摘要</p>
      <section v-for="arc in memory.arcs" :key="arc.number"><h3>第 {{ arc.number }} 卷 · {{ arc.title }}</h3>
        <p v-if="!arc.chapters.length">本卷暂无有效正史章节摘要</p>
        <article v-for="chapter in arc.chapters" :key="chapter.canonCommitId"><h4>第 {{ chapter.chapterNumber }} 章 · {{ chapter.title }}</h4>
          <p>{{ chapter.summary || '来源摘要缺失' }}</p><small>正文 {{ chapter.manuscriptId }} · 行版本 {{ chapter.manuscriptRowVersion }} · 正史 v{{ chapter.canonVersion }}</small></article>
      </section>
      <section v-if="memory.unassignedChapters.length"><h3>未归入当前大纲的正史章节</h3><article v-for="chapter in memory.unassignedChapters" :key="chapter.canonCommitId">
        <h4>第 {{ chapter.chapterNumber }} 章 · {{ chapter.title }}</h4><p>{{ chapter.summary || '来源摘要缺失' }}</p>
        <small>正文 {{ chapter.manuscriptId }} · 行版本 {{ chapter.manuscriptRowVersion }} · 正史 v{{ chapter.canonVersion }}</small>
      </article></section>
    </div>
  </section>
</template>

<style scoped>
.reader-experience-panel { display: grid; gap: 14px; min-width: 0; padding: 18px 0; border-bottom: 1px solid #d8dce0; }
.origin-label { display: block; font-weight: normal; margin-top: 4px; overflow-wrap: anywhere; }
.canon-foreshadows article { border-bottom: 1px solid #d8dce0; padding: 10px 0; overflow-wrap: anywhere; }
header, .tools, .actions, nav { display: flex; align-items: center; gap: 10px; }
header { justify-content: space-between; } h2 { margin: 0; font-size: 16px; } h3 { margin: 0; font-size: 15px; } h4 { margin: 0; font-size: 14px; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 36px; padding: 7px 10px; border: 1px solid #cbd2d9; border-radius: 4px; background: #fff; cursor: pointer; font: inherit; }
button svg { flex-shrink: 0; } button:disabled { opacity: .5; cursor: default; } button[aria-pressed=true] { border-color: #176b63; background: #f3faf8; }
.tools button { width: 36px; padding: 0; } nav { flex-wrap: wrap; padding-bottom: 10px; border-bottom: 1px solid #e1e5e9; }
fieldset, form, .editor, .memory { display: grid; gap: 12px; min-width: 0; } fieldset { border: 0; padding: 0; margin: 0; }
label { display: grid; gap: 5px; font-size: 13px; min-width: 0; } input, select, textarea { box-sizing: border-box; width: 100%; min-width: 0; border: 1px solid #cbd2d9; border-radius: 4px; padding: 8px; font: inherit; background: #fff; color: inherit; }
textarea { resize: vertical; } .fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; } .filter { max-width: 220px; }
.entry-list, .history { list-style: none; padding: 0; margin: 0; } .entry { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr); width: 100%; text-align: left; justify-items: start; border: 0; border-bottom: 1px solid #e1e5e9; border-radius: 0; padding: 12px 6px; }
.entry span { font-size: 12px; } .editor { border-top: 1px solid #cbd2d9; padding-top: 16px; } .confirmation { display: flex; align-items: flex-start; gap: 8px; } .confirmation input { width: 16px; flex-shrink: 0; margin-top: 2px; }
.history li, .memory article { padding: 12px 0; border-bottom: 1px solid #e1e5e9; } blockquote { margin: 8px 0; padding-left: 12px; border-left: 3px solid #829b93; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; max-height: 260px; overflow: auto; } p { margin: 0; } small { color: #626b73; } .error { color: #a03342; }
p, span, strong, small, blockquote, h3, h4 { overflow-wrap: anywhere; } .memory section { display: grid; gap: 8px; } .source-label { color: #176b63; }
@media (max-width: 620px) { .entry { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .entry strong { grid-column: 1 / -1; } .fields { grid-template-columns: 1fr; } }
</style>

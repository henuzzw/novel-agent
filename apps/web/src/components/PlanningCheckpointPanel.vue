<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Check, Layers, Pause, Play, RefreshCw, RotateCcw } from 'lucide-vue-next'
import {
  actOnPlanningBatch, assemblePlanningBatch, createPlanningBatch,
  getCurrentPlanningBible, getPlanningBatch, listPlanningBatches,
  type PlanningBatch, type PlanningCheckpoint,
} from '@/api/planningCheckpoints'
import { getOutlineVersion, type ModelProvider, type OutlineVersion, type StoryBibleVersion } from '@/api/planning'
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'

const props = defineProps<{ projectId: string; provider: ModelProvider; externalBusy?: boolean }>()
const emit = defineEmits<{ assembled: [outline: OutlineVersion]; 'busy-change': [busy: boolean] }>()
const bible = ref<StoryBibleVersion | null>(null)
const batches = ref<PlanningBatch[]>([])
const selectedId = ref('')
const batch = ref<PlanningBatch | null>(null)
const chapterTo = ref(20)
const chunkSize = ref(5)
const instruction = ref('')
const loading = ref(false)
const busy = ref('')
const cancelling = ref(false)
const readError = ref('')
const actionError = ref('')
let scope = 0
let readSequence = 0
let actionSequence = 0
let createIdentity: { fingerprint: string; requestId: string } | null = null

const batchLabels: Record<PlanningBatch['status'], string> = {
  READY: '待推进', RUNNING: '执行中', FAILED: '需要重试', CANCELLED: '已取消', SUCCEEDED: '已拼装草稿',
}
const chunkLabels: Record<PlanningCheckpoint['status'], string> = {
  PENDING: '待生成', RUNNING: '生成中', SUCCEEDED: '已完成 · 可复用', FAILED: '失败 · 待显式重试', CANCELLED: '已取消',
}
const completed = computed(() => batch.value?.checkpoints.filter(c => c.status === 'SUCCEEDED').length ?? 0)
const totalChunks = computed(() => batch.value ? Math.ceil(batch.value.chapterTo / batch.value.chunkSize) : 0)
const allCompleted = computed(() => !!totalChunks.value && completed.value === totalChunks.value &&
  batch.value?.checkpoints[batch.value.checkpoints.length - 1]?.chapterTo === batch.value?.chapterTo)
const sourceChanged = computed(() => !!batch.value && !!bible.value &&
  (batch.value.bibleId !== bible.value.id || batch.value.bibleRowVersion !== bible.value.version))
const canAct = computed(() => !!batch.value && !!bible.value && !sourceChanged.value && !readError.value && !loading.value && !busy.value && !cancelling.value && !props.externalBusy)
const canAdvance = computed(() => canAct.value && batch.value?.status === 'READY' &&
  !allCompleted.value &&
  !batch.value.checkpoints.some(c => ['FAILED', 'CANCELLED', 'RUNNING'].includes(c.status)))
const canAssemble = computed(() => canAct.value && allCompleted.value && batch.value?.status === 'READY')
const validInput = computed(() => Number.isInteger(chapterTo.value) && chapterTo.value >= 1 && chapterTo.value <= 500 &&
  Number.isInteger(chunkSize.value) && chunkSize.value >= 1 && chunkSize.value <= 20)
const canCancel = computed(() => !!batch.value && !loading.value && !cancelling.value &&
  !props.externalBusy && (!busy.value || busy.value === 'run-next') && !['CANCELLED', 'SUCCEEDED'].includes(batch.value.status))

watch(() => !!busy.value || cancelling.value, value => emit('busy-change', value))

function message(error: unknown) { return error instanceof Error ? error.message : '请求失败，请刷新持久进度。' }

async function refresh(preferredId = selectedId.value) {
  const ownScope = scope
  const sequence = ++readSequence
  const projectId = props.projectId
  loading.value = true
  readError.value = ''
  try {
    const [current, list] = await Promise.all([getCurrentPlanningBible(projectId), listPlanningBatches(projectId)])
    const id = list.find(b => b.id === preferredId)?.id ?? list[0]?.id ?? ''
    const detail = id ? await getPlanningBatch(projectId, id) : null
    if (scope !== ownScope || sequence !== readSequence) return
    if (current && (current.status !== 'PUBLISHED' || current.projectId !== projectId)) {
      throw new Error('当前故事圣经不是本项目已发布版本，请刷新或重新发布。')
    }
    if (detail && detail.projectId !== projectId) throw new Error('规划批次不属于当前项目。')
    bible.value = current
    batches.value = list
    selectedId.value = id
    batch.value = detail
  } catch (error) {
    if (scope === ownScope && sequence === readSequence) readError.value = message(error)
  } finally {
    if (scope === ownScope && sequence === readSequence) loading.value = false
  }
}

watch(() => props.projectId, () => {
  scope++
  actionSequence++
  bible.value = null
  batches.value = []
  batch.value = null
  selectedId.value = ''
  chapterTo.value = 20
  chunkSize.value = 5
  instruction.value = ''
  busy.value = ''
  cancelling.value = false
  actionError.value = ''
  createIdentity = null
  void refresh()
}, { immediate: true })
onBeforeUnmount(() => { scope++; readSequence++; actionSequence++; emit('busy-change', false) })

async function create() {
  if (!bible.value || !validInput.value || props.provider === 'LOCAL_TEMPLATE' || props.externalBusy || busy.value || cancelling.value || loading.value || readError.value) return
  const ownScope = scope
  const projectId = props.projectId
  const input = { chapterTo: chapterTo.value, chunkSize: chunkSize.value, provider: props.provider,
    instruction: instruction.value.trim(), expectedBibleVersion: bible.value.version, expectedBibleId: bible.value.id }
  const fingerprint = JSON.stringify({ ...input, bibleId: bible.value.id })
  // Keep the same key after an ambiguous response so an explicit click cannot create a duplicate batch.
  if (createIdentity?.fingerprint !== fingerprint) createIdentity = { fingerprint, requestId: crypto.randomUUID() }
  busy.value = 'create'
  actionError.value = ''
  try {
    const created = await createPlanningBatch(projectId, { ...input, requestId: createIdentity.requestId })
    if (scope !== ownScope) return
    createIdentity = null
    await refresh(created.id)
  } catch (error) {
    if (scope !== ownScope) return
    actionError.value = message(error)
    await refresh()
  } finally {
    if (scope === ownScope) busy.value = ''
  }
}

async function act(action: 'run-next' | 'resume' | 'assemble' | 'open') {
  if (!batch.value || (action !== 'open' && !canAct.value) ||
    (action === 'open' && (busy.value || loading.value || cancelling.value || props.externalBusy))) return
  if (action === 'run-next' && !canAdvance.value) return
  if (action === 'assemble' && !canAssemble.value) return
  const ownScope = scope
  const sequence = ++actionSequence
  const projectId = props.projectId
  const snapshot = batch.value
  busy.value = action
  actionError.value = ''
  try {
    let outline: OutlineVersion | null = null
    if (action === 'assemble') outline = await assemblePlanningBatch(projectId, snapshot)
    else if (action === 'open' && snapshot.outlineVersionId) outline = await getOutlineVersion(projectId, snapshot.outlineVersionId)
    else if (action === 'run-next' || action === 'resume') await actOnPlanningBatch(projectId, snapshot, action)
    if (scope !== ownScope || sequence !== actionSequence) return
    if (outline) emit('assembled', outline)
    await refresh(snapshot.id)
  } catch (error) {
    if (scope !== ownScope || sequence !== actionSequence) return
    actionError.value = message(error)
    // Reads are safe after a timeout or conflict; generation is never retried here.
    await refresh(snapshot.id)
  } finally {
    if (scope === ownScope && sequence === actionSequence) busy.value = ''
  }
}

async function cancel() {
  if (!canCancel.value || !batch.value) return
  const ownScope = scope
  const sequence = ++actionSequence
  const projectId = props.projectId
  const id = batch.value.id
  cancelling.value = true
  actionError.value = ''
  try {
    // A running claim increments its version before the long request returns.
    const current = await getPlanningBatch(projectId, id)
    if (scope !== ownScope || sequence !== actionSequence) return
    await actOnPlanningBatch(projectId, current, 'cancel')
    if (scope === ownScope && sequence === actionSequence) await refresh(id)
  } catch (error) {
    if (scope !== ownScope || sequence !== actionSequence) return
    actionError.value = message(error)
    await refresh(id)
  } finally {
    if (scope === ownScope && sequence === actionSequence) { cancelling.value = false; busy.value = '' }
  }
}
</script>

<template>
  <section class="planning-checkpoint-panel" aria-label="分块规划">
    <header class="planning-heading">
      <div><span class="eyebrow">规划进度</span><h3><Layers :size="19" />分块规划</h3></div>
      <button type="button" class="button secondary" title="刷新持久进度" :disabled="loading || cancelling" @click="refresh()"><RefreshCw :size="16" />刷新进度</button>
    </header>
    <p v-if="loading" role="status">正在读取规划进度…</p>
    <p v-if="readError" class="form-error" role="alert">{{ readError }}</p>
    <p v-else-if="!loading && !bible" class="planning-notice">请先发布故事圣经，再创建分块规划。</p>
    <p v-else-if="bible" class="planning-basis">当前已发布圣经：第 {{ bible.generationNumber }} 版 · 修订 {{ bible.version }}</p>

    <form class="planning-create" @submit.prevent="create">
      <label><span>规划至第几章</span><input v-model.number="chapterTo" type="number" min="1" max="500" step="1" required /></label>
      <label><span>每块章节数</span><input v-model.number="chunkSize" type="number" min="1" max="20" step="1" required /></label>
      <label class="planning-instruction"><span>规划要求</span><textarea v-model="instruction" rows="2" maxlength="1000" /></label>
      <div class="planning-create-actions"><GlobalModelBadge /><button type="submit" class="button secondary" :disabled="!bible || !validInput || provider === 'LOCAL_TEMPLATE' || externalBusy || loading || !!busy || cancelling || !!readError"><Layers :size="16" />{{ busy === 'create' ? '正在创建…' : '创建规划批次' }}</button></div>
    </form>

    <label v-if="batches.length" class="planning-selection"><span>持久规划批次</span><select :value="selectedId" :disabled="loading || !!busy || cancelling" @change="refresh(($event.target as HTMLSelectElement).value)"><option v-for="item in batches" :key="item.id" :value="item.id">第 1～{{ item.chapterTo }} 章 · 每块 {{ item.chunkSize }} 章 · {{ batchLabels[item.status] }} · {{ new Date(item.createdAt).toLocaleString('zh-CN') }} · {{ item.id.slice(0, 8) }}</option></select></label>
    <div v-if="batch" class="planning-progress">
      <div class="planning-progress-heading"><strong>{{ batchLabels[batch.status] }}</strong><span>已完成 {{ completed }} / {{ totalChunks }} 块</span></div>
      <progress :value="completed" :max="totalChunks || 1" aria-label="已完成规划块" />
      <p class="planning-basis">冻结圣经 {{ batch.bibleId }} · 修订 {{ batch.bibleRowVersion }} · {{ batch.provider }}</p>
      <p v-if="batch.instruction" class="planning-basis">{{ batch.instruction }}</p>
      <p v-if="sourceChanged" class="form-error" role="alert">当前已发布圣经已变化，请创建新的批次。旧块不能继续执行或复用。</p>
      <p v-if="batch.status === 'RUNNING' || busy === 'run-next'" class="planning-notice">当前块正在执行。中断后可刷新进度；若仍为执行中，可先取消再恢复。</p>
      <p v-if="batch.status === 'FAILED'" class="planning-notice">执行失败。请核对失败块后显式重试或恢复批次；下次推进可能再次产生模型费用。</p>
      <p v-if="batch.status === 'CANCELLED'" class="planning-notice">批次已取消，已完成块保留，恢复后继续复用。</p>
      <p v-if="batch.status === 'SUCCEEDED'" class="planning-notice">已拼装为大纲草稿，尚未自动发布。</p>
      <ol class="planning-chunks">
        <li v-for="checkpoint in batch.checkpoints" :key="checkpoint.id" :class="['planning-chunk', checkpoint.status.toLowerCase()]">
          <div class="planning-chunk-heading"><strong>第 {{ checkpoint.chapterFrom }}～{{ checkpoint.chapterTo }} 章</strong><span>{{ chunkLabels[checkpoint.status] }}</span><small>尝试 {{ checkpoint.attempt }}</small></div>
          <p v-if="checkpoint.failure" class="form-error">{{ checkpoint.failure }}</p>
          <details v-if="checkpoint.result"><summary>查看已完成内容</summary><div v-for="arc in checkpoint.result.arcs" :key="arc.ordinal"><h4>{{ arc.title }}</h4><p v-for="chapter in arc.chapters" :key="chapter.number">第 {{ chapter.number }} 章 · {{ chapter.title }}：{{ chapter.coreEvent }}</p></div></details>
        </li>
      </ol>
      <div class="planning-actions">
        <button v-if="!['CANCELLED', 'SUCCEEDED'].includes(batch.status)" type="button" class="button secondary" :disabled="!canAdvance" @click="act('run-next')"><Play :size="16" />{{ busy === 'run-next' ? '正在生成当前块…' : '生成下一块' }}</button>
        <button v-if="['CANCELLED', 'FAILED'].includes(batch.status)" type="button" class="button secondary" :disabled="!canAct" @click="act('resume')"><RotateCcw :size="16" />恢复批次</button>
        <button v-if="!['CANCELLED', 'SUCCEEDED'].includes(batch.status)" type="button" class="button secondary" :disabled="!canCancel" @click="cancel"><Pause :size="16" />{{ cancelling ? '正在取消…' : '取消批次' }}</button>
        <button v-if="batch.status !== 'SUCCEEDED'" type="button" class="button primary" :disabled="!canAssemble" @click="act('assemble')"><Check :size="16" />{{ busy === 'assemble' ? '正在拼装…' : '拼装为大纲草稿' }}</button>
        <button v-else-if="batch.outlineVersionId" type="button" class="button secondary" :disabled="!!busy || loading || cancelling" @click="act('open')"><Check :size="16" />查看大纲草稿</button>
      </div>
    </div>
    <p v-else-if="!loading && !readError" class="planning-notice">尚无规划批次。</p>
    <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
  </section>
</template>

<style scoped>
.planning-checkpoint-panel { min-width: 0; margin: 24px 0; padding: 20px 0; border-block: 1px solid #dce3e1; color: #293b35; }
.planning-heading, .planning-progress-heading, .planning-chunk-heading, .planning-create-actions, .planning-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.planning-heading, .planning-progress-heading { justify-content: space-between; }
h3 { display: flex; align-items: center; gap: 8px; margin: 4px 0 0; font-size: 18px; }
h4 { margin: 10px 0; font-size: 14px; }
.planning-create { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-block: 16px; }
label { display: flex; flex-direction: column; min-width: 0; gap: 6px; font-size: 13px; }
input, textarea, select { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; padding: 9px; border: 1px solid #ccd6d1; border-radius: 6px; background: #fff; color: #293b35; font: inherit; }
textarea { resize: vertical; }
.planning-instruction, .planning-create-actions { grid-column: 1 / -1; }
.planning-create-actions { justify-content: space-between; }
.planning-selection { margin-block: 18px; }
.planning-progress { min-width: 0; }
progress { display: block; width: 100%; height: 8px; margin-top: 12px; accent-color: #297a60; }
.planning-basis, .planning-notice, small { font-size: 13px; color: #52635d; line-height: 1.6; }
.planning-basis, .planning-notice, .form-error, details, .planning-chunk-heading { overflow-wrap: anywhere; }
.planning-chunks { list-style: none; padding: 0; margin: 16px 0; }
.planning-chunk { border-top: 1px solid #e0e6e3; padding: 12px 0; }
.planning-chunk-heading span { font-size: 13px; }
.planning-chunk-heading small { margin-left: auto; }
.succeeded .planning-chunk-heading span { color: #247356; }
.failed .planning-chunk-heading span, .form-error { color: #a33141; }
.planning-chunk details { margin-top: 8px; font-size: 13px; line-height: 1.6; }
summary { cursor: pointer; }
.button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; max-width: 100%; min-height: 36px; padding: 8px 12px; border: 1px solid #ccd6d1; border-radius: 6px; font: inherit; font-size: 13px; white-space: normal; cursor: pointer; }
.secondary { background: #fff; color: #293b35; }
.primary { background: #297a60; border-color: #297a60; color: #fff; }
.button:disabled { opacity: .5; cursor: default; }
svg { flex-shrink: 0; }
@media (max-width: 480px) { .planning-create { grid-template-columns: minmax(0, 1fr); } .planning-actions { align-items: stretch; } .planning-actions .button { flex: 1 1 140px; } .planning-chunk-heading small { margin-left: 0; } }
</style>

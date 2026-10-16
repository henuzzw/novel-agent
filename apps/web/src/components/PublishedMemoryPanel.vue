<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { History, Pencil, Plus, RotateCcw, Save, Trash2, X } from 'lucide-vue-next'
import { savePublishedMemory, getPublishedMemory, getPublishedMemoryHistory, listCanonEntities, retryPublishedMemory,
  type FactProposal, type TypedFactPayload, type PublishedMemory } from '@/api/writing'
import { createUuid } from '@/lib/uuid'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

const props = defineProps<{ projectId: string; chapter: number; published: boolean; compact: boolean }>()
const emit = defineEmits<{ open: [] }>()
const client = useQueryClient()
const draft = ref<FactProposal[]>([])
const error = ref('')
const editing = ref(false)
const showHistory = ref(false)
const base = ref<PublishedMemory | null>(null)
const query = useQuery({
  queryKey: computed(() => ['published-memory', props.projectId, props.chapter]),
  queryFn: () => getPublishedMemory(props.projectId, props.chapter),
  enabled: computed(() => props.published),
  refetchInterval: q => ['PENDING', 'RUNNING'].includes(q.state.data?.status ?? '') ? 2000 : false,
})
const entities = useQuery({ queryKey: computed(() => ['canon-entities', props.projectId]),
  queryFn: () => listCanonEntities(props.projectId), enabled: computed(() => !props.compact && props.published),
})
const histories = useQuery({ queryKey: computed(() => ['published-memory-history', props.projectId, props.chapter, query.data.value?.commitId, query.data.value?.version]),
  queryFn: () => getPublishedMemoryHistory(props.projectId, props.chapter, query.data.value!),
  enabled: computed(() => showHistory.value && !!query.data.value?.commitId), retry: false })
const busy = computed(() => ['PENDING', 'RUNNING'].includes(query.data.value?.status ?? '') || save.isPending.value || retry.isPending.value)
function beginEdit() {
  if (!query.data.value || busy.value) return
  base.value = JSON.parse(JSON.stringify(query.data.value))
  draft.value = JSON.parse(JSON.stringify(query.data.value.candidates)).map((fact: FactProposal) => ({ ...fact,
    decision: fact.decision === 'PENDING' ? 'ACCEPTED' : fact.decision }))
  editing.value = true
  error.value = ''
}
function cancelEdit() { editing.value = false; base.value = null; draft.value = JSON.parse(JSON.stringify(query.data.value?.candidates ?? [])); error.value = '' }
watch(() => `${query.data.value?.commitId}:${query.data.value?.version}`, () => {
  if (!editing.value) { draft.value = JSON.parse(JSON.stringify(query.data.value?.candidates ?? [])); error.value = '' }
}, { immediate: true })
watch(() => `${props.projectId}:${props.chapter}`, () => { editing.value = false; base.value = null; showHistory.value = false })
const dirty = computed(() => editing.value && JSON.stringify(draft.value) !== JSON.stringify(base.value?.candidates ?? []))
useUnsavedChanges(dirty, ['section', 'chapter'])
const labels = { NOT_PUBLISHED: '发布后自动整理记忆', LEGACY: '历史正文已发布', PENDING: '记忆整理排队中',
  RUNNING: '正在后台提取并保存记忆', SUCCEEDED: '记忆已落库', NEEDS_CONFIRMATION: '历史记忆尚未全部落库',
  FAILED: '记忆整理未完成', SUPERSEDED: '已改用新版正文', SKIPPED: '已关闭自动整理记忆' }
const label = computed(() => query.data.value ? labels[query.data.value.status] : '正在读取记忆状态…')
function setResult(value: Awaited<ReturnType<typeof getPublishedMemory>>) {
  client.setQueryData(['published-memory', props.projectId, props.chapter], value)
  client.invalidateQueries({ queryKey: ['novel-memory', props.projectId] })
  client.invalidateQueries({ queryKey: ['canon-entities', props.projectId] })
  client.invalidateQueries({ queryKey: ['project', props.projectId] })
  client.invalidateQueries({ queryKey: ['published-memory-history', props.projectId, props.chapter] })
}
const retry = useMutation({ mutationFn: () => {
  if (!query.data.value) throw new Error('请先读取记忆状态。')
  return retryPublishedMemory(props.projectId, props.chapter, query.data.value)
}, onSuccess: setResult, onError: (reason: Error) => { error.value = reason.message } })
const save = useMutation({ mutationFn: () => {
  if (!base.value) throw new Error('请先读取记忆状态。')
  const candidates = draft.value.map(fact => {
    const old = base.value!.candidates.find(item => item.id === fact.id)
    const payload = fact.payload ? { ...fact.payload } : null
    if (payload && old?.object !== fact.object) {
      if (fact.factType === 'EVENT_CREATE') payload.eventSummary = fact.object
      if (fact.factType === 'STATE_CHANGE') payload.afterValue = fact.object
      if (fact.factType === 'KNOWLEDGE_CHANGE') payload.statement = fact.object
      if (fact.factType === 'FORESHADOW_CHANGE') payload.targetEffect = fact.object
    }
    if (payload && old?.predicate !== fact.predicate && fact.factType === 'RELATION_CHANGE') payload.relationType = fact.predicate
    return { ...fact, payload }
  })
  return savePublishedMemory(props.projectId, props.chapter, base.value, candidates)
}, onSuccess: value => { editing.value = false; base.value = null; setResult(value) }, onError: (reason: Error) => { error.value = reason.message } })
const factTypes = { ENTITY_UPSERT: '实体', STATE_CHANGE: '状态', EVENT_CREATE: '事件', RELATION_CHANGE: '关系', KNOWLEDGE_CHANGE: '认知', FORESHADOW_CHANGE: '伏笔' }
function addFact() {
  if (!editing.value) beginEdit()
  if (!editing.value) return
  draft.value.push({ id: `manual-${createUuid()}`, factType: 'EVENT_CREATE', subject: '', predicate: '', object: '', evidence: '', confidence: null, payload: {}, decision: 'ACCEPTED' })
}
function removeFact(fact: FactProposal) {
  if (!base.value?.candidates.some(item => item.id === fact.id)) draft.value = draft.value.filter(item => item.id !== fact.id)
  else fact.decision = 'REJECTED'
}
type IdField = 'entityId' | 'sourceEntityId' | 'targetEntityId' | 'characterId'
function references(fact: FactProposal): { field: IdField; name: string; type: string }[] {
  const p = fact.payload
  if (!p || ['EVENT_CREATE', 'FORESHADOW_CHANGE'].includes(fact.factType)) return []
  if (fact.factType === 'RELATION_CHANGE') return [
    { field: 'sourceEntityId', name: p.sourceEntityName || fact.subject, type: 'CHARACTER' },
    { field: 'targetEntityId', name: p.targetEntityName || fact.object, type: 'CHARACTER' },
  ]
  if (fact.factType === 'KNOWLEDGE_CHANGE') return [{ field: 'characterId', name: p.characterName || fact.subject, type: 'CHARACTER' }]
  return [{ field: 'entityId', name: p.entityName || fact.subject, type: p.stateEntityType || p.entityType || 'CHARACTER' }]
}
function setReference(payload: TypedFactPayload, field: IdField, event: Event) {
  payload[field] = (event.target as HTMLSelectElement).value || null
}
function setEntityType(payload: TypedFactPayload, factType: string, event: Event) {
  payload.entityType = (event.target as HTMLSelectElement).value
  if (factType === 'STATE_CHANGE') payload.stateEntityType = payload.entityType
  payload.entityId = null
  payload.sourceEntityId = null
  payload.characterId = null
}
</script>

<template>
  <section v-if="published || !compact" class="published-memory-panel" aria-label="发布后记忆整理">
    <p v-if="!published" class="quiet-text">正文发布后，会自动在后台整理记忆。</p>
    <template v-else>
      <p :class="query.data.value?.status === 'SUCCEEDED' ? 'acceptance-note' : 'quiet-text'" role="status">{{ label }}<template v-if="['PENDING', 'RUNNING', 'FAILED', 'NEEDS_CONFIRMATION'].includes(query.data.value?.status ?? '')">；下一章生成须等待前文记忆落库成功。</template></p>
      <p v-if="query.isError.value" class="form-error">记忆状态读取失败。<button type="button" class="button secondary compact" @click="query.refetch()">重新读取</button></p>
      <p v-if="query.data.value?.error" class="quiet-text">{{ query.data.value.error }}</p>
      <button v-if="['FAILED', 'SKIPPED', 'NEEDS_CONFIRMATION'].includes(query.data.value?.status ?? '')" type="button" class="button secondary compact" :disabled="busy || editing" @click="retry.mutate()">{{ retry.isPending.value ? '正在提交…' : query.data.value?.status === 'SKIPPED' ? '开始整理记忆' : '重新整理记忆' }}</button>
      <button v-if="compact && published" type="button" class="button secondary compact" @click="emit('open')"><Pencil :size="15" />管理记忆</button>
      <template v-if="!compact">
        <div class="memory-toolbar">
          <p class="quiet-text">已保存 {{ query.data.value?.candidates.filter(f => f.decision === 'ACCEPTED').length ?? 0 }} 项<template v-if="query.data.value?.candidates.some(f => f.decision === 'PENDING')"> · 历史待处理 {{ query.data.value?.candidates.filter(f => f.decision === 'PENDING').length }} 项</template></p>
          <div class="memory-tools">
            <button v-if="!editing" class="icon-button" type="button" title="编辑记忆" aria-label="编辑记忆" :disabled="busy || !query.data.value?.commitId" @click="beginEdit"><Pencil :size="17" /></button>
            <button class="icon-button" type="button" title="新增记忆" aria-label="新增记忆" :disabled="busy || !query.data.value?.commitId || draft.length >= 100" @click="addFact"><Plus :size="17" /></button>
            <button class="icon-button" type="button" title="记忆修改记录" aria-label="记忆修改记录" :aria-expanded="showHistory" @click="showHistory = !showHistory"><History :size="17" /></button>
            <template v-if="editing"><button class="button primary compact" type="button" :disabled="busy || !dirty" @click="save.mutate()"><Save :size="15" />{{ save.isPending.value ? '正在保存…' : '保存记忆' }}</button><button class="icon-button" type="button" title="取消编辑" aria-label="取消编辑" :disabled="busy" @click="cancelEdit"><X :size="17" /></button></template>
          </div>
        </div>
        <div v-if="showHistory" class="memory-history">
          <p v-if="histories.isPending.value">正在读取修改记录…</p>
          <p v-else-if="histories.isError.value" class="form-error">修改记录读取失败。</p>
          <p v-else-if="!histories.data.value?.length" class="quiet-text">暂无修改记录。</p>
          <details v-for="item in histories.data.value ?? []" :key="item.id"><summary>保存版本 {{ item.version }} · {{ item.action === 'EDIT' ? '人工修改前' : '重新整理前' }} · {{ new Date(item.createdAt).toLocaleString() }}</summary><p v-for="fact in item.candidates" :key="fact.id">{{ fact.subject }} · {{ fact.predicate }} · {{ fact.object }}</p></details>
        </div>
        <article v-for="fact in draft" :key="fact.id" class="memory-item">
          <template v-if="editing && fact.decision !== 'REJECTED'">
            <div class="memory-item-heading"><select v-model="fact.factType" aria-label="记忆类别" :disabled="busy"><option v-for="(name, type) in factTypes" :key="type" :value="type">{{ name }}</option></select><button class="icon-button" type="button" title="移除记忆" aria-label="移除记忆" :disabled="busy" @click="removeFact(fact)"><Trash2 :size="16" /></button></div>
            <div class="memory-edit-fields"><label>主体<input v-model="fact.subject" :disabled="busy" /></label><label>变化或事实<input v-model="fact.predicate" :disabled="busy" /></label><label>内容<textarea v-model="fact.object" rows="2" :disabled="busy" /></label><label>本章原文证据<textarea v-model="fact.evidence" rows="3" :disabled="busy" /></label></div>
          </template>
          <template v-else>
          <strong>{{ fact.subject }} · {{ fact.predicate }}</strong><p>{{ fact.object }}</p>
          <blockquote>{{ fact.evidence }}</blockquote>
          </template>
          <template v-if="editing && fact.payload && fact.decision !== 'REJECTED'">
            <label v-if="!['RELATION_CHANGE', 'KNOWLEDGE_CHANGE', 'EVENT_CREATE', 'FORESHADOW_CHANGE'].includes(fact.factType)" class="memory-entity-field"><span>实体类型</span><select :value="fact.payload.stateEntityType || fact.payload.entityType || 'CHARACTER'" :disabled="busy" @change="setEntityType(fact.payload, fact.factType, $event)"><option v-for="type in ['CHARACTER','ITEM','LOCATION','ORGANIZATION','SECRET','RULE']" :key="type" :value="type">{{ type }}</option></select></label>
            <label v-if="fact.factType === 'STATE_CHANGE'" class="memory-entity-field"><span>状态字段</span><input v-model="fact.payload.fieldKey" :disabled="busy" /></label>
            <label v-for="reference in references(fact)" :key="reference.field" class="memory-entity-field">
              <span>“{{ reference.name }}”对应实体</span>
              <select :value="fact.payload[reference.field] || ''" :disabled="busy" @change="setReference(fact.payload, reference.field, $event)">
                <option value="">按名称匹配或创建</option>
                <option v-for="entity in (entities.data.value ?? []).filter(e => e.type === reference.type)" :key="entity.id" :value="entity.id">{{ entity.name }}</option>
              </select>
            </label>
          </template>
          <small>{{ fact.decision === 'ACCEPTED' ? '已保存' : fact.decision === 'REJECTED' ? '已移除' : '历史待处理' }}</small>
          <button v-if="editing && fact.decision === 'REJECTED'" class="icon-button" type="button" title="恢复记忆" aria-label="恢复记忆" :disabled="busy" @click="fact.decision = 'ACCEPTED'"><RotateCcw :size="16" /></button>
        </article>
      </template>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </template>
  </section>
</template>

<style scoped>
.published-memory-panel,
.memory-item > * {
  min-width: 0;
}

.memory-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  padding: 16px 12px;
  border-top: 1px solid #d7dde3;
  overflow-wrap: anywhere;
}
.memory-toolbar, .memory-tools, .memory-item-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.memory-item-heading select { max-width: 160px; }
.memory-edit-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.memory-edit-fields label { display: grid; gap: 5px; min-width: 0; font-size: 13px; }
.memory-edit-fields label:nth-child(n+3) { grid-column: 1 / -1; }
.memory-edit-fields input, .memory-edit-fields textarea { width: 100%; min-width: 0; border: 1px solid #bfc8d1; border-radius: 4px; padding: 8px; font: inherit; }
.memory-history { padding: 10px 0; border-top: 1px solid #d7dde3; overflow-wrap: anywhere; }
.memory-history details { padding: 8px 0; }

.memory-item p,
.memory-item blockquote {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.65;
  color: #37424c;
}

.memory-item blockquote {
  padding: 8px 12px;
  border-left: 3px solid #aebbb8;
  background: #f5f7f7;
}

.memory-item small {
  color: #66717d;
  font-size: 12px;
}

.memory-entity-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(160px, 280px);
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.memory-item select {
  width: 100%;
  min-width: 0;
  min-height: 38px;
  padding: 6px 8px;
  border: 1px solid #bfc8d1;
  border-radius: 6px;
  background: #fff;
}

.memory-item > select {
  max-width: 200px;
}

@media (max-width: 600px) {
  .memory-edit-fields { grid-template-columns: minmax(0, 1fr); }
  .memory-item {
    padding: 12px 0;
  }

  .memory-entity-field {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

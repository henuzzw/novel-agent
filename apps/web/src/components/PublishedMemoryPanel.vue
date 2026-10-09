<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { confirmPublishedMemory, getPublishedMemory, listCanonEntities, retryPublishedMemory,
  type FactProposal, type TypedFactPayload } from '@/api/writing'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

const props = defineProps<{ projectId: string; chapter: number; published: boolean; compact: boolean }>()
const emit = defineEmits<{ open: [] }>()
const client = useQueryClient()
const draft = ref<FactProposal[]>([])
const error = ref('')
const query = useQuery({
  queryKey: computed(() => ['published-memory', props.projectId, props.chapter]),
  queryFn: () => getPublishedMemory(props.projectId, props.chapter),
  enabled: computed(() => props.published),
  refetchInterval: q => ['PENDING', 'RUNNING'].includes(q.state.data?.status ?? '') ? 2000 : false,
})
const entities = useQuery({ queryKey: computed(() => ['canon-entities', props.projectId]),
  queryFn: () => listCanonEntities(props.projectId), enabled: computed(() => !props.compact && props.published),
})
watch(() => `${query.data.value?.commitId}:${query.data.value?.version}`, () => {
  draft.value = JSON.parse(JSON.stringify(query.data.value?.candidates ?? []))
  error.value = ''
}, { immediate: true })
const dirty = computed(() => JSON.stringify(draft.value) !== JSON.stringify(query.data.value?.candidates ?? []))
useUnsavedChanges(dirty, ['section', 'chapter'])
const labels = { NOT_PUBLISHED: '发布后自动整理记忆', LEGACY: '历史正文已发布', PENDING: '记忆整理排队中',
  RUNNING: '正在后台整理记忆', SUCCEEDED: '记忆整理已完成', NEEDS_CONFIRMATION: '部分记忆待核对',
  FAILED: '记忆整理未完成', SUPERSEDED: '已改用新版正文' }
const label = computed(() => query.data.value ? labels[query.data.value.status] : '正在读取记忆状态…')
function setResult(value: Awaited<ReturnType<typeof getPublishedMemory>>) {
  client.setQueryData(['published-memory', props.projectId, props.chapter], value)
  client.invalidateQueries({ queryKey: ['novel-memory', props.projectId] })
  client.invalidateQueries({ queryKey: ['canon-entities', props.projectId] })
}
const retry = useMutation({ mutationFn: () => {
  if (!query.data.value) throw new Error('请先读取记忆状态。')
  return retryPublishedMemory(props.projectId, props.chapter, query.data.value)
}, onSuccess: setResult, onError: (reason: Error) => { error.value = reason.message } })
const confirm = useMutation({ mutationFn: () => {
  if (!query.data.value) throw new Error('请先读取记忆状态。')
  return confirmPublishedMemory(props.projectId, props.chapter, query.data.value, draft.value)
}, onSuccess: setResult, onError: (reason: Error) => { error.value = reason.message } })
type IdField = 'entityId' | 'sourceEntityId' | 'targetEntityId' | 'characterId'
function references(fact: FactProposal): { field: IdField; name: string; type: string }[] {
  const p = fact.payload
  if (!p) return []
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
</script>

<template>
  <section v-if="published || !compact" class="published-memory-panel" aria-label="发布后记忆整理">
    <p v-if="!published" class="quiet-text">正文发布后，会自动在后台整理记忆。</p>
    <template v-else>
      <p class="acceptance-note">{{ label }}。正文已发布，可继续创作下一章。</p>
      <p v-if="query.isError.value" class="form-error">记忆状态读取失败。<button type="button" class="button secondary compact" @click="query.refetch()">重新读取</button></p>
      <p v-if="query.data.value?.error" class="quiet-text">{{ query.data.value.error }}</p>
      <button v-if="query.data.value?.status === 'FAILED'" type="button" class="button secondary compact" :disabled="retry.isPending.value" @click="retry.mutate()">{{ retry.isPending.value ? '正在重试…' : '重试记忆整理' }}</button>
      <button v-if="compact && query.data.value?.status === 'NEEDS_CONFIRMATION'" type="button" class="button secondary compact" @click="emit('open')">核对记忆条目</button>
      <template v-if="!compact">
        <p class="quiet-text">只整理已发布版本。以下条目用于检索，有歧义时核对实体与证据，不影响正文发布。</p>
        <article v-for="fact in draft" :key="fact.id" class="fact-item">
          <strong>{{ fact.subject }} · {{ fact.predicate }}</strong><p>{{ fact.object }}</p>
          <blockquote>{{ fact.evidence }}</blockquote>
          <template v-if="fact.payload && query.data.value?.candidates.find(f => f.id === fact.id)?.decision === 'PENDING'">
            <label v-for="reference in references(fact)" :key="reference.field" class="entity-resolution-row">
              <span>“{{ reference.name }}”对应实体</span>
              <select :value="fact.payload[reference.field] || ''" :disabled="confirm.isPending.value" @change="setReference(fact.payload, reference.field, $event)">
                <option value="">按原名匹配；新实体需核对</option>
                <option v-for="entity in (entities.data.value ?? []).filter(e => e.type === reference.type)" :key="entity.id" :value="entity.id">{{ entity.name }}</option>
              </select>
            </label>
          </template>
          <select v-model="fact.decision" :disabled="query.data.value?.candidates.find(f => f.id === fact.id)?.decision !== 'PENDING' || confirm.isPending.value">
            <option value="PENDING">待核对</option><option value="ACCEPTED">接受</option><option value="REJECTED">不写入记忆</option>
          </select>
        </article>
        <button v-if="query.data.value?.status === 'NEEDS_CONFIRMATION'" type="button" class="button primary" :disabled="!dirty || confirm.isPending.value" @click="confirm.mutate()">{{ confirm.isPending.value ? '正在保存…' : '保存记忆核对' }}</button>
      </template>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </template>
  </section>
</template>

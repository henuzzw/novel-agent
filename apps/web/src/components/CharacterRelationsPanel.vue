<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ArrowRight } from 'lucide-vue-next'
import { listRelationships, listCharacterKnowledge } from '@/api/writing'
import { listPlanningRelationships } from '@/api/planningMaterials'
const props = withDefaults(defineProps<{ projectId: string; characterId: string; view?: 'relationships' | 'knowledge' | 'all' }>(), { view: 'all' })
const planned = useQuery({ queryKey: computed(() => ['planning-relationships', props.projectId, props.characterId]),
  queryFn: () => listPlanningRelationships(props.projectId, props.characterId), enabled: computed(() => props.view !== 'knowledge') })
const relations = useQuery({ queryKey: computed(() => ['canon-relationships', props.projectId, props.characterId]),
  queryFn: () => listRelationships(props.projectId, props.characterId), enabled: computed(() => props.view !== 'knowledge') })
const knowledge = useQuery({ queryKey: computed(() => ['canon-knowledge', props.projectId, props.characterId]),
  queryFn: () => listCharacterKnowledge(props.projectId, props.characterId), enabled: computed(() => props.view !== 'relationships') })
function relationLabel(value: string) {
  return ({ FRIEND: '朋友', FAMILY: '家人', COLLEAGUE: '同事', LOVES: '爱慕', TRUSTS: '信任', DISTRUSTS: '不信任', OPPOSES: '对立', KNOWS: '认识' } as Record<string, string>)[value] ?? value
}
function knowledgeLabel(value: string) {
  return ({ KNOWS: '确认知道', SUSPECTS: '有所怀疑', BELIEVES: '相信', HEARD: '听说', WITNESSED: '亲眼所见' } as Record<string, string>)[value] ?? value
}
function truthLabel(value: string) {
  return ({ TRUE: '符合事实', FALSE: '错误信念', UNKNOWN: '真假未知', PARTIAL: '部分正确' } as Record<string, string>)[value] ?? value
}
function attributes(value: unknown) {
  if (!value || (typeof value === 'object' && !Object.keys(value as object).length)) return ''
  return typeof value === 'string' ? value : Object.entries(value as Record<string, unknown>).map(([key, item]) => `${key}：${String(item)}`).join('；')
}
</script>
<template>
  <section aria-label="人物关系与认知">
    <template v-if="view !== 'knowledge'">
      <h3>规划关系 · 未作为正文事实确认</h3>
      <p v-if="planned.isPending.value" role="status">正在读取规划关系…</p>
      <p v-else-if="planned.isError.value" class="form-error" role="alert">规划关系读取失败：{{ planned.error.value?.message }}</p>
      <p v-else-if="!planned.data.value?.length" class="quiet-text">暂无已确认的关系设定。</p>
      <div v-else class="relationship-list"><article v-for="item in planned.data.value" :key="item.id"><p>{{ item.description }}</p><small>{{ item.characterId ? '人物关系设定' : '全局关系设定' }} · 来源：已确认规划资料</small></article></div>
      <h3>正文正史关系</h3>
      <p v-if="relations.isPending.value" role="status">正在读取人物关系…</p>
      <p v-else-if="relations.isError.value" class="form-error" role="alert">正文关系读取失败：{{ relations.error.value?.message }}</p>
      <p v-else-if="!relations.data.value?.length" class="quiet-text">暂无已确认的人物关系。</p>
      <div v-else class="relationship-list"><article v-for="item in relations.data.value" :key="item.id">
        <div class="relationship-line"><strong>{{ item.sourceEntityName }}</strong><span>{{ relationLabel(item.relationType) }}<ArrowRight :size="15" /></span><strong>{{ item.targetEntityName }}</strong></div>
        <p v-if="attributes(item.attributes)">{{ attributes(item.attributes) }}</p>
        <small>第 {{ item.chapterNumber }} 章 · 正史 V{{ item.canonVersionFrom }}<template v-if="item.evidence"> · 依据：{{ item.evidence }}</template></small>
      </article></div>
    </template>
    <template v-if="view !== 'relationships'">
      <h3>正文正史知识边界</h3>
      <p v-if="knowledge.isPending.value" role="status">正在读取知识边界…</p>
      <p v-else-if="knowledge.isError.value" class="form-error" role="alert">知识边界读取失败：{{ knowledge.error.value?.message }}</p>
      <p v-else-if="!knowledge.data.value?.length" class="quiet-text">暂无已确认的认知记录。</p>
      <div v-else class="knowledge-table"><article v-for="item in knowledge.data.value" :key="item.id">
        <header><span>{{ knowledgeLabel(item.knowledgeType) }}</span><strong :class="['belief-truth', item.beliefTruth.toLowerCase()]">{{ truthLabel(item.beliefTruth) }}</strong></header>
        <p><b>{{ item.subject }}</b> {{ item.predicate }} {{ item.object }}</p>
        <small>第 {{ item.chapterNumber }} 章 · 正史 V{{ item.canonVersionFrom }}<template v-if="item.confidence != null"> · 置信度 {{ Math.round(item.confidence * 100) }}%</template></small><blockquote v-if="item.evidence">{{ item.evidence }}</blockquote>
      </article></div>
    </template>
  </section>
</template>
<style scoped>
section { min-width: 0; overflow-wrap: anywhere; }
.relationship-line { flex-wrap: wrap; }
</style>

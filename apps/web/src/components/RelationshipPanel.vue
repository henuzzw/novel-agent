<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ArrowRight, Brain, Network, Search } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import { listCanonEntities, listCharacterKnowledge, listRelationships } from '@/api/writing'

const props = defineProps<{ projectId: string }>()
const view = ref<'relationships' | 'knowledge'>('relationships')
const search = ref('')
const selectedCharacterId = ref<string | null>(null)

const entitiesQuery = useQuery({
  queryKey: computed(() => ['canon-entities', props.projectId]),
  queryFn: () => listCanonEntities(props.projectId),
})
const characters = computed(() => (entitiesQuery.data.value ?? [])
  .filter((entity) => entity.type === 'CHARACTER')
  .filter((entity) => entity.name.toLowerCase().includes(search.value.trim().toLowerCase())))
watch(characters, (items) => {
  if (!items.some((item) => item.id === selectedCharacterId.value)) selectedCharacterId.value = items[0]?.id ?? null
}, { immediate: true })

const relationshipsQuery = useQuery({
  queryKey: computed(() => ['canon-relationships', props.projectId, selectedCharacterId.value]),
  queryFn: () => listRelationships(props.projectId, selectedCharacterId.value!),
  enabled: computed(() => Boolean(selectedCharacterId.value) && view.value === 'relationships'),
})
const knowledgeQuery = useQuery({
  queryKey: computed(() => ['canon-knowledge', props.projectId, selectedCharacterId.value]),
  queryFn: () => listCharacterKnowledge(props.projectId, selectedCharacterId.value!),
  enabled: computed(() => Boolean(selectedCharacterId.value) && view.value === 'knowledge'),
})
const selectedCharacter = computed(() => characters.value.find((item) => item.id === selectedCharacterId.value) ?? null)

function relationLabel(value: string) {
  return ({ FRIEND: '朋友', FAMILY: '家人', COLLEAGUE: '同事', LOVES: '爱慕', TRUSTS: '信任', DISTRUSTS: '不信任', OPPOSES: '对立', KNOWS: '认识' } as Record<string, string>)[value] ?? value
}
function knowledgeTypeLabel(value: string) {
  return ({ KNOWS: '确认知道', SUSPECTS: '有所怀疑', BELIEVES: '相信', HEARD: '听说', WITNESSED: '亲眼所见' } as Record<string, string>)[value] ?? value
}
function truthLabel(value: string) {
  return ({ TRUE: '符合事实', FALSE: '错误信念', UNKNOWN: '真假未知', PARTIAL: '部分正确' } as Record<string, string>)[value] ?? value
}
function attributesText(value: unknown) {
  if (!value || (typeof value === 'object' && !Object.keys(value as object).length)) return ''
  if (typeof value === 'string') return value
  return Object.entries(value as Record<string, unknown>).map(([key, item]) => `${key}：${String(item)}`).join('；')
}
</script>

<template>
  <div class="relationship-workbench">
    <div class="planning-tabs" role="tablist" aria-label="关系与认知分类">
      <button type="button" :class="{ active: view === 'relationships' }" @click="view = 'relationships'"><Network :size="16" />人物关系</button>
      <button type="button" :class="{ active: view === 'knowledge' }" @click="view = 'knowledge'"><Brain :size="16" />知识边界</button>
    </div>

    <div class="relationship-layout">
      <aside class="character-picker">
        <label><Search :size="15" /><input v-model="search" placeholder="搜索人物" aria-label="搜索人物" /></label>
        <div v-if="entitiesQuery.isPending.value" class="quiet-text">正在读取人物…</div>
        <button v-for="character in characters" :key="character.id" type="button" :class="{ active: selectedCharacterId === character.id }" @click="selectedCharacterId = character.id">
          <span>{{ character.name }}</span><small>正史 V{{ character.canonVersionFrom }}</small>
        </button>
        <p v-if="!entitiesQuery.isPending.value && !characters.length" class="quiet-text">当前正史中还没有人物资料。</p>
      </aside>

      <main class="relationship-content">
        <template v-if="selectedCharacter">
          <div class="section-heading"><div><span class="eyebrow">{{ view === 'relationships' ? '关系视角' : '认知视角' }}</span><h2>{{ selectedCharacter.name }}</h2></div></div>

          <template v-if="view === 'relationships'">
            <div v-if="relationshipsQuery.isPending.value" class="editor-empty">正在读取人物关系…</div>
            <div v-else-if="!relationshipsQuery.data.value?.length" class="editor-empty">暂无已确认的人物关系。</div>
            <div v-else class="relationship-list">
              <article v-for="item in relationshipsQuery.data.value" :key="item.id">
                <div class="relationship-line"><strong>{{ item.sourceEntityName }}</strong><span>{{ relationLabel(item.relationType) }}<ArrowRight :size="15" /></span><strong>{{ item.targetEntityName }}</strong></div>
                <p v-if="attributesText(item.attributes)">{{ attributesText(item.attributes) }}</p>
                <small>第 {{ item.chapterNumber }} 章 · 正史 V{{ item.canonVersionFrom }}<template v-if="item.evidence"> · 依据：{{ item.evidence }}</template></small>
              </article>
            </div>
          </template>

          <template v-else>
            <div v-if="knowledgeQuery.isPending.value" class="editor-empty">正在读取知识边界…</div>
            <div v-else-if="!knowledgeQuery.data.value?.length" class="editor-empty">暂无已确认的认知记录。</div>
            <div v-else class="knowledge-table">
              <article v-for="item in knowledgeQuery.data.value" :key="item.id">
                <header><span>{{ knowledgeTypeLabel(item.knowledgeType) }}</span><strong :class="['belief-truth', item.beliefTruth.toLowerCase()]">{{ truthLabel(item.beliefTruth) }}</strong></header>
                <p><b>{{ item.subject }}</b> {{ item.predicate }} {{ item.object }}</p>
                <small>第 {{ item.chapterNumber }} 章 · 正史 V{{ item.canonVersionFrom }}<template v-if="item.confidence != null"> · 置信度 {{ Math.round(item.confidence * 100) }}%</template></small>
                <blockquote v-if="item.evidence">{{ item.evidence }}</blockquote>
              </article>
            </div>
          </template>
        </template>
        <div v-else class="editor-empty">提交包含人物事实的正史后，可从这里查看关系与知识边界。</div>
      </main>
    </div>
  </div>
</template>

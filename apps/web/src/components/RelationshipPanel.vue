<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { Brain, Network, Search } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import { listCanonEntities } from '@/api/writing'
import CharacterRelationsPanel from './CharacterRelationsPanel.vue'
import PlanningMaterialSyncButton from './PlanningMaterialSyncButton.vue'

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

const selectedCharacter = computed(() => characters.value.find((item) => item.id === selectedCharacterId.value) ?? null)
</script>

<template>
  <div class="relationship-workbench">
    <PlanningMaterialSyncButton :project-id="projectId" />
    <div class="planning-tabs" role="tablist" aria-label="关系与认知分类">
      <button type="button" :class="{ active: view === 'relationships' }" @click="view = 'relationships'"><Network :size="16" />人物关系</button>
      <button type="button" :class="{ active: view === 'knowledge' }" @click="view = 'knowledge'"><Brain :size="16" />知识边界</button>
    </div>

    <div class="relationship-layout">
      <aside class="character-picker">
        <label><Search :size="15" /><input v-model="search" placeholder="搜索人物" aria-label="搜索人物" /></label>
        <div v-if="entitiesQuery.isPending.value" class="quiet-text">正在读取人物…</div>
        <button v-for="character in characters" :key="character.id" type="button" :class="{ active: selectedCharacterId === character.id }" @click="selectedCharacterId = character.id">
          <span>{{ character.name }}</span><small>{{ character.status === 'PLANNED' ? '规划登记' : `正史 V${character.canonVersionFrom}` }}</small>
        </button>
        <p v-if="!entitiesQuery.isPending.value && !characters.length" class="quiet-text">当前正史中还没有人物资料。</p>
      </aside>

      <main class="relationship-content">
        <template v-if="selectedCharacter">
          <div class="section-heading"><div><span class="eyebrow">{{ view === 'relationships' ? '关系视角' : '认知视角' }}</span><h2>{{ selectedCharacter.name }}</h2></div></div>

          <CharacterRelationsPanel :key="`${projectId}:${selectedCharacter.id}`" :project-id="projectId" :character-id="selectedCharacter.id" :view="view" />
        </template>
        <div v-else class="editor-empty">提交包含人物事实的正史后，可从这里查看关系与知识边界。</div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import GlobalModelBadge from '@/components/GlobalModelBadge.vue'
import CharacterBlueprintEditor from '@/components/CharacterBlueprintEditor.vue'
import ReaderExperienceSeedEditor from './ReaderExperienceSeedEditor.vue'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, RefreshCw, Save, Sparkles } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import {
  createStoryBibleRevision,
  completeStoryBibleCharacters,
  generateStoryBible,
  getLatestStoryBible,
  getStoryBibleVersion,
  listStoryBibleVersions,
  publishStoryBible,
  updateStoryBible,
  type GenerationMode,
  type CharacterBlueprint,
  type StoryBibleContent,
  type StoryBibleVersion,
} from '@/api/planning'

const props = defineProps<{ projectId: string }>()
const queryClient = useQueryClient()
const { provider: provider } = useGlobalModelSettings()
const generationMode = ref<GenerationMode>('REVISE')
const baseBibleVersionId = ref('')
const instruction = ref('')
const actionError = ref('')
const draft = ref<StoryBibleContent | null>(null)

function copyContent(value: StoryBibleContent): StoryBibleContent {
  return {
    ...value,
    worldRules: [...value.worldRules],
    supportingCharacters: [...value.supportingCharacters],
    relationshipDynamics: [...value.relationshipDynamics],
    hardConstraints: [...value.hardConstraints],
    openQuestions: [...value.openQuestions],
    readerExperiencePlans: (value.readerExperiencePlans ?? []).map(item => ({ ...item })),
    characterBlueprints: (value.characterBlueprints ?? []).map(character => ({
      ...character,
      initialRelationships: [...character.initialRelationships],
      initialPossessions: [...character.initialPossessions],
      knowledgeBoundaries: [...character.knowledgeBoundaries],
    })),
  }
}

const bibleQuery = useQuery({
  queryKey: computed(() => ['story-bible', props.projectId]),
  queryFn: () => getLatestStoryBible(props.projectId),
})
const versionsQuery = useQuery({
  queryKey: computed(() => ['story-bible-versions', props.projectId]),
  queryFn: () => listStoryBibleVersions(props.projectId),
  enabled: computed(() => !!bibleQuery.data.value),
})
const basePreviewQuery = useQuery({
  queryKey: computed(() => ['story-bible-version', props.projectId, baseBibleVersionId.value]),
  queryFn: () => getStoryBibleVersion(props.projectId, baseBibleVersionId.value),
  enabled: computed(() => !!baseBibleVersionId.value && generationMode.value === 'REVISE'),
})
const selectedBase = computed(() => versionsQuery.data.value?.find(
  (version) => version.id === baseBibleVersionId.value,
))
watch(() => props.projectId, () => {
  baseBibleVersionId.value = ''
  instruction.value = ''
  actionError.value = ''
})

watch(
  () => bibleQuery.data.value,
  (value) => {
    draft.value = value ? copyContent(value.content) : null
  },
  { immediate: true },
)

function updateCache(value: StoryBibleVersion) {
  if (value.projectId !== props.projectId) return
  queryClient.setQueryData(['story-bible', props.projectId], value)
  queryClient.invalidateQueries({ queryKey: ['story-bible-versions', props.projectId] })
  draft.value = copyContent(value.content)
}

function listText(values: string[]) {
  return values.join('\n')
}

function setList(field: keyof StoryBibleContent, value: string) {
  if (!draft.value) return
  ;(draft.value[field] as string[]) = value.split('\n').map((item) => item.trim()).filter(Boolean)
}

const generateMutation = useMutation({
  mutationFn: () => {
    if (generationMode.value === 'REVISE' && baseBibleVersionId.value &&
        basePreviewQuery.data.value?.id !== baseBibleVersionId.value) {
      throw new Error('请先加载并确认选定的历史故事圣经。')
    }
    return generateStoryBible(props.projectId, instruction.value, provider.value,
      bibleQuery.data.value ? generationMode.value : 'REGENERATE',
      generationMode.value === 'REVISE' ? baseBibleVersionId.value || null : null)
  },
  onSuccess: (value) => {
    if (value.projectId !== props.projectId) return
    updateCache(value)
    instruction.value = ''
    actionError.value = ''
  },
  onError: (error: Error) => { actionError.value = error.message },
})

const saveMutation = useMutation({
  mutationFn: () => {
    if (!bibleQuery.data.value || !draft.value) throw new Error('没有可保存的故事圣经。')
    return updateStoryBible(props.projectId, bibleQuery.data.value, draft.value)
  },
  onSuccess: (value) => { if (value.projectId === props.projectId) { updateCache(value); actionError.value = '' } },
  onError: (error: Error) => { actionError.value = error.message },
})

const createRevisionMutation = useMutation({
  mutationFn: () => {
    if (!bibleQuery.data.value || !draft.value) throw new Error('没有可修订的故事圣经。')
    return createStoryBibleRevision(props.projectId, bibleQuery.data.value, draft.value)
  },
  onSuccess: (value) => { if (value.projectId === props.projectId) { updateCache(value); actionError.value = '' } },
  onError: (error: Error) => { actionError.value = error.message },
})

const publishMutation = useMutation({
  mutationFn: () => {
    if (!bibleQuery.data.value) throw new Error('没有可发布的故事圣经。')
    if (hasUnsavedChanges.value) throw new Error('请先保存修改，再确认并发布。')
    return publishStoryBible(props.projectId, bibleQuery.data.value)
  },
  onSuccess: (value) => {
    if (value.projectId === props.projectId) {
      updateCache(value); actionError.value = ''
      queryClient.invalidateQueries({ predicate: query => query.queryKey.includes(props.projectId) })
    }
  },
  onError: (error: Error) => { actionError.value = error.message },
})

const completeCharactersMutation = useMutation({
  mutationFn: () => {
    if (!bibleQuery.data.value) throw new Error('没有可补充人物的故事圣经。')
    if (hasUnsavedChanges.value) throw new Error('请先保存修改，再补全人物底稿。')
    return completeStoryBibleCharacters(props.projectId, bibleQuery.data.value, provider.value, instruction.value)
  },
  onSuccess: value => {
    if (value.projectId !== props.projectId) return
    updateCache(value)
    actionError.value = ''
  },
  onError: (error: Error) => { actionError.value = error.message },
})

const busy = computed(() => generateMutation.isPending.value || saveMutation.isPending.value ||
  createRevisionMutation.isPending.value || publishMutation.isPending.value || completeCharactersMutation.isPending.value)
const draftCharacters = computed<CharacterBlueprint[]>({
  get: () => draft.value?.characterBlueprints ?? [],
  set: value => { if (draft.value) draft.value.characterBlueprints = value },
})
const hasUnsavedChanges = computed(() => !!draft.value && !!bibleQuery.data.value &&
  JSON.stringify(draft.value) !== JSON.stringify(copyContent(bibleQuery.data.value.content)))
const characterError = computed(() => {
  const characters = draft.value?.characterBlueprints ?? []
  if (characters.length > 12) return '人物底稿最多 12 人。'
  const names = characters.map(character => character.name.trim())
  if (new Set(names).size !== names.length) return '人物底稿姓名重复，请用身份区分同名人物。'
  for (const character of characters) {
    if (!character.name.trim() || !character.identity.trim() || !character.coreDesire.trim()) {
      return '人物底稿需填写姓名或称谓、身份与生活目标、核心欲望。'
    }
    for (const list of [character.initialRelationships, character.initialPossessions, character.knowledgeBoundaries]) {
      if (list.length > 20 || list.some(item => item.length > 1000)) return '人物底稿列表最多 20 项，每项不超过 1000 字符。'
    }
  }
  return ''
})

function generate() {
  if (hasUnsavedChanges.value && !window.confirm('当前修改尚未保存，生成新草稿将替换页面中的未保存内容。是否继续？')) return
  generateMutation.mutate()
}
</script>

<template>
  <div class="bible-panel">
    <div class="section-heading">
      <div>
        <span class="eyebrow">规划基准</span>
        <h2>故事圣经</h2>
      </div>
      <span v-if="bibleQuery.data.value" class="version-label">
        第 {{ bibleQuery.data.value.generationNumber }} 版 · {{ bibleQuery.data.value.status === 'PUBLISHED' ? '已发布' : '草稿' }}
      </span>
    </div>

    <div v-if="bibleQuery.isPending.value" class="direction-loading">正在读取故事圣经…</div>
    <div v-else-if="bibleQuery.isError.value" class="status-panel error-panel">
      <strong>故事圣经加载失败</strong><span>{{ bibleQuery.error.value?.message }}</span>
    </div>

    <template v-else-if="draft && bibleQuery.data.value">
      <section v-if="bibleQuery.data.value.changeSummary.length" class="change-summary">
        <strong>本版修改说明</strong>
        <ul><li v-for="item in bibleQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
      </section>
      <div v-if="bibleQuery.data.value.status === 'PUBLISHED'" class="selection-notice">
        <Check :size="17" />已发布版本保持不变；下方修改可保存为新草稿。
      </div>
      <fieldset class="bible-form" :disabled="busy">
        <label class="bible-field full"><span>一句话故事</span><textarea v-model="draft.logline" rows="2" /></label>
        <label class="bible-field"><span>主题表达</span><textarea v-model="draft.theme" rows="4" /></label>
        <label class="bible-field"><span>世界与时代</span><textarea v-model="draft.worldSetting" rows="4" /></label>
        <label class="bible-field"><span>主角设定</span><textarea v-model="draft.protagonist" rows="4" /></label>
        <label class="bible-field"><span>主角弧光</span><textarea v-model="draft.protagonistArc" rows="4" /></label>
        <label class="bible-field"><span>核心冲突</span><textarea v-model="draft.centralConflict" rows="4" /></label>
        <label class="bible-field"><span>失败代价</span><textarea v-model="draft.stakes" rows="4" /></label>
        <label class="bible-field"><span>叙事方式与文风</span><textarea v-model="draft.narrativeStyle" rows="4" /></label>
        <label class="bible-field"><span>结局方向</span><textarea v-model="draft.endingDirection" rows="4" /></label>
        <label class="bible-field"><span>世界规则</span><textarea :value="listText(draft.worldRules)" rows="5" @input="setList('worldRules', ($event.target as HTMLTextAreaElement).value)" /></label>
        <label class="bible-field"><span>重要配角</span><textarea :value="listText(draft.supportingCharacters)" rows="5" @input="setList('supportingCharacters', ($event.target as HTMLTextAreaElement).value)" /></label>
        <label class="bible-field"><span>关系变化</span><textarea :value="listText(draft.relationshipDynamics)" rows="5" @input="setList('relationshipDynamics', ($event.target as HTMLTextAreaElement).value)" /></label>
        <label class="bible-field"><span>不可违反的约束</span><textarea :value="listText(draft.hardConstraints)" rows="5" @input="setList('hardConstraints', ($event.target as HTMLTextAreaElement).value)" /></label>
        <label class="bible-field full"><span>待作者确认的问题</span><textarea :value="listText(draft.openQuestions)" rows="3" @input="setList('openQuestions', ($event.target as HTMLTextAreaElement).value)" /></label>
      </fieldset>
      <CharacterBlueprintEditor v-model="draftCharacters" :disabled="busy" />
      <ReaderExperienceSeedEditor :model-value="draft.readerExperiencePlans ?? []" :disabled="busy" @update:model-value="draft.readerExperiencePlans = $event" />
      <div v-if="characterError" class="form-error" role="alert">{{ characterError }}</div>
    </template>

    <div v-else class="direction-empty">
      <Sparkles :size="30" />
      <h3>把已确认方向扩展成创作基准</h3>
      <p>系统会补齐人物、世界规则、关系、冲突代价和结局约束。</p>
    </div>

    <div class="direction-actions">
      <label class="instruction-field"><span>本次调整要求</span><textarea v-model="instruction" rows="2" maxlength="1000" placeholder="可选，例如：让配角动机更具体" /></label>
      <label v-if="bibleQuery.data.value" class="provider-field"><span>生成方式</span><select v-model="generationMode"><option value="REVISE">基于当前版本调整</option><option value="REGENERATE">重新生成</option></select></label>
      <label v-if="bibleQuery.data.value && generationMode === 'REVISE'" class="provider-field outline-base-field"><span>基准故事圣经</span><select v-model="baseBibleVersionId"><option value="">最新保存版本（第 {{ bibleQuery.data.value.generationNumber }} 版）</option><option v-for="version in versionsQuery.data.value?.filter((item) => item.id !== bibleQuery.data.value?.id) ?? []" :key="version.id" :value="version.id">第 {{ version.generationNumber }} 版 · {{ version.status === 'PUBLISHED' ? '已发布' : '草稿' }} · {{ version.logline }}</option></select></label>
      <GlobalModelBadge />
      <div class="direction-action-buttons">
        <button class="button secondary" type="button" :disabled="busy" @click="generate()"><RefreshCw :size="16" />{{ generateMutation.isPending.value ? '正在生成…' : bibleQuery.data.value ? generationMode === 'REVISE' ? '按要求调整' : '重新生成' : '生成故事圣经' }}</button>
        <button v-if="draft" class="button secondary" type="button" :disabled="busy || hasUnsavedChanges || provider === 'LOCAL_TEMPLATE'" @click="completeCharactersMutation.mutate()"><Sparkles :size="16" />{{ completeCharactersMutation.isPending.value ? '正在补全人物…' : '补全人物底稿' }}</button>
        <button v-if="draft && bibleQuery.data.value?.status === 'PUBLISHED'" class="button secondary" type="button" :disabled="busy || !!characterError" @click="createRevisionMutation.mutate()"><Save :size="16" />{{ createRevisionMutation.isPending.value ? '正在保存…' : '保存为修订草稿' }}</button>
        <button v-if="draft && bibleQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy || !!characterError" @click="saveMutation.mutate()"><Save :size="16" />{{ saveMutation.isPending.value ? '正在保存…' : '保存修改' }}</button>
        <button v-if="draft && bibleQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy || hasUnsavedChanges || !!characterError" @click="publishMutation.mutate()"><Check :size="16" />{{ publishMutation.isPending.value ? '正在发布…' : '确认并发布' }}</button>
      </div>
    </div>
    <div v-if="versionsQuery.isError.value && bibleQuery.data.value" class="form-error" role="alert">历史故事圣经加载失败：{{ versionsQuery.error.value?.message }}</div>
    <details v-if="generationMode === 'REVISE' && baseBibleVersionId" class="outline-base-preview">
      <summary>查看第 {{ selectedBase?.generationNumber ?? '…' }} 版基准故事圣经</summary>
      <div v-if="basePreviewQuery.isPending.value" class="direction-loading">正在读取历史故事圣经…</div>
      <div v-else-if="basePreviewQuery.isError.value" class="form-error" role="alert">历史故事圣经加载失败：{{ basePreviewQuery.error.value?.message }}</div>
      <div v-else-if="basePreviewQuery.data.value" class="outline-base-preview-body">
        <h3>{{ basePreviewQuery.data.value.content.logline }}</h3>
        <p>主题：{{ basePreviewQuery.data.value.content.theme }}</p>
        <p>世界与时代：{{ basePreviewQuery.data.value.content.worldSetting }}</p>
        <p>主角：{{ basePreviewQuery.data.value.content.protagonist }}</p>
        <p>主角弧光：{{ basePreviewQuery.data.value.content.protagonistArc }}</p>
        <p>核心冲突：{{ basePreviewQuery.data.value.content.centralConflict }}</p>
        <p>失败代价：{{ basePreviewQuery.data.value.content.stakes }}</p>
        <p>叙事方式：{{ basePreviewQuery.data.value.content.narrativeStyle }}</p>
        <p>结局方向：{{ basePreviewQuery.data.value.content.endingDirection }}</p>
        <h4>世界规则</h4><ul><li v-for="(item, index) in basePreviewQuery.data.value.content.worldRules" :key="index">{{ item }}</li></ul>
        <h4>重要配角</h4><ul><li v-for="(item, index) in basePreviewQuery.data.value.content.supportingCharacters" :key="index">{{ item }}</li></ul>
        <h4>关系变化</h4><ul><li v-for="(item, index) in basePreviewQuery.data.value.content.relationshipDynamics" :key="index">{{ item }}</li></ul>
        <h4>不可违反的约束</h4><ul><li v-for="(item, index) in basePreviewQuery.data.value.content.hardConstraints" :key="index">{{ item }}</li></ul>
        <h4>待确认问题</h4><ul><li v-for="(item, index) in basePreviewQuery.data.value.content.openQuestions" :key="index">{{ item }}</li></ul>
        <h4>人物底稿</h4>
        <CharacterBlueprintEditor :model-value="basePreviewQuery.data.value.content.characterBlueprints ?? []" disabled />
        <ReaderExperienceSeedEditor :model-value="basePreviewQuery.data.value.content.readerExperiencePlans ?? []" disabled />
      </div>
    </details>
    <div v-if="actionError" class="form-error" role="alert">{{ actionError }}</div>
    <p v-if="bibleQuery.data.value" class="generator-note">本版本由 {{ bibleQuery.data.value.generatorType === 'AUTHOR_EDIT' ? '作者手动修订' : bibleQuery.data.value.generatorType }} 生成</p>
  </div>
</template>

<style scoped>
fieldset.bible-form { min-width: 0; margin: 0; border-left: 0; border-right: 0; }
</style>

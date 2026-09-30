<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, RefreshCw, Save, Sparkles } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import {
  generateStoryBible,
  getLatestStoryBible,
  publishStoryBible,
  updateStoryBible,
  type GenerationMode,
  type ModelProvider,
  type StoryBibleContent,
  type StoryBibleVersion,
} from '@/api/planning'

const props = defineProps<{ projectId: string }>()
const queryClient = useQueryClient()
const provider = ref<ModelProvider>('LOCAL_CODEX')
const generationMode = ref<GenerationMode>('REVISE')
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
  }
}

const bibleQuery = useQuery({
  queryKey: computed(() => ['story-bible', props.projectId]),
  queryFn: () => getLatestStoryBible(props.projectId),
})

watch(
  () => bibleQuery.data.value,
  (value) => {
    draft.value = value ? copyContent(value.content) : null
  },
  { immediate: true },
)

function updateCache(value: StoryBibleVersion) {
  queryClient.setQueryData(['story-bible', props.projectId], value)
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
  mutationFn: () => generateStoryBible(
    props.projectId,
    instruction.value,
    provider.value,
    bibleQuery.data.value ? generationMode.value : 'REGENERATE',
  ),
  onSuccess: (value) => {
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
  onSuccess: (value) => { updateCache(value); actionError.value = '' },
  onError: (error: Error) => { actionError.value = error.message },
})

const publishMutation = useMutation({
  mutationFn: () => {
    if (!bibleQuery.data.value) throw new Error('没有可发布的故事圣经。')
    return publishStoryBible(props.projectId, bibleQuery.data.value)
  },
  onSuccess: (value) => { updateCache(value); actionError.value = '' },
  onError: (error: Error) => { actionError.value = error.message },
})
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
        <Check :size="17" />当前版本已发布，可作为分层大纲的生成基准。
      </div>
      <div class="bible-form">
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
      </div>
    </template>

    <div v-else class="direction-empty">
      <Sparkles :size="30" />
      <h3>把已确认方向扩展成创作基准</h3>
      <p>系统会补齐人物、世界规则、关系、冲突代价和结局约束。</p>
    </div>

    <div class="direction-actions">
      <label class="instruction-field"><span>本次调整要求</span><textarea v-model="instruction" rows="2" maxlength="1000" placeholder="可选，例如：让配角动机更具体" /></label>
      <label v-if="bibleQuery.data.value" class="provider-field"><span>生成方式</span><select v-model="generationMode"><option value="REVISE">基于当前版本调整</option><option value="REGENERATE">重新生成</option></select></label>
      <label class="provider-field"><span>生成模型</span><select v-model="provider"><option value="LOCAL_CODEX">服务端 Codex</option><option value="DEEPSEEK">DeepSeek</option><option value="LOCAL_TEMPLATE">本地模板</option></select></label>
      <div class="direction-action-buttons">
        <button class="button secondary" type="button" :disabled="generateMutation.isPending.value" @click="generateMutation.mutate()"><RefreshCw :size="16" />{{ generateMutation.isPending.value ? '正在生成…' : bibleQuery.data.value ? generationMode === 'REVISE' ? '按要求调整' : '重新生成' : '生成故事圣经' }}</button>
        <button v-if="draft && bibleQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="saveMutation.isPending.value" @click="saveMutation.mutate()"><Save :size="16" />{{ saveMutation.isPending.value ? '正在保存…' : '保存修改' }}</button>
        <button v-if="draft && bibleQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="publishMutation.isPending.value" @click="publishMutation.mutate()"><Check :size="16" />{{ publishMutation.isPending.value ? '正在发布…' : '确认并发布' }}</button>
      </div>
    </div>
    <div v-if="actionError" class="form-error" role="alert">{{ actionError }}</div>
    <p v-if="bibleQuery.data.value" class="generator-note">本版本由 {{ bibleQuery.data.value.generatorType }} 生成</p>
  </div>
</template>

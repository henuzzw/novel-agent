<script setup lang="ts">
import { navigateWorkspaceButtons } from '@/lib/workspace-keyboard'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { BookUser, Clock3, ContactRound, Flag, Save, Search, WandSparkles } from 'lucide-vue-next'
import { computed, reactive, ref, watch } from 'vue'
import WritingStylePanel from './WritingStylePanel.vue'
import ReaderExperiencePanel from './ReaderExperiencePanel.vue'
import PlanningMaterialSyncButton from './PlanningMaterialSyncButton.vue'
import { listPlanningCharacters } from '@/api/planningMaterials'
import EntityFactsPanel from './EntityFactsPanel.vue'
import CharacterRelationsPanel from './CharacterRelationsPanel.vue'
import RelationshipPanel from './RelationshipPanel.vue'
import { completeStoryBibleCharacters, getLatestStoryBible } from '@/api/planning'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import { materialViews, useWorkspaceChoice } from '@/composables/useWorkspaceLocation'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

import {
  initializeCharacterNames,
  listCanonEntities,
  listCanonTimeline,
  listCharacterNames,
  listCharacterProfiles,
  updateCharacterName,
  updateCharacterProfile,
  type CharacterName,
  type CharacterProfile,
} from '@/api/writing'

const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ openBible: [] }>()
const { provider } = useGlobalModelSettings()
const queryClient = useQueryClient()
const view = useWorkspaceChoice('materials', materialViews, 'profiles')
const entityType = ref('ALL')
const search = ref('')
const selectedEntityId = ref<string | null>(null)
const nameError = ref('')
const savingCharacterId = ref<string | null>(null)
const nameDrafts = reactive<Record<string, { canonicalName: string; nickname: string; title: string }>>({})
const nameDraftVersions = reactive<Record<string, number>>({})
type ProfileDraft = Omit<CharacterProfile, 'characterId' | 'roleKey' | 'canonicalName' | 'version'>
const selectedProfileId = ref<string | null>(null)
const profileDrafts = reactive<Record<string, ProfileDraft>>({})
const profileDraftVersions = reactive<Record<string, number>>({})
const profileError = ref('')

const entityTypes = [
  { value: 'ALL', label: '全部' },
  { value: 'LOCATION', label: '地点' },
  { value: 'ORGANIZATION', label: '组织' },
  { value: 'ITEM', label: '物品' },
  { value: 'SECRET', label: '秘密' },
  { value: 'RULE', label: '规则' },
]

const entitiesQuery = useQuery({
  queryKey: computed(() => ['canon-entities', props.projectId]),
  queryFn: () => listCanonEntities(props.projectId),
})
const characterNamesQuery = useQuery({
  queryKey: computed(() => ['character-names', props.projectId]),
  queryFn: () => listCharacterNames(props.projectId),
})
const characterProfilesQuery = useQuery({
  queryKey: computed(() => ['character-profiles', props.projectId]),
  queryFn: () => listCharacterProfiles(props.projectId),
})
const timelineQuery = useQuery({
  queryKey: computed(() => ['canon-timeline', props.projectId]),
  queryFn: () => listCanonTimeline(props.projectId),
  enabled: computed(() => view.value === 'timeline'),
})
const planningCharactersQuery = useQuery({
  queryKey: computed(() => ['planning-characters', props.projectId]),
  queryFn: () => listPlanningCharacters(props.projectId),
})
const selectedBlueprint = computed(() => planningCharactersQuery.data.value
  ?.find(item => item.characterId === selectedProfileId.value)?.blueprint)
const selectedEntity = computed(() => entitiesQuery.data.value?.find((item) => item.id === selectedEntityId.value) ?? null)
const filteredEntities = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return (entitiesQuery.data.value ?? []).filter((item) =>
    item.type !== 'CHARACTER' && (entityType.value === 'ALL' || item.type === entityType.value)
    && (!keyword || item.name.toLowerCase().includes(keyword)))
})

watch(filteredEntities, (items) => {
  if (!items.some((item) => item.id === selectedEntityId.value)) selectedEntityId.value = items[0]?.id ?? null
}, { immediate: true })
watch(() => characterNamesQuery.data.value, (items, previous) => {
  for (const item of items ?? []) {
    const old = previous?.find(value => value.id === item.id)
    if (old && nameDrafts[item.id] && JSON.stringify(nameDrafts[item.id]) !== JSON.stringify(nameInput(old))) continue
    nameDrafts[item.id] = {
      canonicalName: item.canonicalName,
      nickname: item.nickname ?? '',
      title: item.title ?? '',
    }
    nameDraftVersions[item.id] = item.version
  }
}, { immediate: true })
watch(() => characterProfilesQuery.data.value, (items, previous) => {
  for (const item of items ?? []) {
    const old = previous?.find(value => value.characterId === item.characterId)
    if (old && profileDrafts[item.characterId] && JSON.stringify(profileDrafts[item.characterId]) !== JSON.stringify(profileInput(old))) continue
    profileDrafts[item.characterId] = profileInput(item)
    profileDraftVersions[item.characterId] = item.version
  }
  if (!items?.some((item) => item.characterId === selectedProfileId.value)) {
    selectedProfileId.value = items?.[0]?.characterId ?? null
  }
}, { immediate: true })

const selectedProfile = computed(() => characterProfilesQuery.data.value
  ?.find((item) => item.characterId === selectedProfileId.value) ?? null)
const selectedName = computed(() => characterNamesQuery.data.value?.find(item => item.id === selectedProfileId.value))
const profileDirty = computed(() => (characterProfilesQuery.data.value ?? []).some(profile =>
  JSON.stringify(profileDraft(profile)) !== JSON.stringify(profileInput(profile)))
  || (characterNamesQuery.data.value ?? []).some(name => JSON.stringify(nameDraft(name)) !== JSON.stringify(nameInput(name))))
useUnsavedChanges(profileDirty, ['section'])
const missingProfileFields = computed(() => {
  if (!selectedProfile.value) return []
  const draft = profileDraft(selectedProfile.value)
  const fields: [keyof ProfileDraft, string][] = [['gender', '性别'], ['ageDescription', '年龄'], ['identity', '身份'],
    ['appearance', '外貌'], ['background', '背景'], ['externalPersonality', '外在性格'], ['internalPersonality', '内在性格'],
    ['coreDesire', '欲望'], ['fear', '恐惧'], ['flaw', '缺陷'], ['values', '价值观'], ['speechStyle', '声线'],
    ['behaviorHabits', '习惯'], ['behaviorBoundaries', '行为边界'], ['characterArc', '人物弧光']]
  const missing = fields.filter(([key]) => !draft[key]?.trim()).map(([, label]) => label)
  const blueprint = selectedBlueprint.value
  if (!blueprint?.abilitiesAndLimits.trim()) missing.push('能力与限制')
  if (!blueprint?.openingState.trim()) missing.push('开篇状态')
  if (!blueprint?.knowledgeBoundaries.length) missing.push('初始知识边界')
  return missing
})
const bibleQuery = useQuery({ queryKey: computed(() => ['story-bible', props.projectId]), queryFn: () => getLatestStoryBible(props.projectId) })
const completionError = ref('')
const completionNotice = ref('')
const completeCharacters = useMutation({
  mutationFn: () => {
    if (!bibleQuery.data.value) throw new Error('请先保存故事圣经。')
    if (profileDirty.value) throw new Error('请先保存人物档案或姓名的修改。')
    return completeStoryBibleCharacters(props.projectId, bibleQuery.data.value, provider.value,
      '补齐缺失人物设定，包括明确的性别与起点年龄或年龄范围；未知留空，不从姓名或年级推断，不为人物强加秘密。')
  },
  onSuccess: value => {
    queryClient.setQueryData(['story-bible', value.projectId], value)
    queryClient.invalidateQueries({ queryKey: ['story-bible-versions', value.projectId] })
    if (value.projectId !== props.projectId) return
    completionError.value = ''; completionNotice.value = '人物补全草稿已生成，等待确认发布。'
  },
  onError: (reason: Error) => { completionError.value = reason.message },
})
watch(() => props.projectId, () => {
  for (const key of Object.keys(profileDrafts)) delete profileDrafts[key]
  for (const key of Object.keys(nameDrafts)) delete nameDrafts[key]
  for (const key of Object.keys(profileDraftVersions)) delete profileDraftVersions[key]
  for (const key of Object.keys(nameDraftVersions)) delete nameDraftVersions[key]
  selectedProfileId.value = null; selectedEntityId.value = null
  profileError.value = ''; nameError.value = ''; completionError.value = ''; completionNotice.value = ''
})

function profileInput(value: CharacterProfile): ProfileDraft {
  return {
    gender: value.gender ?? '', ageDescription: value.ageDescription ?? '', identity: value.identity ?? '',
    appearance: value.appearance ?? '', background: value.background ?? '',
    externalPersonality: value.externalPersonality ?? '', internalPersonality: value.internalPersonality ?? '',
    coreDesire: value.coreDesire ?? '', fear: value.fear ?? '', flaw: value.flaw ?? '', values: value.values ?? '',
    speechStyle: value.speechStyle ?? '', behaviorHabits: value.behaviorHabits ?? '', secret: value.secret ?? '',
    characterArc: value.characterArc ?? '', behaviorBoundaries: value.behaviorBoundaries ?? '', notes: value.notes ?? '',
  }
}

function profileDraft(value: CharacterProfile) {
  return profileDrafts[value.characterId] ??= profileInput(value)
}

const initializeNamesMutation = useMutation({
  mutationFn: () => initializeCharacterNames(props.projectId),
  onSuccess: (items) => {
    queryClient.setQueryData(['character-names', props.projectId], items)
    queryClient.invalidateQueries({ queryKey: ['canon-entities', props.projectId] })
    queryClient.invalidateQueries({ queryKey: ['character-profiles', props.projectId] })
    nameError.value = ''
  },
  onError: (reason: Error) => { nameError.value = reason.message },
})

const saveNameMutation = useMutation({
  mutationFn: (character: CharacterName) => {
    const draft = nameDrafts[character.id]
    if (!draft?.canonicalName.trim()) throw new Error('正式姓名不能为空。')
    savingCharacterId.value = character.id
    const projectId = props.projectId
    return updateCharacterName(projectId, { ...character, version: nameDraftVersions[character.id] ?? character.version }, {
      canonicalName: draft.canonicalName.trim(),
      nickname: draft.nickname.trim() || null,
      title: draft.title.trim() || null,
    }).then(saved => ({ saved, projectId }))
  },
  onSuccess: ({ saved, projectId }) => {
    if (projectId === props.projectId) { nameDrafts[saved.id] = nameInput(saved); nameDraftVersions[saved.id] = saved.version }
    queryClient.setQueryData<CharacterName[]>(['character-names', projectId], (items) =>
      (items ?? []).map((item) => item.id === saved.id ? saved : item))
    queryClient.invalidateQueries({ predicate: query => query.queryKey.includes(projectId) })
    if (projectId === props.projectId) nameError.value = ''
  },
  onError: (reason: Error) => { nameError.value = reason.message },
  onSettled: () => { savingCharacterId.value = null },
})

const saveProfileMutation = useMutation({
  mutationFn: (profile: CharacterProfile) => {
    const projectId = props.projectId
    return updateCharacterProfile(projectId, { ...profile, version: profileDraftVersions[profile.characterId] ?? profile.version }, profileDraft(profile))
      .then(saved => ({ saved, projectId }))
  },
  onSuccess: ({ saved, projectId }) => {
    queryClient.setQueryData<CharacterProfile[]>(['character-profiles', projectId], (items) =>
      (items ?? []).map((item) => item.characterId === saved.characterId ? saved : item))
    if (projectId !== props.projectId) return
    profileDrafts[saved.characterId] = profileInput(saved)
    profileDraftVersions[saved.characterId] = saved.version
    profileError.value = ''
  },
  onError: (reason: Error) => { profileError.value = reason.message },
})

function roleLabel(value: string | null) {
  if (value === 'PROTAGONIST') return '主角'
  if (value?.startsWith('SUPPORTING_')) return `重要人物 ${value.substring('SUPPORTING_'.length)}`
  return '人物'
}
function nameDraft(character: CharacterName) {
  return nameDrafts[character.id] ??= nameInput(character)
}
function nameInput(character: CharacterName) {
  return {
    canonicalName: character.canonicalName,
    nickname: character.nickname ?? '',
    title: character.title ?? '',
  }
}

function entityTypeLabel(value: string) {
  return entityTypes.find((item) => item.value === value)?.label ?? value
}
</script>

<template>
  <div class="materials-workbench">
    <div class="planning-tabs" role="group" @keydown="navigateWorkspaceButtons" aria-label="故事资料分类">
      <button type="button" :aria-pressed="view === 'profiles'" :class="{ active: view === 'profiles' }" @click="view = 'profiles'"><ContactRound :size="16" />人物档案</button>
      <button type="button" :aria-pressed="view === 'entities'" :class="{ active: view === 'entities' }" @click="view = 'entities'"><BookUser :size="16" />非人物实体</button>
      <button type="button" :aria-pressed="view === 'timeline'" :class="{ active: view === 'timeline' }" @click="view = 'timeline'"><Clock3 :size="16" />事件时间线</button>
      <button type="button" :aria-pressed="view === 'relations'" :class="{ active: view === 'relations' }" @click="view = 'relations'"><ContactRound :size="16" />人物关系</button>
      <button type="button" :aria-pressed="view === 'foreshadows'" :class="{ active: view === 'foreshadows' }" @click="view = 'foreshadows'"><Flag :size="16" />伏笔</button>
      <button type="button" :aria-pressed="view === 'style'" :class="{ active: view === 'style' }" @click="view = 'style'"><WandSparkles :size="16" />写作风格</button>
    </div>

    <WritingStylePanel v-if="view === 'style'" :project-id="projectId" />
    <template v-else-if="view === 'profiles'">
      <div class="section-heading character-name-heading">
        <div><span class="eyebrow">作者设定</span><h2>人物档案</h2></div>
        <PlanningMaterialSyncButton :project-id="projectId" />
        <button v-if="characterProfilesQuery.data.value?.length" class="button secondary" type="button" :disabled="initializeNamesMutation.isPending.value" @click="initializeNamesMutation.mutate()"><WandSparkles :size="16" />补充识别人物</button>
        <button class="button secondary" type="button" :disabled="completeCharacters.isPending.value || profileDirty || !bibleQuery.data.value || provider === 'LOCAL_TEMPLATE'" @click="completeCharacters.mutate()"><WandSparkles :size="16" />{{ completeCharacters.isPending.value ? '正在补全人物…' : '补全人物设定' }}</button>
        <span class="version-label">{{ characterProfilesQuery.data.value?.length ?? 0 }} 人</span>
      </div>
      <p v-if="completionNotice" role="status">{{ completionNotice }} <button class="button secondary" type="button" @click="emit('openBible')">查看待确认圣经</button></p>
      <p v-if="completionError" class="form-error" role="alert">{{ completionError }}</p>
      <div v-if="characterProfilesQuery.isPending.value" class="editor-empty">正在读取人物档案…</div>
      <div v-else-if="characterProfilesQuery.isError.value" class="status-panel error-panel">人物档案加载失败：{{ characterProfilesQuery.error.value?.message }}</div>
      <div v-else-if="!characterProfilesQuery.data.value?.length" class="editor-empty">
        <ContactRound :size="30" /><h3>还没有可编辑的人物</h3><button class="button secondary" type="button" :disabled="initializeNamesMutation.isPending.value" @click="initializeNamesMutation.mutate()"><WandSparkles :size="16" />从故事圣经识别人物</button>
      </div>
      <div v-else class="profile-layout">
        <aside class="entity-list profile-picker" aria-label="人物列表">
          <button v-for="profile in characterProfilesQuery.data.value" :key="profile.characterId" type="button" :class="{ active: selectedProfileId === profile.characterId }" @click="selectedProfileId = profile.characterId">
            <span>{{ profile.canonicalName }}</span><small>{{ roleLabel(profile.roleKey) }}</small>
          </button>
        </aside>
        <div v-if="selectedProfile" class="profile-editor">
        <section v-if="selectedName" aria-label="人物命名">
          <h3>姓名与称谓</h3>
          <form class="profile-name-form" @submit.prevent="saveNameMutation.mutate(selectedName)">
            <label><span>正式姓名</span><input v-model="nameDraft(selectedName).canonicalName" maxlength="200" /></label>
            <label><span>昵称</span><input v-model="nameDraft(selectedName).nickname" maxlength="200" /></label>
            <label><span>称谓</span><input v-model="nameDraft(selectedName).title" maxlength="200" /></label>
            <button class="icon-button" type="submit" title="保存人物命名" aria-label="保存人物命名" :disabled="savingCharacterId === selectedName.id"><Save :size="17" /></button>
          </form>
        </section>
        <p v-if="nameError" class="form-error" role="alert">{{ nameError }}</p>
        <p v-if="characterNamesQuery.isError.value" class="form-error" role="alert">姓名读取失败：{{ characterNamesQuery.error.value?.message }}</p>
        <p v-if="planningCharactersQuery.isError.value" class="form-error" role="alert">人物底稿读取失败：{{ planningCharactersQuery.error.value?.message }}</p>
        <p v-if="missingProfileFields.length" class="profile-missing" role="status">待补充设定：{{ missingProfileFields.join('、') }}</p>
        <form @submit.prevent="saveProfileMutation.mutate(selectedProfile)">
          <header><div><span class="eyebrow">{{ roleLabel(selectedProfile.roleKey) }}</span><h2>{{ selectedProfile.canonicalName }}</h2></div><button class="button primary" type="submit" :disabled="saveProfileMutation.isPending.value"><Save :size="16" />{{ saveProfileMutation.isPending.value ? '正在保存…' : '保存档案' }}</button></header>
          <section><h3>基础信息</h3><div class="profile-grid compact">
            <label><span>性别</span><input v-model="profileDraft(selectedProfile).gender" maxlength="40" placeholder="例如：女" /></label>
            <label><span>年龄</span><input v-model="profileDraft(selectedProfile).ageDescription" maxlength="100" placeholder="例如：故事开始时16岁" /></label>
            <label class="wide"><span>身份</span><textarea v-model="profileDraft(selectedProfile).identity" rows="2" maxlength="3000" placeholder="班级身份、职业或社会角色" /></label>
            <label class="wide"><span>外貌特征</span><textarea v-model="profileDraft(selectedProfile).appearance" rows="2" maxlength="3000" placeholder="只写对辨识和叙事有用的特征" /></label>
            <label class="full"><span>成长与家庭背景</span><textarea v-model="profileDraft(selectedProfile).background" rows="3" maxlength="6000" /></label>
          </div></section>
          <section><h3>性格与动力</h3><div class="profile-grid">
            <label><span>外在性格</span><textarea v-model="profileDraft(selectedProfile).externalPersonality" rows="3" maxlength="4000" placeholder="别人通常怎样看待此人" /></label>
            <label><span>内在性格</span><textarea v-model="profileDraft(selectedProfile).internalPersonality" rows="3" maxlength="4000" placeholder="真实想法、矛盾与防御方式" /></label>
            <label><span>核心欲望</span><textarea v-model="profileDraft(selectedProfile).coreDesire" rows="3" maxlength="3000" /></label>
            <label><span>核心恐惧</span><textarea v-model="profileDraft(selectedProfile).fear" rows="3" maxlength="3000" /></label>
            <label><span>性格缺陷</span><textarea v-model="profileDraft(selectedProfile).flaw" rows="3" maxlength="3000" /></label>
            <label><span>价值观</span><textarea v-model="profileDraft(selectedProfile).values" rows="3" maxlength="3000" /></label>
          </div></section>
          <section><h3>表达与行动</h3><div class="profile-grid">
            <label><span>说话方式</span><textarea v-model="profileDraft(selectedProfile).speechStyle" rows="3" maxlength="4000" placeholder="语气、用词、口头禅和回避方式" /></label>
            <label><span>行为习惯</span><textarea v-model="profileDraft(selectedProfile).behaviorHabits" rows="3" maxlength="4000" /></label>
            <label class="full"><span>不可违背的行为边界</span><textarea v-model="profileDraft(selectedProfile).behaviorBoundaries" rows="3" maxlength="5000" placeholder="没有充分情节依据时，人物绝不会做什么" /></label>
          </div></section>
          <section><h3>长期发展</h3><div class="profile-grid">
            <label><span>隐藏秘密</span><textarea v-model="profileDraft(selectedProfile).secret" rows="3" maxlength="5000" /></label>
            <label><span>人物弧光</span><textarea v-model="profileDraft(selectedProfile).characterArc" rows="3" maxlength="5000" /></label>
            <label class="full"><span>补充备注</span><textarea v-model="profileDraft(selectedProfile).notes" rows="3" maxlength="5000" /></label>
          </div></section>
          <p v-if="profileError" class="form-error" role="alert">{{ profileError }}</p>
          <section v-if="selectedBlueprint" class="blueprint-source">
            <h3>已发布人物底稿 · 作者设定</h3>
            <p><strong>能力与限制：</strong>{{ selectedBlueprint.abilitiesAndLimits || '未设定' }}</p>
            <p><strong>开篇状态：</strong>{{ selectedBlueprint.openingState || '未设定' }}</p>
            <h4>初始物品</h4><ul v-if="selectedBlueprint.initialPossessions.length"><li v-for="(item, index) in selectedBlueprint.initialPossessions" :key="index">{{ item }}</li></ul><p v-else class="quiet-text">未设定</p>
            <h4>初始知识边界</h4><ul v-if="selectedBlueprint.knowledgeBoundaries.length"><li v-for="(item, index) in selectedBlueprint.knowledgeBoundaries" :key="index">{{ item }}</li></ul><p v-else class="quiet-text">未设定</p>
          </section>
        </form>
        <CharacterRelationsPanel :key="`relations:${projectId}:${selectedProfile.characterId}`" :project-id="projectId" :character-id="selectedProfile.characterId" />
        <EntityFactsPanel :key="`facts:${projectId}:${selectedProfile.characterId}`" :project-id="projectId" :entity-id="selectedProfile.characterId" />
        </div>
      </div>
    </template>

    <template v-else-if="view === 'entities'">
      <div class="materials-toolbar">
        <div class="entity-type-filter" role="group" aria-label="实体类型">
          <button v-for="item in entityTypes" :key="item.value" type="button" :class="{ active: entityType === item.value }" @click="entityType = item.value">{{ item.label }}</button>
        </div>
        <label class="materials-search"><Search :size="16" /><input v-model="search" placeholder="搜索名称" aria-label="搜索实体" /></label>
      </div>

      <div v-if="entitiesQuery.isPending.value" class="editor-empty">正在读取故事资料…</div>
      <div v-else-if="entitiesQuery.isError.value" class="status-panel error-panel">故事资料加载失败：{{ entitiesQuery.error.value?.message }}</div>
      <div v-else-if="!filteredEntities.length" class="editor-empty">当前正史中还没有这类资料。</div>
      <div v-else class="materials-layout">
        <aside class="entity-list" aria-label="实体列表">
          <button v-for="entity in filteredEntities" :key="entity.id" type="button" :class="{ active: selectedEntityId === entity.id }" @click="selectedEntityId = entity.id">
            <span>{{ entity.name }}</span><small>{{ entityTypeLabel(entity.type) }} · {{ entity.status === 'PLANNED' ? '规划登记' : `正史 V${entity.canonVersionFrom}` }}</small>
          </button>
        </aside>

        <section v-if="selectedEntity" class="entity-detail">
          <header><div><span class="eyebrow">{{ entityTypeLabel(selectedEntity.type) }}</span><h2>{{ selectedEntity.name }}</h2></div><span class="version-label">{{ selectedEntity.status }}</span></header>

          <EntityFactsPanel :key="`${projectId}:${selectedEntity.id}`" :project-id="projectId" :entity-id="selectedEntity.id" />
        </section>
      </div>
    </template>

    <template v-else-if="view === 'timeline'">
      <div class="section-heading"><div><span class="eyebrow">正史记录</span><h2>事件时间线</h2></div><span class="version-label">{{ timelineQuery.data.value?.length ?? 0 }} 个事件</span></div>
      <div v-if="timelineQuery.isPending.value" class="editor-empty">正在读取事件时间线…</div>
      <div v-else-if="!timelineQuery.data.value?.length" class="editor-empty">提交包含事件的章节后，时间线会显示在这里。</div>
      <div v-else class="timeline-list"><article v-for="item in timelineQuery.data.value" :key="item.id"><div class="timeline-marker">{{ item.chapterNumber }}</div><div><header><strong>{{ item.title }}</strong><span>正史 V{{ item.canonVersionFrom }}</span></header><p>{{ item.summary }}</p><small>{{ item.storyTime || `第 ${item.chapterNumber} 章` }}<template v-if="item.evidence"> · {{ item.evidence }}</template></small></div></article></div>
    </template>

    <RelationshipPanel v-else-if="view === 'relations'" :project-id="projectId" />
    <template v-else>
      <ReaderExperiencePanel :project-id="projectId" />
    </template>
  </div>
</template>

<style scoped>
.materials-workbench > .planning-tabs { flex-wrap: wrap; }
.blueprint-source { overflow-wrap: anywhere; }
.profile-name-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; gap: 12px; align-items: end; }
.profile-name-form label { display: grid; gap: 6px; min-width: 0; font-size: 12px; }
.profile-name-form input { width: 100%; min-width: 0; border: 1px solid #bfc8d1; border-radius: 6px; padding: 8px; }
.profile-missing { color: #856019; font-size: 13px; overflow-wrap: anywhere; }
.character-name-heading { flex-wrap: wrap; gap: 12px; }
.profile-editor > form > header { display: flex; gap: 16px; align-items: center; justify-content: space-between; padding-bottom: 16px; border-bottom: 1px solid #d7dde3; }
.profile-editor > form > header h2 { margin: 2px 0 0; }
.profile-editor > form > section { padding: 18px 0; border-bottom: 1px solid #e1e5e9; }
.profile-editor > form > section h3 { margin: 0 0 12px; font-size: 14px; }
@media (max-width: 640px) {
  .profile-name-form { grid-template-columns: minmax(0, 1fr) auto; }
  .profile-name-form label { grid-column: 1; }
  .profile-name-form button { grid-column: 2; grid-row: 3; }
  .character-name-heading > div:first-child { flex-basis: 100%; }
  .character-name-heading > button, .character-name-heading :deep(.planning-sync) { width: 100%; }
  .character-name-heading :deep(.planning-sync button) { width: 100%; }
  .profile-editor > form > header { flex-wrap: wrap; }
}
</style>

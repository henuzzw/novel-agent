<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { BookUser, Clock3, ContactRound, Flag, Plus, Save, Search, UserRoundCog, WandSparkles } from 'lucide-vue-next'
import { computed, reactive, ref, watch } from 'vue'

import {
  addEntityAlias,
  getEntityState,
  initializeCharacterNames,
  listCanonEntities,
  listCanonTimeline,
  listEntityAliases,
  listEntityMentions,
  listForeshadows,
  listCharacterNames,
  listCharacterProfiles,
  updateCharacterName,
  updateCharacterProfile,
  type CharacterName,
  type CharacterProfile,
  type EntityAlias,
} from '@/api/writing'

const props = defineProps<{ projectId: string }>()
const queryClient = useQueryClient()
const view = ref<'profiles' | 'names' | 'entities' | 'timeline' | 'foreshadows'>('profiles')
const entityType = ref('ALL')
const search = ref('')
const selectedEntityId = ref<string | null>(null)
const alias = ref('')
const aliasType = ref<EntityAlias['aliasType']>('NICKNAME')
const actionError = ref('')
const nameError = ref('')
const savingCharacterId = ref<string | null>(null)
const nameDrafts = reactive<Record<string, { canonicalName: string; nickname: string; title: string }>>({})
type ProfileDraft = Omit<CharacterProfile, 'characterId' | 'roleKey' | 'canonicalName' | 'version'>
const selectedProfileId = ref<string | null>(null)
const profileDrafts = reactive<Record<string, ProfileDraft>>({})
const profileError = ref('')

const entityTypes = [
  { value: 'ALL', label: '全部' },
  { value: 'CHARACTER', label: '人物' },
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
const foreshadowsQuery = useQuery({
  queryKey: computed(() => ['canon-foreshadows', props.projectId]),
  queryFn: () => listForeshadows(props.projectId),
  enabled: computed(() => view.value === 'foreshadows'),
})
const selectedEntity = computed(() => entitiesQuery.data.value?.find((item) => item.id === selectedEntityId.value) ?? null)
const filteredEntities = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return (entitiesQuery.data.value ?? []).filter((item) =>
    (entityType.value === 'ALL' || item.type === entityType.value)
    && (!keyword || item.name.toLowerCase().includes(keyword)))
})
const entityStateQuery = useQuery({
  queryKey: computed(() => ['canon-entity-state', props.projectId, selectedEntityId.value]),
  queryFn: () => getEntityState(props.projectId, selectedEntityId.value!),
  enabled: computed(() => Boolean(selectedEntityId.value)),
})
const aliasesQuery = useQuery({
  queryKey: computed(() => ['canon-entity-aliases', props.projectId, selectedEntityId.value]),
  queryFn: () => listEntityAliases(props.projectId, selectedEntityId.value!),
  enabled: computed(() => Boolean(selectedEntityId.value)),
})
const mentionsQuery = useQuery({
  queryKey: computed(() => ['canon-entity-mentions', props.projectId, selectedEntityId.value]),
  queryFn: () => listEntityMentions(props.projectId, selectedEntityId.value!),
  enabled: computed(() => Boolean(selectedEntityId.value)),
})

watch(filteredEntities, (items) => {
  if (!items.some((item) => item.id === selectedEntityId.value)) selectedEntityId.value = items[0]?.id ?? null
}, { immediate: true })
watch(selectedEntityId, () => { alias.value = ''; actionError.value = '' })
watch(() => characterNamesQuery.data.value, (items) => {
  for (const item of items ?? []) {
    nameDrafts[item.id] = {
      canonicalName: item.canonicalName,
      nickname: item.nickname ?? '',
      title: item.title ?? '',
    }
  }
}, { immediate: true })
watch(() => characterProfilesQuery.data.value, (items) => {
  for (const item of items ?? []) {
    profileDrafts[item.characterId] = profileInput(item)
  }
  if (!items?.some((item) => item.characterId === selectedProfileId.value)) {
    selectedProfileId.value = items?.[0]?.characterId ?? null
  }
}, { immediate: true })

const selectedProfile = computed(() => characterProfilesQuery.data.value
  ?.find((item) => item.characterId === selectedProfileId.value) ?? null)

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
    return updateCharacterName(props.projectId, character, {
      canonicalName: draft.canonicalName.trim(),
      nickname: draft.nickname.trim() || null,
      title: draft.title.trim() || null,
    })
  },
  onSuccess: (saved) => {
    queryClient.setQueryData<CharacterName[]>(['character-names', props.projectId], (items) =>
      (items ?? []).map((item) => item.id === saved.id ? saved : item))
    queryClient.invalidateQueries({ queryKey: ['canon-entities', props.projectId] })
    queryClient.invalidateQueries({ queryKey: ['story-bible', props.projectId] })
    queryClient.invalidateQueries({ queryKey: ['outline', props.projectId] })
    queryClient.invalidateQueries({ queryKey: ['current-outline', props.projectId] })
    queryClient.invalidateQueries({ queryKey: ['character-profiles', props.projectId] })
    nameError.value = ''
  },
  onError: (reason: Error) => { nameError.value = reason.message },
  onSettled: () => { savingCharacterId.value = null },
})

const saveProfileMutation = useMutation({
  mutationFn: (profile: CharacterProfile) => updateCharacterProfile(props.projectId, profile, profileDraft(profile)),
  onSuccess: (saved) => {
    queryClient.setQueryData<CharacterProfile[]>(['character-profiles', props.projectId], (items) =>
      (items ?? []).map((item) => item.characterId === saved.characterId ? saved : item))
    profileDrafts[saved.characterId] = profileInput(saved)
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
  return nameDrafts[character.id] ??= {
    canonicalName: character.canonicalName,
    nickname: character.nickname ?? '',
    title: character.title ?? '',
  }
}

const addAliasMutation = useMutation({
  mutationFn: () => {
    if (!selectedEntityId.value || !alias.value.trim()) throw new Error('请输入别名。')
    return addEntityAlias(props.projectId, selectedEntityId.value, alias.value.trim(), aliasType.value)
  },
  onSuccess: () => {
    alias.value = ''
    actionError.value = ''
    queryClient.invalidateQueries({ queryKey: ['canon-entity-aliases', props.projectId, selectedEntityId.value] })
  },
  onError: (reason: Error) => { actionError.value = reason.message },
})

function entityTypeLabel(value: string) {
  return entityTypes.find((item) => item.value === value)?.label ?? value
}
function aliasTypeLabel(value: string) {
  return ({ NICKNAME: '昵称', TITLE: '称谓', FORMER_NAME: '曾用名', OTHER: '其他' } as Record<string, string>)[value] ?? value
}
function stateFieldLabel(value: string) {
  return ({ location: '所在地点', life_status: '生命状态', physical_status: '身体状态', goal: '当前目标', holder: '持有人' } as Record<string, string>)[value] ?? value
}
function displayValue(value: unknown) {
  if (value == null) return '未知'
  if (typeof value === 'string') return value
  return JSON.stringify(value)
}
function foreshadowStatusLabel(value: string) {
  return ({ PLANTED: '已埋设', REINFORCED: '已强化', PARTIALLY_REVEALED: '部分揭示', RESOLVED: '已回收', ABANDONED: '已放弃' } as Record<string, string>)[value] ?? value
}
</script>

<template>
  <div class="materials-workbench">
    <div class="planning-tabs" role="tablist" aria-label="故事资料分类">
      <button type="button" :class="{ active: view === 'profiles' }" @click="view = 'profiles'"><ContactRound :size="16" />人物档案</button>
      <button type="button" :class="{ active: view === 'names' }" @click="view = 'names'"><UserRoundCog :size="16" />人物命名</button>
      <button type="button" :class="{ active: view === 'entities' }" @click="view = 'entities'"><BookUser :size="16" />实体档案</button>
      <button type="button" :class="{ active: view === 'timeline' }" @click="view = 'timeline'"><Clock3 :size="16" />事件时间线</button>
      <button type="button" :class="{ active: view === 'foreshadows' }" @click="view = 'foreshadows'"><Flag :size="16" />伏笔</button>
    </div>

    <template v-if="view === 'profiles'">
      <div class="section-heading character-name-heading">
        <div><span class="eyebrow">作者设定</span><h2>人物档案</h2></div>
        <span class="version-label">{{ characterProfilesQuery.data.value?.length ?? 0 }} 人</span>
      </div>
      <p class="character-name-note">这里保存人物自身设定。已填写内容会自动用于章节策划、正文写作和审稿。</p>
      <div v-if="characterProfilesQuery.isPending.value" class="editor-empty">正在读取人物档案…</div>
      <div v-else-if="characterProfilesQuery.isError.value" class="status-panel error-panel">人物档案加载失败：{{ characterProfilesQuery.error.value?.message }}</div>
      <div v-else-if="!characterProfilesQuery.data.value?.length" class="editor-empty">
        <ContactRound :size="30" /><h3>还没有可编辑的人物</h3><p>请先在“人物命名”中从故事圣经识别人物。</p>
      </div>
      <div v-else class="profile-layout">
        <aside class="entity-list profile-picker" aria-label="人物列表">
          <button v-for="profile in characterProfilesQuery.data.value" :key="profile.characterId" type="button" :class="{ active: selectedProfileId === profile.characterId }" @click="selectedProfileId = profile.characterId">
            <span>{{ profile.canonicalName }}</span><small>{{ roleLabel(profile.roleKey) }}</small>
          </button>
        </aside>
        <form v-if="selectedProfile" class="profile-editor" @submit.prevent="saveProfileMutation.mutate(selectedProfile)">
          <header><div><span class="eyebrow">{{ roleLabel(selectedProfile.roleKey) }}</span><h2>{{ selectedProfile.canonicalName }}</h2></div><button class="button primary" type="submit" :disabled="saveProfileMutation.isPending.value"><Save :size="16" />{{ saveProfileMutation.isPending.value ? '正在保存…' : '保存档案' }}</button></header>
          <section><h3>基础信息</h3><div class="profile-grid compact">
            <label><span>性别</span><input v-model="profileDraft(selectedProfile).gender" maxlength="40" placeholder="例如：女" /></label>
            <label><span>年龄</span><input v-model="profileDraft(selectedProfile).ageDescription" maxlength="100" placeholder="例如：故事开始时16岁" /></label>
            <label class="wide"><span>身份</span><textarea v-model="profileDraft(selectedProfile).identity" rows="2" maxlength="2000" placeholder="班级身份、职业或社会角色" /></label>
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
        </form>
      </div>
    </template>

    <template v-else-if="view === 'names'">
      <div class="section-heading character-name-heading">
        <div><span class="eyebrow">显示与导出</span><h2>人物命名</h2></div>
        <button class="button secondary" type="button" :disabled="initializeNamesMutation.isPending.value" @click="initializeNamesMutation.mutate()">
          <WandSparkles :size="16" />{{ characterNamesQuery.data.value?.length ? '补充识别人物' : '从故事圣经识别人物' }}
        </button>
      </div>
      <p class="character-name-note">改名后，故事规划、模型提示、正文预览和导出会使用新名字；正文内部仍按同一个人物保存。</p>
      <div v-if="characterNamesQuery.isPending.value" class="editor-empty">正在读取人物命名…</div>
      <div v-else-if="characterNamesQuery.isError.value" class="status-panel error-panel">人物命名加载失败：{{ characterNamesQuery.error.value?.message }}</div>
      <div v-else-if="!characterNamesQuery.data.value?.length" class="editor-empty">
        <UserRoundCog :size="30" /><h3>还没有可配置的人物</h3><p>先生成故事圣经，再点击“从故事圣经识别人物”。</p>
      </div>
      <div v-else class="character-name-list">
        <article v-for="character in characterNamesQuery.data.value" :key="character.id" class="character-name-row">
          <div class="character-name-origin"><span>{{ roleLabel(character.roleKey) }}</span><strong>{{ character.sourceName }}</strong><small>原始识别名称</small></div>
          <label><span>正式姓名</span><input v-model="nameDraft(character).canonicalName" maxlength="200" /></label>
          <label><span>昵称</span><input v-model="nameDraft(character).nickname" maxlength="200" placeholder="可选" /></label>
          <label><span>称谓</span><input v-model="nameDraft(character).title" maxlength="200" placeholder="可选，如班长" /></label>
          <button class="icon-button" type="button" title="保存人物命名" :disabled="savingCharacterId === character.id" @click="saveNameMutation.mutate(character)"><Save :size="17" /></button>
        </article>
      </div>
      <p v-if="nameError" class="form-error" role="alert">{{ nameError }}</p>
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
            <span>{{ entity.name }}</span><small>{{ entityTypeLabel(entity.type) }} · 正史 V{{ entity.canonVersionFrom }}</small>
          </button>
        </aside>

        <section v-if="selectedEntity" class="entity-detail">
          <header><div><span class="eyebrow">{{ entityTypeLabel(selectedEntity.type) }}</span><h2>{{ selectedEntity.name }}</h2></div><span class="version-label">{{ selectedEntity.status }}</span></header>

          <section class="material-section">
            <h3>当前状态</h3>
            <div v-if="entityStateQuery.data.value?.length" class="state-table">
              <div v-for="item in entityStateQuery.data.value" :key="item.changeId"><strong>{{ stateFieldLabel(item.field) }}</strong><span>{{ displayValue(item.value) }}</span><small>第 {{ item.chapterNumber }} 章 · V{{ item.canonVersionFrom }}</small></div>
            </div>
            <p v-else class="quiet-text">暂无状态记录。</p>
          </section>

          <section class="material-section">
            <h3>确认别名</h3>
            <div class="alias-list"><span v-for="item in aliasesQuery.data.value ?? []" :key="item.id">{{ item.alias }}<small>{{ aliasTypeLabel(item.aliasType) }}</small></span><span v-if="!aliasesQuery.data.value?.length" class="quiet-text">暂无别名</span></div>
            <form class="alias-form" @submit.prevent="addAliasMutation.mutate()">
              <input v-model="alias" maxlength="200" placeholder="新增别名" aria-label="新增别名" />
              <select v-model="aliasType" aria-label="别名类型"><option value="NICKNAME">昵称</option><option value="TITLE">称谓</option><option value="FORMER_NAME">曾用名</option><option value="OTHER">其他</option></select>
              <button class="button secondary" type="submit" :disabled="addAliasMutation.isPending.value"><Plus :size="15" />添加</button>
            </form>
            <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
          </section>

          <section class="material-section">
            <h3>历史提及</h3>
            <div v-if="mentionsQuery.data.value?.length" class="mention-list">
              <div v-for="item in mentionsQuery.data.value" :key="item.id"><strong>{{ item.mention }}</strong><span>{{ item.resolutionMethod }} · 置信度 {{ Math.round(item.confidence * 100) }}%</span><small v-if="item.evidence">{{ item.evidence }}</small></div>
            </div>
            <p v-else class="quiet-text">暂无解析记录。</p>
          </section>
        </section>
      </div>
    </template>

    <template v-else-if="view === 'timeline'">
      <div class="section-heading"><div><span class="eyebrow">正史记录</span><h2>事件时间线</h2></div><span class="version-label">{{ timelineQuery.data.value?.length ?? 0 }} 个事件</span></div>
      <div v-if="timelineQuery.isPending.value" class="editor-empty">正在读取事件时间线…</div>
      <div v-else-if="!timelineQuery.data.value?.length" class="editor-empty">提交包含事件的章节后，时间线会显示在这里。</div>
      <div v-else class="timeline-list"><article v-for="item in timelineQuery.data.value" :key="item.id"><div class="timeline-marker">{{ item.chapterNumber }}</div><div><header><strong>{{ item.title }}</strong><span>正史 V{{ item.canonVersionFrom }}</span></header><p>{{ item.summary }}</p><small>{{ item.storyTime || `第 ${item.chapterNumber} 章` }}<template v-if="item.evidence"> · {{ item.evidence }}</template></small></div></article></div>
    </template>

    <template v-else>
      <div class="section-heading"><div><span class="eyebrow">叙事承诺</span><h2>伏笔追踪</h2></div><span class="version-label">{{ foreshadowsQuery.data.value?.length ?? 0 }} 条</span></div>
      <div v-if="foreshadowsQuery.isPending.value" class="editor-empty">正在读取伏笔…</div>
      <div v-else-if="!foreshadowsQuery.data.value?.length" class="editor-empty">审稿接受伏笔候选并提交正史后，会在这里持续追踪。</div>
      <div v-else class="foreshadow-table"><div class="foreshadow-head"><span>伏笔</span><span>状态</span><span>计划回收</span><span>来源</span></div><div v-for="item in foreshadowsQuery.data.value" :key="item.id"><span><strong>{{ item.title }}</strong><small>{{ item.targetEffect }}</small></span><span>{{ foreshadowStatusLabel(item.status) }}</span><span>{{ item.plannedResolveChapter ? `第 ${item.plannedResolveChapter} 章` : '未指定' }}</span><span>正史 V{{ item.canonVersionFrom }}</span></div></div>
    </template>
  </div>
</template>

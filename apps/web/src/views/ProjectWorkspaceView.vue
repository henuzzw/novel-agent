<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  Activity,
  BookOpen,
  Check,
  GitBranch,
  ListTree,
  Pencil,
  PenLine,
  RefreshCw,
  ScrollText,
  Settings,
  Share2,
  Sparkles,
  TriangleAlert,
  Upload,
} from 'lucide-vue-next'
import { computed, reactive, ref, watch } from 'vue'

import {
  generateStoryDirections,
  getLatestOutline,
  getLatestStoryBible,
  getLatestStoryDirections,
  selectStoryDirection,
  type GenerationMode,
  type ModelProvider,
  type StoryDirectionSet,
} from '@/api/planning'
import { getProject, updateCreativeIntent, type CreativeIntentInput, type ProjectSummary } from '@/api/projects'
import { createCreativeIntentPrefill } from '@/lib/creative-intent-prefill'
import StoryBiblePanel from '@/components/StoryBiblePanel.vue'
import OutlinePanel from '@/components/OutlinePanel.vue'
import RelationshipPanel from '@/components/RelationshipPanel.vue'
import StoryMaterialsPanel from '@/components/StoryMaterialsPanel.vue'
import WorkImportPanel from '@/components/WorkImportPanel.vue'
import AgentRunPanel from '@/components/AgentRunPanel.vue'
import WritingWorkbench from '@/components/WritingWorkbench.vue'

const props = defineProps<{ projectId: string }>()
const queryClient = useQueryClient()
const activeSection = ref<'writing' | 'outline' | 'materials' | 'relations' | 'imports' | 'runs'>('outline')
const sectionInitialized = ref(false)
const planningView = ref<'directions' | 'bible' | 'outline'>('directions')
const selectedCandidateId = ref<string | null>(null)
const instruction = ref('')
const modelProvider = ref<ModelProvider>('LOCAL_CODEX')
const generationMode = ref<GenerationMode>('REVISE')
const actionError = ref('')
const prefillNotice = ref('')
const intentNotice = ref('')
const isEditingIntent = ref(false)
const intentLoadedProjectId = ref<string | null>(null)
const intentForm = reactive({
  premise: '',
  genresText: '青春校园',
  targetAudience: '喜欢青春校园与人物关系故事的读者',
  protagonistBrief: '',
  centralConflict: '',
  tonesText: '真实, 细腻, 轻松',
  targetWords: 120000,
  mustHaveText: '',
})

function loadIntent(project: ProjectSummary) {
  const intent = project.creativeIntent
  if (!intent) return
  intentForm.premise = intent.premise ?? ''
  intentForm.genresText = intent.genres.join(', ')
  intentForm.targetAudience = intent.targetAudience ?? ''
  intentForm.protagonistBrief = intent.protagonistBrief ?? ''
  intentForm.centralConflict = intent.centralConflict ?? ''
  intentForm.tonesText = intent.tones.join(', ')
  intentForm.targetWords = intent.targetWords ?? 120000
  intentForm.mustHaveText = intent.mustHave.join('\n')
}

const projectQuery = useQuery({
  queryKey: computed(() => ['project', props.projectId]),
  queryFn: () => getProject(props.projectId),
})

watch(() => projectQuery.data.value, (project) => {
  if (!sectionInitialized.value && project) {
    if (project.entryMode === 'MANUSCRIPT') activeSection.value = 'imports'
    sectionInitialized.value = true
  }

  if (project?.creativeIntent && intentLoadedProjectId.value !== project.id) {
    loadIntent(project)
    intentLoadedProjectId.value = project.id
  }
}, { immediate: true })

const directionsQuery = useQuery({
  queryKey: computed(() => ['story-directions', props.projectId]),
  queryFn: () => getLatestStoryDirections(props.projectId),
})

const storyBibleQuery = useQuery({
  queryKey: computed(() => ['story-bible', props.projectId]),
  queryFn: () => getLatestStoryBible(props.projectId),
})

const latestOutlineQuery = useQuery({
  queryKey: computed(() => ['outline', props.projectId]),
  queryFn: () => getLatestOutline(props.projectId),
})

const canPrefillFromImport = computed(() => Boolean(storyBibleQuery.data.value?.sourceImportId))

watch(
  () => directionsQuery.data.value,
  (value) => {
    selectedCandidateId.value = value?.selectedCandidateId ?? value?.directions[0]?.id ?? null
  },
  { immediate: true },
)

const navigation = [
  { value: 'writing', label: '写作', icon: PenLine, disabled: false },
  { value: 'outline', label: '大纲', icon: ListTree, disabled: false },
  { value: 'materials', label: '故事资料', icon: BookOpen, disabled: false },
  { value: 'relations', label: '关系', icon: Share2, disabled: false },
  { value: 'imports', label: '导入', icon: Upload, disabled: false },
  { value: 'runs', label: '任务', icon: Activity, disabled: false },
  { value: 'settings', label: '设置', icon: Settings, disabled: true },
] as const

function formatWords(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function updateDirectionsCache(value: StoryDirectionSet) {
  queryClient.setQueryData(['story-directions', props.projectId], value)
  selectedCandidateId.value = value.selectedCandidateId ?? value.directions[0]?.id ?? null
}

function splitTags(value: string) {
  return value.split(/[,，]/).map((item) => item.trim()).filter(Boolean)
}

function splitRequirements(value: string) {
  return value
    .split(/\r?\n/)
    .map((item) => item.replace(/^\s*(?:[-*•]|\d+[.、])\s*/, '').trim())
    .filter(Boolean)
}

function intentInput(project: ProjectSummary): CreativeIntentInput {
  return {
    premise: intentForm.premise.trim(),
    genres: splitTags(intentForm.genresText),
    targetAudience: intentForm.targetAudience.trim() || undefined,
    protagonistBrief: intentForm.protagonistBrief.trim(),
    centralConflict: intentForm.centralConflict.trim(),
    tones: splitTags(intentForm.tonesText),
    targetWords: intentForm.targetWords,
    endingPreference: project.creativeIntent?.endingPreference,
    mustHave: splitRequirements(intentForm.mustHaveText),
    avoid: project.creativeIntent?.avoid ?? [],
    stylePreferences: project.creativeIntent?.stylePreferences ?? [],
  }
}

function validateIntent() {
  if (!intentForm.premise.trim() || !intentForm.protagonistBrief.trim() || !intentForm.centralConflict.trim()) {
    throw new Error('请填写故事创意、主角简述和核心冲突。')
  }
  if (!Number.isFinite(intentForm.targetWords) || intentForm.targetWords < 1000) {
    throw new Error('目标字数不能少于 1000 字。')
  }
  const requirements = splitRequirements(intentForm.mustHaveText)
  if (requirements.length > 30) throw new Error('必须保留的信息最多填写 30 条。')
  if (requirements.some((item) => item.length > 300)) throw new Error('每条必须保留的信息不能超过 300 字。')
}

function prefillFromImportedPlanning() {
  const bible = storyBibleQuery.data.value
  if (!bible?.sourceImportId) {
    actionError.value = '当前项目还没有由导入素材生成的故事圣经。'
    return
  }
  const outline = latestOutlineQuery.data.value
  const prefill = createCreativeIntentPrefill(
    bible,
    outline?.sourceBibleVersionId === bible.id ? outline : null,
  )
  intentForm.premise = prefill.premise
  intentForm.genresText = prefill.genresText
  intentForm.targetAudience = prefill.targetAudience
  intentForm.protagonistBrief = prefill.protagonistBrief
  intentForm.centralConflict = prefill.centralConflict
  intentForm.tonesText = prefill.tonesText
  intentForm.targetWords = prefill.targetWords
  intentForm.mustHaveText = [
    ...splitRequirements(intentForm.mustHaveText),
    ...prefill.mustHave,
  ].filter((value, index, values) => values.indexOf(value) === index).join('\n')
  actionError.value = ''
  prefillNotice.value = '已从导入素材生成的故事圣经和大纲填入，请检查并补充必须保留的信息。'
}

async function saveIntentBeforeGeneration() {
  const project = projectQuery.data.value
  if (!project) throw new Error('项目尚未加载完成。')
  validateIntent()
  const updatedProject = await updateCreativeIntent(project, intentInput(project))
  queryClient.setQueryData<ProjectSummary>(['project', props.projectId], updatedProject)
}

function beginIntentEditing() {
  const project = projectQuery.data.value
  if (project) loadIntent(project)
  intentNotice.value = ''
  actionError.value = ''
  isEditingIntent.value = true
}

function cancelIntentEditing() {
  const project = projectQuery.data.value
  if (project) loadIntent(project)
  actionError.value = ''
  isEditingIntent.value = false
}

const saveIntentMutation = useMutation({
  mutationFn: saveIntentBeforeGeneration,
  onSuccess: () => {
    isEditingIntent.value = false
    intentNotice.value = '创作意图已保存。点击“生成新一版”后，新的故事方向才会使用这些信息。'
    actionError.value = ''
  },
  onError: (error: Error) => {
    actionError.value = error.message
  },
})

const setupAndGenerateMutation = useMutation({
  mutationFn: async () => {
    await saveIntentBeforeGeneration()
    return generateStoryDirections(props.projectId, instruction.value, modelProvider.value, 'REGENERATE')
  },
  onSuccess: (value) => {
    updateDirectionsCache(value)
    instruction.value = ''
    intentNotice.value = ''
    actionError.value = ''
  },
  onError: (error: Error) => {
    actionError.value = error.message
  },
})

const generateMutation = useMutation({
  mutationFn: async () => {
    await saveIntentBeforeGeneration()
    return generateStoryDirections(
      props.projectId,
      instruction.value,
      modelProvider.value,
      directionsQuery.data.value ? generationMode.value : 'REGENERATE',
    )
  },
  onSuccess: (value) => {
    updateDirectionsCache(value)
    instruction.value = ''
    intentNotice.value = ''
    actionError.value = ''
  },
  onError: (error: Error) => {
    actionError.value = error.message
  },
})

const selectMutation = useMutation({
  mutationFn: () => {
    const set = directionsQuery.data.value
    if (!set || !selectedCandidateId.value) {
      throw new Error('请先选择一个故事方向。')
    }
    return selectStoryDirection(props.projectId, set, selectedCandidateId.value)
  },
  onSuccess: (value) => {
    updateDirectionsCache(value)
    actionError.value = ''
  },
  onError: (error: Error) => {
    actionError.value = error.message
  },
})
</script>

<template>
  <div class="workspace">
    <aside class="workspace-nav" aria-label="项目导航">
      <button
        v-for="item in navigation"
        :key="item.value"
        type="button"
        :class="{ active: activeSection === item.value }"
        :disabled="item.disabled"
        @click="!item.disabled && (activeSection = item.value)"
      >
        <component :is="item.icon" :size="18" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </button>
    </aside>

    <section v-if="projectQuery.isPending.value" class="workspace-content loading-block">正在加载项目…</section>
    <section v-else-if="projectQuery.isError.value" class="workspace-content status-panel error-panel">
      <strong>项目加载失败</strong>
      <span>{{ projectQuery.error.value?.message }}</span>
    </section>
    <section v-else class="workspace-content">
      <header class="workspace-header">
        <div>
          <span class="eyebrow">正史 v{{ projectQuery.data.value?.currentCanonVersion }}</span>
          <h1>{{ projectQuery.data.value?.name }}</h1>
        </div>
        <span class="save-status">已保存</span>
      </header>

      <WritingWorkbench v-if="activeSection === 'writing'" :project-id="projectId" />
      <StoryMaterialsPanel v-else-if="activeSection === 'materials'" :project-id="projectId" />
      <RelationshipPanel v-else-if="activeSection === 'relations'" :project-id="projectId" />
      <WorkImportPanel v-else-if="activeSection === 'imports'" :project-id="projectId" @planning-generated="activeSection = 'outline'; planningView = 'outline'" />
      <AgentRunPanel v-else-if="activeSection === 'runs'" :project-id="projectId" />

      <div v-else class="direction-workbench">
        <div class="planning-tabs" role="tablist" aria-label="故事规划步骤">
          <button type="button" :class="{ active: planningView === 'directions' }" @click="planningView = 'directions'"><ListTree :size="16" />故事方向</button>
          <button type="button" :class="{ active: planningView === 'bible' }" @click="planningView = 'bible'"><ScrollText :size="16" />故事圣经</button>
          <button type="button" :class="{ active: planningView === 'outline' }" @click="planningView = 'outline'"><GitBranch :size="16" />分层大纲</button>
        </div>
        <div v-if="planningView === 'directions'">
        <div class="section-heading">
          <div>
            <span class="eyebrow">故事规划</span>
            <h2>故事方向</h2>
          </div>
          <div class="section-heading-actions">
            <span v-if="directionsQuery.data.value" class="version-label">
              第 {{ directionsQuery.data.value.generationNumber }} 版
            </span>
            <button
              v-if="projectQuery.data.value?.creativeIntent"
              class="button secondary compact-button"
              type="button"
              :disabled="isEditingIntent"
              @click="beginIntentEditing"
            ><Pencil :size="15" />编辑创作意图</button>
          </div>
        </div>

        <div v-if="directionsQuery.isPending.value" class="direction-loading">正在读取故事方向…</div>
        <div v-else-if="directionsQuery.isError.value" class="status-panel error-panel">
          <strong>故事方向加载失败</strong>
          <span>{{ directionsQuery.error.value?.message }}</span>
        </div>
        <template v-else-if="directionsQuery.data.value">
          <div v-if="directionsQuery.data.value.wordBudget" class="word-budget" aria-label="大纲字数预算">
            <span><strong>{{ formatWords(directionsQuery.data.value.wordBudget.targetWords) }}</strong>目标字数</span>
            <span><strong>{{ directionsQuery.data.value.wordBudget.recommendedVolumeCount }}</strong>建议卷数</span>
            <span><strong>{{ directionsQuery.data.value.wordBudget.recommendedChapterCount }}</strong>建议章节</span>
            <span><strong>{{ formatWords(directionsQuery.data.value.wordBudget.averageChapterWords) }}</strong>平均每章</span>
            <small>全书约 {{ formatWords(directionsQuery.data.value.wordBudget.acceptableMinWords) }}～{{ formatWords(directionsQuery.data.value.wordBudget.acceptableMaxWords) }} 字，单章可按剧情节奏浮动</small>
          </div>

          <div v-if="directionsQuery.data.value.questionsForAuthor.length" class="planning-question">
            <Sparkles :size="18" aria-hidden="true" />
            <span>{{ directionsQuery.data.value.questionsForAuthor[0] }}</span>
          </div>

          <section v-if="directionsQuery.data.value.changeSummary.length" class="change-summary">
            <strong>本版修改说明</strong>
            <ul><li v-for="item in directionsQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
          </section>

          <div class="direction-grid" role="radiogroup" aria-label="故事方向候选">
            <label
              v-for="candidate in directionsQuery.data.value.directions"
              :key="candidate.id"
              class="direction-card"
              :class="{ selected: selectedCandidateId === candidate.id }"
            >
              <input v-model="selectedCandidateId" type="radio" :value="candidate.id" />
              <span class="candidate-check"><Check :size="15" /></span>
              <span class="candidate-title">{{ candidate.title }}</span>
              <span class="candidate-premise">{{ candidate.premise }}</span>
              <span class="candidate-section"><strong>核心冲突</strong>{{ candidate.centralConflict }}</span>
              <span class="candidate-section"><strong>主角弧光</strong>{{ candidate.protagonistArc }}</span>
              <span class="candidate-section"><strong>故事结构</strong>{{ candidate.structure }}</span>
              <span class="candidate-section"><strong>结局方向</strong>{{ candidate.endingDirection }}</span>
              <span class="candidate-list strengths">
                <strong>优势</strong>
                <span v-for="item in candidate.strengths" :key="item">{{ item }}</span>
              </span>
              <span class="candidate-list risks">
                <strong><TriangleAlert :size="14" />风险</strong>
                <span v-for="item in candidate.risks" :key="item">{{ item }}</span>
              </span>
            </label>
          </div>

          <div v-if="directionsQuery.data.value.status === 'SELECTED'" class="selection-notice">
            <Check :size="17" aria-hidden="true" />
            当前故事方向已确认，可以继续生成故事圣经。
          </div>
        </template>

        <form v-else-if="!projectQuery.data.value?.creativeIntent" class="intent-setup" @submit.prevent="setupAndGenerateMutation.mutate()">
          <div class="intent-setup-heading">
            <Sparkles :size="22" aria-hidden="true" />
            <div><h3>先补充创作意图</h3><p>这些信息会成为故事方向的生成依据，之后仍可继续调整。</p></div>
            <button
              v-if="canPrefillFromImport"
              class="button secondary intent-prefill-button"
              type="button"
              @click="prefillFromImportedPlanning"
            ><Sparkles :size="16" />从导入规划自动填写</button>
          </div>
          <div v-if="prefillNotice" class="selection-notice intent-prefill-notice"><Check :size="17" />{{ prefillNotice }}</div>
          <div class="intent-setup-grid">
            <label class="field full-span"><span>一句话创意</span><textarea v-model="intentForm.premise" rows="2" maxlength="2000" placeholder="这是一个什么故事？" /></label>
            <label class="field"><span>小说类型</span><input v-model="intentForm.genresText" maxlength="300" placeholder="用逗号分隔" /></label>
            <label class="field"><span>目标读者</span><input v-model="intentForm.targetAudience" maxlength="300" /></label>
            <label class="field full-span"><span>主角简述</span><textarea v-model="intentForm.protagonistBrief" rows="2" maxlength="2000" placeholder="主角是谁，他想得到什么？" /></label>
            <label class="field full-span"><span>核心冲突</span><textarea v-model="intentForm.centralConflict" rows="2" maxlength="2000" placeholder="什么阻碍主角实现目标？" /></label>
            <label class="field"><span>故事基调</span><input v-model="intentForm.tonesText" maxlength="300" placeholder="用逗号分隔" /></label>
            <label class="field"><span>目标字数</span><input v-model.number="intentForm.targetWords" type="number" min="1000" max="10000000" step="1000" /></label>
            <label class="field full-span"><span>必须保留的信息（每行一条）</span><textarea v-model="intentForm.mustHaveText" rows="5" maxlength="9000" placeholder="例如：考试成绩公布前，男主就暗自希望和喜欢的女生成为同桌。&#10;第二排从左到右依次是：女学生1、女学生2、男主喜欢的女生、男主、喜欢男主的女生。" /></label>
            <label class="field full-span"><span>本次生成要求（可选）</span><textarea v-model="instruction" rows="2" maxlength="1000" placeholder="例如：减少悬疑，更突出人物成长" /></label>
          </div>
          <div class="intent-setup-actions">
            <label class="provider-field"><span>生成模型</span><select v-model="modelProvider"><option value="LOCAL_CODEX">服务端 Codex</option><option value="DEEPSEEK">DeepSeek</option><option value="LOCAL_TEMPLATE">本地模板</option></select></label>
            <button class="button primary" type="submit" :disabled="setupAndGenerateMutation.isPending.value"><Sparkles :size="16" />{{ setupAndGenerateMutation.isPending.value ? '正在保存并生成…' : '保存并生成故事方向' }}</button>
          </div>
        </form>

        <div v-else class="direction-empty">
          <Sparkles :size="30" aria-hidden="true" />
          <h3>从创作意图生成三个方向</h3>
          <p>方案会在人物关系、悬念结构和群像展开上形成明显差异。</p>
        </div>

        <form
          v-if="projectQuery.data.value?.creativeIntent && isEditingIntent"
          class="intent-setup intent-editor"
          @submit.prevent="saveIntentMutation.mutate()"
        >
          <div class="intent-setup-heading">
            <Pencil :size="22" aria-hidden="true" />
            <div><h3>编辑创作意图</h3><p>保存只更新项目输入，不会自动覆盖已有故事方向、故事圣经或大纲。</p></div>
          </div>
          <div class="intent-setup-grid">
            <label class="field full-span"><span>一句话创意</span><textarea v-model="intentForm.premise" rows="2" maxlength="2000" placeholder="这是一个什么故事？" /></label>
            <label class="field"><span>小说类型</span><input v-model="intentForm.genresText" maxlength="300" placeholder="用逗号分隔" /></label>
            <label class="field"><span>目标读者</span><input v-model="intentForm.targetAudience" maxlength="300" /></label>
            <label class="field full-span"><span>主角简述</span><textarea v-model="intentForm.protagonistBrief" rows="2" maxlength="2000" placeholder="主角是谁，他想得到什么？" /></label>
            <label class="field full-span"><span>核心冲突</span><textarea v-model="intentForm.centralConflict" rows="2" maxlength="2000" placeholder="什么阻碍主角实现目标？" /></label>
            <label class="field"><span>故事基调</span><input v-model="intentForm.tonesText" maxlength="300" placeholder="用逗号分隔" /></label>
            <label class="field"><span>目标字数</span><input v-model.number="intentForm.targetWords" type="number" min="1000" max="10000000" step="1000" /></label>
            <label class="field full-span"><span>必须保留的信息（每行一条）</span><textarea v-model="intentForm.mustHaveText" rows="5" maxlength="9000" placeholder="填写不可遗漏的时间顺序、人物关系、空间布局等事实" /></label>
          </div>
          <div class="intent-setup-actions">
            <button class="button secondary" type="button" :disabled="saveIntentMutation.isPending.value" @click="cancelIntentEditing">取消</button>
            <button class="button primary" type="submit" :disabled="saveIntentMutation.isPending.value"><Check :size="16" />{{ saveIntentMutation.isPending.value ? '正在保存…' : '保存创作意图' }}</button>
          </div>
        </form>

        <div v-if="projectQuery.data.value?.creativeIntent && !isEditingIntent" class="direction-actions">
          <label class="instruction-field must-have-field">
            <span>必须保留的信息（每行一条）</span>
            <textarea
              v-model="intentForm.mustHaveText"
              rows="5"
              maxlength="9000"
              placeholder="填写不可遗漏的时间顺序、人物关系、空间布局等事实"
            ></textarea>
            <small>会保存到项目创作意图，并约束每个候选方向及后续故事圣经。</small>
          </label>
          <label class="instruction-field">
            <span>本次调整要求</span>
            <textarea
              v-model="instruction"
              rows="2"
              maxlength="1000"
              placeholder="可选，例如：减少悬疑，更突出女性成长"
            ></textarea>
          </label>
          <label class="provider-field">
            <span>生成方式</span>
            <select v-model="generationMode" :disabled="!directionsQuery.data.value">
              <option value="REVISE">基于当前版本调整</option>
              <option value="REGENERATE">重新生成</option>
            </select>
          </label>
          <label class="provider-field">
            <span>生成模型</span>
            <select v-model="modelProvider">
              <option value="LOCAL_CODEX">服务端 Codex</option>
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="LOCAL_TEMPLATE">本地模板</option>
            </select>
          </label>
          <div class="direction-action-buttons">
            <button
              v-if="directionsQuery.data.value"
              class="button secondary"
              type="button"
              :disabled="generateMutation.isPending.value"
              @click="generateMutation.mutate()"
            >
              <RefreshCw :size="16" />
              {{ generateMutation.isPending.value ? '正在生成…' : generationMode === 'REVISE' ? '按要求调整' : '重新生成' }}
            </button>
            <button
              v-else
              class="button primary"
              type="button"
              :disabled="generateMutation.isPending.value"
              @click="generateMutation.mutate()"
            >
              <Sparkles :size="16" />
              {{ generateMutation.isPending.value ? '正在生成…' : '生成故事方向' }}
            </button>
            <button
              v-if="directionsQuery.data.value"
              class="button primary"
              type="button"
              :disabled="!selectedCandidateId || selectMutation.isPending.value"
              @click="selectMutation.mutate()"
            >
              <Check :size="16" />
              {{ selectMutation.isPending.value ? '正在确认…' : '确认所选方向' }}
            </button>
          </div>
        </div>

        <div v-if="intentNotice" class="selection-notice"><Check :size="17" />{{ intentNotice }}</div>
        <div v-if="actionError" class="form-error" role="alert">{{ actionError }}</div>
        <p v-if="directionsQuery.data.value" class="generator-note">
          本版本由 {{ directionsQuery.data.value.generatorType }} 生成
        </p>
        </div>
        <StoryBiblePanel v-else-if="planningView === 'bible'" :project-id="projectId" />
        <OutlinePanel v-else :project-id="projectId" />
      </div>
    </section>
  </div>
</template>

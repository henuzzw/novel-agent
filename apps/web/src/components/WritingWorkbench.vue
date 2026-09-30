<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { BrainCircuit, Check, Download, FileSearch, FileText, RefreshCw, Save, ScrollText } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import { getCurrentOutline, type GenerationMode, type ModelProvider } from '@/api/planning'
import { getProject } from '@/api/projects'
import {
  acceptManuscript, approveContract, approveReview, commitCanon, generateContract, generateManuscript, generateReview,
  getLatestContract, getLatestManuscript, getLatestReview, getMemoryPreview, updateContract, updateManuscript, updateReview,
  listCanonEntities, manuscriptExportUrl,
  type ChapterContractContent, type ChapterContractVersion,
  type ChapterReviewContent, type ChapterReviewVersion, type FactProposal, type ManuscriptContent, type ManuscriptVersion,
  type StoryEntity,
} from '@/api/writing'

const props = defineProps<{ projectId: string }>()
const queryClient = useQueryClient()
const selectedChapter = ref(1)
const mode = ref<'contract' | 'manuscript' | 'review' | 'memory'>('contract')
const provider = ref<ModelProvider>('LOCAL_CODEX')
const manuscriptGenerationMode = ref<GenerationMode>('REVISE')
const instruction = ref('')
const error = ref('')
const contractDraft = ref<ChapterContractContent | null>(null)
const manuscriptDraft = ref<ManuscriptContent | null>(null)
const reviewDraft = ref<ChapterReviewContent | null>(null)
const committedCanonVersion = ref<number | null>(null)

const outlineQuery = useQuery({ queryKey: computed(() => ['current-outline', props.projectId]), queryFn: () => getCurrentOutline(props.projectId) })
const projectQuery = useQuery({ queryKey: computed(() => ['project', props.projectId]), queryFn: () => getProject(props.projectId) })
const currentChapter = computed(() => outlineQuery.data.value?.content.arcs
  .flatMap((arc) => arc.chapters).find((chapter) => chapter.number === selectedChapter.value))
const memorySearchText = computed(() => {
  const chapter = currentChapter.value
  return chapter ? `${chapter.title} ${chapter.objective} ${chapter.coreEvent}` : `第 ${selectedChapter.value} 章`
})
const memoryQuery = useQuery({
  queryKey: computed(() => ['novel-memory', props.projectId, selectedChapter.value,
    projectQuery.data.value?.currentCanonVersion, provider.value]),
  queryFn: () => getMemoryPreview(props.projectId, selectedChapter.value, memorySearchText.value, provider.value),
  enabled: computed(() => mode.value === 'memory' && (projectQuery.data.value?.currentCanonVersion ?? 0) > 0),
})
const contractQuery = useQuery({
  queryKey: computed(() => ['chapter-contract', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestContract(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const manuscriptQuery = useQuery({
  queryKey: computed(() => ['manuscript', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestManuscript(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const reviewQuery = useQuery({
  queryKey: computed(() => ['chapter-review', props.projectId, selectedChapter.value]),
  queryFn: () => getLatestReview(props.projectId, selectedChapter.value),
  enabled: computed(() => outlineQuery.data.value?.status === 'PUBLISHED'),
})
const entityQuery = useQuery({
  queryKey: computed(() => ['canon-entities', props.projectId]),
  queryFn: () => listCanonEntities(props.projectId),
  enabled: computed(() => mode.value === 'review'),
})

function copyContract(value: ChapterContractContent): ChapterContractContent {
  return {
    ...value,
    locations: [...value.locations], requiredBeats: [...value.requiredBeats],
    requiredReveals: [...value.requiredReveals], forbiddenFacts: [...value.forbiddenFacts],
    foreshadowActions: [...value.foreshadowActions],
  }
}
function copyManuscript(value: ManuscriptContent): ManuscriptContent {
  return { ...value, continuityNotes: [...value.continuityNotes] }
}
function copyReview(value: ChapterReviewContent): ChapterReviewContent {
  return { ...value, issues: value.issues.map((item) => ({ ...item })), factProposals: value.factProposals.map((item) => ({ ...item, payload: item.payload ? { ...item.payload } : null })) }
}
watch(() => contractQuery.data.value, (value) => { contractDraft.value = value ? copyContract(value.content) : null }, { immediate: true })
watch(() => manuscriptQuery.data.value, (value) => { manuscriptDraft.value = value ? copyManuscript(value.content) : null }, { immediate: true })
watch(() => reviewQuery.data.value, (value) => { reviewDraft.value = value ? copyReview(value.content) : null }, { immediate: true })
watch(selectedChapter, () => { error.value = ''; instruction.value = '' })

function setContract(value: ChapterContractVersion) {
  queryClient.setQueryData(['chapter-contract', props.projectId, selectedChapter.value], value)
  contractDraft.value = copyContract(value.content)
}
function setManuscript(value: ManuscriptVersion) {
  queryClient.setQueryData(['manuscript', props.projectId, selectedChapter.value], value)
  manuscriptDraft.value = copyManuscript(value.content)
}
function setReview(value: ChapterReviewVersion) {
  queryClient.setQueryData(['chapter-review', props.projectId, selectedChapter.value], value)
  reviewDraft.value = copyReview(value.content)
}
function lines(value: string) { return value.split('\n').map((item) => item.trim()).filter(Boolean) }
function joined(value: string[] | undefined) { return value?.join('\n') ?? '' }
function toolLabel(value: string) {
  return ({
    GET_RECENT_CHAPTER_SUMMARIES: '近期章节',
    SEARCH_STORY_MEMORY: '语义历史检索',
    GET_RELATED_CANON_FACTS: '相关正史事实',
  } as Record<string, string>)[value] ?? value
}
function factTypeLabel(value: string) {
  return ({
    ENTITY_UPSERT: '实体', EVENT_CREATE: '事件', STATE_CHANGE: '状态变化',
    RELATION_CHANGE: '关系变化', KNOWLEDGE_CHANGE: '人物认知', FORESHADOW_CHANGE: '伏笔',
    EVENT: '事件', STATE: '状态变化',
  } as Record<string, string>)[value] ?? value
}
type EntityRole = 'entity' | 'source' | 'target' | 'character'
function entityTypeFor(fact: FactProposal, role: EntityRole) {
  if (role === 'entity') return fact.payload?.entityType ?? 'CHARACTER'
  return 'CHARACTER'
}
function entityOptions(fact: FactProposal, role: EntityRole): StoryEntity[] {
  const type = entityTypeFor(fact, role)
  return (entityQuery.data.value ?? []).filter((entity) => entity.type === type)
}
function selectedEntityId(fact: FactProposal, role: EntityRole) {
  if (!fact.payload) return ''
  if (role === 'entity') return fact.payload.entityId ?? ''
  if (role === 'source') return fact.payload.sourceEntityId ?? ''
  if (role === 'target') return fact.payload.targetEntityId ?? ''
  return fact.payload.characterId ?? ''
}
function setEntityId(fact: FactProposal, role: EntityRole, event: Event) {
  if (!fact.payload) return
  const value = (event.target as HTMLSelectElement).value || null
  if (role === 'entity') fact.payload.entityId = value
  else if (role === 'source') fact.payload.sourceEntityId = value
  else if (role === 'target') fact.payload.targetEntityId = value
  else fact.payload.characterId = value
}
function mentionName(fact: FactProposal, role: EntityRole) {
  if (!fact.payload) return fact.subject
  if (role === 'entity') return fact.payload.entityName ?? fact.subject
  if (role === 'source') return fact.payload.sourceEntityName ?? fact.subject
  if (role === 'target') return fact.payload.targetEntityName ?? fact.object
  return fact.payload.characterName ?? fact.subject
}
function emptyEntityOption(fact: FactProposal, role: EntityRole) {
  return ['他', '她', '它', '他们', '她们', '它们', '自己', '对方', '那个人', '这个人'].includes(mentionName(fact, role))
    ? '必须选择具体实体'
    : '按名称匹配或创建新实体'
}
function updateList(field: keyof ChapterContractContent, event: Event) {
  if (contractDraft.value) (contractDraft.value[field] as string[]) = lines((event.target as HTMLTextAreaElement).value)
}
function updateNotes(event: Event) { if (manuscriptDraft.value) manuscriptDraft.value.continuityNotes = lines((event.target as HTMLTextAreaElement).value) }
const busy = computed(() => generateContractMutation.isPending.value || saveContractMutation.isPending.value
  || approveContractMutation.isPending.value || generateManuscriptMutation.isPending.value
  || saveManuscriptMutation.isPending.value || acceptManuscriptMutation.isPending.value
  || generateReviewMutation.isPending.value || saveReviewMutation.isPending.value || approveReviewMutation.isPending.value
  || commitCanonMutation.isPending.value)
const fail = (reason: Error) => { error.value = reason.message }

const generateContractMutation = useMutation({ mutationFn: () => generateContract(props.projectId, selectedChapter.value, provider.value, instruction.value), onSuccess: (v) => { setContract(v); instruction.value = ''; error.value = '' }, onError: fail })
const saveContractMutation = useMutation({ mutationFn: () => {
  if (!contractQuery.data.value || !contractDraft.value) throw new Error('没有可保存的章节合同。')
  return updateContract(props.projectId, contractQuery.data.value, contractDraft.value)
}, onSuccess: setContract, onError: fail })
const approveContractMutation = useMutation({ mutationFn: () => {
  if (!contractQuery.data.value) throw new Error('没有可确认的章节合同。')
  return approveContract(props.projectId, contractQuery.data.value)
}, onSuccess: setContract, onError: fail })
const generateManuscriptMutation = useMutation({ mutationFn: () => generateManuscript(
  props.projectId, selectedChapter.value, provider.value, instruction.value,
  manuscriptQuery.data.value ? manuscriptGenerationMode.value : 'REGENERATE',
), onSuccess: (v) => { setManuscript(v); instruction.value = ''; error.value = '' }, onError: fail })
const saveManuscriptMutation = useMutation({ mutationFn: () => {
  if (!manuscriptQuery.data.value || !manuscriptDraft.value) throw new Error('没有可保存的正文。')
  return updateManuscript(props.projectId, manuscriptQuery.data.value, manuscriptDraft.value)
}, onSuccess: setManuscript, onError: fail })
const acceptManuscriptMutation = useMutation({ mutationFn: () => {
  if (!manuscriptQuery.data.value) throw new Error('没有可确认的正文。')
  return acceptManuscript(props.projectId, manuscriptQuery.data.value)
}, onSuccess: setManuscript, onError: fail })
const generateReviewMutation = useMutation({ mutationFn: () => generateReview(props.projectId, selectedChapter.value, provider.value, instruction.value), onSuccess: (v) => { setReview(v); instruction.value = ''; error.value = '' }, onError: fail })
const saveReviewMutation = useMutation({ mutationFn: () => {
  if (!reviewQuery.data.value || !reviewDraft.value) throw new Error('没有可保存的审稿结果。')
  return updateReview(props.projectId, reviewQuery.data.value, reviewDraft.value)
}, onSuccess: setReview, onError: fail })
const approveReviewMutation = useMutation({ mutationFn: () => {
  if (!reviewQuery.data.value) throw new Error('没有可确认的审稿结果。')
  return approveReview(props.projectId, reviewQuery.data.value)
}, onSuccess: setReview, onError: fail })
const commitCanonMutation = useMutation({ mutationFn: () => {
  if (!reviewQuery.data.value || reviewQuery.data.value.status !== 'APPROVED') throw new Error('请先确认审稿结果。')
  return commitCanon(props.projectId, selectedChapter.value, reviewQuery.data.value.id, projectQuery.data.value?.currentCanonVersion ?? 0)
}, onSuccess: (value) => {
  committedCanonVersion.value = value.canonVersion; error.value = ''
  queryClient.invalidateQueries({ queryKey: ['project', props.projectId] })
  queryClient.invalidateQueries({ queryKey: ['novel-memory', props.projectId] })
}, onError: fail })
</script>

<template>
  <div v-if="outlineQuery.isPending.value" class="direction-loading">正在读取已发布大纲…</div>
  <div v-else-if="!outlineQuery.data.value || outlineQuery.data.value.status !== 'PUBLISHED'" class="direction-empty">
    <FileText :size="30" /><h3>先发布分层大纲</h3><p>写作工作台会从已发布大纲中读取章节。</p>
  </div>
  <div v-else class="writing-workbench">
    <aside class="chapter-rail">
      <div class="chapter-rail-title">章节</div>
      <section v-for="arc in outlineQuery.data.value.content.arcs" :key="arc.ordinal">
        <strong>{{ arc.title }}</strong>
        <button v-for="chapter in arc.chapters" :key="chapter.number" type="button"
          :class="{ active: selectedChapter === chapter.number }" @click="selectedChapter = chapter.number">
          <span>{{ chapter.number }}</span><span>{{ chapter.title }}</span>
        </button>
      </section>
    </aside>

    <main class="chapter-editor">
      <div class="writing-toolbar">
        <div class="planning-tabs" role="tablist">
          <button type="button" :class="{ active: mode === 'contract' }" @click="mode = 'contract'"><ScrollText :size="16" />章节合同</button>
          <button type="button" :class="{ active: mode === 'manuscript' }" @click="mode = 'manuscript'"><FileText :size="16" />正文草稿</button>
          <button type="button" :class="{ active: mode === 'review' }" @click="mode = 'review'"><FileSearch :size="16" />审稿与记忆</button>
          <button type="button" :class="{ active: mode === 'memory' }" @click="mode = 'memory'"><BrainCircuit :size="16" />长期记忆</button>
        </div>
        <span>第 {{ selectedChapter }} 章</span>
      </div>

      <template v-if="mode === 'contract'">
        <div class="editor-status"><strong>章节合同</strong><span v-if="contractQuery.data.value">第 {{ contractQuery.data.value.versionNumber }} 版 · {{ contractQuery.data.value.status === 'APPROVED' ? '已确认' : '草稿' }}</span></div>
        <div v-if="contractDraft" class="contract-grid">
          <label><span>章节标题</span><input v-model="contractDraft.chapterTitle" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>视角人物</span><input v-model="contractDraft.pov" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label class="full"><span>章节目标</span><textarea v-model="contractDraft.objective" rows="2" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>故事时间</span><input v-model="contractDraft.storyTime" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>退出状态</span><input v-model="contractDraft.expectedExitState" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <label><span>必写节拍</span><textarea :value="joined(contractDraft.requiredBeats)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('requiredBeats', $event)" /></label>
          <label><span>必要揭示</span><textarea :value="joined(contractDraft.requiredReveals)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('requiredReveals', $event)" /></label>
          <label><span>禁止事实</span><textarea :value="joined(contractDraft.forbiddenFacts)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('forbiddenFacts', $event)" /></label>
          <label><span>伏笔动作</span><textarea :value="joined(contractDraft.foreshadowActions)" rows="4" :disabled="contractQuery.data.value?.status === 'APPROVED'" @input="updateList('foreshadowActions', $event)" /></label>
          <label class="full"><span>结尾钩子</span><textarea v-model="contractDraft.hook" rows="2" :disabled="contractQuery.data.value?.status === 'APPROVED'" /></label>
          <div class="word-range">建议 {{ contractDraft.suggestedMinWords }}～{{ contractDraft.suggestedMaxWords }} 字，可按剧情自然浮动</div>
        </div>
        <div v-else class="editor-empty">为本章生成一份可编辑的写作合同。</div>
      </template>

      <template v-else-if="mode === 'manuscript'">
        <div class="editor-status"><strong>正文草稿</strong><span v-if="manuscriptQuery.data.value">第 {{ manuscriptQuery.data.value.versionNumber }} 版 · {{ manuscriptQuery.data.value.status === 'AUTHOR_ACCEPTED' ? '作者已确认' : '草稿' }}</span><a v-if="manuscriptQuery.data.value" class="button secondary compact" :href="manuscriptExportUrl(projectId, selectedChapter, manuscriptQuery.data.value.id)"><Download :size="15" />导出本章</a></div>
        <div v-if="manuscriptDraft" class="manuscript-form">
          <section v-if="manuscriptQuery.data.value?.changeSummary.length" class="change-summary">
            <strong>本版修改说明</strong>
            <ul><li v-for="item in manuscriptQuery.data.value.changeSummary" :key="item">{{ item }}</li></ul>
          </section>
          <input v-model="manuscriptDraft.title" class="manuscript-title" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" />
          <textarea v-model="manuscriptDraft.body" class="manuscript-body" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" />
          <label><span>章节摘要</span><textarea v-model="manuscriptDraft.summary" rows="3" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" /></label>
          <label><span>连续性备注</span><textarea :value="joined(manuscriptDraft.continuityNotes)" rows="3" :disabled="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" @input="updateNotes" /></label>
          <p v-if="manuscriptQuery.data.value?.status === 'AUTHOR_ACCEPTED'" class="acceptance-note"><Check :size="16" />作者已确认。待审稿与记忆抽取通过后，才会写入正史。</p>
        </div>
        <div v-else class="editor-empty">确认章节合同后，即可生成本章正文草稿。</div>
      </template>

      <template v-else-if="mode === 'review'">
        <div class="editor-status"><strong>审稿与候选记忆</strong><span v-if="reviewQuery.data.value">第 {{ reviewQuery.data.value.versionNumber }} 版 · {{ reviewQuery.data.value.status === 'APPROVED' ? '已确认' : '待处理' }}</span></div>
        <div v-if="reviewDraft" class="review-panel">
          <p class="review-summary">{{ reviewDraft.summary }}</p>
          <section><h3>问题清单</h3>
            <article v-for="issue in reviewDraft.issues" :key="issue.id" class="review-item">
              <header><span :class="['severity', issue.severity.toLowerCase()]">{{ issue.severity }}</span><strong>{{ issue.category }}</strong></header>
              <p>{{ issue.description }}</p><blockquote>{{ issue.evidence }}</blockquote><small>{{ issue.suggestion }}</small>
              <label v-if="reviewQuery.data.value?.status === 'DRAFT'" class="resolve-check"><input v-model="issue.resolved" type="checkbox" />已处理</label>
            </article>
            <p v-if="!reviewDraft.issues.length" class="quiet-text">未发现需要处理的问题。</p>
          </section>
          <section><h3>候选事实</h3>
            <article v-for="fact in reviewDraft.factProposals" :key="fact.id" class="fact-item">
              <div><span>{{ factTypeLabel(fact.factType) }}</span><strong>{{ fact.subject }} · {{ fact.predicate }}</strong></div>
              <p>{{ fact.object }}</p><small>证据：{{ fact.evidence }}</small>
              <small v-if="fact.confidence != null">置信度：{{ Math.round(fact.confidence * 100) }}%</small>
              <div v-if="fact.payload && ['ENTITY_UPSERT', 'STATE_CHANGE'].includes(fact.factType)" class="entity-resolution-row">
                <label><span>“{{ mentionName(fact, 'entity') }}”对应实体</span>
                  <select :value="selectedEntityId(fact, 'entity')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'entity', $event)">
                    <option value="">{{ emptyEntityOption(fact, 'entity') }}</option>
                    <option v-for="entity in entityOptions(fact, 'entity')" :key="entity.id" :value="entity.id">{{ entity.name }}</option>
                  </select>
                </label>
              </div>
              <div v-else-if="fact.payload && fact.factType === 'RELATION_CHANGE'" class="entity-resolution-row two">
                <label><span>“{{ mentionName(fact, 'source') }}”</span><select :value="selectedEntityId(fact, 'source')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'source', $event)"><option value="">{{ emptyEntityOption(fact, 'source') }}</option><option v-for="entity in entityOptions(fact, 'source')" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
                <label><span>“{{ mentionName(fact, 'target') }}”</span><select :value="selectedEntityId(fact, 'target')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'target', $event)"><option value="">{{ emptyEntityOption(fact, 'target') }}</option><option v-for="entity in entityOptions(fact, 'target')" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
              </div>
              <div v-else-if="fact.payload && fact.factType === 'KNOWLEDGE_CHANGE'" class="entity-resolution-row">
                <label><span>“{{ mentionName(fact, 'character') }}”对应人物</span><select :value="selectedEntityId(fact, 'character')" :disabled="reviewQuery.data.value?.status === 'APPROVED'" @change="setEntityId(fact, 'character', $event)"><option value="">{{ emptyEntityOption(fact, 'character') }}</option><option v-for="entity in entityOptions(fact, 'character')" :key="entity.id" :value="entity.id">{{ entity.name }}</option></select></label>
              </div>
              <select v-model="fact.decision" :disabled="reviewQuery.data.value?.status === 'APPROVED'">
                <option value="PENDING">待决定</option><option value="ACCEPTED">接受</option><option value="REJECTED">拒绝</option>
              </select>
            </article>
          </section>
          <p v-if="reviewQuery.data.value?.status === 'APPROVED'" class="acceptance-note"><Check :size="16" />审稿门禁已通过，下一步可提交正史。</p>
          <p v-if="committedCanonVersion" class="acceptance-note"><Check :size="16" />已提交为正史 v{{ committedCanonVersion }}，检索与图谱投影正在后台同步。</p>
        </div>
        <div v-else class="editor-empty">作者确认正文后，运行一致性审稿并抽取候选事实。</div>
      </template>

      <template v-else>
        <div class="editor-status"><strong>第 {{ selectedChapter }} 章可用记忆</strong><span>正史 V{{ projectQuery.data.value?.currentCanonVersion ?? 0 }}</span></div>
        <div v-if="(projectQuery.data.value?.currentCanonVersion ?? 0) === 0" class="editor-empty">提交第一章正史后，长期记忆会从下一章开始参与创作。</div>
        <div v-else-if="memoryQuery.isPending.value" class="editor-empty">正在召回相关正文与事实…</div>
        <div v-else-if="memoryQuery.data.value" class="memory-panel">
          <div class="memory-usage">
            <span>正文阶段约 {{ memoryQuery.data.value.usage.estimatedTokens }} / {{ memoryQuery.data.value.usage.budgetTokens }} Token</span>
            <strong v-if="memoryQuery.data.value.usage.truncated">已按预算裁剪</strong>
            <strong v-else>预算充足</strong>
          </div>
          <p class="quiet-text">只读工具：{{ memoryQuery.data.value.usage.toolsUsed.map(toolLabel).join('、') || '无' }}</p>
          <section>
            <h3>相关历史正文</h3>
            <article v-for="item in memoryQuery.data.value.semanticMemories" :key="`${item.chapterNumber}-${item.canonVersion}`" class="memory-item">
              <header><strong>第 {{ item.chapterNumber }} 章</strong><span>相关度 {{ Math.round(item.similarity * 100) }}%</span></header>
              <p class="memory-summary">{{ item.summary }}</p>
              <details v-if="item.content"><summary>查看相关片段</summary><p>{{ item.content }}</p></details>
            </article>
            <p v-if="!memoryQuery.data.value.semanticMemories.length" class="quiet-text">此前章节中暂无可召回的正文。</p>
          </section>
          <section>
            <h3>已确认事实</h3>
            <article v-for="(fact, index) in memoryQuery.data.value.graphFacts" :key="`${fact.canonVersion}-${index}`" class="memory-fact">
              <strong>{{ fact.subject }}</strong><span>{{ fact.predicate }}</span><p>{{ fact.object }}</p>
              <small v-if="fact.evidence">依据：{{ fact.evidence }}</small>
            </article>
            <p v-if="!memoryQuery.data.value.graphFacts.length" class="quiet-text">暂无已确认事实。</p>
          </section>
        </div>
      </template>

      <div v-if="mode !== 'memory'" class="writing-actions">
        <label class="instruction-field"><span>本次写作要求</span><textarea v-model="instruction" rows="2" placeholder="可选，例如：增强对话张力，减少解释" /></label>
        <label v-if="mode === 'manuscript' && manuscriptQuery.data.value" class="provider-field"><span>生成方式</span><select v-model="manuscriptGenerationMode"><option value="REVISE">基于当前正文调整</option><option value="REGENERATE">重新创作一版</option></select></label>
        <label class="provider-field"><span>生成模型</span><select v-model="provider"><option value="LOCAL_CODEX">服务端 Codex</option><option value="DEEPSEEK">DeepSeek</option><option value="LOCAL_TEMPLATE">本地模板</option></select></label>
        <div class="direction-action-buttons" v-if="mode === 'contract'">
          <button class="button secondary" type="button" :disabled="busy" @click="generateContractMutation.mutate()"><RefreshCw :size="16" />{{ contractQuery.data.value ? '生成新一版' : '生成合同' }}</button>
          <button v-if="contractQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy" @click="saveContractMutation.mutate()"><Save :size="16" />保存</button>
          <button v-if="contractQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy" @click="approveContractMutation.mutate()"><Check :size="16" />确认合同</button>
        </div>
        <div class="direction-action-buttons" v-else-if="mode === 'manuscript'">
          <button class="button secondary" type="button" :disabled="busy || contractQuery.data.value?.status !== 'APPROVED'" @click="generateManuscriptMutation.mutate()"><RefreshCw :size="16" />{{ generateManuscriptMutation.isPending.value ? '正在生成…' : manuscriptQuery.data.value ? manuscriptGenerationMode === 'REVISE' ? '按要求调整' : '重新创作' : '生成正文' }}</button>
          <button v-if="manuscriptQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy" @click="saveManuscriptMutation.mutate()"><Save :size="16" />保存</button>
          <button v-if="manuscriptQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy" @click="acceptManuscriptMutation.mutate()"><Check :size="16" />作者确认</button>
        </div>
        <div class="direction-action-buttons" v-else-if="mode === 'review'">
          <button class="button secondary" type="button" :disabled="busy || manuscriptQuery.data.value?.status !== 'AUTHOR_ACCEPTED'" @click="generateReviewMutation.mutate()"><RefreshCw :size="16" />{{ reviewQuery.data.value ? '重新审稿' : '开始审稿' }}</button>
          <button v-if="reviewQuery.data.value?.status === 'DRAFT'" class="button secondary" type="button" :disabled="busy" @click="saveReviewMutation.mutate()"><Save :size="16" />保存处理</button>
          <button v-if="reviewQuery.data.value?.status === 'DRAFT'" class="button primary" type="button" :disabled="busy" @click="approveReviewMutation.mutate()"><Check :size="16" />确认审稿</button>
          <button v-if="reviewQuery.data.value?.status === 'APPROVED'" class="button primary" type="button" :disabled="busy" @click="commitCanonMutation.mutate()"><Check :size="16" />{{ commitCanonMutation.isPending.value ? '正在提交…' : '提交正史' }}</button>
        </div>
      </div>
      <div v-if="error" class="form-error" role="alert">{{ error }}</div>
    </main>
  </div>
</template>

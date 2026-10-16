<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, ChevronLeft, ChevronRight, Eye, MessageSquare, Plus, RefreshCw, Send, Square, Undo2, X } from 'lucide-vue-next'
import { decideChatChange, getChatChanges, getChatSources, getChatTurns, readChatSource, sendChat, stopChat, type ChatChange } from '@/api/projectChat'
import { createUuid } from '@/lib/uuid'

const props = defineProps<{ projectId: string }>()
const client = useQueryClient()
const question = ref('')
const error = ref('')
const tab = ref<'changes' | 'sources'>('changes')
const filter = ref('')
const selected = ref<string | null>(null)
const sourceKey = ref('')
const offset = ref(0)
const turns = useQuery({ queryKey: computed(() => ['project-chat-turns', props.projectId]), queryFn: () => getChatTurns(props.projectId),
  refetchInterval: query => query.state.data?.some(turn => turn.status === 'RUNNING') ? 2000 : false })
const busy = computed(() => turns.data.value?.some(turn => turn.status === 'RUNNING') ?? false)
const changes = useQuery({ queryKey: computed(() => ['project-chat-changes', props.projectId]), queryFn: () => getChatChanges(props.projectId),
  refetchInterval: () => busy.value ? 2000 : false })
const sources = useQuery({ queryKey: computed(() => ['project-chat-sources', props.projectId]), queryFn: () => getChatSources(props.projectId) })
const page = useQuery({ queryKey: computed(() => ['project-chat-source', props.projectId, sourceKey.value, offset.value]),
  queryFn: () => readChatSource(props.projectId, sourceKey.value, offset.value), enabled: computed(() => !!sourceKey.value) })
const shownSources = computed(() => sources.data.value?.filter(source => `${source.title} ${source.key}`.toLowerCase().includes(filter.value.toLowerCase())) ?? [])
const currentChange = computed(() => changes.data.value?.find(change => change.id === selected.value) ?? changes.data.value?.[0])
const changeGroup = computed(() => changes.data.value?.filter(change => change.turnId === currentChange.value?.turnId
  && change.status === currentChange.value.status && change.key.split(':').slice(0, 2).join(':') === currentChange.value.key.split(':').slice(0, 2).join(':')) ?? [])
const statusText = { RUNNING: '请求中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已停止' }
const changeText = { PENDING: '待确认', APPLIED: '已应用', REJECTED: '已放弃', UNDONE: '已撤销' }

const refresh = async () => {
  await client.invalidateQueries({ predicate: query => query.queryKey.includes(props.projectId) })
}
const sending = useMutation({ mutationFn: ({ id, text }: { id: string; text: string }) => sendChat(props.projectId, id, text),
  onSuccess: async () => { question.value = ''; await refresh() }, onError: failure => { error.value = failure.message } })
const stopping = useMutation({ mutationFn: (id: string) => stopChat(props.projectId, id),
  onSuccess: refresh, onError: failure => { error.value = failure.message } })
const deciding = useMutation({ mutationFn: ({ change, action }: { change: ChatChange; action: 'apply' | 'reject' | 'undo' }) =>
  decideChatChange(props.projectId, change.id, action), onSuccess: refresh, onError: failure => { error.value = failure.message } })
function send() {
  if (!question.value.trim() || busy.value || sending.isPending.value) return
  error.value = ''
  try { sending.mutate({ id: createUuid(), text: question.value.trim() }) }
  catch (failure) { error.value = failure instanceof Error ? failure.message : String(failure) }
}
function decide(change: ChatChange, action: 'apply' | 'reject' | 'undo') {
  error.value = ''; deciding.mutate({ change, action })
}
function openSource(key: string) { sourceKey.value = key; offset.value = 0; tab.value = 'sources' }
function reference(key: string, title: string) { question.value += `${question.value ? '\n' : ''}关于「${title}」（${key}）：` }
</script>

<template>
  <section class="project-chat" aria-label="项目创作对话">
    <header class="chat-header"><h2><MessageSquare :size="20" />创作对话</h2>
      <button class="button secondary" title="刷新对话与资料" @click="refresh"><RefreshCw :size="16" />刷新</button>
    </header>
    <p v-if="error || turns.error.value || changes.error.value" class="form-error" role="alert">{{ error || turns.error.value?.message || changes.error.value?.message }}</p>
    <div class="chat-layout">
      <div class="conversation">
        <div class="chat-history" aria-live="polite" :aria-busy="busy">
          <p v-if="turns.isPending.value">正在加载对话…</p>
          <p v-else-if="!turns.data.value?.length" class="empty-state">暂无对话</p>
          <article v-for="turn in turns.data.value" :key="turn.id" class="chat-turn">
            <header><strong>作者</strong><time>{{ new Date(turn.createdAt).toLocaleString() }}</time></header>
            <div class="chat-question">{{ turn.question }}</div>
            <header class="reply-heading"><strong>创作编辑</strong><span :class="['turn-status', turn.status.toLowerCase()]">{{ statusText[turn.status] }}</span>
              <button v-if="turn.status === 'RUNNING'" class="button secondary small" :disabled="stopping.isPending.value" @click="stopping.mutate(turn.id)"><Square :size="13" />停止</button>
            </header>
            <div v-if="turn.reply" class="chat-reply">{{ turn.reply }}</div>
            <p v-else-if="turn.status === 'RUNNING'" class="muted">正在读取项目资料并生成回复…</p>
            <p v-if="turn.error" class="form-error">{{ turn.error }}</p>
            <details v-if="turn.sources.length" class="references"><summary>资料来源 · {{ turn.sources.length }}</summary>
              <ul><li v-for="(source, index) in turn.sources" :key="index">
                <button v-if="sources.data.value?.some(item => item.key === source.key)" class="source-link" @click="openSource(source.key)">{{ source.title }}</button>
                <span v-else>{{ source.title }}</span>
                <small v-if="source.characters">{{ source.offset }}–{{ source.end }} / {{ source.characters }} 字符</small>
                <code>{{ source.key }}</code>
              </li></ul>
            </details>
          </article>
        </div>
        <form class="chat-composer" @submit.prevent="send">
          <label for="chat-question">本次问题或修改要求</label>
          <textarea id="chat-question" v-model="question" rows="4" maxlength="8000" :disabled="sending.isPending.value" />
          <footer><small>{{ question.length }} / 8000</small><button class="button" type="submit" :disabled="!question.trim() || busy || sending.isPending.value"><Send :size="16" />{{ sending.isPending.value ? '发送中…' : '发送' }}</button></footer>
        </form>
      </div>
      <aside class="chat-inspector" aria-label="修改候选与项目资料">
        <div class="inspector-tabs" role="group">
          <button :class="{ active: tab === 'changes' }" :aria-pressed="tab === 'changes'" @click="tab = 'changes'">修改候选 <span>{{ changes.data.value?.filter(change => change.status === 'PENDING').length ?? 0 }}</span></button>
          <button :class="{ active: tab === 'sources' }" :aria-pressed="tab === 'sources'" @click="tab = 'sources'">项目资料</button>
        </div>
        <template v-if="tab === 'changes'">
          <p v-if="!changes.data.value?.length" class="empty-state">暂无修改候选</p>
          <label v-else class="change-selector">选择候选
            <select :value="currentChange?.id" @change="selected = ($event.target as HTMLSelectElement).value"><option v-for="change in changes.data.value" :key="change.id" :value="change.id">{{ change.title }} · {{ changeText[change.status] }}</option></select>
          </label>
          <div v-if="currentChange" class="change-detail">
            <h3>{{ currentChange.title }}</h3><p class="muted">{{ changeText[currentChange.status] }}</p>
            <h4>修改理由</h4><p>{{ currentChange.reason }}</p><h4>影响范围</h4><p>{{ currentChange.impact }}</p>
            <p class="impact-notice" v-if="currentChange.key.startsWith('SNOWFLAKE:')">后续雪花步骤需继续生成并重新确认；已确认规划不自动替换。</p>
            <p class="impact-notice" v-else-if="currentChange.key.startsWith('OUTLINE:')">另存大纲草稿；后续章节待核对，发布依据暂不改变。</p>
            <p class="impact-notice" v-else>更新独立人物档案；不自动改正文、规划硬约束或正史。</p>
            <div v-for="member in changeGroup" :key="member.id"><h4 v-if="changeGroup.length > 1">{{ member.title }}</h4>
              <div class="comparison"><section><h4>修改前</h4><div class="prose">{{ member.before || '（空）' }}</div></section>
                <section><h4>修改后</h4><div class="prose">{{ member.after }}</div></section></div>
            </div>
            <footer class="decision-actions">
              <template v-if="currentChange.status === 'PENDING'">
                <button class="button" :disabled="deciding.isPending.value || busy" @click="decide(currentChange, 'apply')"><Check :size="16" />确认应用{{ changeGroup.length > 1 ? `本组 ${changeGroup.length} 项` : '' }}</button>
                <button class="button secondary" :disabled="deciding.isPending.value" @click="decide(currentChange, 'reject')"><X :size="16" />{{ changeGroup.length > 1 ? '放弃本组' : '放弃' }}</button>
              </template>
              <button v-else-if="currentChange.status === 'APPLIED'" class="button secondary" :disabled="deciding.isPending.value || busy" @click="decide(currentChange, 'undo')"><Undo2 :size="16" />撤销本次修改</button>
            </footer>
          </div>
        </template>
        <template v-else>
          <label class="source-filter">筛选资料<input v-model="filter" type="search" /></label>
          <p v-if="sources.error.value" class="form-error">{{ sources.error.value.message }}</p>
          <div class="source-list"><div v-for="source in shownSources" :key="source.key" class="source-row">
            <button class="source-link" @click="openSource(source.key)"><Eye :size="14" />{{ source.title }}<small>{{ source.writable ? '可修改' : '只读' }} · {{ source.characters }}字符</small></button>
            <button class="icon-button" :title="`引用${source.title}`" :aria-label="`引用${source.title}`" @click="reference(source.key, source.title)"><Plus :size="16" /></button>
          </div></div>
          <section v-if="sourceKey" class="source-preview"><h3>{{ page.data.value?.title ?? '正在读取资料…' }}</h3>
            <p v-if="page.error.value" class="form-error">{{ page.error.value.message }}</p>
            <div v-if="page.data.value" class="prose">{{ page.data.value.text || '（空）' }}</div>
            <footer v-if="page.data.value"><button class="icon-button" title="上一页" aria-label="上一页" :disabled="offset === 0" @click="offset = Math.max(0, offset - 6000)"><ChevronLeft :size="18" /></button>
              <small>{{ offset }}–{{ page.data.value.end }} / {{ page.data.value.characters }}</small>
              <button class="icon-button" title="下一页" aria-label="下一页" :disabled="page.data.value.end >= page.data.value.characters" @click="offset = page.data.value!.end"><ChevronRight :size="18" /></button></footer>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.project-chat { min-width: 0; }
.chat-header, .chat-turn header, .chat-composer footer, .decision-actions, .source-preview footer { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.chat-header { justify-content: space-between; margin-bottom: 16px; }
h2 { display: flex; gap: 8px; align-items: center; font-size: 20px; margin: 0; } h3 { font-size: 16px; margin: 12px 0; } h4 { font-size: 13px; margin: 14px 0 8px; }
.chat-layout { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(320px, 1fr); border-top: 1px solid #dce1df; }
.conversation, .chat-inspector { min-width: 0; }
.conversation { padding: 20px 24px 0 0; } .chat-inspector { border-left: 1px solid #dce1df; padding: 16px 0 0 24px; }
.chat-history { max-height: 65vh; min-height: 200px; overflow-y: auto; padding-right: 8px; scroll-behavior: smooth; }
.chat-turn { padding: 12px 0 24px; border-bottom: 1px solid #e5e8e6; } .chat-turn header { font-size: 13px; } time, .muted, small, .empty-state { color: #69726e; }
.chat-question, .chat-reply, .prose, p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.chat-question { border-left: 3px solid #497b71; padding: 8px 12px; margin: 10px 0 18px; background: #f1f6f4; }
.reply-heading { margin-bottom: 10px; } .turn-status { margin-left: auto; } .turn-status.failed { color: #a33636; } .turn-status.running { color: #8a681d; }
.references { margin-top: 16px; font-size: 12px; } summary { cursor: pointer; } .references ul { padding-left: 18px; } .references code { display: block; overflow-wrap: anywhere; color: #69726e; } .references small { margin-left: 6px; }
.chat-composer { padding-top: 16px; } label { display: grid; gap: 8px; font-size: 13px; font-weight: 600; } textarea, select, input { width: 100%; min-width: 0; box-sizing: border-box; } textarea { resize: vertical; margin-top: 8px; } .chat-composer footer { justify-content: space-between; margin-top: 10px; }
.inspector-tabs { display: flex; border-bottom: 1px solid #dce1df; margin-bottom: 16px; } .inspector-tabs button { background: transparent; border: 0; border-bottom: 2px solid transparent; padding: 10px 12px; cursor: pointer; } .inspector-tabs .active { border-bottom-color: #35665c; color: #35665c; } .inspector-tabs span { margin-left: 5px; }
.comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 14px; border-block: 1px solid #dce1df; padding-bottom: 14px; } .comparison section { min-width: 0; } .comparison .prose { max-height: 400px; overflow-y: auto; font-size: 13px; }
.decision-actions { margin-top: 16px; } .impact-notice { color: #80631f; border-left: 2px solid #bc993e; padding-left: 10px; font-size: 12px; }
.source-list { max-height: 240px; overflow: auto; margin-top: 14px; } .source-row { display: flex; align-items: center; border-bottom: 1px solid #e5e8e6; gap: 8px; padding: 8px 0; } .source-link { background: none; border: none; color: #35665c; cursor: pointer; text-align: left; overflow-wrap: anywhere; min-width: 0; padding: 0; } .source-row .source-link { flex: 1; } .source-link svg { vertical-align: middle; margin-right: 4px; } .source-link small { display: block; margin-top: 4px; }
.icon-button { display: inline-grid; place-items: center; width: 32px; height: 32px; flex: 0 0 32px; border: 1px solid #dce1df; background: white; border-radius: 4px; cursor: pointer; } button:disabled { opacity: .5; cursor: default; } .source-preview { border-top: 1px solid #dce1df; margin-top: 14px; } .source-preview .prose { max-height: 380px; overflow-y: auto; font-size: 13px; } .source-preview footer { justify-content: space-between; margin-top: 12px; }
@media (max-width: 1050px) { .chat-layout { grid-template-columns: minmax(0, 1fr); } .conversation { padding-right: 0; } .chat-inspector { border-left: 0; border-top: 1px solid #dce1df; padding: 16px 0; margin-top: 24px; } }
@media (max-width: 600px) { .comparison { grid-template-columns: minmax(0, 1fr); } .chat-history { max-height: none; } .chat-header h2 { font-size: 18px; } }
</style>

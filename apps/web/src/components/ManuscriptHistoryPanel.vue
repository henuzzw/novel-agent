<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ArrowLeftRight, GitCommitHorizontal, RefreshCw } from 'lucide-vue-next'
import { getManuscriptVersion, listManuscriptVersions, type ManuscriptVersion, type ManuscriptVersionSummary } from '@/api/writing'
import { useWorkspaceChoice } from '@/composables/useWorkspaceLocation'
import TextDiffView from './TextDiffView.vue'

const props = defineProps<{ projectId: string; chapterNumber: number; publishedId?: string | null }>()
const versionsQuery = useQuery({
  queryKey: computed(() => ['manuscript-versions', props.projectId, props.chapterNumber]),
  queryFn: () => listManuscriptVersions(props.projectId, props.chapterNumber),
})
const versions = computed(() => [...(versionsQuery.data.value ?? [])].sort((a, b) => b.versionNumber - a.versionNumber))
const ids = () => versions.value.map(version => version.id)
const rightId = useWorkspaceChoice('compareTo', ids, () => versions.value[0]?.id ?? '')
function suggestedLeft(id: string) {
  const index = versions.value.findIndex(version => version.id === id)
  const version = versions.value[index]
  return version?.baseManuscriptVersionId && ids().includes(version.baseManuscriptVersionId)
    ? version.baseManuscriptVersionId : versions.value[index + 1]?.id ?? versions.value[index - 1]?.id ?? id
}
const leftId = useWorkspaceChoice('compareFrom', ids, () => suggestedLeft(rightId.value))
const field = useWorkspaceChoice('compareField', ['body', 'title', 'summary', 'continuityNotes'] as const, 'body')
const leftQuery = useQuery({
  queryKey: computed(() => ['manuscript-version', props.projectId, props.chapterNumber, leftId.value]),
  queryFn: () => getManuscriptVersion(props.projectId, props.chapterNumber, leftId.value), enabled: computed(() => !!leftId.value),
})
const rightQuery = useQuery({
  queryKey: computed(() => ['manuscript-version', props.projectId, props.chapterNumber, rightId.value]),
  queryFn: () => getManuscriptVersion(props.projectId, props.chapterNumber, rightId.value), enabled: computed(() => !!rightId.value),
})
function owned(version: ManuscriptVersion | undefined, id: string) {
  return version?.id === id && version.projectId === props.projectId && version.chapterNumber === props.chapterNumber ? version : null
}
const left = computed(() => owned(leftQuery.data.value, leftId.value))
const right = computed(() => owned(rightQuery.data.value, rightId.value))
const fields = [{ key: 'body', label: '正文' }, { key: 'title', label: '标题' }, { key: 'summary', label: '摘要' }, { key: 'continuityNotes', label: '连续性备注' }] as const
function text(version: ManuscriptVersion | null, key: typeof field.value) {
  const value = version?.content[key] ?? ''
  return Array.isArray(value) ? value.join('\n') : value
}
function date(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
function state(version: Pick<ManuscriptVersionSummary, 'id' | 'status'>) { return version.id === props.publishedId ? '当前已发布' : version.status === 'AUTHOR_ACCEPTED' ? '作者已确认' : '草稿' }
function label(version: ManuscriptVersionSummary) { return `第 ${version.versionNumber} 版 · ${state(version)} · ${version.title}` }
function origin(version: ManuscriptVersion) {
  const type = version.generatorType
  if (type.startsWith('LOCAL_EDIT_')) return '局部编辑'
  if (type.includes('JUDGE') || type.includes('QUALITY')) return '检查与修订'
  if (type.includes('MANUAL') || type.includes('AUTHOR')) return '作者编辑'
  if (type.includes('CODEX')) return 'ChatGPT'
  if (type.includes('DEEPSEEK')) return 'DeepSeek'
  return type === 'LOCAL_TEMPLATE' ? '本地模板' : type
}
function choose(version: ManuscriptVersionSummary) { rightId.value = version.id; leftId.value = suggestedLeft(version.id) }
function swap() { const old = leftId.value; leftId.value = rightId.value; rightId.value = old }
async function refresh() {
  const result = await versionsQuery.refetch()
  if (result.isError) return
  if (leftId.value) await leftQuery.refetch()
  if (rightId.value) await rightQuery.refetch()
}
const loadError = computed(() => versionsQuery.error.value ?? leftQuery.error.value ?? rightQuery.error.value)
</script>

<template>
  <section class="manuscript-history" aria-label="正文版本对比">
    <header class="history-title"><h3>版本对比</h3><span>{{ versions.length }} 个保存版本</span><button type="button" title="刷新版本" aria-label="刷新版本" :disabled="versionsQuery.isFetching.value || leftQuery.isFetching.value || rightQuery.isFetching.value" @click="refresh"><RefreshCw :size="16" /></button></header>
    <div v-if="loadError" class="load-error" role="alert">版本读取失败：{{ loadError.message }}<button type="button" @click="refresh"><RefreshCw :size="15" />重试</button></div>
    <p v-if="versionsQuery.isPending.value" class="empty" role="status">正在读取正文版本…</p>
    <p v-else-if="!versions.length && !loadError" class="empty" role="status">当前章暂无保存版本</p>
    <div v-else-if="versions.length" class="history-layout">
      <aside class="version-log" aria-label="版本记录">
        <button v-for="version in versions" :key="version.id" type="button" :class="{ selected: version.id === rightId, base: version.id === leftId }" :aria-pressed="version.id === rightId" @click="choose(version)">
          <GitCommitHorizontal :size="17" /><span><strong>第 {{ version.versionNumber }} 版 <small v-if="version.id === leftId">基准</small></strong><span>{{ state(version) }}</span><time :datetime="version.createdAt">{{ date(version.createdAt) }}</time><span class="version-title">{{ version.title }}</span></span>
        </button>
      </aside>
      <div class="comparison">
        <div class="version-selectors">
          <label><span>基准版本</span><select v-model="leftId" aria-label="基准版本"><option v-for="version in versions" :key="version.id" :value="version.id">{{ label(version) }}</option></select></label>
          <button type="button" title="交换两个版本" aria-label="交换两个版本" @click="swap"><ArrowLeftRight :size="17" /></button>
          <label><span>对比版本</span><select v-model="rightId" aria-label="对比版本"><option v-for="version in versions" :key="version.id" :value="version.id">{{ label(version) }}</option></select></label>
        </div>
        <p v-if="versions.length === 1" class="empty">当前章只有一个保存版本</p>
        <p v-if="leftQuery.isPending.value || rightQuery.isPending.value" class="empty" role="status">正在读取两版完整正文…</p>
        <template v-else-if="left && right">
          <div class="version-metadata">
            <article v-for="version in [left, right]" :key="version === left ? 'left' : 'right'">
              <strong>第 {{ version.versionNumber }} 版 · {{ state(version) }}</strong>
              <span>{{ origin(version) }} · {{ date(version.createdAt) }} · {{ version.content.body.length.toLocaleString() }} 字符</span>
              <span v-if="version.baseManuscriptVersionId">基于第 {{ versions.find(item => item.id === version.baseManuscriptVersionId)?.versionNumber ?? '未知' }} 版</span>
              <details v-if="version.changeSummary.length || version.authorInstruction"><summary>修改依据与说明</summary><p v-if="version.authorInstruction">{{ version.authorInstruction }}</p><ul v-if="version.changeSummary.length"><li v-for="(note, index) in version.changeSummary" :key="index">{{ note }}</li></ul></details>
            </article>
          </div>
          <nav class="content-tabs" aria-label="对比内容">
            <button v-for="item in fields" :key="item.key" type="button" :aria-pressed="field === item.key" @click="field = item.key">{{ item.label }}<span v-if="text(left, item.key) !== text(right, item.key)" class="change-dot" :aria-label="`${item.label}有变化`"></span></button>
          </nav>
          <TextDiffView :key="`${projectId}-${chapterNumber}-${leftId}-${rightId}-${field}`" :before="text(left, field)" :after="text(right, field)" :before-label="`第 ${left.versionNumber} 版`" :after-label="`第 ${right.versionNumber} 版`" />
        </template>
        <p v-else-if="!loadError && !leftQuery.isPending.value && !rightQuery.isPending.value" class="load-error" role="alert">所选版本与当前项目或章节不匹配</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.manuscript-history, .comparison { min-width: 0; }.history-title { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; border-bottom: 1px solid #dbe2e6; padding-bottom: 14px; }
h3 { margin: 0; font-size: 17px; }.history-title > span { color: #6f7d85; font-size: 13px; }.history-title > button { margin-left: auto; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 32px; padding: 6px 9px; background: #fff; color: #31424b; border: 1px solid #d4dce1; border-radius: 4px; font: inherit; cursor: pointer; }
button:focus-visible, select:focus-visible { outline: 2px solid #176b63; outline-offset: 2px; }button:disabled { opacity: .45; cursor: default; }
.history-layout { display: grid; grid-template-columns: minmax(145px, 190px) minmax(0, 1fr); gap: 20px; margin-top: 18px; }
.version-log { min-width: 0; border-right: 1px solid #dbe2e6; padding-right: 14px; max-height: 78vh; overflow-y: auto; }
.version-log button { display: flex; justify-content: flex-start; align-items: flex-start; width: 100%; text-align: left; gap: 8px; border: 0; border-radius: 0; padding: 12px 7px; border-left: 2px solid transparent; border-bottom: 1px solid #edf0f2; }
.version-log svg { flex-shrink: 0; margin-top: 1px; }.version-log button > span { display: grid; gap: 5px; min-width: 0; }.version-log strong { font-size: 13px; }.version-log span span, time { font-size: 12px; color: #6b7a84; overflow-wrap: anywhere; }.version-log small { font-size: 10px; padding-left: 4px; color: #27669a; }
.version-log .selected { background: #edf5f2; border-left-color: #277469; }.version-title { overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.version-selectors { display: grid; grid-template-columns: minmax(0, 1fr) 34px minmax(0, 1fr); align-items: end; gap: 12px; }.version-selectors label { display: grid; gap: 7px; min-width: 0; font-size: 12px; color: #65757f; }
select { width: 100%; min-width: 0; height: 36px; padding: 5px 8px; border: 1px solid #cbd5db; border-radius: 4px; background: #fff; color: #243c45; font: inherit; font-size: 13px; }
.version-metadata { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; padding: 16px 0; border-bottom: 1px solid #dbe2e6; }.version-metadata article { display: grid; align-content: start; gap: 6px; min-width: 0; }.version-metadata strong { font-size: 13px; }.version-metadata span { color: #6f7d85; font-size: 12px; overflow-wrap: anywhere; }
details { font-size: 12px; color: #647680; }summary { cursor: pointer; }details p, details li { white-space: pre-wrap; overflow-wrap: anywhere; }details ul { padding-left: 16px; }
.content-tabs { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 14px; }.content-tabs button { border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; font-size: 13px; }.content-tabs button[aria-pressed=true] { border-bottom-color: #176b63; color: #176b63; }.change-dot { width: 6px; height: 6px; border-radius: 50%; background: #277ba8; }
.empty { color: #6a7a84; padding: 14px 0; font-size: 13px; }.load-error { color: #a53241; padding: 12px 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
@media (max-width: 1000px) { .history-layout { grid-template-columns: 1fr; gap: 16px; }.version-log { display: flex; overflow-x: auto; max-height: none; border-right: 0; border-bottom: 1px solid #dbe2e6; padding: 0; }.version-log button { flex: 0 0 145px; width: 145px; border-bottom: 0; }.version-title { display: none !important; } }
@media (max-width: 600px) { .version-selectors { grid-template-columns: minmax(0, 1fr) 32px; gap: 10px; }.version-selectors label:last-child { grid-row: 2; }.version-selectors > button { grid-row: 1 / 3; grid-column: 2; align-self: center; }.version-metadata { gap: 12px; } }
</style>

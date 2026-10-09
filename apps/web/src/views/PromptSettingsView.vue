<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Check, History, RefreshCw, RotateCcw, Save, Search } from 'lucide-vue-next'
import { ApiError } from '@/api/http'
import { listPrompts, promptHistory, resetPrompt, savePrompt, type AgentPrompt, type PromptRevision } from '@/api/prompts'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

const route = useRoute()
const router = useRouter()
const templates = ref<AgentPrompt[]>([])
const search = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const conflicted = ref(false)
const notice = ref('')
const system = ref('')
const sessionSystem = ref('')
const guidance = ref('')
const showHistory = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const revisions = ref<PromptRevision[]>([])
let historyRequest = 0
const selected = computed(() => templates.value.find(item => item.key === route.query.template) ?? templates.value[0])
const dirty = computed(() => !!selected.value && (system.value !== selected.value.systemPrompt
  || sessionSystem.value !== selected.value.sessionSystemPrompt || guidance.value !== selected.value.guidance))
const valid = computed(() => !!system.value.trim() && !!sessionSystem.value.trim()
  && system.value.length <= 40000 && sessionSystem.value.length <= 40000 && guidance.value.length <= 40000)
const workflows = computed(() => new Set(templates.value.map(item => item.workflow)).size)
const groups = computed(() => {
  const result = new Map<string, AgentPrompt[]>()
  const term = search.value.trim().toLowerCase()
  for (const item of templates.value) {
    if (term && !`${item.name} ${item.key} ${item.group}`.toLowerCase().includes(term)) continue
    if (!result.has(item.group)) result.set(item.group, [])
    result.get(item.group)!.push(item)
  }
  return result
})

useUnsavedChanges(dirty, ['template'])
watch(selected, value => {
  system.value = value?.systemPrompt ?? ''
  sessionSystem.value = value?.sessionSystemPrompt ?? ''
  guidance.value = value?.guidance ?? ''
  revisions.value = []
  showHistory.value = false
  historyError.value = ''
  historyLoading.value = false
  historyRequest++
  notice.value = ''
  error.value = ''
  conflicted.value = false
})

function message(cause: unknown) {
  return cause instanceof Error ? cause.message : '请求失败，请稍后重试'
}

async function load() {
  if (dirty.value && !window.confirm('重新读取会放弃未保存的修改，继续吗？')) return
  loading.value = true
  error.value = ''
  try { templates.value = await listPrompts() }
  catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 404
      ? '当前后端尚未加载提示词管理接口，请重启后端服务。'
      : message(cause)
  }
  finally { loading.value = false }
}

async function reconcile() {
  if (loading.value || saving.value || !selected.value) return
  const draft = { key: selected.value.key, system: system.value, sessionSystem: sessionSystem.value, guidance: guidance.value }
  loading.value = true
  error.value = ''
  try {
    templates.value = await listPrompts()
    await nextTick()
    if (selected.value?.key === draft.key) {
      system.value = draft.system
      sessionSystem.value = draft.sessionSystem
      guidance.value = draft.guidance
      notice.value = '已读取最新配置 · 当前编辑仍保留'
    }
    conflicted.value = false
  } catch (cause) { error.value = message(cause) }
  finally { loading.value = false }
}

function choose(key: string) {
  if (saving.value || key === selected.value?.key) return
  void router.push({ path: route.path, query: { ...route.query, template: key } })
}

async function persist(reset = false) {
  const current = selected.value
  if (!current || saving.value || (!reset && (!dirty.value || !valid.value))) return
  if (reset && !window.confirm(`恢复“${current.name}”的系统默认提示词？本次未保存的修改也将被清除。`)) return
  const payload = { systemPrompt: system.value, sessionSystemPrompt: sessionSystem.value, guidance: guidance.value, version: current.version }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const saved = reset ? await resetPrompt(current.key, current.version) : await savePrompt(current.key, payload)
    templates.value = templates.value.map(item => item.key === saved.key ? saved : item)
    // Flush the selected-template watcher before setting the completion message.
    await nextTick()
    notice.value = reset ? `已恢复默认 · 版本 ${saved.version}` : `已保存 · 版本 ${saved.version}`
  } catch (cause) {
    conflicted.value = cause instanceof ApiError && cause.status === 409
    error.value = cause instanceof ApiError && cause.status === 409
      ? '提示词已在其他页面更新。本次编辑仍保留，请核对最新版本后再保存。'
      : message(cause)
  } finally { saving.value = false }
}

async function toggleHistory() {
  showHistory.value = !showHistory.value
  if (!showHistory.value || !selected.value) return
  const key = selected.value.key
  const request = ++historyRequest
  historyLoading.value = true
  historyError.value = ''
  try {
    const values = await promptHistory(key)
    if (request === historyRequest && selected.value?.key === key) revisions.value = values
  } catch (cause) {
    if (request === historyRequest && selected.value?.key === key) historyError.value = message(cause)
  } finally {
    if (request === historyRequest && selected.value?.key === key) historyLoading.value = false
  }
}

function useRevision(revision: PromptRevision) {
  if (saving.value || !selected.value) return
  if (dirty.value && !window.confirm('替换当前未保存的编辑，继续吗？')) return
  system.value = revision.systemPrompt ?? selected.value.defaultSystemPrompt
  sessionSystem.value = revision.sessionSystemPrompt ?? selected.value.defaultSessionSystemPrompt
  guidance.value = revision.guidance
  notice.value = `已载入版本 ${revision.version} · 尚未保存`
}

function date(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '系统默认'
}

onMounted(load)
</script>

<template>
  <section class="prompt-settings">
    <div class="prompt-page-heading">
      <div>
        <RouterLink class="prompt-back" to="/projects"><ArrowLeft :size="16" />项目列表</RouterLink>
        <h1>提示词管理</h1>
        <p class="muted prompt-counts"><span>全局配置</span><span>{{ workflows }} 个 Agent</span><span>{{ templates.length }} 个阶段模板</span></p>
      </div>
      <button type="button" class="icon-button" title="重新读取提示词" aria-label="重新读取提示词" :disabled="loading || saving" @click="load"><RefreshCw :size="18" /></button>
    </div>
    <p v-if="loading" role="status">正在读取提示词…</p>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <button v-if="conflicted" type="button" class="button secondary prompt-conflict-button" :disabled="loading || saving" @click="reconcile"><RefreshCw :size="16" />保留编辑，读取最新配置</button>
    <div v-if="templates.length" class="prompt-layout">
      <aside class="prompt-navigation" aria-label="Agent 提示词">
        <label class="prompt-search"><Search :size="16" /><input v-model="search" type="search" placeholder="搜索阶段" aria-label="搜索提示词" /></label>
        <div v-for="[group, items] in groups" :key="group" class="prompt-group">
          <h2>{{ group }}</h2>
          <button v-for="item in items" :key="item.key" type="button" :aria-pressed="selected?.key === item.key" :class="{ active: selected?.key === item.key }" :disabled="saving || loading" @click="choose(item.key)">
            <span>{{ item.name }}</span><small>{{ item.customized ? '已调整' : '默认' }} · v{{ item.version }}</small>
          </button>
        </div>
        <p v-if="!groups.size" class="muted">无匹配阶段</p>
      </aside>
      <section v-if="selected" class="prompt-editor" :aria-label="`${selected.name}编辑`">
        <div class="prompt-editor-heading">
          <div><h2>{{ selected.name }}</h2><span class="muted prompt-key">{{ selected.workflow }}</span></div>
          <div class="prompt-actions">
            <button type="button" class="icon-button" title="最近 50 个版本" aria-label="版本历史" :aria-expanded="showHistory" :disabled="saving" @click="toggleHistory"><History :size="18" /></button>
            <button type="button" class="button secondary secondary-button" :disabled="saving || loading || (!selected.customized && !dirty)" @click="persist(true)"><RotateCcw :size="16" />恢复默认</button>
            <button type="button" class="button primary primary-button" :disabled="saving || loading || !dirty || !valid" @click="persist()"><Save :size="16" />{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </div>
        <div class="prompt-meta"><span>{{ dirty ? '未保存' : `版本 ${selected.version}` }}</span><span>{{ date(selected.updatedAt) }}</span><span>后续生成生效 · 历史结果不变</span></div>
        <p v-if="notice" class="prompt-notice" role="status"><Check :size="16" />{{ notice }}</p>
        <fieldset class="prompt-fields" :disabled="saving || loading">
          <label for="prompt-session-system">系统提示词<span class="muted">{{ sessionSystem.length }} / 40,000</span></label>
          <textarea id="prompt-session-system" v-model="sessionSystem" spellcheck="false" rows="7" maxlength="40000" />
          <label for="prompt-system">用户提示词<span class="muted">{{ system.length }} / 40,000</span></label>
          <textarea id="prompt-system" v-model="system" spellcheck="false" rows="14" maxlength="40000" />
          <label for="prompt-guidance">阶段执行规则<span class="muted">{{ guidance.length }} / 40,000</span></label>
          <textarea id="prompt-guidance" v-model="guidance" spellcheck="false" rows="7" maxlength="40000" />
        </fieldset>
        <p v-if="!sessionSystem.trim()" class="form-error">系统提示词不能为空。</p>
        <p v-if="!system.trim()" class="form-error">用户提示词不能为空。</p>
        <details v-if="dirty" class="prompt-reference"><summary>当前已保存配置 · 版本 {{ selected.version }}</summary><h4>系统提示词</h4><pre>{{ selected.sessionSystemPrompt }}</pre><h4>用户提示词</h4><pre>{{ selected.systemPrompt }}</pre><pre v-if="selected.guidance">{{ selected.guidance }}</pre></details>
        <details class="prompt-reference"><summary>默认提示词</summary><h4>系统提示词</h4><pre>{{ selected.defaultSessionSystemPrompt }}</pre><h4>用户提示词</h4><pre>{{ selected.defaultSystemPrompt }}</pre></details>
        <details class="prompt-reference"><summary>固定业务边界 · 项目资料、作者本次要求和输出结构单独保留</summary><pre>{{ selected.protectedRules }}</pre></details>
        <section v-if="showHistory" class="prompt-history" aria-label="版本历史">
          <h3>最近 50 个版本</h3>
          <p v-if="historyLoading" role="status">正在读取历史…</p>
          <p v-else-if="historyError" role="alert" class="form-error">{{ historyError }}</p>
          <p v-else-if="!revisions.length" class="muted">尚无编辑记录</p>
          <details v-for="revision in revisions" :key="revision.version" class="prompt-reference">
            <summary>版本 {{ revision.version }} · {{ revision.operation === 'RESET' ? '恢复默认' : '保存' }} · {{ date(revision.createdAt) }}</summary>
            <h4>系统提示词</h4><pre>{{ revision.sessionSystemPrompt ?? selected.defaultSessionSystemPrompt }}</pre>
            <h4>用户提示词</h4><pre>{{ revision.systemPrompt ?? selected.defaultSystemPrompt }}</pre>
            <pre v-if="revision.guidance">{{ revision.guidance }}</pre>
            <button type="button" class="button secondary secondary-button" :disabled="saving" @click="useRevision(revision)"><RotateCcw :size="16" />载入此版本</button>
          </details>
        </section>
      </section>
    </div>
  </section>
</template>

<style scoped>
.prompt-settings { max-width: 1540px; margin: 0 auto; padding: 28px 32px; }
.muted { color: #687780; }
.prompt-counts { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 13px; }
.prompt-counts span { white-space: nowrap; }
.prompt-page-heading, .prompt-editor-heading { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.prompt-page-heading { margin-bottom: 24px; }
.prompt-page-heading h1 { font-size: 24px; margin: 12px 0 8px; }
.prompt-page-heading p { margin: 0; }
.prompt-back { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
.prompt-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 32px; align-items: start; }
.prompt-navigation { position: sticky; top: 86px; max-height: calc(100dvh - 110px); overflow-y: auto; padding-right: 12px; border-right: 1px solid #dbe2e6; }
.prompt-search { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; color: #5f6d76; }
.prompt-search input { width: 100%; min-width: 0; padding: 9px; border: 1px solid #cbd6dc; border-radius: 4px; background: #fff; font: inherit; font-size: 13px; }
.prompt-group h2 { font-size: 12px; color: #687780; margin: 18px 10px 6px; }
.prompt-group button { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: 10px; border: 0; border-radius: 4px; background: transparent; padding: 10px; text-align: left; color: #38464d; cursor: pointer; }
.prompt-group button span { min-width: 0; overflow-wrap: anywhere; }
.prompt-group button small { flex-shrink: 0; color: #78848a; font-size: 11px; }
.prompt-group button:hover { background: #f2f5f6; }
.prompt-group button.active { background: #e9f3f0; color: #28605a; font-weight: 600; }
.prompt-editor { min-width: 0; }
.prompt-editor-heading h2 { font-size: 19px; margin: 0 0 8px; }
.prompt-key { font-size: 12px; overflow-wrap: anywhere; }
.prompt-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.prompt-actions button, .prompt-history button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.prompt-actions svg { flex-shrink: 0; }
.prompt-meta { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; color: #687780; margin: 18px 0 22px; }
.prompt-fields { border: 0; margin: 0; padding: 0; min-width: 0; }
.prompt-fields label { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 10px; font-size: 14px; font-weight: 600; }
.prompt-fields label span { font-size: 12px; font-weight: 400; }
.prompt-fields textarea { display: block; width: 100%; box-sizing: border-box; resize: vertical; min-height: 180px; padding: 14px; margin-bottom: 24px; font-size: 14px; line-height: 1.75; color: #29363c; border: 1px solid #cbd6dc; border-radius: 4px; background: #fff; }
.prompt-reference { padding: 14px 0; border-top: 1px solid #dbe2e6; }
.prompt-reference summary { cursor: pointer; font-size: 13px; overflow-wrap: anywhere; }
.prompt-reference pre { font-family: inherit; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.75; color: #53616b; max-height: 420px; overflow: auto; }
.prompt-history { margin-top: 20px; }
.prompt-history h3 { font-size: 15px; }
.prompt-notice { display: flex; align-items: center; gap: 7px; color: #28605a; font-size: 13px; }
.prompt-conflict-button { display: inline-flex; align-items: center; gap: 7px; margin-bottom: 18px; }
@media (max-width: 800px) { .prompt-settings { padding: 20px 16px; } .prompt-layout { grid-template-columns: 1fr; gap: 22px; } .prompt-navigation { position: static; max-height: 230px; border-right: 0; border-bottom: 1px solid #dbe2e6; padding: 0 0 12px; } .prompt-editor-heading { flex-wrap: wrap; align-items: flex-start; } .prompt-meta { margin-bottom: 18px; } }
@media (max-width: 420px) { .prompt-settings { padding: 16px 12px; } .prompt-page-heading h1 { font-size: 22px; } .prompt-actions { width: 100%; } .prompt-actions .primary-button { margin-left: auto; } }
</style>

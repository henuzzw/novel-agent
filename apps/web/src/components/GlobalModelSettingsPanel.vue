<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { Check, RefreshCw, Save } from 'lucide-vue-next'
import { getChatGptModels, updateGlobalModelSettings, type GlobalModelSettings } from '@/api/modelSettings'
import { useGlobalModelSettings } from '@/composables/useGlobalModelSettings'
import ChatGptConnectionPanel from '@/components/ChatGptConnectionPanel.vue'
import type { ChatGptTransport } from '@/api/chatGptConnection'
const { settings, apply, load } = useGlobalModelSettings()
const draft = ref<GlobalModelSettings | null>(null)
const saved = ref(false)
const transport = ref<ChatGptTransport>('APP_SERVER')
watch(settings, (value) => { draft.value = value ? { ...value } : null }, { immediate: true })
const models = useQuery({ queryKey: ['global-chatgpt-models'], queryFn: getChatGptModels, retry: false, staleTime: 300_000,
  enabled: computed(() => draft.value?.provider === 'LOCAL_CODEX') })
const selectedModel = computed(() => models.data.value?.find(model => model.model === draft.value?.codexModel))
const efforts = computed(() => transport.value === 'SIWC_HTTP' ? ['none', 'minimal', 'low', 'medium', 'high', 'xhigh'] : selectedModel.value?.efforts ?? [])
const effortLabels: Record<string, string> = { none: '不推理', minimal: '极低', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高', ultra: '超高' }
watch(() => draft.value?.codexModel, () => {
  if (draft.value && selectedModel.value && !efforts.value.includes(draft.value.codexEffort)) draft.value.codexEffort = selectedModel.value.defaultEffort
})
const changed = computed(() => JSON.stringify(draft.value) !== JSON.stringify(settings.value))
const valid = computed(() => !!draft.value && (draft.value.provider !== 'LOCAL_CODEX' || efforts.value.includes(draft.value.codexEffort)))
const save = useMutation({ mutationFn: () => updateGlobalModelSettings(draft.value!), onSuccess: (value) => { apply(value); saved.value = true } })
watch(draft, () => { if (changed.value) saved.value = false }, { deep: true })
</script>
<template>
  <section class="global-settings">
    <header><h2>全局模型设置</h2><button type="button" class="icon-button" title="重新读取设置" aria-label="重新读取全局设置" :disabled="save.isPending.value" @click="load"><RefreshCw :size="16" /></button></header>
    <form v-if="draft" @submit.prevent="save.mutate()">
      <label>供应商<select v-model="draft.provider" aria-label="全局模型供应商" :disabled="save.isPending.value"><option value="LOCAL_CODEX">ChatGPT</option><option value="DEEPSEEK">DeepSeek</option><option value="LOCAL_TEMPLATE">本地模板（流程验证）</option></select></label>
      <template v-if="draft.provider === 'LOCAL_CODEX'">
        <label>模型<select v-model="draft.codexModel" aria-label="ChatGPT 模型" :disabled="models.isPending.value || save.isPending.value">
          <option v-if="!selectedModel" :value="draft.codexModel">{{ draft.codexModel }}（待核对）</option>
          <option v-for="model in models.data.value" :key="model.model" :value="model.model">{{ model.label }}</option>
        </select></label>
        <label>推理强度<select v-model="draft.codexEffort" aria-label="ChatGPT 推理强度" :disabled="!selectedModel || save.isPending.value"><option v-for="effort in efforts" :key="effort" :value="effort">{{ effortLabels[effort] ?? effort }}</option></select></label>
        <p v-if="transport === 'SIWC_HTTP'">模型是否接受所选推理强度，以实际请求结果为准。</p>
        <span v-if="models.isPending.value" role="status">正在读取模型…</span>
        <p v-if="models.isError.value" class="form-error" role="alert">{{ models.error.value?.message }}<button type="button" class="icon-button" title="重新读取模型" aria-label="重新读取模型" @click="models.refetch()"><RefreshCw :size="16" /></button></p>
      </template>
      <label v-else-if="draft.provider === 'DEEPSEEK'">模型<select v-model="draft.deepSeekModel" aria-label="DeepSeek 模型" :disabled="save.isPending.value"><option value="deepseek-flash">V4.1 Flash（deepseek-flash）</option><option value="deepseek-v4-pro">V4 Pro（deepseek-v4-pro）</option></select></label>
      <button class="button primary" type="submit" :disabled="!changed || !valid || save.isPending.value"><Save :size="16" />{{ save.isPending.value ? '正在保存…' : '保存全局设置' }}</button>
      <span v-if="saved && !changed" class="save-status"><Check :size="15" />已保存</span>
      <p v-if="save.isError.value" class="form-error" role="alert">{{ save.error.value?.message }}</p>
    </form>
    <ChatGptConnectionPanel v-if="draft?.provider === 'LOCAL_CODEX'" @transport="transport = $event" />
  </section>
</template>
<style scoped>
.global-settings { padding: 20px 24px; border-bottom: 1px solid #d8dce0; background: #f7f9fa; }
header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
h2 { font-size: 16px; margin: 0; }
form { display: flex; align-items: end; flex-wrap: wrap; gap: 12px; }
label { display: grid; gap: 6px; font-size: 13px; min-width: 0; }
select { max-width: 100%; min-height: 36px; }
p { flex-basis: 100%; overflow-wrap: anywhere; }
@media (max-width: 640px) { label { width: 100%; } .global-settings { padding: 16px; } }
</style>

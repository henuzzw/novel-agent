import { computed, ref } from 'vue'
import { getGlobalModelSettings, type GlobalModelSettings } from '@/api/modelSettings'
const settings = ref<GlobalModelSettings | null>(null)
const loadError = ref('')
const loading = ref(false)
let pendingLoad: Promise<void> | null = null
let revision = 0
const savedSettings = computed(() => settings.value)
const provider = computed(() => settings.value?.provider ?? 'LOCAL_CODEX')
const label = computed(() => {
  const value = settings.value
  if (!value) return '模型设置'
  if (value.provider === 'DEEPSEEK') return value.deepSeekModel === 'deepseek-flash' ? 'DeepSeek V4.1 Flash' : 'DeepSeek V4 Pro'
  if (value.provider === 'LOCAL_TEMPLATE') return '本地模板'
  return `ChatGPT · ${value.codexModel} · ${value.codexEffort}`
})

function load(): Promise<void> {
  if (pendingLoad) return pendingLoad
  loading.value = true
  loadError.value = ''
  const startedAtRevision = revision
  pendingLoad = (async () => {
    try {
      const value = await getGlobalModelSettings()
      // A read started before a successful save must not replace the saved value.
      if (revision === startedAtRevision) settings.value = value
    } catch (error) {
      if (revision === startedAtRevision) loadError.value = error instanceof Error ? error.message : '模型设置读取失败'
    }
  })().finally(() => {
    loading.value = false
    pendingLoad = null
  })
  return pendingLoad
}

function apply(value: GlobalModelSettings) {
  revision++
  settings.value = value
  loadError.value = ''
}

export function useGlobalModelSettings() {
  return { settings: savedSettings, provider, label, loading, loadError, load, apply }
}

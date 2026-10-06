import { computed, ref } from 'vue'
import { getGlobalModelSettings, type GlobalModelSettings } from '@/api/modelSettings'
const settings = ref<GlobalModelSettings | null>(null)
const loadError = ref('')
const loading = ref(false)
export function useGlobalModelSettings() {
  const provider = computed(() => settings.value?.provider ?? 'LOCAL_CODEX')
  const label = computed(() => {
    const value = settings.value
    if (!value) return '模型设置'
    if (value.provider === 'DEEPSEEK') return value.deepSeekModel === 'deepseek-flash' ? 'DeepSeek V4.1 Flash' : 'DeepSeek V4 Pro'
    if (value.provider === 'LOCAL_TEMPLATE') return '本地模板'
    return `ChatGPT · ${value.codexModel} · ${value.codexEffort}`
  })
  async function load() {
    loading.value = true
    loadError.value = ''
    try { settings.value = await getGlobalModelSettings() }
    catch (error) { loadError.value = error instanceof Error ? error.message : '模型设置读取失败' }
    finally { loading.value = false }
  }
  function apply(value: GlobalModelSettings) { settings.value = value }
  return { settings: computed(() => settings.value), provider, label, loading, loadError, load, apply }
}

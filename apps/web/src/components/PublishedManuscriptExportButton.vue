<script setup lang="ts">
import { Download, LoaderCircle, X } from 'lucide-vue-next'
import { ref } from 'vue'
import { getPublishedManuscriptExport } from '@/api/writing'

const props = defineProps<{ projectId: string; projectName?: string }>()
const pending = ref(false)
const error = ref('')
async function exportManuscripts() {
  if (pending.value) return
  const projectId = props.projectId
  const name = (props.projectName || '小说').replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').slice(0, 120)
  pending.value = true
  error.value = ''
  try {
    const text = await getPublishedManuscriptExport(projectId)
    if (projectId !== props.projectId) return
    const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${name}-已完成正文.md`
    document.body.appendChild(link)
    try { link.click() } finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
  } catch (failure) {
    if (projectId === props.projectId) error.value = (failure as Error).message
  } finally { pending.value = false }
}
</script>

<template>
  <div class="published-export">
    <button class="icon-button" type="button" :disabled="pending" :aria-busy="pending"
      :title="pending ? '正在导出…' : '导出已完成正文'" :aria-label="pending ? '正在导出…' : '导出已完成正文'" @click="exportManuscripts">
      <LoaderCircle v-if="pending" class="export-spinner" :size="18" aria-hidden="true" /><Download v-else :size="18" aria-hidden="true" />
    </button>
    <div v-if="error" class="export-error" role="alert">
      <span class="form-error">{{ error }}</span>
      <button class="icon-button" type="button" title="关闭导出错误" aria-label="关闭导出错误" @click="error = ''"><X :size="16" aria-hidden="true" /></button>
    </div>
  </div>
</template>

<style scoped>
.published-export { position: relative; display: flex; flex-shrink: 0; }
.export-error { position: absolute; top: calc(100% + 8px); right: 0; width: min(360px, calc(100vw - 24px)); display: flex; align-items: flex-start; gap: 8px; padding: 10px; border: 1px solid #d7dde3; border-radius: 6px; background: #fff; box-shadow: 0 4px 16px #00000014; }
.export-error .form-error { flex: 1; min-width: 0; margin: 0; overflow-wrap: anywhere; }
.export-error .icon-button { flex-shrink: 0; }
.export-spinner { animation: export-spin 1s linear infinite; }
@keyframes export-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .export-spinner { animation: none; } }
</style>

<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ArrowLeft, FileText, Sparkles, Upload } from 'lucide-vue-next'
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { createProjectFromStory, type CreativeStrategy } from '@/api/projects'

const router = useRouter()
const client = useQueryClient()
const sourceMode = ref<'file' | 'text'>('file')
const name = ref('')
const story = ref('')
const file = ref<File | null>(null)
const strategy = ref<CreativeStrategy>('FANQIE_GRIPPING')
const error = ref('')
const create = useMutation({
  mutationFn: () => createProjectFromStory({ name: name.value.trim(), creativeStrategy: strategy.value,
    file: sourceMode.value === 'file' ? file.value : null, text: sourceMode.value === 'text' ? story.value : undefined }),
  onSuccess: async project => {
    await client.invalidateQueries({ queryKey: ['projects'] })
    await router.push({ path: `/projects/${project.id}`, query: { section: 'imports', analyze: '1' } })
  },
  onError: (reason: Error) => { error.value = reason.message },
})
function submit() {
  error.value = ''
  if (sourceMode.value === 'file' && !file.value) { error.value = '请选择故事文件。'; return }
  if (sourceMode.value === 'text' && !story.value.trim()) { error.value = '请粘贴故事文字。'; return }
  if (file.value && sourceMode.value === 'file' && file.value.size > 20 * 1024 * 1024) {
    error.value = '文件不能超过20 MB。'; return
  }
  create.mutate()
}
function selectFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null }
</script>

<template>
  <section class="page create-page">
    <div class="create-header">
      <RouterLink class="back-link" to="/projects"><ArrowLeft :size="17" />返回项目</RouterLink>
      <h1>创建小说项目</h1>
    </div>
    <form class="create-form" @submit.prevent="submit">
      <fieldset class="form-section">
        <legend>故事来源</legend>
        <div class="source-options" role="radiogroup" aria-label="故事来源">
          <label :class="{ selected: sourceMode === 'file' }"><input v-model="sourceMode" type="radio" value="file" :disabled="create.isPending.value" /><Upload :size="18" />导入故事文件</label>
          <label :class="{ selected: sourceMode === 'text' }"><input v-model="sourceMode" type="radio" value="text" :disabled="create.isPending.value" /><FileText :size="18" />粘贴故事文字</label>
        </div>
        <label v-if="sourceMode === 'file'" class="field full-span"><span>故事文件</span><input type="file" accept=".txt,.md,.docx,.pdf" :disabled="create.isPending.value" @change="selectFile" /><small>TXT、Markdown、DOCX、文本型 PDF · 最大20 MB</small></label>
        <label v-else class="field full-span"><span>故事文字</span><textarea v-model="story" rows="12" :disabled="create.isPending.value" placeholder="在此粘贴故事内容" /></label>
      </fieldset>
      <fieldset class="form-section">
        <legend>项目设置</legend>
        <label class="field full-span"><span>项目名称（选填）</span><input v-model="name" maxlength="200" :disabled="create.isPending.value" placeholder="留空则根据故事自动生成书名" /></label>
        <div class="source-options" role="radiogroup" aria-label="创作策略">
          <label :class="{ selected: strategy === 'STANDARD' }"><input v-model="strategy" type="radio" value="STANDARD" :disabled="create.isPending.value" />标准创作</label>
          <label :class="{ selected: strategy === 'FANQIE_GRIPPING' }"><input v-model="strategy" type="radio" value="FANQIE_GRIPPING" :disabled="create.isPending.value" />番茄强开篇</label>
        </div>
      </fieldset>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div class="form-actions"><RouterLink class="button secondary" to="/projects">取消</RouterLink><button class="button primary" type="submit" :disabled="create.isPending.value"><Sparkles :size="16" />{{ create.isPending.value ? '正在创建并导入…' : '创建并解析' }}</button></div>
    </form>
  </section>
</template>

<style scoped>
.source-options { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.source-options label { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 10px 12px; border: 1px solid #cbd2d9; border-radius: 4px; cursor: pointer; overflow-wrap: anywhere; }
.source-options label.selected { border-color: #176b63; background: #f3faf8; }
.source-options input { width: auto; margin: 0; accent-color: #176b63; }
.source-options svg { flex-shrink: 0; }
textarea { resize: vertical; }
@media (max-width: 480px) { .source-options { grid-template-columns: 1fr; } }
</style>

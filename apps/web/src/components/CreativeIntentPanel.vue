<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check, Pencil, Sparkles } from 'lucide-vue-next'
import { getProject, updateCreativeIntent, type ProjectSummary } from '@/api/projects'
import { getLatestOutline, getPlanningSnapshot } from '@/api/planning'
import { createCreativeIntentPrefill } from '@/lib/creative-intent-prefill'

const props = defineProps<{ projectId: string }>()
const client = useQueryClient()
const editing = ref(false)
const notice = ref('')
const error = ref('')
const form = reactive({ premise: '', genresText: '', targetAudience: '', protagonistBrief: '',
  centralConflict: '', tonesText: '', targetWords: 50000, mustHaveText: '' })
const projectQuery = useQuery({ queryKey: computed(() => ['project', props.projectId]), queryFn: () => getProject(props.projectId) })
function load(project: ProjectSummary) {
  const value = project.creativeIntent
  form.premise = value?.premise ?? ''
  form.genresText = value?.genres.join(', ') ?? ''
  form.targetAudience = value?.targetAudience ?? ''
  form.protagonistBrief = value?.protagonistBrief ?? ''
  form.centralConflict = value?.centralConflict ?? ''
  form.tonesText = value?.tones.join(', ') ?? ''
  form.targetWords = value?.targetWords ?? 50000
  form.mustHaveText = value?.mustHave.join('\n') ?? ''
}
watch(() => projectQuery.data.value, value => { if (value && !editing.value) load(value) }, { immediate: true })
const tags = (text: string) => text.split(/[,，]/).map(value => value.trim()).filter(Boolean)
const requirements = (text: string) => text.split(/\r?\n/).map(value => value.replace(/^\s*(?:[-*•]|\d+[.、])\s*/, '').trim()).filter(Boolean)
const save = useMutation({
  mutationFn: async () => {
    const project = projectQuery.data.value
    if (!project) throw new Error('项目尚未加载完成。')
    if (!form.premise.trim() || !form.protagonistBrief.trim() || !form.centralConflict.trim()) throw new Error('请填写故事创意、主角简述和核心冲突。')
    if (!Number.isFinite(form.targetWords) || form.targetWords < 1000 || form.targetWords > 10000000) throw new Error('目标字数应为 1000～10000000 字。')
    const mustHave = requirements(form.mustHaveText)
    if (mustHave.length > 30 || mustHave.some(value => value.length > 300)) throw new Error('必须保留的信息最多 30 条，每条不超过 300 字。')
    return updateCreativeIntent(project, { premise: form.premise.trim(), genres: tags(form.genresText),
      targetAudience: form.targetAudience.trim() || undefined, protagonistBrief: form.protagonistBrief.trim(),
      centralConflict: form.centralConflict.trim(), tones: tags(form.tonesText), targetWords: form.targetWords,
      mustHave, endingPreference: project.creativeIntent?.endingPreference,
      avoid: project.creativeIntent?.avoid ?? [], stylePreferences: project.creativeIntent?.stylePreferences ?? [] })
  },
  onSuccess: value => { client.setQueryData(['project', value.id], value); editing.value = false; error.value = ''; notice.value = '创作意图已保存' },
  onError: (reason: Error) => { error.value = reason.message },
})
const prefill = useMutation({
  mutationFn: async () => {
    const snapshot = await getPlanningSnapshot(props.projectId)
    if (!snapshot?.sourceImportId) throw new Error('尚未确认导入雪花规划。')
    const outline = await getLatestOutline(props.projectId)
    return createCreativeIntentPrefill(snapshot, outline?.sourceBibleVersionId === snapshot.id ? outline : null)
  },
  onSuccess: value => { Object.assign(form, value, { mustHaveText: [...new Set([...requirements(form.mustHaveText), ...value.mustHave])].join('\n') }); error.value = '' },
  onError: (reason: Error) => { error.value = reason.message },
})
function edit() { if (projectQuery.data.value) load(projectQuery.data.value); editing.value = true; notice.value = ''; error.value = '' }
function cancel() { editing.value = false; if (projectQuery.data.value) load(projectQuery.data.value); error.value = '' }
</script>

<template>
  <section class="creative-intent">
    <header><h2>创作意图</h2><button v-if="!editing" class="button secondary" type="button" :disabled="!projectQuery.data.value" @click="edit"><Pencil :size="16" />编辑创作意图</button></header>
    <p v-if="projectQuery.isPending.value" role="status">正在读取创作意图…</p>
    <p v-if="projectQuery.isError.value" class="form-error" role="alert">{{ projectQuery.error.value?.message }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <form v-if="editing" @submit.prevent="save.mutate()">
      <fieldset :disabled="save.isPending.value || prefill.isPending.value" class="intent-fields">
        <label class="field full-span"><span>一句话创意</span><textarea v-model="form.premise" rows="2" maxlength="2000" /></label>
        <label class="field"><span>小说类型</span><input v-model="form.genresText" maxlength="300" /></label>
        <label class="field"><span>目标读者</span><input v-model="form.targetAudience" maxlength="300" /></label>
        <label class="field full-span"><span>主角简述</span><textarea v-model="form.protagonistBrief" rows="2" maxlength="2000" /></label>
        <label class="field full-span"><span>核心冲突</span><textarea v-model="form.centralConflict" rows="2" maxlength="2000" /></label>
        <label class="field"><span>故事基调</span><input v-model="form.tonesText" maxlength="300" /></label>
        <label class="field"><span>目标字数</span><input v-model.number="form.targetWords" type="number" min="1000" max="10000000" step="1000" /></label>
        <label class="field full-span"><span>必须保留的信息（每行一条）</span><textarea v-model="form.mustHaveText" rows="4" maxlength="9000" /></label>
      </fieldset>
      <footer>
        <button class="button secondary" type="button" :disabled="save.isPending.value || prefill.isPending.value" @click="prefill.mutate()"><Sparkles :size="16" />从导入规划填写</button>
        <button class="button secondary" type="button" :disabled="save.isPending.value || prefill.isPending.value" @click="cancel">取消</button>
        <button class="button primary" type="submit" :disabled="save.isPending.value || prefill.isPending.value"><Check :size="16" />{{ save.isPending.value ? '正在保存…' : '保存创作意图' }}</button>
      </footer>
    </form>
    <p v-else-if="projectQuery.data.value?.creativeIntent">{{ projectQuery.data.value.creativeIntent.premise }}</p>
  </section>
</template>

<style scoped>
header, footer { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
header { justify-content: space-between; }
h2 { font-size: 16px; margin: 0; }
.intent-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; border: 0; padding: 16px 0; margin: 0; min-width: 0; }
.full-span { grid-column: 1 / -1; }
.field { min-width: 0; }
input, textarea { width: 100%; min-width: 0; box-sizing: border-box; }
@media (max-width: 600px) { .intent-fields { grid-template-columns: minmax(0, 1fr); } }
</style>

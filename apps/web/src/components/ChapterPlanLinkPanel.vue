<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { Check } from 'lucide-vue-next'
import { confirmChapterPlanLink, listChapterPlanLinks, type ChapterPlanLink } from '@/api/creationPreparations'
import { getReaderExperience, readerExperienceTransitions } from '@/api/readerExperience'
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ recorded: [] }>()
const cache = useQueryClient()
const selected = ref<string[]>([])
const busy = ref(false)
const error = ref('')
const query = useQuery({ queryKey: computed(() => ['chapter-plan-links', props.projectId]), queryFn: () => listChapterPlanLinks(props.projectId) })
const labels: Record<string, string> = { SET_UP: '已埋设', REINFORCED: '已强化', PAYOFF: '已兑现', OPEN: '开放保留', ABANDONED: '放弃' }
watch(() => props.projectId, () => { selected.value = []; busy.value = false; error.value = '' })
const requestKeys = new Map<string, string>()
async function confirm(link: ChapterPlanLink) {
  if (!selected.value.includes(link.id)) return
  const project = props.projectId; busy.value = true; error.value = ''
  try {
    const entry = await getReaderExperience(project, link.planId)
    if (!readerExperienceTransitions(entry).includes(link.state as Exclude<typeof entry.state, 'PLANNED'>)) throw new Error('当前台账状态不能直接转为该进度，请先在台账核对前置阶段。')
    const key = `${project}:${link.id}:${entry.plan.version}`
    if (!requestKeys.has(key)) requestKeys.set(key, crypto.randomUUID())
    await confirmChapterPlanLink(project, link, entry.plan.version, requestKeys.get(key)!)
    if (project !== props.projectId) return
    selected.value = selected.value.filter(id => id !== link.id)
    await cache.invalidateQueries({ predicate: q => q.queryKey.includes(project) }); emit('recorded')
  } catch (failure) { if (project === props.projectId) error.value = failure instanceof Error ? failure.message : '登记失败' }
  finally { if (project === props.projectId) busy.value = false }
}
</script>
<template>
  <section v-if="query.data.value?.length || query.error.value" class="links">
    <h3>正文与规划台账关联</h3>
    <p v-if="error || query.error.value" role="alert" class="error">{{ error || query.error.value?.message }}</p>
    <article v-for="link in query.data.value" :key="link.id">
      <strong>{{ link.title }}</strong><span>第 {{ link.chapterNumber }} 章 · {{ labels[link.state] }}</span>
      <blockquote>{{ link.evidence }}</blockquote>
      <span v-if="link.recorded">已登记</span><span v-else-if="link.stale" class="error">来源已失效</span>
      <div v-else class="actions"><label><input v-model="selected" type="checkbox" :value="link.id" :disabled="busy">确认原文支持该进度</label><button type="button" :disabled="busy || !selected.includes(link.id)" @click="confirm(link)"><Check :size="15" />登记进度</button></div>
    </article>
  </section>
</template>
<style scoped>
.links { padding: 18px 0; border-top: 1px solid #dce2e5; } h3 { font-size: 16px; } article { padding: 12px 0; border-bottom: 1px solid #e5e9eb; }
strong, span { display: block; overflow-wrap: anywhere; } span { font-size: 13px; color: #687780; margin-top: 6px; }
blockquote { margin: 12px 0; padding-left: 12px; border-left: 3px solid #b0bbc1; overflow-wrap: anywhere; line-height: 1.7; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; } label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
button { display: inline-flex; gap: 6px; align-items: center; background: #fff; border: 1px solid #cbd3d8; padding: 7px 10px; border-radius: 4px; cursor: pointer; } button:disabled { opacity: .5; cursor: default; } .error { color: #a63838; }
</style>

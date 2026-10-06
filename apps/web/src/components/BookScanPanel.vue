<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ListChecks, RefreshCw } from 'lucide-vue-next'
import { getBookScan } from '@/api/bookScan'
const props = defineProps<{ projectId: string }>()
const opened = ref(false)
const query = useQuery({ queryKey: computed(() => ['book-scan', props.projectId]),
  queryFn: () => getBookScan(props.projectId), enabled: opened })
</script>
<template>
  <section class="book-scan" aria-label="全书来源核对">
    <header><h3><ListChecks :size="18" />全书来源核对</h3><button class="button secondary" type="button" :disabled="opened && query.isFetching.value" @click="opened = true; query.refetch()"><RefreshCw :size="16" />{{ query.isFetching.value ? '正在核对…' : '核对来源' }}</button></header>
    <p v-if="opened && query.isError.value" class="form-error" role="alert">{{ query.error.value?.message }}</p>
    <template v-if="query.data.value">
      <p class="scan-notice">{{ query.data.value.notice }}</p>
      <p>大纲 {{ query.data.value.plannedChapters }} 章 · 有效正史 {{ query.data.value.canonChapters }} 章 · 正史版本 {{ query.data.value.canonVersion }}</p>
      <ul class="scan-observations"><li v-for="(item, index) in query.data.value.observations" :key="index"><strong v-if="item.chapters.length">第 {{ item.chapters.join('、') }} 章：</strong>{{ item.detail }}</li></ul>
      <details><summary>章节摘要与来源</summary><ol class="scan-chapters"><li v-for="chapter in query.data.value.chapters" :key="chapter.number"><h4>第 {{ chapter.number }} 章 · {{ chapter.title }}</h4><p>{{ chapter.summary ?? '缺少有效正史摘要' }}</p><small v-if="chapter.manuscriptId">正文 {{ chapter.manuscriptId }} · 行版本 {{ chapter.manuscriptRowVersion }} · 正史提交 {{ chapter.canonCommitId }}</small></li></ol></details>
    </template>
  </section>
</template>
<style scoped>
.book-scan { border-top: 1px solid #dfe3e0; padding-block: 20px; min-width: 0; }
header { display: flex; justify-content: space-between; gap: 16px; align-items: center; flex-wrap: wrap; }
h3 { font-size: 16px; display: flex; align-items: center; gap: 8px; margin: 0; } h4 { font-size: 14px; margin: 0; }
.scan-notice, small { color: #68716b; font-size: 13px; }
.scan-observations { padding-left: 20px; line-height: 1.8; }
.scan-chapters { list-style: none; padding: 0; } .scan-chapters li { padding-block: 12px; border-bottom: 1px solid #dfe3e0; }
p, li, small { white-space: pre-wrap; overflow-wrap: anywhere; } summary { cursor: pointer; }
</style>
